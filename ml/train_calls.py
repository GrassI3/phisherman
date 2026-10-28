"""Train the call model on public scam-call datasets and export it in the SMS model's JSON format.

Data (caller's side only, which is what Live Caption gives us):
  BothBosu/scam-dialogue   1,600 English synthetic calls, Apache-2.0
  ysangam Indian Cyber Scam PhoneCall Hinglish, 10k rows / ~743 unique snippets, Apache-2.0
Same features and scorer as the SMS model (TF-IDF word 1-2 + char_wb 3-5, logistic regression),
so the phone reuses SmsModel. The phone scores the call so far after every caption line, so we
train on growing prefixes of each call and measure the same way: a call counts as flagged when
any prefix crosses the threshold.

Honesty notes:
  * BothBosu's call type decides its label (every delivery call is safe, every refund call is a
    scam), so a high score there partly means "learned the topic". Our hand-written Indian
    scripts (assets/voice_scripts.tsv, never trained on) are reported separately for that reason.
  * ysangam rows are an opener + a template sentence; they are split by template, not by row,
    so the same sentence never lands in both train and test.
"""
import csv
import json
import re
import time
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupShuffleSplit
from sklearn.pipeline import FeatureUnion

from normalize import NORMALIZER_VERSION, normalize
from sms_engine import TOKEN_PATTERN, SmsEngine

ML = Path(__file__).resolve().parent
HF = ML / "data/raw/hf_scam"
ASSETS = ML.parent / "android/app/src/main/assets"
MIN_WORDS = 10                 # the phone waits for this much speech before asking the model
SUSPICIOUS_MIN_PRECISION = 0.95
DANGEROUS_MIN_PRECISION = 0.99


def bothbosu():
    d = pd.read_csv(HF / "scam_dialogue_all.csv")
    calls = []
    for i, r in d.iterrows():
        turns = re.split(r"(?=\b(?:caller|receiver):)", str(r.dialogue))
        lines = [t.strip()[len("caller:"):].strip() for t in turns if t.strip().startswith("caller:")]
        if lines:
            calls.append(dict(src="bothbosu", group=f"b{i}", kind=r.type, y=int(str(r.label) == "1"), lines=lines))
    return calls


def ysangam():
    d = pd.read_csv(HF / "india_hinglish_calls.csv").drop_duplicates("text")
    calls = []
    for r in d.itertuples():
        sents = [s.strip() for s in re.split(r"(?<=[.?!])\s+", r.text) if s.strip()]
        # rows are "<opener> <template sentence(s)>": group by the last sentence
        calls.append(dict(src="ysangam", group="y" + sents[-1].lower(), kind=r.scam_category, y=int(r.label),
                          lines=[r.text]))
    return calls


def dailydialog():
    """Everyday English conversations (normal calls look like this, not like a delivery script)."""
    calls = []
    for s in ("train", "validation", "test"):
        for r in pd.read_parquet(ML / f"data/raw/dailydialog/{s}.parquet").itertuples():
            lines = [str(u).strip() for u in r.utterances if str(u).strip()]
            calls.append(dict(src="dailydialog", group="d" + " ".join(lines[:2]).lower(), kind="normal", y=0, lines=lines))
    return calls


def messages():
    """Real normal UCI messages and our Indian SMS set: short everyday/Hinglish speech (normal) and
    Indian scam wording (KYC, OTP, digital arrest). One line each. UCI spam is left out: ads are
    not phone scams."""
    uci = pd.read_csv(ML / "data/raw/uci_sms/SMSSpamCollection", sep="\t", header=None, names=["label", "text"],
                      quoting=csv.QUOTE_NONE, encoding="utf-8")
    ind = pd.read_csv(ML / "sms_india_synthetic.tsv", sep="\t", quoting=csv.QUOTE_NONE, encoding="utf-8")
    rows = [("uci_ham", 0, t) for t in uci[uci.label == "ham"].text]
    rows += [("india_sms", int(l == "phishing"), t) for l, t in zip(ind.label, ind.text) if l in ("phishing", "normal")]
    seen, calls = set(), []
    for src, y, t in rows:
        n = normalize(t)[0]
        if n and n not in seen:
            seen.add(n)
            calls.append(dict(src=src, group="m" + n, kind="msg", y=y, lines=[t]))
    return calls


def scripts():
    rows = list(csv.DictReader(open(ASSETS / "voice_scripts.tsv", encoding="utf-8"), delimiter="\t"))
    return [dict(src="scripts", group=r["id"], kind=r["kind"], id=r["id"], y=int(r["kind"] == "scam"),
                 lines=[l.removeprefix("[PAY] ") for l in r["lines"].split(" || ")]) for r in rows]


def prefixes(lines):
    """Text the phone would score after each caption line (the call so far)."""
    return [" ".join(lines[:k]) for k in range(1, len(lines) + 1)]


def examples(calls):
    """Training rows: every prefix with at least MIN_WORDS words, plus the full call."""
    xs, ys, ws = [], [], []
    for c in calls:
        ps = [p for p in prefixes(c["lines"]) if len(p.split()) >= MIN_WORDS] or [" ".join(c["lines"])]
        for p in ps:
            xs.append(normalize(p)[0]); ys.append(c["y"]); ws.append(1.0 / len(ps))  # each call weighs 1
    return xs, np.array(ys), np.array(ws)


def call_peak(prob, calls):
    """Highest model probability over the call's prefixes (what decides the alert on the phone)."""
    out = []
    for c in calls:
        ps = [p for p in prefixes(c["lines"]) if len(p.split()) >= MIN_WORDS]
        out.append(max(prob(normalize(p)[0]) for p in ps) if ps else 0.0)
    return np.array(out)


def threshold_for_precision(y, p, min_precision):
    for t in np.unique(p):
        flag = p >= t
        if flag.sum() and (y[flag] == 1).mean() >= min_precision:
            return float(t)
    return 1.0


def metrics(y, flag):
    tp = int((flag & (y == 1)).sum()); fp = int((flag & (y == 0)).sum())
    fn = int((~flag & (y == 1)).sum()); tn = int((~flag & (y == 0)).sum())
    prec = tp / max(tp + fp, 1); rec = tp / max(tp + fn, 1)
    return {"precision": round(prec, 4), "recall": round(rec, 4), "fdr": round(rec, 4),
            "accuracy": round((tp + tn) / max(len(y), 1), 4), "fpr": round(fp / max(fp + tn, 1), 4),
            "f1": round(2 * prec * rec / max(prec + rec, 1e-9), 4),
            "n": int(len(y)), "scams": int(y.sum()), "tp": tp, "fp": fp, "fn": fn, "tn": tn}


def split(calls, test_size, seed):
    g = [c["group"] for c in calls]
    a, b = next(GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=seed).split(calls, groups=g))
    return [calls[i] for i in a], [calls[i] for i in b]


def main():
    t0 = time.time()
    data = bothbosu() + ysangam() + dailydialog() + messages()
    tr, te = split(data, 0.2, 42)
    tr, va = split(tr, 0.15, 7)
    assert not {c["group"] for c in te} & {c["group"] for c in tr + va}
    for name, part in (("train", tr), ("validation", va), ("test", te)):
        print(name, pd.Series([(c["src"], c["y"]) for c in part]).value_counts().sort_index().to_dict())

    x, y, w = examples(tr)
    vec = FeatureUnion([
        ("w", TfidfVectorizer(analyzer="word", token_pattern=TOKEN_PATTERN, ngram_range=(1, 2), min_df=4, max_features=20000, sublinear_tf=True)),
        ("c", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=8, max_features=30000, sublinear_tf=True)),
    ])
    clf = LogisticRegression(max_iter=3000, class_weight="balanced", C=1.0).fit(vec.fit_transform(x), y, sample_weight=w)
    prob = lambda n: float(clf.predict_proba(vec.transform([n]))[0, 1])

    wv, cv = vec.transformer_list[0][1], vec.transformer_list[1][1]
    nw, coef = len(wv.vocabulary_), clf.coef_[0]
    export = {
        "version": "call-1", "normalizer": NORMALIZER_VERSION, "token_pattern": TOKEN_PATTERN, "min_words": MIN_WORDS,
        "word": {"vocab": {k: int(v) for k, v in wv.vocabulary_.items()}, "idf": [round(float(v), 6) for v in wv.idf_],
                 "coef": [round(float(v), 6) for v in coef[:nw]], "ngram": [1, 2]},
        "char": {"vocab": {k: int(v) for k, v in cv.vocabulary_.items()}, "idf": [round(float(v), 6) for v in cv.idf_],
                 "coef": [round(float(v), 6) for v in coef[nw:]], "ngram": [3, 5]},
        "intercept": round(float(clf.intercept_[0]), 6),
    }
    path = ASSETS / "call_model.json"
    json.dump(export, open(path, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

    # the exported JSON, scored by the hand-written scorer the phone copies, must match sklearn
    eng = SmsEngine(None, json.load(open(path, encoding="utf-8")))
    sample = x[:300]
    diff = max(abs(eng.prob(n) - prob(n)) for n in sample)
    print(f"exported scorer vs sklearn: max diff {diff:.2e}")
    assert diff < 1e-3

    # thresholds on validation calls, scored the way the phone scores them
    pva, yva = call_peak(eng.prob, va), np.array([c["y"] for c in va])
    thr_s = threshold_for_precision(yva, pva, SUSPICIOUS_MIN_PRECISION)
    thr_d = max(threshold_for_precision(yva, pva, DANGEROUS_MIN_PRECISION), thr_s)
    thresholds_path = ASSETS / "thresholds.json"
    thresholds = json.load(open(thresholds_path))
    thresholds["call"] = {"suspicious": thr_s, "dangerous": thr_d, "min_words": MIN_WORDS,
                          "rule": f"precision >= {SUSPICIOUS_MIN_PRECISION} / {DANGEROUS_MIN_PRECISION} on validation calls (not the test set)"}
    json.dump(thresholds, open(thresholds_path, "w"), indent=2)

    res = {"generated": time.strftime("%Y-%m-%d"), "model": "TF-IDF word 1-2 + char_wb 3-5, logistic regression",
           "thresholds": thresholds["call"], "model_bytes": path.stat().st_size,
           "train_calls": len(tr), "validation_calls": len(va),
           "datasets": {"bothbosu": "BothBosu/scam-dialogue (English, synthetic), caller turns",
                        "ysangam": "Indian Cyber Scam PhoneCall Hinglish (unique snippets, split by template)",
                        "scripts": "assets/voice_scripts.tsv, hand-written Indian calls, never trained on"},
           "test": {}}
    sc = scripts()
    calls_te = [c for c in te if c["src"] in ("bothbosu", "ysangam", "dailydialog")]
    for name, part in [("calls", calls_te)] + [(s, [c for c in te if c["src"] == s]) for s in
                       ("bothbosu", "ysangam", "dailydialog", "uci_ham", "india_sms")] + [("scripts", sc)]:
        p, yy = call_peak(eng.prob, part), np.array([c["y"] for c in part])
        res["test"][name] = {"alert": metrics(yy, p >= thr_s), "red": metrics(yy, p >= thr_d)}
        a, r = res["test"][name]["alert"], res["test"][name]["red"]
        print(f"{name:9s} N {a['n']:4d} scams {a['scams']:4d} | alert P {a['precision']:.3f} R {a['recall']:.3f} FPR {a['fpr']:.3f}"
              f" | red P {r['precision']:.3f} R {r['recall']:.3f} FPR {r['fpr']:.3f}")
    res["scripts_detail"] = [{"id": c["id"], "kind": c["kind"], "peak": round(float(p), 3)}
                             for c, p in zip(sc, call_peak(eng.prob, sc))]
    for d in res["scripts_detail"]:
        print(f"   {d['id']:3s} {d['kind']:6s} peak {d['peak']:.3f}")
    json.dump(res, open(ML / "results_calls_model.json", "w", encoding="utf-8"), indent=2, ensure_ascii=False)

    # held-out calls for the Kotlin end-to-end eval (rules + model through CallSession); stays in
    # ml/data (gitignored) because it is dataset text
    with open(ML / "data/calls_test.tsv", "w", encoding="utf-8", newline="") as f:
        f.write("src\tlabel\tlines\n")
        for c in calls_te:
            f.write(f"{c['src']}\t{c['y']}\t{' || '.join(l.replace(chr(9), ' ') for l in c['lines'])}\n")
    # parity: Kotlin must reproduce these probabilities from call_model.json
    out = ML.parent / "android/app/src/test/resources/call_parity_expected.tsv"
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write("text\tprob\n")
        for c in sc:
            t = " ".join(c["lines"])
            f.write(f"{t}\t{eng.prob(normalize(t)[0])!r}\n")
    print("thresholds", round(thr_s, 3), round(thr_d, 3), f"| model {path.stat().st_size / 1e6:.2f} MB | {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()

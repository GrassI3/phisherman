"""Train the SMS model (TF-IDF word + char_wb n-grams, logistic regression) and export JSON.

Data: UCI SMS Spam Collection (ham -> normal, spam -> scam), CloveAI/india-spam-sms (Indian bank
alerts, UPI, bills, KYC/Aadhaar scams; template-generated, so split by template and capped per
template) and our hand-written Indian set (phishing / normal / promotional). Positive = scam.
"""
import csv
import re
import json
import time
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import precision_recall_curve
from sklearn.model_selection import GroupShuffleSplit, train_test_split
from sklearn.pipeline import FeatureUnion

from normalize import NORMALIZER_VERSION, normalize
from sms_engine import SmsEngine, TOKEN_PATTERN

ML = Path(__file__).resolve().parent
ASSETS = ML.parent / "android/app/src/main/assets"
SYNTHETIC_WEIGHT = 3.0  # our Indian messages are few; weight them up in training
PER_TEMPLATE = 5        # rows kept per CloveAI template (20k rows are ~300 templates)


def load():
    uci = pd.read_csv(ML / "data/raw/uci_sms/SMSSpamCollection", sep="\t", header=None,
                      names=["label", "text"], quoting=csv.QUOTE_NONE, encoding="utf-8")
    uci = uci.assign(cls=uci.label.map({"ham": "normal", "spam": "phishing"}), sender="", source="uci")
    ind = pd.read_csv(ML / "sms_india_synthetic.tsv", sep="\t", quoting=csv.QUOTE_NONE, encoding="utf-8")
    ind = ind.rename(columns={"label": "cls"}).assign(source="india_synthetic")
    hf = pd.read_parquet(ML / "data/raw/india_spam_sms/train.parquet")
    hf = hf.assign(cls=hf.label.map({0: "normal", 1: "phishing"}), sender="", source="india_hf",
                   template=hf.text.map(template_key))
    hf = hf.groupby("template", group_keys=False).head(PER_TEMPLATE)
    d = pd.concat([uci[["cls", "sender", "text", "source"]], ind[["cls", "sender", "text", "source"]],
                   hf[["cls", "sender", "text", "source", "template"]]], ignore_index=True)
    d["sender"] = d.sender.fillna("").astype(str)
    d["y"] = (d.cls == "phishing").astype(int)
    d["norm"] = d.text.map(lambda t: normalize(t)[0])
    return d.drop_duplicates("norm")


def template_key(t):
    """Generated messages differ only in names and numbers; those collapse to one template."""
    return re.sub(r"[A-Z][a-z]+|[0-9]+", "#", t)


def split(d):
    parts = []
    for src, frac in (("uci", 0.2), ("india_synthetic", 0.3)):
        s = d[d.source == src]
        tr, te = train_test_split(s, test_size=frac, stratify=s.cls, random_state=42)
        parts.append((tr, te))
    s = d[d.source == "india_hf"]  # by template: the same template is never in train and test
    a, b = next(GroupShuffleSplit(n_splits=1, test_size=0.25, random_state=42).split(s, groups=s.template))
    parts.append((s.iloc[a], s.iloc[b]))
    return pd.concat([p[0] for p in parts]), pd.concat([p[1] for p in parts])


def metrics(y, yhat):
    tp = int((yhat & (y == 1)).sum()); fp = int((yhat & (y == 0)).sum())
    fn = int((~yhat & (y == 1)).sum()); tn = int((~yhat & (y == 0)).sum())
    prec = tp / max(tp + fp, 1); rec = tp / max(tp + fn, 1)
    return {"precision": round(prec, 4), "recall": round(rec, 4), "fdr": round(rec, 4),
            "fpr": round(fp / max(fp + tn, 1), 4), "f1": round(2 * prec * rec / max(prec + rec, 1e-9), 4),
            "n": int(len(y)), "n_phishing": int(y.sum()), "tp": tp, "fp": fp, "fn": fn, "tn": tn}


def threshold_for_precision(y, p, min_precision):
    pr, _, th = precision_recall_curve(y, p)
    ok = [t for prec, t in zip(pr[:-1], th) if prec >= min_precision]
    return float(min(ok)) if ok else 0.5


PARITY_EXTRA = [  # edge cases on top of the Indian test messages
    ("9876543210", "Cl1ck h3re to cla1m your 🎁 prize www.win-prize.example now!! call 9876543210"),
    ("AD-HDFCBK", "Never share OTP 482913 with anyone. HDFC Bank never asks for OTP, PIN or CVV."),
    ("9812345678", "OTP kisi ko mat batao, bank kabhi OTP nahi maangta"),
    ("9812345678", "Sir apna OTP batao, account verify karna hai"),
    ("VM-SBIINB", "Your statement is ready. Pay via onlinesbi.sbi"),
    ("", "Install TeamViewer now"),
    ("+919876543210", "SBI: update your KYC at sbi-kyc.example/login today"),
    ("Rahul", "KYC_Update_Form.pdf.apk"),
    ("9876543210", "Please install SBI_Rewards.apk to claim your points"),
    ("Rahul", "Bank statement.pdf"),
]


def write_parity(eng, rows):
    """Expected values for the Kotlin SMS parity tests (normaliser, scorer, rules, levels)."""
    items = list(zip(rows.sender, rows.text)) + PARITY_EXTRA
    out = ML / "sms_parity_expected.tsv"
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write("sender\ttext\tnorm\turls\tprob\tlevel\thits\tlink_level\n")
        for sender, text in items:
            norm, urls = normalize(text)
            level, hits, _, p, link_level = eng.decide(sender, text)
            f.write("\t".join([sender, text, norm, " ".join(urls), repr(p), level, ",".join(hits), link_level or ""]) + "\n")
    for d in (ML.parent / "android/app/src/test/resources", ML.parent / "android/app/src/androidTest/assets"):
        d.mkdir(parents=True, exist_ok=True)
        (d / out.name).write_bytes(out.read_bytes())
    print(f"sms parity: {len(items)} messages -> {out.name}")


def main():
    t0 = time.time()
    d = load()
    tr, te = split(d)
    # thresholds are chosen on a validation split carved from TRAIN; the test set is only scored
    tr, va = train_test_split(tr, test_size=0.15, stratify=tr.source + tr.cls, random_state=1)
    print("train:", tr.groupby(["source", "cls"]).size().to_dict())
    print("validation:", len(va))
    print("test: ", te.groupby(["source", "cls"]).size().to_dict())

    vec = FeatureUnion([
        ("w", TfidfVectorizer(analyzer="word", token_pattern=TOKEN_PATTERN, ngram_range=(1, 2), min_df=2, sublinear_tf=True)),
        ("c", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=3, sublinear_tf=True)),
    ])
    Xtr = vec.fit_transform(tr.norm)
    w_sample = np.where(tr.source == "india_synthetic", SYNTHETIC_WEIGHT, 1.0)
    clf = LogisticRegression(max_iter=3000, class_weight="balanced", C=3.0).fit(Xtr, tr.y, sample_weight=w_sample)

    w, c = vec.transformer_list[0][1], vec.transformer_list[1][1]
    nw = len(w.vocabulary_)
    coef = clf.coef_[0]
    export = {
        "version": "sms-1", "normalizer": NORMALIZER_VERSION, "token_pattern": TOKEN_PATTERN,
        "word": {"vocab": {k: int(v) for k, v in w.vocabulary_.items()}, "idf": [round(float(x), 6) for x in w.idf_],
                 "coef": [round(float(x), 6) for x in coef[:nw]], "ngram": [1, 2]},
        "char": {"vocab": {k: int(v) for k, v in c.vocabulary_.items()}, "idf": [round(float(x), 6) for x in c.idf_],
                 "coef": [round(float(x), 6) for x in coef[nw:]], "ngram": [3, 5]},
        "intercept": round(float(clf.intercept_[0]), 6),
    }
    path = ASSETS / "sms_model.json"
    json.dump(export, open(path, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

    # our hand-written scorer (the one Kotlin copies) must match sklearn
    p_sk = clf.predict_proba(vec.transform(te.norm))[:, 1]
    eng_model = json.load(open(path, encoding="utf-8"))
    thresholds_path = ASSETS / "thresholds.json"
    thresholds = json.load(open(thresholds_path))
    p_va = clf.predict_proba(vec.transform(va.norm))[:, 1]
    # template-generated rows are too easy to set a threshold on; use the real/hand-written ones
    real = (va.source != "india_hf").values
    thr_s = threshold_for_precision(va.y.values[real], p_va[real], 0.90)
    thr_d = max(threshold_for_precision(va.y.values[real], p_va[real], 0.98), thr_s)
    thresholds["sms"] = {"suspicious": thr_s, "dangerous": thr_d, "rule": "precision >= 0.90 / 0.98 on validation messages from UCI + our Indian set (not the test set)"}
    json.dump(thresholds, open(thresholds_path, "w"), indent=2)

    from link_engine import LinkEngine
    eng = SmsEngine(LinkEngine(), eng_model)
    p_ours = np.array([eng.prob(n) for n in te.norm])
    diff = float(np.abs(p_ours - p_sk).max())
    print(f"hand-written scorer vs sklearn: max diff {diff:.2e}")
    assert diff < 1e-3, diff

    # full engine (model + rules + link engine) on the test set
    decided = [eng.decide(s, t) for s, t in zip(te.sender, te.text)]
    lv = np.array([x[0] for x in decided])
    y = te.y.values
    res = {"generated": time.strftime("%Y-%m-%d"), "thresholds": thresholds["sms"],
           "model_bytes": path.stat().st_size, "vocab": {"word": nw, "char": len(c.vocabulary_)},
           "datasets": {"uci": "UCI SMS Spam Collection (spam treated as scam)",
                        "india_hf": "CloveAI/india-spam-sms (MIT), split by template, max 5 rows per template",
                        "india_synthetic": "ml/sms_india_synthetic.tsv, hand-written, all synthetic"},
           "model_only": {}, "engine": {}, "engine_promotional_fpr": None}
    for src in ("all", "uci", "india_hf", "india_synthetic"):
        m = np.ones(len(te), bool) if src == "all" else (te.source == src).values
        res["model_only"][src] = metrics(y[m], p_sk[m] >= thr_s)
        res["engine"][src] = metrics(y[m], np.isin(lv[m], ["SUSPICIOUS", "DANGEROUS"]))
    promo = (te.cls == "promo").values
    res["engine_promotional_fpr"] = {"n": int(promo.sum()),
                                     "flagged": int(np.isin(lv[promo], ["SUSPICIOUS", "DANGEROUS"]).sum())}
    res["india_test_details"] = [
        {"cls": cl, "sender": s, "text": t[:90], "level": x[0], "hits": x[1], "p": round(x[3], 3)}
        for cl, s, t, x, src in zip(te.cls, te.sender, te.text, decided, te.source) if src == "india_synthetic"]
    json.dump(res, open(ML / "results_sms.json", "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    write_parity(eng, te[te.source == "india_synthetic"])

    print(f"sms_model.json {path.stat().st_size / 1e6:.2f} MB, vocab word {nw} char {len(c.vocabulary_)}")
    print("thresholds", {k: round(v, 3) for k, v in (("suspicious", thr_s), ("dangerous", thr_d))})
    for k in ("model_only", "engine"):
        for src, m in res[k].items():
            print(f"{k:10s} {src:16s} P {m['precision']:.3f} R {m['recall']:.3f} FPR {m['fpr']:.4f} N {m['n']} (phishing {m['n_phishing']})")
    print("promotional flagged:", res["engine_promotional_fpr"])
    for x in res["india_test_details"]:
        wrong = (x["cls"] == "phishing") != (x["level"] != "SAFE")
        print(f"  {'MISS' if wrong else 'ok  '} {x['cls']:8s} {x['level']:10s} p={x['p']:.2f} {x['hits']} {x['text'][:60]}")
    print(f"done in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()

"""Phase 1 extras: parity file, hard-negative FPR, device test links, full-engine unseen recall.

Run after train_link.py and build_blocklist.py. Merges an "extras" block into results_link.json.
"""
import csv
import json
import shutil
from pathlib import Path

import numpy as np
import pandas as pd

from features import FEATURE_NAMES, features, registrable_domain
from link_engine import LinkEngine
from train_link import load_train_sources

ML = Path(__file__).resolve().parent
KOTLIN_TEST_DIRS = [ML.parent / "android/app/src/test/resources",   # JVM unit test (features)
                    ML.parent / "android/app/src/androidTest/assets"]  # on-device test (model + engine)


def read_lines(path):
    return [l.strip() for l in open(path, encoding="utf-8") if l.strip() and not l.startswith("#")]


def write_parity(e):
    rows = []
    for u in read_lines(ML / "parity_urls.txt"):
        f = features(u, e.brands)
        level, reason, _ = e.decide(u)
        rows.append([u] + [repr(float(v)) for v in f] + [repr(e.prob(u)), level or "IGNORED", reason])
    out = ML / "parity_expected.csv"
    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["url"] + FEATURE_NAMES + ["prob", "level", "reason"])
        w.writerows(rows)
    for d in KOTLIN_TEST_DIRS:
        d.mkdir(parents=True, exist_ok=True)
        shutil.copy(out, d / "parity_expected.csv")
    return len(rows)


def hard_negatives(e):
    urls = read_lines(ML / "hard_negatives.txt")
    model_only = [e.decide(u, use_lists=False) for u in urls]
    engine = [e.decide(u) for u in urls]
    flagged_model = [(u, d[0], round(d[2], 3)) for u, d in zip(urls, model_only) if d[0] != "SAFE"]
    flagged_engine = [(u, d[0], d[1]) for u, d in zip(urls, engine) if d[0] != "SAFE"]
    return {"n": len(urls),
            "fpr_model_only": round(len(flagged_model) / len(urls), 4),
            "fpr_engine": round(len(flagged_engine) / len(urls), 4),
            "flagged_model_only": flagged_model, "flagged_engine": flagged_engine}


def test_links(e):
    out = []
    for line in read_lines(ML / "test_links.txt"):
        tid, url, expected = [x.strip() for x in line.split("|")]
        level, reason, p = e.decide(url)
        exp = expected.split("(")[0].strip().upper()
        ok = ("IGNORED" in exp and level is None) or (level is not None and (
            level in exp or ("OR HIGHER" in exp and level == "DANGEROUS")))
        out.append({"id": tid, "url": url, "expected": expected, "got": level or "IGNORED",
                    "reason": reason, "prob": None if p is None else round(p, 3), "pass": bool(ok)})
    return out


def unseen_engine(e, n=40000):
    train_domains = set(load_train_sources().dom)
    b = pd.read_csv(ML / "data/raw/phiusiil/PhiUSIIL_Phishing_URL_Dataset.csv", usecols=["URL", "label"])
    b = b.rename(columns={"URL": "url"}).dropna().drop_duplicates("url")
    b["label"] = 1 - b.label
    b = b[~b.url.map(registrable_domain).isin(train_domains)].sample(n, random_state=0)
    y = b.label.values
    res = {}
    for name, lists in (("model_only", False), ("engine", True)):
        lv = np.array([e.decide(u, use_lists=lists)[0] for u in b.url])
        for cut in ("SUSPICIOUS", "DANGEROUS"):
            yhat = np.isin(lv, ["DANGEROUS"] if cut == "DANGEROUS" else ["SUSPICIOUS", "DANGEROUS"])
            tp = int((yhat & (y == 1)).sum()); fp = int((yhat & (y == 0)).sum())
            res[f"{name}_{cut.lower()}"] = {"precision": round(tp / max(tp + fp, 1), 4),
                                            "recall": round(tp / max(y.sum(), 1), 4),
                                            "fpr": round(fp / max((y == 0).sum(), 1), 4), "n": int(n)}
    return res


def main():
    e = LinkEngine()
    extras = {"parity_urls": write_parity(e), "hard_negatives": hard_negatives(e),
              "test_links": test_links(e), "unseen_engine_sample": unseen_engine(e)}
    path = ML / "results_link.json"
    results = json.load(open(path))
    results["extras"] = extras
    json.dump(results, open(path, "w"), indent=2)

    hn = extras["hard_negatives"]
    print(f"parity file: {extras['parity_urls']} URLs -> parity_expected.csv (+ Kotlin test resources)")
    print(f"hard negatives (N={hn['n']}): FPR without allow/block lists {hn['fpr_model_only']}, full engine {hn['fpr_engine']}")
    for x in hn["flagged_model_only"]:
        print("   model flags:", x)
    for x in hn["flagged_engine"]:
        print("   engine flags:", x)
    print("test links:")
    for t in extras["test_links"]:
        print(f"   {t['id']:4s} {'PASS' if t['pass'] else 'FAIL'}  got {t['got']:10s} ({t['reason']}, p={t['prob']})  expected {t['expected']}")
    print("unseen sample, full engine vs model only:")
    for k, v in extras["unseen_engine_sample"].items():
        print(f"   {k:22s} P {v['precision']:.3f}  R {v['recall']:.3f}  FPR {v['fpr']:.4f}  N {v['n']}")


if __name__ == "__main__":
    main()

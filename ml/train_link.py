"""Train the Phisherman link model, measure it honestly, export ONNX + JSON assets.

Data: URL-Phish + pirocheto/phishing-url + PhiUSIIL (label flipped), about 360k URLs, split by
registrable domain so no site is in both train and test. PhiUSIIL is down-weighted (see
PHIUSIIL_WEIGHT) and thresholds are chosen on validation URLs from the other two sources, whose
legitimate URLs have real paths like the links people get.
"""
import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pandas as pd
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import confusion_matrix, precision_recall_curve
from sklearn.model_selection import GroupShuffleSplit

from features import FEATURE_NAMES, features, load_brands, registrable_domain, write_feature_order

ML = Path(__file__).resolve().parent
RAW = ML / "data/raw"
ASSETS = ML.parent / "android/app/src/main/assets"

SUSPICIOUS_MIN_PRECISION = 0.90  # amber: user decides
DANGEROUS_MIN_PRECISION = 0.98   # red: strong warning
# every legitimate PhiUSIIL URL is a bare homepage (no path), so at full weight the model learns
# "has a path = phishing" and flags normal links; at 0.2 it adds phishing variety without that
PHIUSIIL_WEIGHT = 0.2


def load_train_sources():
    a = pd.read_csv(RAW / "url_phish/Dataset.csv", usecols=["url", "label"]).assign(source="url_phish")
    p = pd.concat([pd.read_parquet(RAW / f"pirocheto/{s}.parquet", columns=["url", "status"])
                   for s in ("train", "test")])
    p = p.assign(label=(p.status == "phishing").astype(int), source="pirocheto")[["url", "label", "source"]]
    b = pd.read_csv(RAW / "phiusiil/PhiUSIIL_Phishing_URL_Dataset.csv", usecols=["URL", "label"]).rename(columns={"URL": "url"})
    b = b.assign(label=1 - b.label, source="phiusiil")  # PhiUSIIL: 1 = legitimate; ours: 1 = phishing
    d = pd.concat([a, p, b], ignore_index=True).dropna(subset=["url"])
    d["url"] = d.url.astype(str).str.strip()
    d = d[d.url.str.len() > 0].drop_duplicates("url")
    d["dom"] = d.url.map(registrable_domain)
    return d


def featurize(urls, brands):
    return np.array([features(u, brands) for u in urls], dtype=np.float32)


def report(y, p, thr):
    yhat = (p >= thr).astype(int)
    tn, fp, fn, tp = confusion_matrix(y, yhat, labels=[0, 1]).ravel()
    prec = tp / max(tp + fp, 1)
    rec = tp / max(tp + fn, 1)
    return {"precision": round(prec, 4), "recall": round(rec, 4), "fdr": round(rec, 4),
            "fpr": round(fp / max(fp + tn, 1), 4), "f1": round(2 * prec * rec / max(prec + rec, 1e-9), 4),
            "n": int(len(y)), "n_phishing": int(y.sum()), "tp": int(tp), "fp": int(fp), "fn": int(fn), "tn": int(tn)}


def threshold_for_precision(y, p, min_precision):
    pr, _, th = precision_recall_curve(y, p)
    ok = [t for prec, t in zip(pr[:-1], th) if prec >= min_precision]
    return float(min(ok)) if ok else 0.5


def main():
    t0 = time.time()
    brands = load_brands()
    d = load_train_sources()
    print("train sources:", d.groupby(["source", "label"]).size().to_dict())

    split = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
    tr_idx, te_idx = next(split.split(d, d.label, groups=d.dom))
    tr, te = d.iloc[tr_idx], d.iloc[te_idx]
    assert not set(tr.dom) & set(te.dom), "domain leak between train and test"
    # thresholds are chosen on a validation split carved from TRAIN (also by domain); the test set
    # is used once, for the final numbers, so it never influences any choice
    va_split = GroupShuffleSplit(n_splits=1, test_size=0.15, random_state=7)
    fit_idx, va_idx = next(va_split.split(tr, tr.label, groups=tr.dom))
    tr, va = tr.iloc[fit_idx], tr.iloc[va_idx]
    assert not (set(tr.dom) & set(va.dom)) and not (set(va.dom) & set(te.dom)), "domain leak"

    print(f"rows: train {len(tr)}, validation {len(va)}, test {len(te)}")

    Xtr, Xva, Xte = (featurize(x.url, brands) for x in (tr, va, te))
    ytr, yva, yte = tr.label.values, va.label.values, te.label.values
    print(f"features computed in {time.time() - t0:.0f}s")

    clf = RandomForestClassifier(n_estimators=120, max_depth=14, min_samples_leaf=2,
                                 class_weight="balanced", n_jobs=-1, random_state=42)
    clf.fit(Xtr, ytr, sample_weight=np.where(tr.source.values == "phiusiil", PHIUSIIL_WEIGHT, 1.0))
    pva = clf.predict_proba(Xva)[:, 1]
    pte = clf.predict_proba(Xte)[:, 1]

    real = va.source.values != "phiusiil"
    thr_s = threshold_for_precision(yva[real], pva[real], SUSPICIOUS_MIN_PRECISION)
    thr_d = max(threshold_for_precision(yva[real], pva[real], DANGEROUS_MIN_PRECISION), thr_s)
    te_real = te.source.values != "phiusiil"

    importances = sorted(zip(FEATURE_NAMES, clf.feature_importances_.round(4).tolist()),
                         key=lambda kv: -kv[1])
    results = {
        "generated": time.strftime("%Y-%m-%d"),
        "model": "RandomForest(n_estimators=120, max_depth=14)",
        "feature_version": json.load(open(ASSETS / "feature_order.json"))["version"]
        if (ASSETS / "feature_order.json").exists() else None,
        "datasets": {"train_test": "URL-Phish v2 + pirocheto/phishing-url + PhiUSIIL (UCI 967), split by registrable domain"},
        "rows": {"train": int(len(tr)), "validation": int(len(va)), "test": int(len(te))},
        "thresholds": {"suspicious": thr_s, "dangerous": thr_d},
        "phiusiil_weight": PHIUSIIL_WEIGHT,
        "test": {"suspicious": report(yte, pte, thr_s), "dangerous": report(yte, pte, thr_d)},
        "test_real_paths": {"suspicious": report(yte[te_real], pte[te_real], thr_s),
                            "dangerous": report(yte[te_real], pte[te_real], thr_d)},
        "test_by_source": {s: report(yte[te.source.values == s], pte[te.source.values == s], thr_s)
                           for s in te.source.unique()},
        "feature_importance": importances,
    }

    # export assets
    ASSETS.mkdir(parents=True, exist_ok=True)
    write_feature_order(ASSETS / "feature_order.json")
    results["feature_version"] = json.load(open(ASSETS / "feature_order.json"))["version"]
    onx = convert_sklearn(clf, initial_types=[("input", FloatTensorType([None, Xtr.shape[1]]))],
                          options={id(clf): {"zipmap": False}}, target_opset=17)
    (ASSETS / "link_model.onnx").write_bytes(onx.SerializeToString())

    thresholds_path = ASSETS / "thresholds.json"
    thresholds = json.load(open(thresholds_path)) if thresholds_path.exists() else {}
    thresholds["link"] = {"suspicious": thr_s, "dangerous": thr_d,
                          "rule": f"precision >= {SUSPICIOUS_MIN_PRECISION} / {DANGEROUS_MIN_PRECISION} on validation URLs from URL-Phish + pirocheto (not the test set)"}
    json.dump(thresholds, open(thresholds_path, "w"), indent=2)

    # the exported model must reproduce sklearn
    sess = ort.InferenceSession(str(ASSETS / "link_model.onnx"))
    sample = Xte[:2000]
    p_onnx = sess.run(None, {"input": sample})[1][:, 1]
    diff = float(np.abs(p_onnx - clf.predict_proba(sample)[:, 1]).max())
    results["onnx_max_abs_diff"] = diff
    assert diff < 0.01, f"ONNX differs from sklearn by {diff}"
    results["onnx_outputs"] = [o.name for o in sess.get_outputs()]
    results["onnx_bytes"] = (ASSETS / "link_model.onnx").stat().st_size

    json.dump(results, open(ML / "results_link.json", "w"), indent=2)
    print(json.dumps({k: results[k] for k in ("thresholds", "test", "test_by_source")}, indent=1))
    print("top features:", importances[:6])
    print(f"onnx: {results['onnx_bytes'] / 1e6:.1f} MB, outputs {results['onnx_outputs']}, max diff {diff:.2e}")
    print(f"done in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()

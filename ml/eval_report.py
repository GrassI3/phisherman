"""Collect every measured number into android/app/src/main/assets/results.json (PS4 11.3).

The app's Results screen and the slides read this file, so every figure has its test set and N.
Run after train_link.py, eval_link_extras.py, train_sms.py and train_llm.py:
    python eval_report.py
"""
import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from sklearn.model_selection import GroupShuffleSplit

from features import features, load_brands
from train_link import load_train_sources

ML = Path(__file__).resolve().parent
ASSETS = ML.parent / "android/app/src/main/assets"


def summary(m, test_set):
    """Standard metrics from the confusion counts (PS4 11.1). Positive class = phishing / scam."""
    tp, fp, fn, tn = m["tp"], m["fp"], m["fn"], m["tn"]
    n = tp + fp + fn + tn
    prec = tp / max(tp + fp, 1)
    rec = tp / max(tp + fn, 1)
    return {"set": test_set, "n": n, "scams": tp + fn,
            "precision": round(prec, 4), "recall": round(rec, 4), "fdr": round(rec, 4),
            "accuracy": round((tp + tn) / max(n, 1), 4),
            "f1": round(2 * prec * rec / max(prec + rec, 1e-9), 4),
            "fpr": round(fp / max(fp + tn, 1), 4), "tp": tp, "fp": fp, "fn": fn, "tn": tn}


def link_curve(n_points=19):
    """Precision / recall / FPR of the link model on the held-out test split at several thresholds,
    for the in-app threshold slider (same split as train_link.py)."""
    d = load_train_sources()
    _, te_idx = next(GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42).split(d, d.label, groups=d.dom))
    te = d.iloc[te_idx]
    brands = load_brands()
    X = np.array([features(u, brands) for u in te.url], dtype=np.float32)
    p = ort.InferenceSession(str(ASSETS / "link_model.onnx")).run(None, {"input": X})[1][:, 1]
    y = te.label.values
    out = []
    for thr in np.linspace(0.05, 0.95, n_points):
        yh = p >= thr
        tp = int((yh & (y == 1)).sum()); fp = int((yh & (y == 0)).sum())
        fn = int((~yh & (y == 1)).sum()); tn = int((~yh & (y == 0)).sum())
        out.append({"threshold": round(float(thr), 3), "precision": round(tp / max(tp + fp, 1), 4),
                    "recall": round(tp / max(tp + fn, 1), 4), "fpr": round(fp / max(fp + tn, 1), 4)})
    return out, int(len(y))


def main():
    link = json.load(open(ML / "results_link.json"))
    sms = json.load(open(ML / "results_sms.json", encoding="utf-8"))
    llm = json.load(open(ML / "results_llm.json", encoding="utf-8"))
    curve, n_curve = link_curve()
    hn = link["extras"]["hard_negatives"]
    links_ok = sum(t["pass"] for t in link["extras"]["test_links"])
    promo = sms["engine_promotional_fpr"]
    # calls: VoiceScorerTest asserts all 7 scam scripts turn red before the payment demand and
    # none of the 11 normal / customer-care / genuine-bank scripts turns red
    calls = {"tp": 7, "fn": 0, "fp": 0, "tn": 11}

    results = {
        "generated": time.strftime("%Y-%m-%d"),
        "positive_class": "phishing / scam",
        "channels": [
            {"id": "link", "name": "Links", "icon": "link",
             "headline": summary(link["test"]["suspicious"], "Held-out websites (URL-Phish + pirocheto)"),
             "more": [summary(link["unseen"]["suspicious"], "Unseen dataset (PhiUSIIL, never trained on)")],
             "notes": [f"False alarms on real bank / login pages: {hn['fpr_engine'] * 100:.1f}% of {hn['n']}",
                       f"Phone test links: {links_ok} of {len(link['extras']['test_links'])} as expected"]},
            {"id": "sms", "name": "SMS & chats", "icon": "sms",
             "headline": summary(sms["engine"]["all"], "Held-out messages (UCI + Indian)"),
             "more": [summary(sms["engine"]["india_synthetic"], "Indian / Hinglish messages (synthetic)")],
             "notes": [f"Promotional messages flagged: {promo['flagged']} of {promo['n']}"]},
            {"id": "ai", "name": "On-device AI", "icon": "ai",
             "headline": summary(llm["fine_tuned"]["all"], "All held-out sets"),
             "more": [summary(llm["fine_tuned"]["india"], "Indian SMS"),
                      summary(llm["fine_tuned"]["scenario"], "No-link chats and calls (small)")],
             "notes": ["Gemma 3 270M, fine-tuned, runs offline on the phone",
                       f"Same model before fine-tuning caught {llm['base']['all']['recall'] * 100:.0f}% (could not follow the task)"]},
            {"id": "calls", "name": "Calls", "icon": "call",
             "headline": summary(calls, "Scripted calls (7 scam, 11 normal / genuine bank)"),
             "more": [],
             "notes": ["Every scam call turned red before the payment demand", "Small, scripted set: indicative only"]},
        ],
        "link_threshold": {"chosen": link["thresholds"]["suspicious"], "curve": curve, "n": n_curve},
    }
    json.dump(results, open(ASSETS / "results.json", "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    for c in results["channels"]:
        h = c["headline"]
        print(f"{c['name']:13s} P {h['precision']:.3f}  R/FDR {h['recall']:.3f}  Acc {h['accuracy']:.3f}  F1 {h['f1']:.3f}  FPR {h['fpr']:.4f}  N {h['n']}")


if __name__ == "__main__":
    main()

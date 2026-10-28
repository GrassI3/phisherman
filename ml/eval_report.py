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
    # calls: held-out calls (train_calls.py split) replayed through the phone's CallSession by
    # VoiceDatasetEval; alert = amber or red. "combined" = rules + trained model, what the app runs
    voice = json.load(open(ML / "results_voice.json", encoding="utf-8"))
    calls_model = json.load(open(ML / "results_calls_model.json", encoding="utf-8"))
    scripts = calls_model["test"]["scripts"]["alert"]

    results = {
        "generated": time.strftime("%Y-%m-%d"),
        "positive_class": "phishing / scam",
        "channels": [
            {"id": "link", "name": "Links", "icon": "link",
             "headline": summary(link["test_real_paths"]["suspicious"], "Held-out websites with real paths (URL-Phish + pirocheto)"),
             "more": [summary(link["test_by_source"]["phiusiil"], "Held-out PhiUSIIL websites (homepages)"),
                      summary(link["test"]["suspicious"], "All held-out websites (3 datasets)")],
             "notes": [f"False alarms on real bank / login pages: {hn['fpr_engine'] * 100:.1f}% of {hn['n']}",
                       f"Phone test links: {links_ok} of {len(link['extras']['test_links'])} as expected",
                       "Trained on about 290k URLs from 3 public datasets; sites in the test set were never trained on"]},
            {"id": "sms", "name": "SMS & chats", "icon": "sms",
             "headline": summary(sms["engine"]["all"], "Held-out messages (UCI + Indian)"),
             "more": [summary(sms["engine"]["india_hf"], "Indian bank / UPI / scam messages (unseen templates)"),
                      summary(sms["engine"]["uci"], "UCI SMS (real messages)"),
                      summary(sms["engine"]["india_synthetic"], "Indian / Hinglish messages (hand-written)")],
             "notes": [f"Promotional messages flagged: {promo['flagged']} of {promo['n']}"]},
            {"id": "ai", "name": "On-device AI", "icon": "ai",
             "headline": summary(llm["fine_tuned"]["all"], "All held-out sets"),
             "more": [summary(llm["fine_tuned"]["india"], "Indian SMS"),
                      summary(llm["fine_tuned"]["scenario"], "No-link chats and calls (small)")],
             "notes": ["Gemma 3 270M, fine-tuned, runs offline on the phone",
                       f"Same model before fine-tuning caught {llm['base']['all']['recall'] * 100:.0f}% (could not follow the task)"]},
            {"id": "calls", "name": "Calls", "icon": "call",
             "headline": summary(voice["all"]["combined"]["alert"], "Held-out calls + everyday conversations, any alert"),
             "more": [summary(voice["all"]["rules"]["alert"], "Same calls, phrase rules only (before training)"),
                      summary(voice["ysangam"]["combined"]["alert"], "Hinglish scam calls (unseen templates)"),
                      summary(voice["bothbosu"]["combined"]["alert"], "English scam dialogues (synthetic)"),
                      summary(scripts, "Our 18 hand-written Indian calls (never trained on)")],
             "notes": ["Trained model + phrase rules, on the caller's words from Live Caption",
                       "Public call datasets are synthetic and easy; real calls will be harder than these numbers",
                       "The on-device AI adds a second opinion on calls (amber on its own)"]},
        ],
        "link_threshold": {"chosen": link["thresholds"]["suspicious"], "curve": curve, "n": n_curve},
    }
    json.dump(results, open(ASSETS / "results.json", "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    for c in results["channels"]:
        h = c["headline"]
        print(f"{c['name']:13s} P {h['precision']:.3f}  R/FDR {h['recall']:.3f}  Acc {h['accuracy']:.3f}  F1 {h['f1']:.3f}  FPR {h['fpr']:.4f}  N {h['n']}")


if __name__ == "__main__":
    main()

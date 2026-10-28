"""Training / test data for the on-device scam LLM (Gemma 3 270M, fine-tuned).

Task: read one message, a short chat, or a call transcript and answer "LABEL | reason".
The prompt template here must match the app exactly (sms/ScamLlm.kt + the .task bundle).
"""
import csv
import json
import random
import re
from pathlib import Path

import pandas as pd
from sklearn.model_selection import train_test_split

ML = Path(__file__).resolve().parent
LABELS = ["SAFE", "SUSPICIOUS", "SCAM"]
REASONS = ["none", "promo", "credential_request", "payment_request", "authority_impersonation",
           "threat_urgency", "prize_lottery", "job_investment", "remote_access", "family_impersonation",
           "secrecy", "link_lure", "other_scam"]
MAX_TEXT = 600

# Gemma chat turns; the .task bundle adds PROMPT_PREFIX / PROMPT_SUFFIX around user_prompt()
PROMPT_PREFIX = "<start_of_turn>user\n"
PROMPT_SUFFIX = "<end_of_turn>\n<start_of_turn>model\n"


def user_prompt(source, sender, text):
    text = text.replace(" || ", "\n")[:MAX_TEXT]
    return f"Scam check. Source: {source or 'unknown'}. From: {sender or 'unknown'}.\n{text}\nReply: LABEL | reason"


def answer(label, reason):
    return f"{label} | {reason}"


def full_example(source, sender, text, label, reason):
    return PROMPT_PREFIX + user_prompt(source, sender, text) + PROMPT_SUFFIX + answer(label, reason) + "<end_of_turn>"


_REASON_RULES = [
    ("remote_access", r"anydesk|teamviewer|quick ?support|rustdesk|\.apk|install .{0,30}app"),
    ("credential_request", r"\botp\b|\bpin\b|cvv|password|card number|card details|bank details|account details"),
    ("authority_impersonation", r"police|cbi|customs|\brbi\b|income tax|trai|officer|cyber cell"),
    ("prize_lottery", r"\bwon\b|winner|prize|lottery|claim|reward|congratulations|lucky|free entry|mubarak"),
    ("job_investment", r"\bjob\b|earn|income|invest|returns|crypto|\bloan\b|work from home"),
    ("threat_urgency", r"block|suspend|disconnect|expire|deactivat|arrest|urgent|band ho|kat jayega|बंद"),
    ("payment_request", r"\bpay\b|\bfee\b|send money|transfer|bhej"),
    ("link_lure", r"https?://|www\.|\.com|\.example|bit\.ly"),
]


def guess_reason(text):
    t = text.lower()
    for reason, rx in _REASON_RULES:
        if re.search(rx, t):
            return reason
    return "other_scam"


def load_sources(seed=42):
    rows = []
    uci = pd.read_csv(ML / "data/raw/uci_sms/SMSSpamCollection", sep="\t", header=None,
                      names=["label", "text"], quoting=csv.QUOTE_NONE, encoding="utf-8")
    ham = uci[uci.label == "ham"].sample(1200, random_state=seed)
    spam = uci[uci.label == "spam"]
    for t in ham.text:
        rows.append(dict(src="uci", source="SMS", sender="", text=t, label="SAFE", reason="none"))
    for t in spam.text:
        rows.append(dict(src="uci", source="SMS", sender="", text=t, label="SCAM", reason=guess_reason(t)))

    ind = pd.read_csv(ML / "sms_india_synthetic.tsv", sep="\t", quoting=csv.QUOTE_NONE, encoding="utf-8")
    for r in ind.itertuples():
        label = "SCAM" if r.label == "phishing" else "SAFE"
        reason = guess_reason(r.text) if label == "SCAM" else ("promo" if r.label == "promo" else "none")
        rows.append(dict(src="india", source="SMS", sender=str(r.sender), text=r.text, label=label, reason=reason))

    sc = pd.read_csv(ML / "llm_scenarios.tsv", sep="\t", quoting=csv.QUOTE_NONE, encoding="utf-8")
    for r in sc.itertuples():
        rows.append(dict(src="scenario", source=r.source, sender=str(r.sender), text=r.text, label=r.label, reason=r.reason,
                         kind=r.kind))
    d = pd.DataFrame(rows).drop_duplicates("text")
    d["kind"] = d.get("kind", pd.Series(dtype=str)).fillna("msg")
    assert set(d.label) <= set(LABELS) and set(d.reason) <= set(REASONS), (set(d.label), set(d.reason) - set(REASONS))
    return d


def split(d, seed=42):
    tr, te = [], []
    for src, frac in (("uci", 0.2), ("india", 0.3), ("scenario", 0.3)):
        s = d[d.src == src]
        a, b = train_test_split(s, test_size=frac, stratify=s.label, random_state=seed)
        tr.append(a); te.append(b)
    return pd.concat(tr), pd.concat(te)


def to_records(df, weights=None):
    out = []
    for r in df.itertuples():
        n = (weights or {}).get(r.src, 1)
        out += [{"prompt": PROMPT_PREFIX + user_prompt(r.source, r.sender, r.text) + PROMPT_SUFFIX,
                 "completion": answer(r.label, r.reason) + "<end_of_turn>",
                 "label": r.label, "src": r.src, "kind": r.kind}] * n
    random.Random(0).shuffle(out)
    return out


if __name__ == "__main__":
    d = load_sources()
    tr, te = split(d)
    print(d.groupby(["src", "label"]).size().to_dict())
    print("train", len(tr), "test", len(te))
    print(full_example("WhatsApp", "+91 98450 12345", "Hi mom, new number, send 15000", "SCAM", "family_impersonation"))
    json.dump({"labels": LABELS, "reasons": REASONS, "prompt_prefix": PROMPT_PREFIX, "prompt_suffix": PROMPT_SUFFIX},
              open(ML / "llm_format.json", "w"), indent=2)

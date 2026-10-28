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


URL_RE = re.compile(r"(?:https?://|www\.)\S+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|in|net|org|co|ly|xyz|top|info|link|site|online|app|me|io|example|sbi)(?:/\S*)?",
                    re.IGNORECASE)


def strip_links(text):
    """Links are judged by the link engine; in the training data 'has a link' mostly meant spam."""
    return re.sub(r"[ \t]+", " ", URL_RE.sub(" ", text)).strip()


def user_prompt(source, sender, text):
    text = strip_links(text.replace(" || ", "\n"))[:MAX_TEXT]
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
    rows += load_hf_sources(set(uci.text))
    rows += genuine_bank_messages()
    d = pd.DataFrame(rows).drop_duplicates("text")
    d["kind"] = d.get("kind", pd.Series(dtype=str)).fillna("msg")
    assert set(d.label) <= set(LABELS) and set(d.reason) <= set(REASONS), (set(d.label), set(d.reason) - set(REASONS))
    return d


HF = ML / "data/raw/hf_scam"
SOURCES = (("uci", 0.2), ("india", 0.3), ("scenario", 0.3), ("hf_calls", 0.2), ("hf_dialogue", 0.2), ("hf_hinglish", 0.3),
           ("bank_genuine", 0.3))


def _dialogue_window(dialogue):
    """A ~MAX_TEXT window from a third of the way into a call, where the ask usually happens."""
    turns = re.split(r"\s*(?=\b(?:caller|receiver):)", dialogue.strip())
    turns = [t.replace("caller:", "Caller:").replace("receiver:", "You:").strip() for t in turns if t.strip()]
    start = len(turns) // 3
    out = []
    for t in turns[start:]:
        if sum(len(x) for x in out) + len(t) > MAX_TEXT:
            break
        out.append(t)
    return " || ".join(out or turns[-2:])


_BANKS = ["SBI", "HDFC Bank", "ICICI Bank", "Axis Bank", "Kotak", "PNB", "Bank of Baroda", "IOB", "Canara Bank", "Paytm", "PhonePe", "Amazon", "Flipkart", "IRCTC", "Zomato", "Swiggy"]
_OTP_TEMPLATES = [
    "{code} is your OTP for login to {b}. Do not share it with anyone. {b} never asks for OTP.",
    "Your OTP for {b} transaction of Rs {amt} is {code}. Valid for 10 minutes. Never share your OTP with anyone.",
    "Dear Customer, use OTP {code} to verify your mobile number on {b}. Do not share this code.",
    "{code} is the verification code for your {b} account. If you did not request this, ignore this message.",
    "OTP for your {b} order is {code}. Share it only with the delivery agent at your doorstep.",
    "Aapka {b} OTP {code} hai. Ise kisi ke saath share na karein.",
]
_TXN_TEMPLATES = [
    "Rs {amt} debited from your {b} account XX{acct} on {date}. Not you? Call the number on the back of your card.",
    "Your {b} account XX{acct} is credited with Rs {amt} on {date}. Available balance Rs {bal}.",
    "Rs {amt} paid to {shop} via {b} UPI. Ref {ref}.",
    "Your {b} credit card statement is ready. Total due Rs {amt}, due date {date}.",
    "EMI of Rs {amt} for loan account XX{acct} is due on {date}. Please keep sufficient balance. - {b}",
]


def genuine_bank_messages(n=60, seed=7):
    """Templated genuine OTP-delivery and transaction alerts (synthetic): teaches that delivering
    an OTP is normal and only asking for one is a scam (UCI has almost none of these)."""
    rnd = random.Random(seed)
    rows = []
    for i in range(n):
        b = rnd.choice(_BANKS)
        tpl = rnd.choice(_OTP_TEMPLATES if i % 2 == 0 else _TXN_TEMPLATES)
        text = tpl.format(b=b, code=rnd.randint(100000, 999999), amt=f"{rnd.randint(50, 90000):,}", acct=rnd.randint(1000, 9999),
                          date=f"{rnd.randint(1, 28):02d}-{rnd.choice(['Oct', 'Nov', 'Dec'])}-26", bal=f"{rnd.randint(1000, 200000):,}",
                          shop=rnd.choice(["Sharma Stores", "Big Bazaar", "Cafe Coffee Day", "Apollo Pharmacy", "Reliance Fresh"]),
                          ref=rnd.randint(10**11, 10**12 - 1))
        rows.append(dict(src="bank_genuine", source=rnd.choice(["SMS", "Messages"]), sender=rnd.choice(["AD-", "VM-", "JD-"]) + b.upper().replace(" ", "")[:6],
                         text=text, label="SAFE", reason="none", kind="msg"))
    return rows


def load_hf_sources(uci_texts):
    """Public datasets from Hugging Face (see README for licences and caveats)."""
    rows = []
    if (HF / "india_hinglish_calls.csv").exists():  # ysangam, Apache-2.0: 10k rows but only ~743 unique
        a = pd.read_csv(HF / "india_hinglish_calls.csv").drop_duplicates("text")
        for r in a.itertuples():
            label = "SCAM" if r.label == 1 else "SAFE"
            rows.append(dict(src="hf_calls", source="Call", sender="Unknown caller", text=f"Caller: {r.text}",
                             label=label, reason=guess_reason(r.text) if label == "SCAM" else "none", kind="call"))
    if (HF / "scam_dialogue_all.csv").exists():  # BothBosu/scam-dialogue, Apache-2.0, synthetic English calls
        b = pd.read_csv(HF / "scam_dialogue_all.csv").drop_duplicates("dialogue")
        for r in b.itertuples():
            text = _dialogue_window(r.dialogue)
            label = "SCAM" if r.label == 1 else "SAFE"
            rows.append(dict(src="hf_dialogue", source="Call", sender="Unknown caller", text=text,
                             label=label, reason=guess_reason(text) if label == "SCAM" else "none", kind="call"))
    if (HF / "hinglish_scam_text.json").exists():  # bolewara, CC-BY-4.0: mostly UCI copies, keep the rest
        j = json.load(open(HF / "hinglish_scam_text.json", encoding="utf-8"))["rows"]
        for r in j:
            if r["text"] in uci_texts:
                continue
            label = "SCAM" if r["label"] == 1 else "SAFE"
            rows.append(dict(src="hf_hinglish", source="SMS", sender="", text=r["text"], label=label,
                             reason=guess_reason(r["text"]) if label == "SCAM" else "none", kind="msg"))
    return rows


def split(d, seed=42):
    tr, te = [], []
    for src, frac in SOURCES:
        if src not in set(d.src):
            continue
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

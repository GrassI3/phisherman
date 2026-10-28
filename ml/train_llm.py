"""LoRA fine-tune Gemma 3 270M (instruction-tuned) as an on-device scam checker, then merge.

Runs on the laptop GPU. Output: ml/llm/merged (Hugging Face safetensors + tokenizer), which
convert_llm.sh turns into a .task file for MediaPipe on the phone. Writes results_llm.json.
"""
import json
import math
import re
import time
from pathlib import Path

import torch
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer

from llm_data import LABELS, load_sources, split, to_records

ML = Path(__file__).resolve().parent
OUT = ML / "llm"
BASE = "unsloth/gemma-3-270m-it"  # ungated mirror of google/gemma-3-270m-it (Gemma terms apply)
MAX_LEN = 320
EPOCHS = 3
BATCH = 8
LR = 2e-4
WEIGHTS = {"uci": 1, "india": 3, "scenario": 6}  # the Indian and scenario data are small: weight them up

ANSWER_RE = re.compile(r"(SAFE|SUSPICIOUS|SCAM)\s*\|\s*([a-z_]+)")


def encode(tok, rec):
    p = tok(rec["prompt"], add_special_tokens=True)["input_ids"]  # adds <bos>
    c = tok(rec["completion"], add_special_tokens=False)["input_ids"]
    ids = (p + c)[:MAX_LEN]
    labels = ([-100] * len(p) + c)[:MAX_LEN]  # loss on the answer only
    return ids, labels


def batches(tok, recs, bs):
    for i in range(0, len(recs), bs):
        enc = [encode(tok, r) for r in recs[i:i + bs]]
        n = max(len(x[0]) for x in enc)
        ids = torch.full((len(enc), n), tok.pad_token_id)
        lab = torch.full((len(enc), n), -100)
        att = torch.zeros((len(enc), n), dtype=torch.long)
        for j, (a, b) in enumerate(enc):
            ids[j, :len(a)] = torch.tensor(a); lab[j, :len(b)] = torch.tensor(b); att[j, :len(a)] = 1
        yield ids, lab, att


@torch.no_grad()
def predict(model, tok, prompts, bs=16):
    out = []
    tok.padding_side = "left"
    for i in range(0, len(prompts), bs):
        enc = tok(prompts[i:i + bs], return_tensors="pt", padding=True, add_special_tokens=True).to(model.device)
        gen = model.generate(**enc, max_new_tokens=10, do_sample=False)
        out += tok.batch_decode(gen[:, enc["input_ids"].shape[1]:], skip_special_tokens=True)
    tok.padding_side = "right"
    return out


def parse(text):
    m = ANSWER_RE.search(text)
    return (m.group(1), m.group(2)) if m else ("SAFE", "unparsed")


def metrics(y_true, y_pred):
    pos = {"SCAM", "SUSPICIOUS"}
    tp = sum(t in pos and p in pos for t, p in zip(y_true, y_pred))
    fp = sum(t not in pos and p in pos for t, p in zip(y_true, y_pred))
    fn = sum(t in pos and p not in pos for t, p in zip(y_true, y_pred))
    tn = len(y_true) - tp - fp - fn
    prec = tp / max(tp + fp, 1); rec = tp / max(tp + fn, 1)
    return {"precision": round(prec, 4), "recall": round(rec, 4), "fpr": round(fp / max(fp + tn, 1), 4),
            "f1": round(2 * prec * rec / max(prec + rec, 1e-9), 4), "n": len(y_true), "tp": tp, "fp": fp, "fn": fn, "tn": tn}


def evaluate(model, tok, te, tag):
    recs = to_records(te)
    preds = [parse(t) for t in predict(model, tok, [r["prompt"] for r in recs])]
    res = {"all": metrics([r["label"] for r in recs], [p[0] for p in preds])}
    for src in ("uci", "india", "scenario"):
        idx = [i for i, r in enumerate(recs) if r["src"] == src]
        res[src] = metrics([recs[i]["label"] for i in idx], [preds[i][0] for i in idx])
    res["unparsed"] = sum(p[1] == "unparsed" for p in preds)
    print(tag, json.dumps({k: (v if not isinstance(v, dict) else {m: v[m] for m in ("precision", "recall", "fpr", "n")}) for k, v in res.items()}))
    return res, recs, preds


def main():
    t0 = time.time()
    torch.manual_seed(0)
    d = load_sources()
    tr, te = split(d)
    train = to_records(tr, WEIGHTS)
    print(f"train examples {len(train)} (weighted), test {len(te)}; cuda={torch.cuda.is_available()}")

    tok = AutoTokenizer.from_pretrained(BASE)
    model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype=torch.bfloat16, attn_implementation="eager").cuda()
    base_res, _, _ = evaluate(model, tok, te, "base (no fine-tune)")

    model = get_peft_model(model, LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
                                             target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]))
    model.print_trainable_parameters()
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=LR, weight_decay=0.0)
    steps = EPOCHS * math.ceil(len(train) / BATCH)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / 30) * max(0.0, 1 - s / steps))
    model.train()
    step = 0
    for ep in range(EPOCHS):
        recs = train[:]
        __import__("random").Random(ep).shuffle(recs)
        for ids, lab, att in batches(tok, recs, BATCH):
            loss = model(input_ids=ids.cuda(), attention_mask=att.cuda(), labels=lab.cuda()).loss
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step(); sched.step(); opt.zero_grad()
            step += 1
            if step % 50 == 0:
                print(f"epoch {ep} step {step}/{steps} loss {loss.item():.4f} ({time.time() - t0:.0f}s)")
    model.eval()
    model = model.merge_and_unload()
    res, recs, preds = evaluate(model, tok, te, "fine-tuned")

    OUT.mkdir(exist_ok=True)
    merged = OUT / "merged"
    model.save_pretrained(merged, safe_serialization=True)
    tok.save_pretrained(merged)
    misses = [{"src": r["src"], "kind": r["kind"], "true": r["label"], "pred": p, "text": r["prompt"].split("\n", 2)[2][:160]}
              for r, p in zip(recs, preds) if (r["label"] != "SAFE") != (p[0] != "SAFE")]
    json.dump({"generated": time.strftime("%Y-%m-%d"), "base_model": BASE, "lora": {"r": 16, "alpha": 32}, "epochs": EPOCHS,
               "train_examples_weighted": len(train), "base": base_res, "fine_tuned": res, "errors": misses},
              open(ML / "results_llm.json", "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    print(f"saved {merged} in {time.time() - t0:.0f}s; {len(misses)} errors")


if __name__ == "__main__":
    main()

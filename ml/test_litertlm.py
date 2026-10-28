"""Smoke test of the converted on-device model (.litertlm) with the LiteRT-LM runtime (CPU).

Checks that conversion kept the fine-tuned behaviour and that the runtime's prompt template
renders exactly the training prompt. Runs in the WSL converter env:
    python ml/test_litertlm.py ml/llm/phisherman_scam.litertlm
"""
import sys
import time

import litert_lm

from llm_data import PROMPT_PREFIX, PROMPT_SUFFIX, user_prompt

CASES = [
    ("WhatsApp", "+91 98450 12345", "Hi mom, I lost my phone, this is my new number. Please send Rs 15,000 urgently, will explain later", "SCAM"),
    ("Call", "Unknown caller", "Caller: Hello, I am calling from SBI head office. || Caller: Your account will be blocked today. || Caller: Tell me the OTP you just received.", "SCAM"),
    ("WhatsApp", "Sanjay (Manager)", "Them: Did the vendor invoice come? || You: Yes, approved || Them: Great, process it through the usual payment system by Friday", "SAFE"),
    ("SMS", "AD-HDFCBK", "482913 is your OTP for login. Do not share it with anyone. HDFC Bank never asks for your OTP", "SAFE"),
    ("Instagram", "crypto.queen.official", "Hey dear, I trade crypto and make good money. Start with just 20k, trust me, I will guide you", "SCAM"),
    ("WhatsApp", "Mummy", "Beta khana kha liya? Aaj papa jaldi aa rahe hain", "SAFE"),
]


def main(path):
    engine = litert_lm.Engine(path)
    ok = 0
    for i, (src, sender, text, want) in enumerate(CASES):
        conv = engine.create_conversation()
        prompt = user_prompt(src, sender, text)
        if i == 0:
            rendered = conv.render_message_to_string(prompt) if hasattr(conv, "render_message_to_string") else None
            expected = PROMPT_PREFIX + prompt + PROMPT_SUFFIX
            print("template matches training:", rendered is not None and expected in rendered)
        t0 = time.time()
        out = conv.send_message(prompt)
        text_out = out if isinstance(out, str) else str(out)
        got = "SCAM" if "SCAM" in text_out else "SUSPICIOUS" if "SUSPICIOUS" in text_out else "SAFE" if "SAFE" in text_out else "?"
        ok += got == want
        print(f"{'ok ' if got == want else 'BAD'} want {want:4s} got {text_out.strip()[:40]!r} ({(time.time() - t0) * 1000:.0f} ms)  <- {text[:60]}")
        conv.close()
    print(f"{ok}/{len(CASES)} correct")
    engine.close()


if __name__ == "__main__":
    main(sys.argv[1])

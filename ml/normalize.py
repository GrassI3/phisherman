"""SMS normaliser (source of truth). Ported 1:1 to Kotlin (sms/SmsNormalizer.kt).

Only explicit ASCII character classes are used in regexes, never \\b / \\w / \\s, because Python
and Java disagree on those for Devanagari. Bump NORMALIZER_VERSION when anything changes.
"""
import re

NORMALIZER_VERSION = "sms-norm-2"

# links with a scheme, www., or a bare host with a common TLD (SMS links often omit the scheme)
URL_RE = re.compile(
    r"(?:https?://|www\.)[^ \t\r\n]+"
    r"|(?<![a-z0-9@.-])[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|in|net|org|co|ly|xyz|top|info|link|site|online|app|me|io|example|sbi|gov\.in|co\.in)(?:/[^ \t\r\n]*)?(?![a-z0-9])"
)
LONG_NUM_RE = re.compile(r"(?<![0-9])[0-9]{9,}(?![0-9])")
OTP_RE = re.compile(r"(?<![0-9a-z])[0-9]{4,8}(?![0-9a-z])")
TOKEN_EDGE = "(?<![a-z0-9]){}(?![a-z0-9])"

# Hinglish / spelling variants -> one form (PS4 8.2). Keys are matched as whole ASCII words.
VARIANTS = {
    "k.y.c": "kyc", "e-kyc": "ekyc", "aadhar": "aadhaar", "adhaar": "aadhaar", "adhar": "aadhaar",
    "blocked": "block", "band": "block", "bandh": "block", "suspended": "suspend",
    "turant": "jaldi", "fauran": "jaldi", "immediately": "urgent", "urgently": "urgent",
    "acount": "account", "a/c": "account", "acc": "account", "khaata": "khata",
    "plz": "please", "pls": "please", "bhejiye": "bhejo", "bhejein": "bhejo", "bhej": "bhejo",
    "batayein": "batao", "bataye": "batao", "bataiye": "batao", "bataen": "batao",
}
_VARIANT_RES = [(re.compile(TOKEN_EDGE.format(re.escape(k))), v)
                for k, v in sorted(VARIANTS.items(), key=lambda kv: -len(kv[0]))]

# digit-for-letter tricks inside mostly-letter words ("cl1ck h3re"); leaves "xx4321", "28oct26"
LEET = {"0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t"}
WORD_RE = re.compile(r"[a-z0-9]+")


def _deleet(m):
    w = m.group(0)
    letters = sum("a" <= c <= "z" for c in w)
    if len(w) >= 3 and letters > len(w) - letters and all(c in LEET for c in w if not "a" <= c <= "z"):
        return "".join(LEET.get(c, c) for c in w)
    return w


def collapse_ws(s):
    return " ".join(s.split())


def normalize(text):
    """Return (normalised_text, urls). URLs are extracted before any rewriting.

    Links are removed from the text, not replaced by a token: in the training data almost every
    message with a link is spam, so a "has a link" token made the model flag any link (YouTube,
    Wikipedia). Links are judged only by the link engine.
    """
    s = "".join(" " if ord(c) > 0xFFFF else c for c in text)  # emoji and other astral symbols
    s = collapse_ws(s.lower())
    urls = [m.group(0) for m in URL_RE.finditer(s)]
    s = URL_RE.sub(" ", s)
    s = LONG_NUM_RE.sub(" numtoken ", s)
    s = OTP_RE.sub(" otpcode ", s)
    s = WORD_RE.sub(_deleet, s)
    for rx, v in _VARIANT_RES:
        s = rx.sub(v, s)
    return collapse_ws(s), urls

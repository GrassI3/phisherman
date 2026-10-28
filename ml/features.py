"""URL features for the Phisherman link model (source of truth).

Every rule here is ported 1:1 to Kotlin (UrlFeatures.kt). Change both sides together,
bump FEATURE_VERSION, and rerun the parity test.
"""
import json
import math
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit

FEATURE_VERSION = "link-features-2"

FEATURE_NAMES = [
    "url_len", "host_len", "dot_count", "subdomain_depth", "digit_ratio",
    "hyphen_count_host", "special_chars", "has_at", "has_ip_host", "is_https",
    "host_entropy", "is_shortener", "susp_words", "path_len", "num_params",
    "brand_min_lev", "brand_in_subdomain", "tld_risk", "free_host",
]

# Multi-part public suffixes we handle; the same list lives in Kotlin.
MULTI = {
    "co.in", "gov.in", "nic.in", "ac.in", "org.in", "net.in", "edu.in", "res.in", "firm.in", "gen.in", "ind.in",
    "co.uk", "ac.uk", "gov.uk", "org.uk", "ltd.uk", "plc.uk",
    "com.au", "edu.au", "gov.au", "org.au", "net.au",
    "co.jp", "ac.jp", "or.jp", "ne.jp", "go.jp",
    "com.br", "gov.br", "org.br", "com.cn", "edu.cn", "gov.cn",
    "co.nz", "ac.nz", "co.za", "ac.za", "com.sg", "edu.sg", "com.my", "com.pk", "com.bd",
    "co.kr", "ac.kr", "com.tr", "com.mx", "com.ar", "co.id", "ac.id",
}
SHORTENERS = {
    "bit.ly", "t.co", "tinyurl.com", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "ow.ly",
    "buff.ly", "shorturl.at", "tiny.cc", "rebrand.ly", "s.id", "t.ly", "v.gd",
}
SUSP = ["login", "verify", "kyc", "update", "secure", "bonus", "refund", "otp",
        "account", "suspend", "confirm", "reward"]
RISKY_TLD = {"xyz", "top", "icu", "cfd", "click", "buzz", "sbs", "rest"}  # tunable, keep small
# Platforms where anyone can create a subdomain in minutes; matched on the registrable domain.
FREE_HOSTS = {
    "000webhostapp.com", "weebly.com", "weeblysite.com", "wixsite.com", "godaddysites.com", "square.site", "webflow.io",
    "firebaseapp.com", "web.app", "netlify.app", "vercel.app", "github.io", "gitlab.io", "pages.dev",
    "workers.dev", "glitch.me", "herokuapp.com", "repl.co", "blogspot.com", "wordpress.com",
    "sites.google.com", "carrd.co", "framer.app", "cloudaccess.host", "xsph.ru", "zzz.com.ua",
    "duckdns.org", "ngrok.io", "ngrok-free.app", "trycloudflare.com", "azurewebsites.net",
    "appspot.com", "r2.dev", "ipfs.io", "mybluehost.me", "yolasite.com", "jimdosite.com",
}
SPECIALS = "@?=&%_"
MAX_URL_LEN = 2048  # longer URLs are truncated before feature extraction

IPV4 = re.compile(r"\d{1,3}(\.\d{1,3}){3}")

_BRANDS_PATH = Path(__file__).resolve().parent.parent / "android/app/src/main/assets/brands.json"


def load_brands(path=_BRANDS_PATH):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def entropy(s):
    if not s:
        return 0.0
    n = len(s)
    freq = {}
    for ch in s:
        freq[ch] = freq.get(ch, 0) + 1
    return -sum((v / n) * math.log2(v / n) for v in freq.values())


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def deglyph(s, homoglyphs):
    """Undo common lookalike substitutions (0->o, rn->m ...). Multi-char keys first."""
    for k in sorted(homoglyphs, key=len, reverse=True):
        s = s.replace(k, homoglyphs[k])
    return s


def split_host(host):
    """Return (registrable_domain, subdomain_labels)."""
    labels = host.split(".")
    n = 3 if len(labels) >= 3 and ".".join(labels[-2:]) in MULTI else 2
    if len(labels) < n:
        return host, []
    return ".".join(labels[-n:]), labels[:-n]


def parse(url):
    """Canonical parse shared by features and the train/test split."""
    u = url.strip()[:MAX_URL_LEN]
    if "://" not in u:
        u = "http://" + u
    try:
        sp = urlsplit(u)
        host = (sp.hostname or "").lower().rstrip(".")
    except ValueError:  # malformed, e.g. bad IPv6 brackets
        sp = urlsplit("http://invalid")
        host = ""
    is_ip = bool(IPV4.fullmatch(host)) or ":" in host
    reg, sub = ("", []) if is_ip or not host else split_host(host)
    return u, sp, host, is_ip, reg, sub


def is_free_host(host, reg):
    return reg in FREE_HOSTS or any(host == h or host.endswith("." + h) for h in FREE_HOSTS)


def registrable_domain(url):
    _, _, host, is_ip, reg, _ = parse(url)
    return host if is_ip else reg


def features(url, brands):
    u, sp, host, is_ip, reg, sub = parse(url)
    raw = url.strip()[:MAX_URL_LEN]
    label = reg.split(".")[0] if reg else ""
    path = unquote(sp.path or "").lower()
    low = host + path

    # brand features: compare the de-glyphed registrable label against brand tokens
    glabel = deglyph(label, brands["homoglyphs"])
    lev_min = 1.0
    if glabel:
        for t in brands["tokens"]:
            lev_min = min(lev_min, lev(glabel, t) / max(len(glabel), len(t)))
    official = reg in brands["official"]
    in_sub = int(any(t in host for t in brands["tokens"]) and not official)
    tld = reg.split(".")[-1] if reg else ""

    return [
        len(raw),
        len(host),
        host.count("."),
        len(sub),
        sum(c.isdigit() for c in raw) / max(len(raw), 1),
        host.count("-"),
        sum(raw.count(c) for c in SPECIALS),
        int("@" in sp.netloc),
        int(is_ip),
        int(sp.scheme == "https"),
        entropy(host),
        int(host in SHORTENERS),
        sum(low.count(w) for w in SUSP),
        len(sp.path or ""),
        len([p for p in (sp.query or "").split("&") if p]),
        lev_min,
        in_sub,
        int(tld in RISKY_TLD or "xn--" in host),
        int(is_free_host(host, reg)),
    ]


def write_feature_order(path):
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"version": FEATURE_VERSION, "features": FEATURE_NAMES,
                   "multi_suffixes": sorted(MULTI), "shorteners": sorted(SHORTENERS),
                   "susp_words": SUSP, "risky_tlds": sorted(RISKY_TLD), "free_hosts": sorted(FREE_HOSTS),
                   "max_url_len": MAX_URL_LEN}, f, indent=2)

"""Reference link decision logic. The Kotlin UrlAnalyser / RiskEngine must give the same levels.

Order: scheme check -> allowlist -> blocklist -> shortener (always SUSPICIOUS: its string says
nothing about the destination) -> model + thresholds -> brand rules raise SAFE to SUSPICIOUS:
  brand_lookalike: registrable label within 0.25 edit distance of a brand token
  brand_in_host:   any host part (split on . and -) equals a brand name, or is one edit away when
                   it is 5+ characters long (sbi-kyc.example, hdfcbnk-login.example)
both only when the registrable domain is not an official brand domain.
"""
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

from bloom import Bloom
from build_blocklist import norm_host
import re

from features import FEATURE_NAMES, SHORTENERS, deglyph, features, is_free_host, lev, load_brands, parse

ASSETS = Path(__file__).resolve().parent.parent / "android/app/src/main/assets"
LEVELS = ["SAFE", "SUSPICIOUS", "DANGEROUS"]
BRAND_LOOKALIKE_MAX_LEV = 0.25  # normalised edit distance; 0.0 = exact brand token after de-glyphing
I_BRAND_LEV = FEATURE_NAMES.index("brand_min_lev")


def brand_names(brands):
    """Brand tokens plus the first label of every official domain (hdfcbank, onlinesbi ...), sorted."""
    names = set(brands["tokens"]) | {d.split(".")[0] for d in brands["official"]}
    return sorted(names)


def brand_in_host(host, reg, brands, names):
    """The brand part a lookalike host uses, or None. Only for non-official registrable domains."""
    if not host or reg in brands["official"]:
        return None
    for part in re.split(r"[.-]", host):
        p = deglyph(part, brands["homoglyphs"])
        for n in names:
            if p == n or (len(p) >= 5 and lev(p, n) <= 1):
                return n
    return None


class LinkEngine:
    def __init__(self):
        self.brands = load_brands()
        self.brand_names = brand_names(self.brands)
        self.allow = set(json.load(open(ASSETS / "allowlist.json"))["domains"]) | set(self.brands["official"])
        meta = json.load(open(ASSETS / "blocklist.json"))
        self.bloom = Bloom(meta["m_bits"], meta["k"])
        self.bloom.bits = bytearray((ASSETS / "blocklist.bloom").read_bytes())
        th = json.load(open(ASSETS / "thresholds.json"))["link"]
        self.thr_s, self.thr_d = th["suspicious"], th["dangerous"]
        self.sess = ort.InferenceSession(str(ASSETS / "link_model.onnx"))

    def prob(self, url, f=None):
        x = np.array([f if f is not None else features(url, self.brands)], dtype=np.float32)
        return float(self.sess.run(None, {"input": x})[1][0, 1])

    def decide(self, url, use_lists=True):
        """Return (level, reason_id, model_prob). level is None for non-http(s) input."""
        s = url.strip()
        scheme = s.split(":", 1)[0].lower() if ":" in s.split("/", 1)[0] else ""
        if scheme and scheme not in ("http", "https"):
            return None, "not_http", None
        _, _, host, is_ip, reg, _ = parse(s)
        if use_lists and not is_ip:
            if reg in self.allow and not is_free_host(host, reg):
                return "SAFE", "allowlist", None
            if norm_host(host) in self.bloom or (reg and reg in self.bloom):
                return "DANGEROUS", "blocklist", None
        if host in SHORTENERS:
            return "SUSPICIOUS", "shortener", None
        f = features(s, self.brands)
        p = self.prob(s, f)
        level = 2 if p >= self.thr_d else 1 if p >= self.thr_s else 0
        reason = "model"
        if level == 0 and f[I_BRAND_LEV] <= BRAND_LOOKALIKE_MAX_LEV and reg not in self.brands["official"]:
            level, reason = 1, "brand_lookalike"
        if level == 0 and brand_in_host(host, reg, self.brands, self.brand_names):
            level, reason = 1, "brand_in_host"
        return LEVELS[level], reason, p


if __name__ == "__main__":
    import sys
    e = LinkEngine()
    for u in sys.argv[1:]:
        print(u, e.decide(u))
    assert len(FEATURE_NAMES) == 19

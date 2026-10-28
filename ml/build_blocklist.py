"""Build the on-device blocklist (Bloom filter) from Phishing.Database active domains.

Entries under allowlisted / official / free-host registrable domains are dropped, because those
lists flag individual pages on shared platforms (e.g. docs.google.com) and would block whole sites.
Lookup on device: normalised host (lower-case, no trailing dot, no leading "www."), then the
registrable domain.
"""
import json
import random
from pathlib import Path

from bloom import Bloom, size_for
from features import FREE_HOSTS, SHORTENERS, load_brands, split_host

ML = Path(__file__).resolve().parent
ASSETS = ML.parent / "android/app/src/main/assets"
SRC = ML / "data/raw/phishing_database/phishing-domains-ACTIVE.txt"
FP_RATE = 0.001
# Shared platforms listed as a whole in the source; blocking them would block every user of them.
SHARED_PLATFORMS = {"telegra.ph", "dweb.link", "firebasestorage.googleapis.com"}


def norm_host(h):
    h = h.strip().lower().rstrip(".")
    return h[4:] if h.startswith("www.") else h


def main():
    brands = load_brands()
    allow = set(json.load(open(ASSETS / "allowlist.json"))["domains"]) | set(brands["official"])

    kept, dropped = set(), 0
    for line in open(SRC, encoding="utf-8", errors="ignore"):
        h = norm_host(line)
        if not h or h.startswith("#") or " " in h:
            continue
        reg, _ = split_host(h)
        if reg in allow or h in FREE_HOSTS or h in SHORTENERS or h in SHARED_PLATFORMS:
            dropped += 1
            continue
        kept.add(h)

    m, k = size_for(len(kept), FP_RATE)
    bf = Bloom(m, k)
    for h in kept:
        bf.add(h)

    # measure the real false-positive rate on random strings that are not in the set
    rnd = random.Random(7)
    probes = ["probe-%d-%x.example" % (i, rnd.getrandbits(40)) for i in range(50000)]
    fp = sum(p in bf for p in probes) / len(probes)
    assert all(h in bf for h in list(kept)[:5000])

    (ASSETS / "blocklist.bloom").write_bytes(bytes(bf.bits))
    meta = {"version": "block-1", "source": "Phishing.Database phishing-domains-ACTIVE.txt (MIT)",
            "built": __import__("time").strftime("%Y-%m-%d"), "entries": len(kept), "dropped": dropped,
            "m_bits": m, "k": k, "target_fp": FP_RATE, "measured_fp": fp,
            "hash": "fnv1a64 double hashing, see ml/bloom.py"}
    json.dump(meta, open(ASSETS / "blocklist.json", "w"), indent=2)
    print(json.dumps(meta, indent=1))
    print(f"blocklist.bloom: {len(bf.bits) / 1e6:.2f} MB")
    for t in ("docs.google.com", "sites.google.com", "hdfcbank.com", "bit.ly", "telegra.ph"):
        print(f"  {t} blocked? {t in bf}")


if __name__ == "__main__":
    main()

"""Bloom filter for the on-device phishing-domain blocklist.

Ported 1:1 to Kotlin (link/Blocklist.kt). Hashing: FNV-1a 64-bit over UTF-8 bytes, with two
seeds, combined by double hashing: bit_i = (h1 + i * h2) mod m. Bits are stored little-endian
within each byte (bit b of the filter is byte b >> 3, mask 1 << (b & 7)).
"""
import math

FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
MASK64 = 0xFFFFFFFFFFFFFFFF
SEED2 = 0x9E3779B97F4A7C15

## hello my name is rushikesh gaonkar i dont know how i have access to this repository but i have it i dont know what even i am doing 
def fnv1a64(data, seed=FNV_OFFSET):
    h = seed
    for b in data:
        h ^= b
        h = (h * FNV_PRIME) & MASK64
    return h


def size_for(n, fp_rate):
    m = math.ceil(-n * math.log(fp_rate) / (math.log(2) ** 2))
    m = (m + 7) // 8 * 8
    k = max(1, round(m / n * math.log(2)))
    return m, k


def positions(item, m, k):
    data = item.encode("utf-8")
    h1 = fnv1a64(data)
    h2 = fnv1a64(data, FNV_OFFSET ^ SEED2) | 1
    return [((h1 + i * h2) & MASK64) % m for i in range(k)]


class Bloom:
    def __init__(self, m, k):
        self.m, self.k = m, k
        self.bits = bytearray(m // 8)

    def add(self, item):
        for p in positions(item, self.m, self.k):
            self.bits[p >> 3] |= 1 << (p & 7)

    def __contains__(self, item):
        return all(self.bits[p >> 3] & (1 << (p & 7)) for p in positions(item, self.m, self.k))

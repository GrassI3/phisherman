package com.phisherman.link

/** Bloom filter built by ml/build_blocklist.py; hashing must match ml/bloom.py exactly. */
class Blocklist(private val bits: ByteArray, private val m: Long, private val k: Int) {

    init {
        check(bits.size.toLong() * 8 == m) { "blocklist.bloom has ${bits.size * 8} bits, metadata says $m" }
    }

    operator fun contains(item: String): Boolean {
        val data = item.toByteArray(Charsets.UTF_8)
        val h1 = fnv1a64(data, FNV_OFFSET)
        val h2 = fnv1a64(data, FNV_OFFSET xor SEED2) or 1uL
        val mm = m.toULong()
        for (i in 0 until k) {
            val p = ((h1 + i.toULong() * h2) % mm).toLong()
            if (bits[(p ushr 3).toInt()].toInt() and (1 shl (p and 7).toInt()) == 0) return false
        }
        return true
    }

    companion object {
        private const val FNV_OFFSET = 0xCBF29CE484222325uL
        private const val FNV_PRIME = 0x100000001B3uL
        private const val SEED2 = 0x9E3779B97F4A7C15uL

        fun fnv1a64(data: ByteArray, seed: ULong): ULong {
            var h = seed
            for (b in data) {
                h = h xor (b.toULong() and 0xFFuL)
                h *= FNV_PRIME
            }
            return h
        }

        /** Same normalisation as build_blocklist.norm_host. */
        fun normHost(h: String): String {
            val s = h.trim().lowercase().trimEnd('.')
            return if (s.startsWith("www.")) s.substring(4) else s
        }
    }
}

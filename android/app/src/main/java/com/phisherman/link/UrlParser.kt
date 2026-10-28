package com.phisherman.link

/**
 * Mirror of Python's urllib.parse.urlsplit / .hostname / unquote, limited to what features.py uses.
 * Any difference here shows up as a parity-test failure, so keep it boring and literal.
 */
object UrlParser {
    private const val SCHEME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-."

    data class Split(val scheme: String, val netloc: String, val path: String, val query: String)

    /** Returns null where Python raises ValueError (mismatched or invalid IPv6 brackets). */
    fun urlsplit(input: String): Split? {
        var url = input.trimStart { it <= ' ' }.filterNot { it == '\t' || it == '\r' || it == '\n' }
        var scheme = ""
        val colon = url.indexOf(':')
        if (colon > 0 && url[0].isAsciiLetter() && url.substring(0, colon).all { it in SCHEME_CHARS }) {
            scheme = url.substring(0, colon).lowercase()
            url = url.substring(colon + 1)
        }
        var netloc = ""
        if (url.startsWith("//")) {
            val rest = url.substring(2)
            var delim = rest.length
            for (c in "/?#") {
                val w = rest.indexOf(c)
                if (w >= 0) delim = minOf(delim, w)
            }
            netloc = rest.substring(0, delim)
            url = rest.substring(delim)
            val open = '[' in netloc
            val close = ']' in netloc
            if (open != close) return null
            if (open) {
                val bracketed = netloc.substringAfter('[').substringBefore(']')
                if (!isBracketedHostValid(bracketed)) return null
            }
        }
        val hash = url.indexOf('#')
        if (hash >= 0) url = url.substring(0, hash)
        var query = ""
        val q = url.indexOf('?')
        if (q >= 0) {
            query = url.substring(q + 1)
            url = url.substring(0, q)
        }
        return Split(scheme, netloc, url, query)
    }

    private fun isBracketedHostValid(h: String): Boolean =
        h.startsWith("v") || (':' in h && h.isNotEmpty() && h.all { it.isHexDigit() || it == ':' || it == '.' || it == '%' })

    /** Python SplitResult.hostname: lower-cased, null when empty. */
    fun hostname(netloc: String): String? {
        val hostinfo = netloc.substringAfterLast('@')
        val host = if ('[' in hostinfo) hostinfo.substringAfter('[').substringBefore(']')
        else hostinfo.substringBefore(':')
        return host.ifEmpty { null }?.lowercase()
    }

    /** Python urllib.parse.unquote (UTF-8, invalid bytes replaced). */
    fun unquote(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder()
        val buf = java.io.ByteArrayOutputStream()
        fun flush() {
            if (buf.size() > 0) {
                out.append(String(buf.toByteArray(), Charsets.UTF_8))
                buf.reset()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s[i + 1].isHexDigit() && s[i + 2].isHexDigit()) {
                buf.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
                continue
            }
            if (c.code < 128) buf.write(c.code) else {
                flush()
                out.append(c)
            }
            i++
        }
        flush()
        return out.toString()
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

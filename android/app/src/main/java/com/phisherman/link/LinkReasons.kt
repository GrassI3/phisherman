package com.phisherman.link

import com.phisherman.engine.Reason

/** User-readable reasons for link verdicts, English and Hindi, strongest first. */
object LinkReasons {
    val BLOCKLIST = Reason("blocklist",
        "This website is on a list of known phishing sites",
        "यह वेबसाइट ज्ञात फ़िशिंग साइटों की सूची में है")
    val SHORTENER = Reason("shortener",
        "Shortened link hides where it really goes",
        "छोटा किया गया लिंक असली पता छिपाता है")
    val MODEL = Reason("model",
        "This link's pattern matches phishing links we have seen",
        "इस लिंक का पैटर्न फ़िशिंग लिंक जैसा है")

    fun allowlisted(reg: String) = Reason("allowlist", "Known website: $reg", "भरोसेमंद वेबसाइट: $reg")

    /** Reasons read from the URL itself; f is null when the model was not run (blocklist hit). */
    fun fromUrl(p: ParsedUrl, f: FloatArray?, cfg: LinkConfig): List<Reason> {
        val out = mutableListOf<Reason>()
        val brand = UrlFeatures.closestBrand(p.reg, cfg)
        val official = p.reg in cfg.official
        if (brand != null && brand.second <= LinkDecider.BRAND_LOOKALIKE_MAX_LEV && !official) {
            val name = cfg.brandNames[brand.first] ?: brand.first.uppercase()
            val real = cfg.brandDomains[brand.first]?.firstOrNull() ?: "its official site"
            out += Reason("brand_lookalike",
                "Looks like $name, but the real site is $real",
                "$name जैसा दिखता है, पर असली साइट $real है")
        } else if (!official) {
            cfg.brandTokens.firstOrNull { it in p.host }?.let { t ->
                val name = cfg.brandNames[t] ?: t.uppercase()
                out += Reason("brand_in_host",
                    "Uses the name $name, but the website is actually ${p.reg.ifEmpty { p.host }}",
                    "$name का नाम इस्तेमाल करता है, पर असली वेबसाइट ${p.reg.ifEmpty { p.host }} है")
            }
        }
        if (p.isIp) out += Reason("ip_host",
            "Uses a raw number address instead of a website name",
            "वेबसाइट के नाम की जगह सीधा नंबर (IP) इस्तेमाल करता है")
        if ('@' in p.split.netloc) out += Reason("at_sign",
            "Contains '@', a trick that hides the real website",
            "'@' चिह्न है, जो असली वेबसाइट छिपाने की चाल है")
        if (p.sub.size >= 3) out += Reason("deep_subdomain",
            "The real website is hidden behind many extra parts",
            "असली वेबसाइट कई हिस्सों के पीछे छिपी है")
        if (UrlFeatures.isFreeHost(p.host, p.reg, cfg)) out += Reason("free_host",
            "Hosted on a free website builder that anyone can use",
            "मुफ़्त वेबसाइट बिल्डर पर बनी है जिसे कोई भी इस्तेमाल कर सकता है")
        if ("xn--" in p.host) out += Reason("punycode",
            "Uses lookalike letters to imitate another website",
            "दूसरी वेबसाइट की नकल करने के लिए मिलते-जुलते अक्षर इस्तेमाल करता है")
        else if (p.reg.substringAfterLast('.', "") in cfg.riskyTlds) out += Reason("risky_tld",
            "Uses a cheap web address ending often seen in scams",
            "ऐसा सस्ता डोमेन जो अक्सर धोखाधड़ी में दिखता है")
        val low = p.host + UrlParser.unquote(p.split.path).lowercase()
        val bait = cfg.suspWords.filter { it in low }
        if (bait.isNotEmpty()) out += Reason("bait_words",
            "Contains bait words: ${bait.take(3).joinToString(", ")}",
            "लालच वाले शब्द हैं: ${bait.take(3).joinToString(", ")}")
        if (f != null && p.split.scheme != "https" && out.size < 3) out += Reason("no_https",
            "Not a secure (https) connection",
            "सुरक्षित (https) कनेक्शन नहीं है")
        return out
    }
}

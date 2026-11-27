package org.echchara.app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A string in English and Kannada. */
data class Txt(val en: String, val kn: String = "") {
    fun get(kannada: Boolean): String = if (kannada && kn.isNotEmpty()) kn else en
}

enum class Sev { HIGH, MID, LOW, SAFE }

data class Flag(
    val id: String?,
    val sev: Sev,
    val w: Double,
    val label: Txt,
    val why: Txt,
    val todo: Txt? = null,
    val examples: List<String> = emptyList()
)

data class UrlResult(val score: Int, val flags: List<Flag>, val reg: String, val known: Boolean)

data class Analysis(
    val ruleScore: Int,
    val ml: Double,
    val score: Int,
    val flags: List<Flag>,
    val todos: List<Txt>,
    val type: Txt?,
    val org: String?,
    val summary: Txt
) {
    val band: Band get() = bandOf(score)
}

enum class Band(val label: Txt) {
    SAFE(Txt("Looks safe", "ಸುರಕ್ಷಿತವಾಗಿ ಕಾಣುತ್ತದೆ")),
    WARN(Txt("Suspicious", "ಅನುಮಾನಾಸ್ಪದ")),
    DANGER(Txt("Dangerous", "ಅಪಾಯಕಾರಿ"))
}

fun bandOf(s: Int) = when { s >= 60 -> Band.DANGER; s >= 30 -> Band.WARN; else -> Band.SAFE }

private fun combine(ws: List<Double>): Int = ((1 - ws.fold(1.0) { p, w -> p * (1 - w) }) * 100).roundToInt()
private fun rx(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))

private class Rule(
    val id: String, val sev: Sev, val w: Double, val label: Txt,
    val en: Regex, val kn: Regex?, val why: Txt, val todo: Txt?
)

object Engine {

    // ---------------- URL analysis ----------------
    private val BRANDS = listOf("google","gmail","paypal","apple","icloud","microsoft","outlook","amazon","netflix","facebook","instagram","whatsapp","telegram","linkedin","sbi","onlinesbi","hdfcbank","hdfc","icicibank","icici","axisbank","kotak","canarabank","karnatakabank","unionbank","bankofbaroda","paytm","phonepe","gpay","bhim","npci","flipkart","myntra","meesho","irctc","incometax","indiapost","uidai","epfo","airtel","jio","bsnl","bescom","mescom","hescom","gescom","cesc","kptcl","ksrtc","sevasindhu","parivahan","echallan","swiggy","zomato","fedex","dhl","bluedart","spotify","youtube","hotstar","bookmyshow","makemytrip","bigbasket","blinkit","zepto","nykaa","dunzo")
    /** Official short-link domains that brands use in their own SMS. */
    private val BRAND_SHORT = mapOf("fkrt.it" to "flipkart", "fkrt.cc" to "flipkart", "fkrt.co" to "flipkart", "amzn.to" to "amazon", "amzn.in" to "amazon", "amzn.eu" to "amazon", "spoti.fi" to "spotify", "myntr.it" to "myntra", "youtu.be" to "youtube", "wa.me" to "whatsapp", "g.co" to "google", "swig.gy" to "swiggy", "zoma.to" to "zomato", "phon.pe" to "phonepe", "m.paytm.me" to "paytm", "paytm.me" to "paytm", "jio.com" to "jio", "airtel.in" to "airtel")
    private val OK_TLDS = setOf("com","in","co.in","net","org","co","io","bank.in","sbi","uk","co.uk")
    private val TRUSTED = listOf("gov.in","nic.in")
    private val BANK_BRANDS = listOf("sbi","onlinesbi","hdfcbank","hdfc","icicibank","icici","axisbank","kotak","canarabank","karnatakabank","unionbank","bankofbaroda")
    private val RISKY_TLDS = setOf("zip","mov","top","xyz","click","country","gq","tk","ml","cf","ga","work","rest","support","live","icu","cam","buzz","monster","quest","cfd","sbs","lol","loan","win","bid","info","online","site","shop","vip","cyou","apk")
    private val SHORTENERS = setOf("bit.ly","tinyurl.com","t.co","goo.gl","is.gd","cutt.ly","rb.gy","shorturl.at","ow.ly","tiny.cc","t.ly","s.id","rebrand.ly","bitly.com")
    private val SLD = setOf("co","com","gov","org","net","ac","edu","nic","res","gen","firm","ind","bank")
    private val KEYWORDS = listOf("login","signin","verify","verification","secure","account","update","wallet","kyc","banking","netbank","confirm","unlock","reward","refund","support","helpdesk","password","auth","validate","suspend","billpay","challan","redeliver","offer")
    private val CONFUSE = mapOf('а' to 'a','е' to 'e','о' to 'o','р' to 'p','с' to 'c','у' to 'y','х' to 'x','і' to 'i','ј' to 'j','ѕ' to 's','ο' to 'o','α' to 'a','ν' to 'v','ρ' to 'p','τ' to 't','ι' to 'i','κ' to 'k','0' to 'o','1' to 'l','3' to 'e','5' to 's','7' to 't','4' to 'a','$' to 's','@' to 'a')

    private fun skeleton(s: String) = s.lowercase().map { CONFUSE[it] ?: it }.joinToString("").replace("rn", "m").replace("vv", "w")

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    fun analyzeUrl(input: String): UrlResult? {
        val raw = input.trim()
        val sm = Regex("^([a-z][a-z0-9+.-]*)://", RegexOption.IGNORE_CASE).find(raw)
        val scheme = sm?.groupValues?.get(1)?.lowercase() ?: ""
        val rest = if (sm != null) raw.substring(sm.value.length) else raw
        val ae = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (ae < 0) rest else rest.substring(0, ae)
        val after = if (ae < 0) "" else rest.substring(ae)
        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at) else ""
        var hostport = if (at >= 0) authority.substring(at + 1) else authority
        var port = ""
        Regex(":(\\d+)$").find(hostport)?.let { port = it.groupValues[1]; hostport = hostport.substring(0, hostport.length - it.value.length) }
        val host = hostport.lowercase().trimEnd('.')
        val qi = after.indexOfFirst { it == '?' || it == '#' }
        val path = if (qi < 0) after else after.substring(0, qi)
        val query = if (qi < 0) "" else after.substring(qi)
        val labels = host.split('.').filter { it.isNotEmpty() }
        val isIP = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
        if (!isIP && (labels.size < 2 || host.any { it.isWhitespace() })) return null
        var rc = 2
        if (!isIP && labels.size >= 3 && labels[labels.size - 2] in SLD && labels.last().length == 2) rc = 3
        val reg = if (isIP) host else labels.takeLast(rc).joinToString(".")
        val regLabel = if (isIP) host else labels[labels.size - rc]
        val tld = if (isIP) "" else labels.takeLast(rc - 1).joinToString(".")
        val sub = if (isIP) "" else labels.dropLast(rc).joinToString(".")
        val trusted = TRUSTED.any { host == it || host.endsWith(".$it") }
        val bankIn = host.endsWith(".bank.in")
        val flags = mutableListOf<Flag>()
        fun add(sev: Sev, w: Double, l: Txt, why: Txt) = flags.add(Flag(null, sev, w, l, why))
        var official: String? = BRAND_SHORT[reg] ?: BRAND_SHORT[host]

        if (scheme == "http") add(Sev.MID, .15, Txt("No encryption (http)", "ಎನ್‌ಕ್ರಿಪ್ಶನ್ ಇಲ್ಲ (http)"), Txt("Real banks always use HTTPS.", "ನಿಜವಾದ ಬ್ಯಾಂಕ್‌ಗಳು ಯಾವಾಗಲೂ HTTPS ಬಳಸುತ್ತವೆ."))
        if (isIP) add(Sev.HIGH, .4, Txt("Raw IP address instead of a name", "ಹೆಸರಿನ ಬದಲು IP ಸಂಖ್ಯೆ"), Txt("Organisations use their domain name.", "ಸಂಸ್ಥೆಗಳು ಡೊಮೇನ್ ಹೆಸರು ಬಳಸುತ್ತವೆ."))
        if (userinfo.isNotEmpty()) add(Sev.HIGH, .4, Txt("Hidden \"@\" trick", "ಮರೆಮಾಚಿದ \"@\" ತಂತ್ರ"), Txt("The real destination is $host.", "ನಿಜವಾದ ತಾಣ $host."))
        if (host.any { it.code > 127 } || host.contains("xn--")) add(Sev.HIGH, .45, Txt("Look-alike characters", "ಒಂದೇ ರೀತಿ ಕಾಣುವ ಅಕ್ಷರಗಳು"), Txt("Non-Latin letters that look like normal ones.", "ಸಾಮಾನ್ಯದಂತೆ ಕಾಣುವ ಬೇರೆ ಅಕ್ಷರಗಳು."))
        if (port.isNotEmpty() && port != "80" && port != "443") add(Sev.MID, .15, Txt("Unusual port :$port", "ಅಸಾಮಾನ್ಯ ಪೋರ್ಟ್ :$port"), Txt("Public websites rarely need a port number.", "ಸಾರ್ವಜನಿಕ ಸೈಟ್‌ಗಳಿಗೆ ಪೋರ್ಟ್ ಅಪರೂಪ."))

        if (!isIP && !trusted && official == null) {
            val skel = skeleton(regLabel)
            val tokens = regLabel.split('-')
            var hit = false
            if (regLabel in BRANDS) {
                hit = true
                if (tld in OK_TLDS) official = regLabel
                else add(Sev.HIGH, .4, Txt("\"$regLabel\" on an unusual ending (.$tld)", "\"$regLabel\" ಅಸಾಮಾನ್ಯ ಅಂತ್ಯದಲ್ಲಿ (.$tld)"), Txt("This organisation does not use this ending.", "ಆ ಸಂಸ್ಥೆ ಈ ಅಂತ್ಯ ಬಳಸುವುದಿಲ್ಲ."))
            }
            if (!hit) for (b in BRANDS) {
                val sb = skeleton(b)
                if ((skel == sb && regLabel != b) || tokens.any { it != b && skeleton(it) == sb }) {
                    add(Sev.HIGH, .6, Txt("Disguised as \"$b\"", "\"$b\" ಎಂಬಂತೆ ವೇಷ"), Txt("$regLabel swaps letters for look-alikes.", "$regLabel ನಲ್ಲಿ ಅಕ್ಷರ ಬದಲಿಸಲಾಗಿದೆ.")); hit = true; break
                }
                val d = min(lev(skel, sb), lev(regLabel, b))
                if (b.length >= 5 && d > 0 && d <= (if (b.length >= 8) 2 else 1)) {
                    add(Sev.HIGH, .55, Txt("Misspelling of \"$b\"", "\"$b\" ನ ತಪ್ಪು ಕಾಗುಣಿತ"), Txt("$regLabel is one or two letters away from $b.", "$regLabel ಒಂದೆರಡು ಅಕ್ಷರ ಮಾತ್ರ ಬೇರೆ.")); hit = true; break
                }
                if ((b.length >= 4 && regLabel.contains(b)) || b in tokens) {
                    add(Sev.HIGH, .55, Txt("\"$b\" plus extra words", "\"$b\" ಜೊತೆ ಹೆಚ್ಚುವರಿ ಪದಗಳು"), Txt("Brand + kyc / secure / login is a classic phishing domain.", "ಬ್ರ್ಯಾಂಡ್ + kyc / login ಫಿಶಿಂಗ್ ಲಕ್ಷಣ.")); hit = true; break
                }
            }
            if (!hit && official == null) {
                val elsewhere = "$sub $path".lowercase()
                BRANDS.firstOrNull { if (it.length >= 4) elsewhere.contains(it) else Regex("\\b$it\\b").containsMatchIn(elsewhere) }?.let { b ->
                    add(Sev.HIGH, .45, Txt("\"$b\" used as bait", "\"$b\" ಅನ್ನು ಆಮಿಷವಾಗಿ ಬಳಸಲಾಗಿದೆ"), Txt("The site really belongs to $reg.", "ಸೈಟ್ ನಿಜವಾಗಿ $reg ಗೆ ಸೇರಿದೆ."))
                }
            }
            if (official == null && sub.isNotEmpty() && Regex("(^|\\.)[a-z0-9-]{2,}\\.(com|co\\.in|in|net|org|bank\\.in|gov\\.in)$").containsMatchIn(sub))
                add(Sev.HIGH, .35, Txt("Fake address placed in front", "ಮುಂದೆ ಇಟ್ಟ ನಕಲಿ ವಿಳಾಸ"), Txt("$sub is only the front part of the address. The site really belongs to $reg.", "$sub ವಿಳಾಸದ ಮುಂಭಾಗ ಮಾತ್ರ. ಈ ಸೈಟ್ ನಿಜವಾಗಿ $reg ಗೆ ಸೇರಿದೆ."))
            val last = labels.last()
            if (last in RISKY_TLDS) add(Sev.MID, .22, Txt("High-abuse ending .$last", "ಹೆಚ್ಚು ದುರ್ಬಳಕೆಯ ಅಂತ್ಯ .$last"), Txt("Cheap endings used for throwaway scam sites.", "ವಂಚನೆಗೆ ಹೆಚ್ಚು ಬಳಸುವ ಅಗ್ಗದ ಅಂತ್ಯ."))
            if (reg in SHORTENERS) add(Sev.MID, .3, Txt("Shortened link", "ಸಂಕ್ಷಿಪ್ತ ಲಿಂಕ್"), Txt("The real destination is hidden.", "ನಿಜವಾದ ತಾಣ ಮರೆಯಾಗಿದೆ."))
            if (sub.split('.').count { it.isNotEmpty() } >= 3) add(Sev.MID, .15, Txt("Long chain of subdomains", "ಉದ್ದದ ಉಪಡೊಮೇನ್ ಸರಪಳಿ"), Txt("Hides the real domain on a phone screen.", "ನಿಜವಾದ ಡೊಮೇನ್ ಮರೆಮಾಡುತ್ತದೆ."))
            if (host.count { it == '-' } >= 2) add(Sev.LOW, .1, Txt("Several hyphens in the domain", "ಡೊಮೇನ್‌ನಲ್ಲಿ ಹಲವು ಹೈಫನ್‌ಗಳು"), Txt("Common in phishing kits.", "ಫಿಶಿಂಗ್ ಕಿಟ್‌ಗಳಲ್ಲಿ ಸಾಮಾನ್ಯ."))
            val toks = "$host $path".lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            val kw = KEYWORDS.filter { k -> toks.any { it == k || (k.length >= 5 && it.contains(k)) } }
            if (kw.isNotEmpty() && official == null && reg !in SHORTENERS) add(Sev.LOW, .12, Txt("Pressure words in the address", "ವಿಳಾಸದಲ್ಲಿ ಒತ್ತಡದ ಪದಗಳು"), Txt("Contains ${kw.take(3).joinToString(", ")}.", "${kw.take(3).joinToString(", ")} ಪದಗಳಿವೆ."))
        }
        if (Regex("\\.apk(\\b|$)", RegexOption.IGNORE_CASE).containsMatchIn(path) || host.endsWith(".apk")) add(Sev.HIGH, .5, Txt("Downloads an app file (.apk)", "ಆ್ಯಪ್ ಫೈಲ್ (.apk) ಡೌನ್‌ಲೋಡ್"), Txt("APKs from outside the Play Store can read your SMS and OTPs.", "ಹೊರಗಿನ APK ನಿಮ್ಮ SMS ಮತ್ತು OTP ಓದಬಹುದು."))
        if (Regex("(=|%3d)(https?|%68%74%74%70)", RegexOption.IGNORE_CASE).containsMatchIn(query)) add(Sev.MID, .2, Txt("Redirects to another site", "ಬೇರೆ ಸೈಟ್‌ಗೆ ಮರುನಿರ್ದೇಶನ"), Txt("The link carries a second address.", "ಲಿಂಕ್ ಒಳಗೆ ಇನ್ನೊಂದು ವಿಳಾಸವಿದೆ."))

        var score = combine(flags.map { it.w })
        val hasHigh = flags.any { it.sev == Sev.HIGH }
        if (official != null && !hasHigh) { score = min(score, 6); flags.add(0, Flag(null, Sev.SAFE, 0.0, Txt("Real $official domain", "ನಿಜವಾದ $official ಡೊಮೇನ್"), Txt("$reg is the organisation's own domain.", "$reg ಆ ಸಂಸ್ಥೆಯ ಸ್ವಂತ ಡೊಮೇನ್."))) }
        if (trusted && !hasHigh) { score = min(score, 6); flags.add(0, Flag(null, Sev.SAFE, 0.0, Txt("Government domain (.gov.in)", "ಸರ್ಕಾರಿ ಡೊಮೇನ್ (.gov.in)"), Txt("Only government bodies can register gov.in.", "gov.in ಸರ್ಕಾರಿ ಸಂಸ್ಥೆಗಳಿಗೆ ಮಾತ್ರ."))) }
        if (bankIn && !hasHigh && !trusted) {
            score = min(score, 6)
            if (official == null) flags.add(0, Flag(null, Sev.SAFE, 0.0, Txt("RBI-verified bank domain (.bank.in)", "RBI ದೃಢೀಕೃತ ಬ್ಯಾಂಕ್ ಡೊಮೇನ್ (.bank.in)"), Txt("Only banks regulated by the RBI can register .bank.in addresses.", "RBI ನಿಯಂತ್ರಿತ ಬ್ಯಾಂಕ್‌ಗಳು ಮಾತ್ರ .bank.in ವಿಳಾಸ ಪಡೆಯಬಹುದು.")))
        }
        if (!bankIn && flags.any { f -> f.sev == Sev.HIGH && BANK_BRANDS.any { f.label.en.contains("\"$it\"") } })
            flags.add(Flag(null, Sev.LOW, 0.0, Txt("Real bank websites end in .bank.in", "ನಿಜವಾದ ಬ್ಯಾಂಕ್ ವೆಬ್‌ಸೈಟ್‌ಗಳು .bank.in ನಲ್ಲಿ ಕೊನೆಗೊಳ್ಳುತ್ತವೆ"), Txt("Since 2025, RBI-regulated banks use addresses ending in .bank.in, such as hdfc.bank.in.", "2025 ರಿಂದ RBI ನಿಯಂತ್ರಿತ ಬ್ಯಾಂಕ್‌ಗಳು .bank.in ವಿಳಾಸ ಬಳಸುತ್ತವೆ (ಉದಾ: hdfc.bank.in).")))
        return UrlResult(score, flags, reg, official != null || trusted || (bankIn && !hasHigh))
    }

    // ---------------- Message rules ----------------
    private val RULES = listOf(
        Rule("otpshare", Sev.HIGH, .45, Txt("Asks you to share a code", "ಕೋಡ್ ಹಂಚಿಕೊಳ್ಳಲು ಕೇಳುತ್ತಿದೆ"),
            rx("""\b(share|send|tell|forward|provide|read out|give)\b[^.\n]{0,40}\b(otp|code|pin|password)\b|\b(otp|pin|password)\b[^.\n]{0,40}\b(share|send|tell|forward|give|read out)\s+(it|that|me|us|the (otp|code|pin))\b"""), rx("""(OTP|ಒಟಿಪಿ|ಕೋಡ್|ಪಿನ್)[^.\n]{0,30}(ಕಳುಹಿಸಿ|ಹೇಳಿ|ಹಂಚಿಕೊಳ್ಳಿ|kalisi|heli)"""),
            Txt("No bank or app will ever ask you to read out or forward a one-time code.", "ಯಾವ ಬ್ಯಾಂಕ್ ಅಥವಾ ಆ್ಯಪ್ OTP ಕೇಳುವುದಿಲ್ಲ."), Txt("Never share an OTP or PIN, even with someone claiming to be the bank.", "OTP ಅಥವಾ PIN ಯಾರಿಗೂ ಹೇಳಬೇಡಿ.")),
        Rule("upipin", Sev.HIGH, .45, Txt("PIN to \"receive\" money", "ಹಣ \"ಪಡೆಯಲು\" PIN"),
            rx("""\b(enter|type|input)\b[^.\n]{0,30}\b(upi )?pin\b[^.\n]{0,40}\b(receive|get|claim|refund|credit)|\b(collect request|approve (the )?(payment )?request)"""), rx("""ಹಣ ಪಡೆಯಲು[^.\n]{0,20}(ಪಿನ್|PIN)"""),
            Txt("You never need a PIN to receive money. A PIN only sends money out.", "ಹಣ ಪಡೆಯಲು PIN ಬೇಕಿಲ್ಲ. PIN ಹಣ ಕಳುಹಿಸಲು ಮಾತ್ರ."), Txt("Decline any collect request you did not start.", "ನೀವು ಆರಂಭಿಸದ collect request ತಿರಸ್ಕರಿಸಿ.")),
        Rule("remote", Sev.HIGH, .4, Txt("Remote-access or unknown app", "ರಿಮೋಟ್ ಆಕ್ಸೆಸ್ / ಅಪರಿಚಿತ ಆ್ಯಪ್"),
            rx("""\b(anydesk|teamviewer|quick ?support|rustdesk|airdroid|screen ?shar(e|ing)|install (this|the|our) app|download (this|the) app)\b"""), rx("""ಆ್ಯಪ್ ಡೌನ್‌ಲೋಡ್ ಮಾಡಿ|ಆ್ಯಪ್ ಇನ್‌ಸ್ಟಾಲ್|ಸ್ಕ್ರೀನ್ ಶೇರ್|ಎನಿ ?ಡೆಸ್ಕ್"""),
            Txt("Screen-sharing and side-loaded apps let the caller read your OTPs.", "ಇಂತಹ ಆ್ಯಪ್‌ಗಳು ನಿಮ್ಮ OTP ಓದುತ್ತವೆ."), Txt("Uninstall any app a stranger asked you to install.", "ಅಪರಿಚಿತರು ಹೇಳಿದ ಆ್ಯಪ್ ಅನ್‌ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿ.")),
        Rule("secrecy", Sev.HIGH, .35, Txt("Demands secrecy or isolation", "ಗೌಪ್ಯತೆಗೆ ಒತ್ತಾಯ"),
            rx("""\b(don'?t|do not) (tell|inform|share this with) (anyone|anybody|family|your family|the bank)|\bkeep (this |it )?(confidential|secret)|\b(don'?t|do not) disconnect|\bstay on the (call|line|video)|\bdigital arrest\b"""), rx("""ಯಾರಿಗೂ ಹೇಳಬೇಡಿ|ಮನೆಯವರಿಗೆ ಹೇಳಬೇಡಿ|ಕರೆ ಕಡಿತಗೊಳಿಸಬೇಡಿ|ಡಿಜಿಟಲ್ ಅರೆಸ್ಟ್"""),
            Txt("Real officials never forbid you from talking to family.", "ನಿಜವಾದ ಅಧಿಕಾರಿಗಳು ಹೀಗೆ ಹೇಳುವುದಿಲ್ಲ."), Txt("Hang up and tell someone you trust. Police do not arrest anyone over a call.", "ಕರೆ ಕಡಿತಗೊಳಿಸಿ, ನಂಬಿಕಸ್ಥರಿಗೆ ತಿಳಿಸಿ.")),
        Rule("creds", Sev.HIGH, .3, Txt("Asks for login or card details", "ಲಾಗಿನ್ ಅಥವಾ ಕಾರ್ಡ್ ವಿವರ ಕೇಳುತ್ತಿದೆ"),
            rx("""\b(password|passcode|cvv|card number|card details|login details|net ?banking (id|details)|verify your (account|identity|details)|confirm your (account|details|identity)|update your (kyc|details|account|pan|information)|update pan|kyc update)\b"""), rx("""ಕೆವೈಸಿ ನವೀಕರಿಸ|ಖಾತೆಯನ್ನು ಪರಿಶೀಲಿಸ|ಪಾಸ್‌ವರ್ಡ್|KYC update maadi"""),
            Txt("Requests to verify or update details are the core of phishing.", "ವಿವರ ನವೀಕರಿಸಿ ಎನ್ನುವುದು ಫಿಶಿಂಗ್ ತಂತ್ರ."), Txt("Use the official app. Never use the link in the message.", "ಅಧಿಕೃತ ಆ್ಯಪ್ ಬಳಸಿ. ಸಂದೇಶದ ಲಿಂಕ್ ಬಳಸಬೇಡಿ.")),
        Rule("threat", Sev.HIGH, .25, Txt("Threat or penalty", "ಬೆದರಿಕೆ ಅಥವಾ ದಂಡ"),
            rx("""\b(suspend(ed)?|block(ed)?|deactivat(ed|ion)|disconnect(ed|ion)|locked|terminat(ed|ion)|legal action|arrest(ed)?|warrant|penalty|fine of|police|FIR|court|money laundering|seized|frozen|banned|or else)\b"""), rx("""ಬ್ಲಾಕ್|ಸ್ಥಗಿತ|ಬಂಧನ|ಪೊಲೀಸ್|ದಂಡ|block aag"""),
            Txt("Scammers invent a consequence so you act before checking.", "ಪರಿಶೀಲಿಸುವ ಮೊದಲೇ ಕ್ರಮಕ್ಕಾಗಿ ಬೆದರಿಸುತ್ತಾರೆ."), Txt("Check your account yourself in the official app.", "ಖಾತೆಯನ್ನು ಅಧಿಕೃತ ಆ್ಯಪ್‌ನಲ್ಲಿ ನೀವೇ ಪರಿಶೀಲಿಸಿ.")),
        Rule("money", Sev.HIGH, .3, Txt("Unusual payment demand", "ಅಸಾಮಾನ್ಯ ಪಾವತಿ ಬೇಡಿಕೆ"),
            rx("""\b(gift ?cards?|google play (cards?|codes?)|bitcoin|btc|usdt|crypto(currency)?|wire transfer|western union|processing (fee|charges)|registration fee|release fee|customs (duty|fee|charges)|security deposit|(safe|secure|verification|rbi) account|transfer (all )?(your )?(savings|funds|money)|pay (rs\.?|₹|inr)\s?[\d,]+)\b"""), rx("""ಶುಲ್ಕ ಪಾವತಿಸಿ|ವೀಸಾ ಶುಲ್ಕ|ಹಣ ವರ್ಗಾಯಿಸಿ|ಗಿಫ್ಟ್ ಕಾರ್ಡ್|ನೋಂದಣಿ ಶುಲ್ಕ"""),
            Txt("Gift cards, crypto, safe accounts and release fees are fraud routes.", "ಗಿಫ್ಟ್ ಕಾರ್ಡ್, ಕ್ರಿಪ್ಟೋ, ಶುಲ್ಕ: ವಂಚನೆಯ ದಾರಿಗಳು."), Txt("Do not pay any fee to receive a prize, parcel, refund or job.", "ಬಹುಮಾನ, ರೀಫಂಡ್, ಕೆಲಸಕ್ಕಾಗಿ ಶುಲ್ಕ ಕಟ್ಟಬೇಡಿ.")),
        Rule("power", Sev.HIGH, .35, Txt("Electricity disconnection threat", "ವಿದ್ಯುತ್ ಕಡಿತದ ಬೆದರಿಕೆ"),
            rx("""\b(electricity|power|current|light)\b[^.\n]{0,40}\b(disconnect\w*|cut|discontinu\w*)\b|\belectricity officer\b|\bsupply will be cut\b"""), rx("""ವಿದ್ಯುತ್[^.\n]{0,25}ಕಡಿತ|current cut"""),
            Txt("ESCOMs do not ask you to call a personal mobile number.", "ESCOM ಗಳು ವೈಯಕ್ತಿಕ ಸಂಖ್ಯೆಗೆ ಕರೆ ಮಾಡಲು ಹೇಳುವುದಿಲ್ಲ."), Txt("Check your bill in the ESCOM app or call 1912.", "ESCOM ಆ್ಯಪ್‌ನಲ್ಲಿ ಬಿಲ್ ನೋಡಿ ಅಥವಾ 1912 ಗೆ ಕರೆ ಮಾಡಿ.")),
        Rule("scheme", Sev.HIGH, .35, Txt("Fee in the name of a government scheme", "ಸರ್ಕಾರಿ ಯೋಜನೆಯ ಹೆಸರಿನಲ್ಲಿ ಶುಲ್ಕ"),
            rx("""\b(gruha ?lakshmi|gruha ?jyothi|anna ?bhagya|shakti scheme|yuva ?nidhi|pm[- ]?kisan|ayushman)\b[^\n]{0,80}\b(fee|pay|registration|pending|not credited|link|click)\b"""), rx("""(ಗೃಹಲಕ್ಷ್ಮಿ|ಗೃಹ ಜ್ಯೋತಿ|ಅನ್ನಭಾಗ್ಯ|ಯುವನಿಧಿ)[^\n]{0,60}(ಶುಲ್ಕ|ಪಾವತಿ|ಲಿಂಕ್)"""),
            Txt("Karnataka guarantee schemes have no registration fee.", "ಗ್ಯಾರಂಟಿ ಯೋಜನೆಗಳಿಗೆ ನೋಂದಣಿ ಶುಲ್ಕವಿಲ್ಲ."), Txt("Ask at Grama One / Karnataka One or on Seva Sindhu.", "ಗ್ರಾಮ ಒನ್ ಅಥವಾ ಸೇವಾ ಸಿಂಧುವಿನಲ್ಲಿ ವಿಚಾರಿಸಿ.")),
        Rule("challan", Sev.HIGH, .4, Txt("Fake traffic e-challan", "ನಕಲಿ ಟ್ರಾಫಿಕ್ ಇ-ಚಲನ್"),
            rx("""\b(e-?challan|traffic (fine|challan)|rto)\b[^.\n]{0,60}\b(apk|download|app|pay now|link)\b"""), rx("""ಚಲನ್[^.\n]{0,40}(ಡೌನ್‌ಲೋಡ್|ಲಿಂಕ್|APK)"""),
            Txt("Fake challan apps steal SMS and OTPs.", "ನಕಲಿ ಚಲನ್ ಆ್ಯಪ್ SMS ಮತ್ತು OTP ಕದಿಯುತ್ತದೆ."), Txt("Check challans only on the official Parivahan site.", "ಅಧಿಕೃತ ಪರಿವಾಹನ್ ಸೈಟ್‌ನಲ್ಲಿ ಮಾತ್ರ ಪರಿಶೀಲಿಸಿ.")),
        Rule("invest", Sev.HIGH, .4, Txt("Investment or trading \"tips\" scheme", "ಹೂಡಿಕೆ ಅಥವಾ ಟ್ರೇಡಿಂಗ್ \"ಟಿಪ್ಸ್\" ಯೋಜನೆ"),
            rx("""\b(stock (market )?tips|share market tips|trading (tips|group|signals?|mentor)|vip (group|trading|membership)|(guaranteed|assured|special|vip|institutional|pre-?ipo) (ipo )?allotment|institutional (account|trading)|block deals?|guaranteed (returns?|profits?)|assured (returns?|profits?)|(daily|weekly|monthly) (profits?|returns?) of|double your (money|investment)|\d+\s?% (daily|weekly|monthly|per day|per week|per month) (returns?|profits?)|withdraw(al)? (tax|fees?|charges?)|pay (the )?(tax|fees?) to withdraw|upper circuit|multibagger|(crypto|usdt) (investment|trading|mining) (plan|platform|app|group))\b"""), rx("""ಷೇರು (ಟಿಪ್ಸ್|ಸಲಹೆ)|ಟ್ರೇಡಿಂಗ್ (ಗುಂಪು|ಗ್ರೂಪ್|ಟಿಪ್ಸ್)|ಖಚಿತ (ಲಾಭ|ಆದಾಯ)|ಹಣ ದುಪ್ಪಟ್ಟು|ದಿನಕ್ಕೆ[^.\n]{0,15}ಲಾಭ|ಹಿಂಪಡೆಯಲು[^.\n]{0,20}(ತೆರಿಗೆ|ಶುಲ್ಕ)"""),
            Txt("Fake investment groups show fake profits, then ask for \"tax\" or fees before you can withdraw.", "ನಕಲಿ ಹೂಡಿಕೆ ಗುಂಪುಗಳು ನಕಲಿ ಲಾಭ ತೋರಿಸಿ, ಹಣ ತೆಗೆಯುವ ಮೊದಲು ಶುಲ್ಕ ಕೇಳುತ್ತವೆ."), Txt("Invest only through a SEBI-registered broker. Pay only to UPI IDs ending in @valid.", "SEBI ನೋಂದಾಯಿತ ಬ್ರೋಕರ್ ಮೂಲಕ ಮಾತ್ರ ಹೂಡಿಕೆ ಮಾಡಿ. @valid UPI ID ಗೆ ಮಾತ್ರ ಪಾವತಿಸಿ.")),
        Rule("apk", Sev.HIGH, .6, Txt("App file sent as a message (.apk)", "ಸಂದೇಶದಲ್ಲಿ ಆ್ಯಪ್ ಫೈಲ್ (.apk)"),
            rx("""\b[\w-]+\.apk\b|\b(wedding|marriage|invitation|invite|challan|rto|kyc|bill|aadhaar|pm ?kisan|customer care|customer support|bank)[^.\n]{0,25}\b(apk|app file)\b|\b(download|install|open) (the |this )?(attached )?apk\b"""), rx("""ಮದುವೆ ಆಮಂತ್ರಣ[^.\n]{0,25}(ಆ್ಯಪ್|apk|APK)"""),
            Txt("Wedding cards, bills and challans are never .apk files. Such apps read your SMS and OTPs.", "ಮದುವೆ ಆಮಂತ್ರಣ, ಬಿಲ್, ಚಲನ್ ಎಂದಿಗೂ .apk ಅಲ್ಲ. ಇಂತಹ ಆ್ಯಪ್ SMS, OTP ಓದುತ್ತದೆ."), Txt("Do not open the file. If you installed it, turn on flight mode, uninstall it and call your bank.", "ಫೈಲ್ ತೆರೆಯಬೇಡಿ. ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿದ್ದರೆ ಫ್ಲೈಟ್ ಮೋಡ್ ಆನ್ ಮಾಡಿ, ಅನ್‌ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿ, ಬ್ಯಾಂಕ್‌ಗೆ ಕರೆ ಮಾಡಿ.")),
        Rule("esim", Sev.HIGH, .4, Txt("eSIM or SIM \"upgrade\" request", "eSIM ಅಥವಾ SIM \"ಅಪ್‌ಗ್ರೇಡ್\" ವಿನಂತಿ"),
            rx("""\be-?sim\b[^.\n]{0,40}\b(activat\w*|upgrad\w*|convert\w*|switch\w*|request\w*|link|qr( code)?)\b|\b(activat\w*|upgrad\w*|convert\w*)\b[^.\n]{0,30}\be-?sim\b|\bsim (card )?(swap|upgrade|replacement)\b|\bsim (card )?will be (blocked|deactivated|suspended)\b|\b(4g|5g) sim (card )?upgrade\b"""), rx("""ಇ-?ಸಿಮ್|eSIM[^.\n]{0,20}(ಸಕ್ರಿಯ|ಅಪ್‌ಗ್ರೇಡ್)|ಸಿಮ್[^.\n]{0,20}(ಬ್ಲಾಕ್|ಅಪ್‌ಗ್ರೇಡ್|ಬದಲಾವಣೆ)"""),
            Txt("An eSIM link or QR code from a stranger can move your number to their phone, with all your OTPs.", "ಅಪರಿಚಿತರ eSIM ಲಿಂಕ್ ಅಥವಾ QR ನಿಮ್ಮ ಸಂಖ್ಯೆ ಮತ್ತು OTP ಗಳನ್ನು ಅವರ ಫೋನ್‌ಗೆ ಸಾಗಿಸಬಹುದು."), Txt("Change a SIM only in your operator's app or store. If your signal suddenly vanishes, call your bank from another phone.", "SIM ಬದಲಾವಣೆ ಆಪರೇಟರ್ ಆ್ಯಪ್ ಅಥವಾ ಮಳಿಗೆಯಲ್ಲಿ ಮಾತ್ರ. ಸಿಗ್ನಲ್ ಹೋದರೆ ಬೇರೆ ಫೋನ್‌ನಿಂದ ಬ್ಯಾಂಕ್‌ಗೆ ಕರೆ ಮಾಡಿ.")),
        Rule("mule", Sev.HIGH, .4, Txt("Wants to use your bank account", "ನಿಮ್ಮ ಬ್ಯಾಂಕ್ ಖಾತೆ ಬಳಸಲು ಕೇಳುತ್ತಿದೆ"),
            rx("""\b(rent|lend|sell|give) (out )?(your|ur) (bank )?(account|a/c|current account|savings account|upi( id)?|atm card|debit card|sim)\b|\b(use|using) (your|ur) (bank )?(account|a/c|upi)\b[^.\n]{0,40}\b(commission|receive|transfer|payments?|transactions?)\b|\bcommission\b[^.\n]{0,40}\b(per|for every|on each|on every) (transaction|transfer|payment|lakh)\b|\b(receiv\w*|accept\w*) (payments?|money|funds) (in|into|on) (your|ur) (bank )?(account|upi)\b[^.\n]{0,60}\b(transfer\w*|send|forward\w*)\b|\b(payment|transaction) processing (agent|partner|job)\b"""), rx("""ಖಾತೆ[^.\n]{0,15}ಬಾಡಿಗೆ|ನಿಮ್ಮ (ಬ್ಯಾಂಕ್ )?ಖಾತೆ(ಯನ್ನು)? (ಬಳಸಲು|ಕೊಡಿ)|ಪ್ರತಿ ವಹಿವಾಟಿಗೆ[^.\n]{0,15}ಕಮಿಷನ್"""),
            Txt("Recruiters pay commission to pass scam money through your account. Police arrest the account holder.", "ವಂಚನೆಯ ಹಣ ನಿಮ್ಮ ಖಾತೆಯ ಮೂಲಕ ಸಾಗಿಸಲು ಕಮಿಷನ್ ಕೊಡುತ್ತಾರೆ. ಪೊಲೀಸರು ಖಾತೆದಾರರನ್ನು ಬಂಧಿಸುತ್ತಾರೆ."), Txt("Never let anyone use your account, UPI, ATM card or SIM. Report the offer to 1930.", "ನಿಮ್ಮ ಖಾತೆ, UPI, ATM ಕಾರ್ಡ್ ಅಥವಾ SIM ಯಾರಿಗೂ ಕೊಡಬೇಡಿ. 1930 ಗೆ ವರದಿ ಮಾಡಿ.")),
        Rule("boss", Sev.MID, .3, Txt("\"Boss\" asking for an urgent favour", "\"ಬಾಸ್\" ತುರ್ತು ಸಹಾಯ ಕೇಳುತ್ತಿದ್ದಾರೆ"),
            rx("""\b(this is|it'?s|i am|i'?m) (your )?(ceo|md|managing director|director|boss|manager|chairman|president|principal)\b|\b(i'?m|i am) (in|at) (a |the )?(meeting|conference|board meeting)\b[^.\n]{0,60}\b(can'?t|cannot|unable to) (talk|call|take calls|speak)\b|\b(can'?t|cannot) (talk|take calls|speak) (now|right now)\b[^.\n]{0,80}\b(transfer|payment|pay|gift ?cards?|vendor|urgent)\b|\b(urgent|immediate) (payment|transfer) to (a |our |the )?(vendor|supplier|client|party)\b|\bsave (this|my) new (number|whatsapp)\b"""), rx("""ನಾನು ಮೀಟಿಂಗ್‌ನಲ್ಲಿದ್ದೇನೆ|ಈ ಹೊಸ ಸಂಖ್ಯೆ ಸೇವ್ ಮಾಡಿ|ತುರ್ತಾಗಿ ಹಣ ವರ್ಗಾಯಿಸಿ"""),
            Txt("Scammers copy a senior's WhatsApp and say they are busy, so you will not call to check.", "ವಂಚಕರು ಹಿರಿಯರ ವಾಟ್ಸಾಪ್ ನಕಲಿಸಿ, ತಾವು ಬ್ಯುಸಿ ಎನ್ನುತ್ತಾರೆ."), Txt("Call your boss on the number you already have before you pay or open any file.", "ಪಾವತಿಸುವ ಮೊದಲು ನಿಮ್ಮಲ್ಲಿರುವ ಸಂಖ್ಯೆಗೆ ಬಾಸ್‌ಗೆ ಕರೆ ಮಾಡಿ.")),
        Rule("novalid", Sev.HIGH, .3, Txt("Investment payment to a UPI ID without @valid", "@valid ಇಲ್ಲದ UPI ID ಗೆ ಹೂಡಿಕೆ ಪಾವತಿ"),
            rx("""(?!x)x"""), null,
            Txt("SEBI-registered brokers and mutual funds collect money only through UPI IDs ending in @valid.", "SEBI ನೋಂದಾಯಿತ ಬ್ರೋಕರ್‌ಗಳು @valid UPI ID ಮೂಲಕ ಮಾತ್ರ ಹಣ ಪಡೆಯುತ್ತಾರೆ."), Txt("Do not pay. Check the UPI ID on SEBI Check first.", "ಪಾವತಿಸಬೇಡಿ. ಮೊದಲು SEBI Check ನಲ್ಲಿ UPI ID ಪರಿಶೀಲಿಸಿ.")),
        Rule("family", Sev.MID, .32, Txt("Money request posing as a friend or relative", "ಸ್ನೇಹಿತ / ಸಂಬಂಧಿಕರಂತೆ ಹಣದ ಬೇಡಿಕೆ"),
            rx("""\b(new number|my (phone|mobile) (broke|fell|is (lost|broken))|lost my phone|phone (broke|fell)|(i'?m|i am|iam|this is|it'?s) (your )?(friend|brother|bro|sister|son|daughter|cousin|uncle|nephew|niece|colleague|classmate)|(send|transfer)\s+(me|us)\s+(some\s+)?(money|cash|amount)|(please|pls|plz|kindly)\s+(send|transfer)\s+(some\s+)?(money|cash|amount)|^\s*(send|transfer)\s+(some\s+)?(money|cash)|\bi\s+need\s+(some\s+)?(money|cash))\b"""), rx("""ಹೊಸ ಸಂಖ್ಯೆ|ಫೋನ್ ಕಳೆದು"""),
            Txt("Relative-impersonation scams start this way.", "ಸಂಬಂಧಿಕರಂತೆ ನಟಿಸುವ ವಂಚನೆ ಹೀಗೆ ಶುರು."), Txt("Call the person on their old saved number first. Voices can be cloned with AI, so ask for your family code word.", "ಹಳೆಯ ಉಳಿಸಿದ ಸಂಖ್ಯೆಗೆ ಕರೆ ಮಾಡಿ ಖಚಿತಪಡಿಸಿ. AI ಧ್ವನಿ ನಕಲಿಸಬಹುದು, ಕುಟುಂಬದ ಗುಪ್ತ ಪದ ಕೇಳಿ.")),
        Rule("gulf", Sev.MID, .28, Txt("Overseas job with visa fee", "ವಿದೇಶಿ ಉದ್ಯೋಗ, ವೀಸಾ ಶುಲ್ಕ"),
            rx("""\b(dubai|saudi|qatar|kuwait|oman|bahrain|gulf|abroad)\b[^.\n]{0,60}\b(visa|job|salary)\b|\bvisa (processing|charges|fee)"""), rx("""(ದುಬೈ|ವಿದೇಶ)[^.\n]{0,30}(ಉದ್ಯೋಗ|ವೀಸಾ)"""),
            Txt("Fake Gulf jobs demand visa fees up front.", "ನಕಲಿ ಗಲ್ಫ್ ಉದ್ಯೋಗ ಮುಂಗಡ ಶುಲ್ಕ ಕೇಳುತ್ತದೆ."), Txt("Verify the agent on the eMigrate portal.", "eMigrate ಪೋರ್ಟಲ್‌ನಲ್ಲಿ ಏಜೆಂಟ್ ಪರಿಶೀಲಿಸಿ.")),
        Rule("urgency", Sev.MID, .2, Txt("Artificial urgency", "ಕೃತಕ ತುರ್ತು"),
            rx("""\b(urgent(ly)?|immediately|right away|within (\d+|24|48|two|one) ?(hours?|hrs?|minutes?|mins?)|in \d+ (hours?|minutes?)|today only|tonight|last (chance|warning|reminder)|final (notice|warning|reminder)|act now|expires? (today|soon|in)|asap|limited seats|before midnight)\b"""), rx("""ತುರ್ತು|ತಕ್ಷಣ|ಕೂಡಲೇ|ಇಂದೇ|ಇಂದು ರಾತ್ರಿ|\bbega\b|koodale|turant|jaldi|ivattu raatri"""),
            Txt("Short deadlines stop you from verifying.", "ಗಡುವು ನೀವು ಪರಿಶೀಲಿಸದಂತೆ ತಡೆಯಲು."), Txt("Slow down. A real problem will still be there after you check.", "ನಿಧಾನಿಸಿ. ನಿಜವಾದ ಸಮಸ್ಯೆ ನಂತರವೂ ಇರುತ್ತದೆ.")),
        Rule("id", Sev.MID, .2, Txt("Identity document mentioned", "ಗುರುತಿನ ದಾಖಲೆ ಉಲ್ಲೇಖ"),
            rx("""\b(kyc|pan( card)?|aadhaa?r|passport (number|details)|voter id)\b"""), rx("""ಕೆವೈಸಿ|ಆಧಾರ್|ಪಾನ್ ಕಾರ್ಡ್"""),
            Txt("KYC and PAN are the top bank-phishing hooks in India.", "KYC, PAN ಪ್ರಮುಖ ಫಿಶಿಂಗ್ ಆಮಿಷಗಳು."), Txt("Update KYC only in your bank app or at a branch.", "KYC ಬ್ಯಾಂಕ್ ಆ್ಯಪ್ ಅಥವಾ ಶಾಖೆಯಲ್ಲಿ ಮಾತ್ರ.")),
        Rule("prize", Sev.MID, .2, Txt("Too-good-to-be-true reward", "ನಂಬಲಾಗದ ಬಹುಮಾನ"),
            rx("""\b(congratulations|you('ve| have)? won|winner|lottery|lucky draw|jackpot|prize|claim your (reward|gift|prize)|free (iphone|gift|recharge)|guaranteed returns?)\b"""), rx("""ಅಭಿನಂದನೆಗಳು|ಬಹುಮಾನ|ಗೆದ್ದಿದ್ದೀರಿ|ಲಾಟರಿ"""),
            Txt("Unexpected winnings are bait. The fee comes next.", "ಅನಿರೀಕ್ಷಿತ ಬಹುಮಾನ ಆಮಿಷ."), Txt("If you did not enter, you did not win.", "ನೀವು ಭಾಗವಹಿಸದಿದ್ದರೆ ಗೆದ್ದಿಲ್ಲ.")),
        Rule("job", Sev.MID, .25, Txt("Easy-money job offer", "ಸುಲಭ ಹಣದ ಕೆಲಸ"),
            rx("""\b(work from home|part[- ]time (job|work)|earn (rs\.?|₹)?\s?[\d,]+\s?(per day|daily|/day|a day)?|daily (income|earning|salary)|liking (youtube )?videos|like (youtube )?videos|rate (hotels|products|restaurants)|prepaid task|tasks? to earn|telegram group|join (our )?telegram|trading group)\b"""), rx("""ಮನೆಯಿಂದಲೇ ಕೆಲಸ|ದಿನಕ್ಕೆ ₹?\s?\d+|ಪಾರ್ಟ್ ಟೈಮ್"""),
            Txt("Task scams pay a little, then ask you to invest.", "ಟಾಸ್ಕ್ ವಂಚನೆ ನಂತರ ಹೂಡಿಕೆ ಕೇಳುತ್ತದೆ."), Txt("Real employers never ask you to pay to work.", "ನಿಜವಾದ ಉದ್ಯೋಗದಾತರು ಹಣ ಕೇಳುವುದಿಲ್ಲ.")),
        Rule("parcel", Sev.MID, .2, Txt("Parcel or delivery problem", "ಪಾರ್ಸೆಲ್ / ಡೆಲಿವರಿ ಸಮಸ್ಯೆ"),
            rx("""\b(parcel|package|shipment|courier)\b[^.\n]{0,40}\b(held|on hold|failed|pending|undeliver\w*|address (issue|incomplete)|re-?schedul\w*|illegal|drugs|seized)"""), rx("""ಪಾರ್ಸೆಲ್[^.\n]{0,30}(ತಡೆ|ಬಾಕಿ|ಸ್ಥಗಿತ)"""),
            Txt("Couriers don't text for fees or call about drugs.", "ಕೊರಿಯರ್‌ಗಳು ಹೀಗೆ ಮಾಡುವುದಿಲ್ಲ."), Txt("Track parcels only in the courier's own app.", "ಕೊರಿಯರ್‌ನ ಸ್ವಂತ ಆ್ಯಪ್‌ನಲ್ಲಿ ಮಾತ್ರ ಟ್ರ್ಯಾಕ್ ಮಾಡಿ.")),
        Rule("callback", Sev.MID, .2, Txt("Pushes you to call a number", "ಸಂಖ್ಯೆಗೆ ಕರೆ ಮಾಡಲು ಒತ್ತಾಯ"),
            rx("""\b(call|contact|whatsapp|dial|ring)\b[^.\n]{0,30}(\+?\d[\d\s-]{8,}\d)|\bpress 1\b"""), rx("""(ಕರೆ ಮಾಡಿ|call maadi)[^.\n]{0,25}(\+?\d[\d\s-]{8,}\d)"""),
            Txt("Numbers inside scam messages lead to fake support desks.", "ಇಂತಹ ಸಂಖ್ಯೆಗಳು ನಕಲಿ ಸಹಾಯ ಕೇಂದ್ರಗಳು."), Txt("Call back only on a number you found yourself.", "ನೀವೇ ಹುಡುಕಿದ ಸಂಖ್ಯೆಗೆ ಮಾತ್ರ ಕರೆ ಮಾಡಿ.")),
        Rule("greeting", Sev.LOW, .1, Txt("Generic greeting", "ಸಾಮಾನ್ಯ ಸಂಬೋಧನೆ"),
            rx("""\bdear (customer|user|client|sir/madam|sir|madam|account holder|valued customer|member|consumer|subscriber)\b"""), rx("""ಆತ್ಮೀಯ ಗ್ರಾಹಕರೇ"""),
            Txt("Services you use know your name.", "ನಿಮ್ಮ ಸೇವೆಗಳಿಗೆ ನಿಮ್ಮ ಹೆಸರು ಗೊತ್ತು."), null)
    )
    private val SAFE_CUES = listOf(
        rx("""\b(do not|never|don'?t) share (this|your|the|it)?\s?(otp|code|pin)?\b""") to Txt("Warns you not to share the code", "ಕೋಡ್ ಹಂಚಿಕೊಳ್ಳಬೇಡಿ ಎಂದು ಎಚ್ಚರಿಸುತ್ತದೆ"),
        rx("""ಹಂಚಿಕೊಳ್ಳಬೇಡಿ""") to Txt("Warns you not to share the code", "ಕೋಡ್ ಹಂಚಿಕೊಳ್ಳಬೇಡಿ ಎಂದು ಎಚ್ಚರಿಸುತ್ತದೆ"),
        rx("""\bnever (asks?|call|request)s? (you )?for\b""") to Txt("States it will never ask for your details", "ವಿವರ ಕೇಳುವುದಿಲ್ಲ ಎಂದು ಹೇಳುತ್ತದೆ"),
        rx("""\b(ignore if already paid|if this was you, no action needed|no action needed)\b""") to Txt("No pressure to act", "ಕ್ರಮಕ್ಕೆ ಒತ್ತಡವಿಲ್ಲ")
    )
    private val LINK_RE = rx("""(?:https?://[^\s<>"']+|www\.[^\s<>"']+|\b[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:[a-z]{2,24})(?:/[^\s<>"']*)?)""")
    private val NEG_RE = rx("""(\b(not|never|no one|nobody)\b|n't)[^.\n]{0,35}$""")
    private val UPI_ID_RE = Regex("""\b[\w.-]{2,64}@([a-z]{2,24})\b(?![.\w])""", RegexOption.IGNORE_CASE)
    private val STORE_RE = rx("""play ?store|app ?store|google play""")
    private val NEG_IN = rx("""\b(not|never)\b|n't""")

    private data class RuleResult(val score: Int, val flags: List<Flag>, val todos: List<Txt>, val safeCue: Boolean = false)

    private fun rules(text: String): RuleResult {
        val linkRanges = mutableListOf<IntRange>()
        val links = mutableListOf<UrlResult>()
        for (m in LINK_RE.findAll(text)) {
            val u = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
            val prev = if (m.range.first > 0) text[m.range.first - 1] else ' '
            val next = if (m.range.first + u.length < text.length) text[m.range.first + u.length] else ' '
            if (prev == '@' || prev.isLetterOrDigit() || prev == '_' || next == '@') continue
            val hostPart = u.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").split('/', '?', '#')[0]
            if (Regex("\\.(pdf|jpg|jpeg|png|docx?|xlsx?|txt|zip|apk)$", RegexOption.IGNORE_CASE).containsMatchIn(u) && !u.startsWith("http", true)) continue
            if (!Regex("[a-z]{2,}$", RegexOption.IGNORE_CASE).containsMatchIn(hostPart)) continue
            val r = analyzeUrl(u) ?: continue
            links.add(r); linkRanges.add(m.range.first until m.range.first + u.length)
        }
        fun inLink(r: IntRange) = linkRanges.any { r.first <= it.last && r.last >= it.first }
        val hits = LinkedHashMap<String, MutableList<String>>()
        for (rule in RULES) for (re in listOfNotNull(rule.en, rule.kn)) for (m in re.findAll(text)) {
            if (inLink(m.range)) continue
            if (rule.id == "apk" && STORE_RE.containsMatchIn(text.substring(m.range.first, min(text.length, m.range.last + 61)))) continue
            if (rule.id in setOf("otpshare", "creds", "upipin")) {
                val before = text.substring(max(0, m.range.first - 45), m.range.first)
                if (NEG_RE.containsMatchIn(before) || NEG_IN.containsMatchIn(m.value)) continue
            }
            val ex = hits.getOrPut(rule.id) { mutableListOf() }
            if (ex.size < 3 && m.value !in ex) ex.add(m.value)
        }
        if ("invest" in hits) {
            val upis = UPI_ID_RE.findAll(text).filter { !it.groupValues[1].startsWith("valid", ignoreCase = true) }.map { it.value }.toList()
            if (upis.isNotEmpty()) hits["novalid"] = upis.take(2).toMutableList()
        }
        val safe = SAFE_CUES.filter { it.first.containsMatchIn(text) }.map { it.second }.distinctBy { it.en }
        val ws = hits.keys.map { id -> RULES.first { it.id == id }.w }.toMutableList()
        links.filter { it.score >= 20 }.forEach { ws.add(min(.7, it.score / 100.0 * .8)) }
        var score = combine(ws)
        if (safe.isNotEmpty() && "otpshare" !in hits && "upipin" !in hits) score = (score * .55).roundToInt()
        val flags = mutableListOf<Flag>()
        for (l in links) {
            val top = l.flags.filter { it.sev != Sev.SAFE }.maxByOrNull { it.w }
            when {
                l.score >= 25 -> flags.add(Flag("link", if (l.score >= 60) Sev.HIGH else Sev.MID, 1.0, Txt("Risky link: ${l.reg}", "ಅಪಾಯಕಾರಿ ಲಿಂಕ್: ${l.reg}"), top?.let { Txt(it.label.en + ". " + it.why.en, it.label.kn + ". " + it.why.kn) } ?: Txt("")))
                l.known -> flags.add(Flag(null, Sev.SAFE, 0.0, Txt("Link to ${l.reg}", "${l.reg} ಗೆ ಲಿಂಕ್"), l.flags.first().why))
                else -> flags.add(Flag(null, Sev.LOW, 0.0, Txt("Link to ${l.reg}", "${l.reg} ಗೆ ಲಿಂಕ್"), Txt("Unknown domain. Check it belongs to the sender.", "ಅಪರಿಚಿತ ಡೊಮೇನ್.")))
            }
        }
        for ((id, ex) in hits) { val r = RULES.first { it.id == id }; flags.add(Flag(id, r.sev, r.w, r.label, r.why, r.todo, ex)) }
        safe.forEach { flags.add(Flag(null, Sev.SAFE, 0.0, it, Txt("Genuine messages often include this.", "ನಿಜವಾದ ಸಂದೇಶಗಳಲ್ಲಿ ಸಾಮಾನ್ಯ."))) }
        val todos = mutableListOf<Txt>()
        if (links.any { it.score >= 25 }) todos.add(Txt("Do not open the link. Use the official app instead.", "ಲಿಂಕ್ ತೆರೆಯಬೇಡಿ. ಅಧಿಕೃತ ಆ್ಯಪ್ ಬಳಸಿ."))
        flags.mapNotNull { it.todo }.forEach { t -> if (todos.none { it.en == t.en }) todos.add(t) }
        val order = mapOf(Sev.HIGH to 0, Sev.MID to 1, Sev.LOW to 2, Sev.SAFE to 3)
        val safeCue = safe.isNotEmpty() && "otpshare" !in hits && "upipin" !in hits
        return RuleResult(score, flags.sortedWith(compareBy<Flag> { order[it.sev] }.thenByDescending { it.w }), todos, safeCue)
    }

    // ---------------- Naive Bayes ----------------
    private class NB { val c = IntArray(2); val tc = IntArray(2); val f = arrayOf(HashMap<String, Int>(), HashMap<String, Int>()); val v = HashSet<String>() }
    private var nb: NB? = null

    private fun tokens(text: String): List<String> {
        var s = text.lowercase()
        val extra = mutableListOf<String>()
        s = Regex("""(?:https?://|www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,24}(?:/\S*)?""").replace(s) { m ->
            analyzeUrl(m.value)?.let { extra.add(if (it.score >= 40) "__badurl" else if (it.known) "__goodurl" else "__url") }; " "
        }
        s = Regex("""\+?\d[\d\s-]{8,}\d""").replace(s) { extra.add("__phone"); " " }
        s = Regex("""(rs\.?|₹|inr)\s?[\d,]+(\.\d+)?""").replace(s) { extra.add("__money"); " " }
        val w = Regex("""[\p{L}\p{M}]+""").findAll(s).map { it.value }.filter { it.length > 1 }.toList()
        val bi = (0 until max(0, w.size - 1)).map { w[it] + "_" + w[it + 1] }
        return w + bi + extra
    }

    private fun train(): NB {
        val m = NB()
        for ((y, x) in Data.ROWS + Store.feedbackRows()) { m.c[y]++; for (t in tokens(x)) { m.f[y][t] = (m.f[y][t] ?: 0) + 1; m.tc[y]++; m.v.add(t) } }
        return m
    }

    fun retrain() { nb = train() }

    fun ml(text: String): Double {
        val m = nb ?: train().also { nb = it }
        val V = m.v.size.toDouble(); val N = (m.c[0] + m.c[1]).toDouble()
        val lp = doubleArrayOf(ln(m.c[0] / N), ln(m.c[1] / N))
        for (t in tokens(text)) { if (t !in m.v) continue; for (y in 0..1) lp[y] += ln(((m.f[y][t] ?: 0) + 1) / (m.tc[y] + V)) }
        val mx = max(lp[0], lp[1]); val e0 = exp(lp[0] - mx); val e1 = exp(lp[1] - mx)
        return e1 / (e0 + e1)
    }

    fun fuse(rule: Int, ml: Double?, safeCue: Boolean = false): Int {
        if (ml == null) return rule
        var s = (.6 * rule + .4 * ml * 100).roundToInt()
        if (rule >= 60 || ml >= .9) s = max(s, 62)
        if (rule < 20 && ml < .35) s = min(s, 20)
        // A genuine-message cue ("do not share this code") with no scam rule outweighs the classifier.
        if (safeCue && rule < 20) s = min(s, 20)
        // Without any scam rule, the classifier alone can make a message Suspicious but never Dangerous.
        if (rule < 20) s = min(s, 45)
        // A strong scam rule can't be pulled down to "safe" by the classifier.
        if (rule >= 40) s = max(s, if (rule >= 55) 60 else 45)
        return s.coerceIn(0, 100)
    }

    // ---------------- Explanation ----------------
    private val TYPES = mapOf(
        "invest" to Txt("Investment / trading scam", "ಹೂಡಿಕೆ / ಟ್ರೇಡಿಂಗ್ ವಂಚನೆ"), "novalid" to Txt("Investment / trading scam", "ಹೂಡಿಕೆ / ಟ್ರೇಡಿಂಗ್ ವಂಚನೆ"),
        "apk" to Txt("Malicious app file (APK)", "ಹಾನಿಕಾರಕ ಆ್ಯಪ್ ಫೈಲ್ (APK)"), "esim" to Txt("SIM / eSIM takeover", "SIM / eSIM ಕಬಳಿಕೆ"),
        "mule" to Txt("Money-mule recruitment", "ಹಣ ಸಾಗಣೆ (ಮ್ಯೂಲ್) ನೇಮಕಾತಿ"), "boss" to Txt("Boss / CEO impersonation", "ಬಾಸ್ / CEO ನಟನೆ"),
        "kyc" to Txt("KYC phishing", "KYC ಫಿಶಿಂಗ್"), "upipin" to Txt("UPI collect-request scam", "UPI collect request ವಂಚನೆ"),
        "secrecy" to Txt("Digital arrest / impersonation", "ಡಿಜಿಟಲ್ ಅರೆಸ್ಟ್ / ನಟನೆ"), "power" to Txt("Power-cut scam", "ವಿದ್ಯುತ್ ಕಡಿತ ವಂಚನೆ"),
        "scheme" to Txt("Government-scheme fee scam", "ಸರ್ಕಾರಿ ಯೋಜನೆ ಶುಲ್ಕ ವಂಚನೆ"), "challan" to Txt("Fake e-challan", "ನಕಲಿ ಇ-ಚಲನ್"),
        "family" to Txt("Relative impersonation", "ಸಂಬಂಧಿಕರಂತೆ ನಟನೆ"), "gulf" to Txt("Overseas job scam", "ವಿದೇಶಿ ಉದ್ಯೋಗ ವಂಚನೆ"),
        "job" to Txt("Task / job scam", "ಟಾಸ್ಕ್ / ಉದ್ಯೋಗ ವಂಚನೆ"), "otpshare" to Txt("OTP theft", "OTP ಕಳ್ಳತನ"),
        "remote" to Txt("Remote-access scam", "ರಿಮೋಟ್ ಆಕ್ಸೆಸ್ ವಂಚನೆ"), "prize" to Txt("Prize / lottery scam", "ಬಹುಮಾನ / ಲಾಟರಿ ವಂಚನೆ"),
        "parcel" to Txt("Parcel scam", "ಪಾರ್ಸೆಲ್ ವಂಚನೆ"), "creds" to Txt("Credential phishing", "ಲಾಗಿನ್ ಫಿಶಿಂಗ್"),
        "link" to Txt("Phishing link", "ಫಿಶಿಂಗ್ ಲಿಂಕ್"), "money" to Txt("Payment fraud", "ಪಾವತಿ ವಂಚನೆ"), "threat" to Txt("Threat-based scam", "ಬೆದರಿಕೆ ವಂಚನೆ")
    )
    private val PRIORITY = listOf("upipin","secrecy","esim","apk","novalid","invest","mule","power","scheme","challan","boss","family","gulf","job","otpshare","remote","prize","parcel","creds","link","money","threat")
    private val ORGS = listOf(
        rx("""\bsebi\b""") to "SEBI", rx("""\bjio\b""") to "Jio",
        rx("""\bsbi\b|state bank|yono""") to "SBI", rx("canara") to "Canara Bank", rx("karnataka ?bank") to "Karnataka Bank",
        rx("""\bhdfc""") to "HDFC Bank", rx("""\bicici""") to "ICICI Bank", rx("mescom|ಮೆಸ್ಕಾಂ") to "MESCOM", rx("bescom|ಬೆಸ್ಕಾಂ") to "BESCOM",
        rx("""\bcbi\b""") to "CBI", rx("police|crime branch|ಪೊಲೀಸ್") to "Police", rx("fedex") to "FedEx", rx("""\brbi\b""") to "RBI",
        rx("flipkart") to "Flipkart", rx("amazon") to "Amazon", rx("airtel") to "Airtel", rx("ksrtc") to "KSRTC",
        rx("gruha ?lakshmi|ಗೃಹಲಕ್ಷ್ಮಿ") to "Govt of Karnataka", rx("income ?tax") to "Income Tax Dept"
    )

    fun analyze(text: String): Analysis {
        val r = rules(text)
        val p = ml(text)
        val s = fuse(r.score, p, r.safeCue)
        val ids = r.flags.mapNotNull { it.id }.toSet()
        var typeKey: String? = null
        if (s >= 30) typeKey = when {
            "id" in ids && ("creds" in ids || "link" in ids) -> "kyc"
            "prize" in ids && "threat" !in ids && "invest" !in ids -> "prize"
            else -> PRIORITY.firstOrNull { it in ids }
        }
        val type = typeKey?.let { TYPES[it] }
        val org = if (s >= 30) ORGS.firstOrNull { it.first.containsMatchIn(text) }?.second else null
        val bad = r.flags.filter { it.sev == Sev.HIGH || it.sev == Sev.MID }.take(3)
        val summary = when {
            s >= 60 -> Txt(
                "This looks like a scam${type?.let { " (${it.en})" } ?: ""}${org?.let { " pretending to be from $it" } ?: ""}. " +
                    (if (bad.isNotEmpty()) "Warning signs: ${bad.joinToString("; ") { it.label.en }}. " else "") + "Do not click, pay or share anything.",
                "ಇದು ${org?.let { "$it ಹೆಸರಿನಲ್ಲಿ ಬಂದ " } ?: ""}${type?.let { it.kn + " " } ?: "ವಂಚನೆಯ "}ಸಂದೇಶದಂತೆ ಕಾಣುತ್ತದೆ. " +
                    (if (bad.isNotEmpty()) "ಎಚ್ಚರಿಕೆ ಲಕ್ಷಣಗಳು: ${bad.joinToString("; ") { it.label.kn }}. " else "") + "ಕ್ಲಿಕ್ ಮಾಡಬೇಡಿ, ಪಾವತಿಸಬೇಡಿ, ಏನನ್ನೂ ಹಂಚಿಕೊಳ್ಳಬೇಡಿ.")
            s >= 30 -> Txt(
                "This message has warning signs${org?.let { " and mentions $it" } ?: ""}: ${bad.joinToString("; ") { it.label.en }.ifEmpty { "unusual wording" }}. Check with the organisation through its official app before you act.",
                "ಈ ಸಂದೇಶದಲ್ಲಿ ಎಚ್ಚರಿಕೆಯ ಲಕ್ಷಣಗಳಿವೆ: ${bad.joinToString("; ") { it.label.kn }.ifEmpty { "ಅಸಾಮಾನ್ಯ ಪದಗಳು" }}. ಕ್ರಮ ಕೈಗೊಳ್ಳುವ ಮೊದಲು ಅಧಿಕೃತ ಆ್ಯಪ್‌ನಲ್ಲಿ ಪರಿಶೀಲಿಸಿ.")
            else -> Txt(
                "We found no strong scam signals. If money or a code is involved, confirm with the sender through a number you already know.",
                "ಬಲವಾದ ವಂಚನೆಯ ಲಕ್ಷಣಗಳು ಕಂಡುಬಂದಿಲ್ಲ. ಹಣ ಅಥವಾ ಕೋಡ್ ವಿಷಯವಿದ್ದರೆ, ತಿಳಿದ ಸಂಖ್ಯೆಯ ಮೂಲಕ ಖಚಿತಪಡಿಸಿ.")
        }
        return Analysis(r.score, p, s, r.flags, r.todos, type, org, summary)
    }

    /** Registered business sender IDs (e.g. JD-FLPKRT-S, VM-SPTIFY) contain letters; personal numbers do not. */
    fun isRegisteredSender(sender: String) = sender.any { it.isLetter() }

    /**
     * Should an incoming SMS raise a notification? Registered business senders (Flipkart, Spotify, banks)
     * send offers, OTPs and payment reminders all day, so they need strong scam evidence before we alert.
     */
    fun shouldAlertSms(sender: String, a: Analysis) =
        if (isRegisteredSender(sender)) a.score >= 60 && a.ruleScore >= 45 else a.score >= 30

    /** Call alerts need real scam behaviour, not just a mention of a bank. */
    fun shouldAlertCall(a: Analysis) = a.score >= 60 && a.ruleScore >= 45

    /** Pre-answer hints about the caller's number. */
    fun numberHints(number: String): List<Txt> {
        val n = number.replace(Regex("[\\s()-]"), "")
        val out = mutableListOf<Txt>()
        if (n.isEmpty()) out.add(Txt("Hidden number", "ಮರೆಮಾಚಿದ ಸಂಖ್ಯೆ"))
        else if (n.startsWith("+") && !n.startsWith("+91")) out.add(Txt("International number", "ಅಂತರರಾಷ್ಟ್ರೀಯ ಸಂಖ್ಯೆ"))
        val local = n.removePrefix("+91").removePrefix("0")
        if (local.startsWith("140")) out.add(Txt("Telemarketing series (140)", "ಟೆಲಿಮಾರ್ಕೆಟಿಂಗ್ ಸರಣಿ (140)"))
        return out
    }
}

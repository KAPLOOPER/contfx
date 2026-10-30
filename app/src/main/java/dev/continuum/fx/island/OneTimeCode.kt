package dev.continuum.fx.island

/**
 * Finds a one-time code in a message: "482913 is your verification code", "Your Uber code: 4829",
 * "G-482913 is your Google verification code", "Use 123 456 to sign in".
 *
 * A code is only taken when the message says it's one (a word like code, OTP, PIN, verify or
 * sign-in, in a few languages), and the number nearest those words wins. Amounts, times, years,
 * phone numbers and order numbers are passed over. What comes back is what you'd type: the
 * digits (or letters and digits) without any "G-" prefix, spaces or dashes.
 */
object OneTimeCode {

    /** Words that say a message carries a code, matched case-insensitively. */
    private val KEYWORDS = listOf(
        "code", "otp", "passcode", "pass code", "one-time", "one time", "verification", "verify", "verifying",
        "pin", "2fa", "two-factor", "two factor", "2-step", "two-step", "security code", "sign-in", "sign in",
        // Not "confirm": order confirmations carry numbers that aren't codes.
        "login", "log in", "log-in", "authentication", "authenticate", "token",
        // Other languages, the most common forms.
        "código", "codigo", "codice", "kod", "kode", "код", "验证码", "驗證碼", "認証", "確認コード", "인증",
        "bestätigung", "verifizierung", "vérification", "verificación", "verifica", "senha",
    )

    private val OFFERS = listOf("promo", "discount", "coupon", "voucher", "referral", "gift", "offer", "redeem")

    private const val CURRENCY = "$€£¥₹₩₽"

    /** Digits 4 to 8 long, or two groups of 3 or 4 split by a space or dash, or a mixed code of letters and digits. */
    private val CANDIDATE = Regex(
        """(?<![\p{L}\p{N}#$€£¥₹₩₽+/\\@_])(?:[A-Z]-)?(\d{3,4}[ -]\d{3,4}|\d{4,8}|(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{5,8})(?![\p{L}\p{N}%/\\@_]|[.,:-]\p{N})""",
    )

    /** The code in [title] and [text], or null when there isn't one. */
    fun find(title: String, text: String): String? {
        val all = "$title\n$text"
        val lower = all.lowercase()
        // "Promo code", "gift code" and the like are offers, not sign-ins.
        val hits = KEYWORDS.flatMap { k -> occurrences(lower, k) }.filter { h ->
            val lead = lower.substring(maxOf(0, h.first - 12), h.first)
            OFFERS.none { lead.contains(it) }
        }
        if (hits.isEmpty()) return null

        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (m in CANDIDATE.findAll(all)) {
            val raw = m.groupValues[1]
            val code = raw.filter { it != ' ' && it != '-' }
            if (!plausible(all, m.range, raw, code, hits)) continue
            val distance = hits.minOf { h -> distance(m.range, h) }
            if (distance > 80) continue
            // Six digits is by far the most common length: a nudge when two are equally near.
            val score = distance * 4 + (if (code.length == 6 && code.all { it.isDigit() }) 0 else 3)
            if (score < bestScore) {
                bestScore = score
                best = code
            }
        }
        return best
    }

    private fun plausible(all: String, range: IntRange, raw: String, code: String, hits: List<IntRange>): Boolean {
        val before = all.substring(maxOf(0, range.first - 3), range.first)
        val after = all.substring(range.last + 1, minOf(all.length, range.last + 4))
        // Money, either side ("$1234", "1234 €"), and percentages.
        if (before.trimEnd().lastOrNull()?.let { it in CURRENCY } == true) return false
        if (after.trimStart().firstOrNull()?.let { it in CURRENCY || it == '%' } == true) return false
        // Part of a longer number: a phone number or a card, say.
        if (before.trimEnd().lastOrNull()?.isDigit() == true || after.trimStart().firstOrNull()?.isDigit() == true) {
            if (raw.none { it == ' ' || it == '-' }) return false
        }
        val digits = code.all { it.isDigit() }
        // A year, unless it's plainly what the code words name ("code: 2024", "PIN is 1999").
        if (digits && code.length == 4 && code.toInt() in 1900..2099) {
            val named = hits.any { h ->
                h.last < range.first && all.substring(h.last + 1, range.first).lowercase().matches(Regex("""[\s:：-]*(is)?[\s:：-]*"""))
            }
            if (!named) return false
        }
        // Mixed letters and digits only straight after the code words, where nothing else could be
        // meant, and only jumbled ones ("F7K2Q"): a word in it ("SAVE20") makes it a promotion.
        if (!digits) {
            if (Regex("[A-Z]{3,}").containsMatchIn(code)) return false
            val tight = hits.any { h -> (h.last < range.first && range.first - h.last <= 6) || (h.first > range.last && h.first - range.last <= 4) }
            if (!tight) return false
        }
        return true
    }

    private fun occurrences(s: String, k: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = s.indexOf(k)
        while (i >= 0) {
            // Whole words for Latin keywords ("pin" not in "spinning"), anywhere for the others.
            val latin = k.all { it.code < 0x2E80 }
            val startOk = !latin || i == 0 || !s[i - 1].isLetter()
            val end = i + k.length
            val endOk = !latin || end >= s.length || !s[end].isLetter() || k.length >= 6 ||
                // A plural: "codes".
                (s[end] == 's' && (end + 1 >= s.length || !s[end + 1].isLetter()))
            if (startOk && endOk) out += i until i + k.length
            i = s.indexOf(k, i + 1)
        }
        return out
    }

    private fun distance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }
}

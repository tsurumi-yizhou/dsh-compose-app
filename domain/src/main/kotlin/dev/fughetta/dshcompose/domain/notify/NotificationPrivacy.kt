package dev.fughetta.dshcompose.domain.notify

/** Bounded display text only. Raw request arguments never enter log messages or preferences. */
object NotificationPrivacy {
    private val prefixes =
        listOf(
            "sk-",
            "AIza",
            "ghp_",
            "github_pat_",
            "glpat-",
            "npm_",
            "hf_",
            "AKIA",
            "ASIA",
            "LTAI",
            "AKID",
            "xoxb-",
            "xoxp-",
            "sk_live_",
            "sk_test_",
            "rk_live_",
            "rk_test_",
            "whsec_",
            "eyJ",
        )
    private val assignments =
        Regex(
            "(?i)([\\\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|password|secret|cookie)[\\\"']?\\s*[:=]\\s*)(?:[\\\"][^\\\"]*[\\\"]|'[^']*'|[^\\s,;}]+)",
        )
    private val bearer = Regex("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val urlPassword = Regex("([A-Za-z][A-Za-z0-9+.-]*://[^\\s/:@?#]+:)[^\\s/@?#]+(@)")

    fun mask(value: String): String {
        val input = value.take(16_384)
        val out = StringBuilder(input.length)
        var at = 0
        while (at < input.length) {
            if (input.startsWith("-----BEGIN ", at)) {
                val header = input.indexOf("PRIVATE KEY-----", at + 11)
                if (header in (at + 11)..(at + 80)) {
                    val end = input.indexOf("-----END ", header + 16)
                    val finish =
                        if (end >= 0)
                            input.indexOf("-----", end + 9).let {
                                if (it >= 0) it + 5 else input.length
                            }
                        else input.length
                    out.append("••••••")
                    at = finish
                    continue
                }
            }
            val prefix = prefixes.firstOrNull { input.startsWith(it, at) }
            if (prefix != null) {
                var end = at + prefix.length
                while (
                    end < input.length && (input[end].isLetterOrDigit() || input[end] in "_-.+/=")
                ) end++
                if (end - at >= prefix.length + 4) {
                    out.append("••••••")
                    at = end
                    continue
                }
            }
            val c = input[at++]
            // Render direction overrides and invisible controls visibly in actionable text.
            if (
                c in '\u200b'..'\u200f' ||
                    c in '\u202a'..'\u202e' ||
                    c in '\u2066'..'\u2069' ||
                    c == '\u061c'
            )
                out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else out.append(c)
        }
        return urlPassword.replace(
            assignments.replace(bearer.replace(out.toString(), "••••••")) {
                "${it.groupValues[1]}••••••"
            },
        ) {
            "${it.groupValues[1]}••••••${it.groupValues[2]}"
        }
    }

    fun snippet(value: String?, length: Int = 120): String? {
        value ?: return null
        val text =
            mask(value)
                .replace(Regex("```[A-Za-z0-9_-]*|\\*\\*|__|`"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        if (text.isEmpty()) return null
        val end = text.offsetByCodePoints(0, minOf(length, text.codePointCount(0, text.length)))
        return text.substring(0, end) + if (end < text.length) "…" else ""
    }

    fun exact(value: String): String? = value.takeIf { it.length <= 3072 && mask(it) == it }
}

package dev.fughetta.dshcompose.icons

internal object PictureShortcuts {
    /** A 432 px PNG is well under this; anything near it is not one of ours. */
    const val MAX_PNG_BYTES = 2 * 1024 * 1024

    /** Launchers cut long labels anyway; the short label's own cap is 10 to 12 characters. */
    private const val MAX_LABEL = 24

    const val PREFIX = "picture:"

    /** Each picture is its own shortcut (pinning another doesn't replace the first). */
    fun id(now: Long) = "$PREFIX$now"

    fun label(raw: String?): String {
        val clean = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return "DSH Compose"
        return if (clean.codePointCount(0, clean.length) <= MAX_LABEL) clean
        else clean.substring(0, clean.offsetByCodePoints(0, MAX_LABEL))
    }
}

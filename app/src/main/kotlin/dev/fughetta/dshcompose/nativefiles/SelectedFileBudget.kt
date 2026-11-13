package dev.fughetta.dshcompose.nativefiles

/** Hard policy bounds, independent of a provider's missing or incorrect SIZE column. */
internal class SelectedFileBudget(
    val maxFiles: Int,
    val maxBytes: Int,
    val maxTotalBytes: Int,
    /**
     * The app's own pick for its launch screen (a picture or clip): one file up to [OWN_MAX].
     * Plugins never get it.
     */
    own: Boolean = false,
) {
    private val counts = mutableMapOf<String, Int>()
    private var total = 0

    init {
        if (own) {
            require(maxFiles == 1 && maxBytes in 1..OWN_MAX && maxTotalBytes in 1..OWN_MAX)
        } else {
            require(
                maxFiles in 1..4 &&
                    maxBytes in 1..8 * 1024 * 1024 &&
                    maxTotalBytes in 1..16 * 1024 * 1024,
            )
        }
    }

    companion object {
        /** A launch screen's clip: 30 MB. */
        const val OWN_MAX = 30 * 1024 * 1024
    }

    fun metadata(sizes: List<Long?>) {
        require(sizes.size in 1..maxFiles) { "too-many-files" }
        require(sizes.all { it == null || it in 0..maxBytes.toLong() }) { "too-large" }
        require(sizes.filterNotNull().sum() <= maxTotalBytes.toLong()) { "too-large" }
    }

    /** One extra byte is enough to detect a dishonest size even at the exact cap. */
    fun chunkSize(handle: String): Int =
        minOf(64 * 1024, maxBytes - (counts[handle] ?: 0) + 1, maxTotalBytes - total + 1)

    fun accept(handle: String, bytes: Int) {
        val next = (counts[handle] ?: 0).toLong() + bytes
        require(bytes >= 0 && next <= maxBytes && total.toLong() + bytes <= maxTotalBytes) {
            "too-large"
        }
        counts[handle] = next.toInt()
        total += bytes
    }
}

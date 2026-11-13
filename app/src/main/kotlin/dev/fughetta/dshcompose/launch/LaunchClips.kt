package dev.fughetta.dshcompose.launch

internal object LaunchClips {
    /** The poster's long side at most: a phone screen's. */
    private const val POSTER_SIDE = 1440

    /** The frame as shown: a quarter turn swaps the stored width and height. */
    fun shown(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (((rotation % 360) + 360) % 360 == 90 || ((rotation % 360) + 360) % 360 == 270)
            height to width
        else width to height

    fun posterSize(width: Int, height: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= POSTER_SIDE) return width to height
        val scale = POSTER_SIDE.toDouble() / long
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}

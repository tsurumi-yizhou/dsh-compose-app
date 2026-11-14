package dev.fughetta.dshcompose.ui.dynamicui

import java.util.Base64

/**
 * genui pictures never borrow host URLs or credentials. Inspect dimensions before bitmap decoding.
 */
internal data class InlinePictureData(val source: String, val width: Int, val height: Int) {
    override fun toString() = "InlinePictureData(width=$width, height=$height)"
}

internal fun inlinePicture(source: String): InlinePictureData? {
    if (source.length > 65536 || !rasterData.containsMatchIn(source)) return null
    val bytes =
        try {
            Base64.getDecoder().decode(source.substringAfter(',').filterNot { it.isWhitespace() })
        } catch (_: IllegalArgumentException) {
            return null
        }
    val (width, height) = rasterSize(bytes) ?: return null
    return if (width in 1..4096 && height in 1..4096 && width.toLong() * height <= 4_000_000)
        InlinePictureData(source, width, height)
    else null
}

private val rasterData =
    Regex("""^data:image/(?:png|jpeg|jpg|webp|gif);base64,""", RegexOption.IGNORE_CASE)

internal fun rasterSize(bytes: ByteArray): Pair<Int, Int>? {
    fun u8(index: Int) = bytes[index].toInt() and 255
    fun be16(index: Int) = (u8(index) shl 8) or u8(index + 1)
    fun le16(index: Int) = u8(index) or (u8(index + 1) shl 8)
    fun le24(index: Int) = le16(index) or (u8(index + 2) shl 16)
    fun be32(index: Int) = (be16(index) shl 16) or be16(index + 2)
    fun at(index: Int, signature: String) =
        index + signature.length <= bytes.size &&
            signature.indices.all { u8(index + it) == signature[it].code }
    if (bytes.size >= 24 && at(0, "\u0089PNG\r\n\u001A\n") && at(12, "IHDR"))
        return be32(16) to be32(20)
    if (bytes.size >= 10 && (at(0, "GIF87a") || at(0, "GIF89a"))) return le16(6) to le16(8)
    if (bytes.size >= 2 && u8(0) == 255 && u8(1) == 216) {
        var index = 2
        while (index + 9 < bytes.size) {
            if (u8(index) != 255) return null
            val marker = u8(index + 1)
            if (marker == 255) {
                index++
                continue
            }
            if (marker == 216 || marker in 208..215 || marker == 1) {
                index += 2
                continue
            }
            val length = be16(index + 2)
            if (length < 2) return null
            if (marker in 192..207 && marker !in setOf(196, 200, 204))
                return be16(index + 7) to be16(index + 5)
            index += 2 + length
        }
    }
    if (bytes.size >= 30 && at(0, "RIFF") && at(8, "WEBP")) {
        if (at(12, "VP8 ") && at(23, "\u009D\u0001*"))
            return (le16(26) and 16383) to (le16(28) and 16383)
        if (at(12, "VP8L") && u8(20) == 47) {
            val bits = le24(21) or (u8(24) shl 24)
            return ((bits and 16383) + 1) to (((bits ushr 14) and 16383) + 1)
        }
        if (at(12, "VP8X")) return (le24(24) + 1) to (le24(27) + 1)
    }
    return null
}

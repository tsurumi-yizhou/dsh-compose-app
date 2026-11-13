package dev.fughetta.dshcompose.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.fughetta.dshcompose.launch.LaunchClips
import dev.fughetta.dshcompose.ui.appearance.*
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.*

internal class AppearanceMedia(private val context: Context) {
    data class Imported(
        val path: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val bitmap: Bitmap,
        val edge: Int?,
        val kind: LaunchMediaKind,
        val poster: String?,
        val duration: Long?,
    )

    suspend fun import(kind: AppearanceImport, uri: Uri): Imported =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val video = mime.startsWith("video/")
            require(!video || kind == AppearanceImport.Launch) { "APPEARANCE_MEDIA_UNSUPPORTED" }
            val maximum = if (video) 30L * 1024 * 1024 else 8L * 1024 * 1024
            val directory = File(context.filesDir, "appearance").apply { mkdirs() }
            val destination =
                File(directory, UUID.randomUUID().toString() + if (video) ".video" else ".image")
            var poster: File? = null
            try {
                var count = 0L
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output ->
                        val chunk = ByteArray(32768)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytes = input.read(chunk)
                            if (bytes < 0) break
                            count += bytes
                            require(count <= maximum) { "APPEARANCE_MEDIA_TOO_LARGE" }
                            output.write(chunk, 0, bytes)
                        }
                    }
                } ?: error("APPEARANCE_MEDIA_UNREADABLE")
                if (video) {
                    val reader = MediaMetadataRetriever()
                    try {
                        reader.setDataSource(destination.absolutePath)
                        val width =
                            reader
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                ?.toIntOrNull() ?: error("APPEARANCE_MEDIA_UNREADABLE")
                        val height =
                            reader
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                ?.toIntOrNull() ?: error("APPEARANCE_MEDIA_UNREADABLE")
                        val rotation =
                            reader
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                                ?.toIntOrNull() ?: 0
                        val shown = LaunchClips.shown(width, height, rotation)
                        val scale = minOf(1.0, 1440.0 / max(width, height))
                        val (posterWidth, posterHeight) = LaunchClips.posterSize(width, height)
                        val frame =
                            (if (android.os.Build.VERSION.SDK_INT >= 27)
                                reader.getScaledFrameAtTime(
                                    0,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    posterWidth,
                                    posterHeight,
                                )
                            else
                                reader
                                    .getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?.let { source ->
                                        val scaled =
                                            Bitmap.createScaledBitmap(
                                                source,
                                                maxOf(1, (source.width * scale).toInt()),
                                                maxOf(1, (source.height * scale).toInt()),
                                                true,
                                            )
                                        if (scaled !== source) source.recycle()
                                        scaled
                                    }) ?: error("APPEARANCE_MEDIA_UNREADABLE")
                        poster = File(directory, destination.nameWithoutExtension + ".png")
                        poster.outputStream().use {
                            frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        Imported(
                            destination.absolutePath,
                            shown.first,
                            shown.second,
                            count,
                            frame,
                            edge(frame),
                            LaunchMediaKind.Video,
                            poster.absolutePath,
                            reader
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull(),
                        )
                    } finally {
                        reader.release()
                    }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(destination.absolutePath, options)
                    require(options.outWidth > 0 && options.outHeight > 0) {
                        "APPEARANCE_MEDIA_UNSUPPORTED"
                    }
                    val bitmap = decode(destination.absolutePath)
                    val animation =
                        mime == "image/gif" ||
                            destination.inputStream().use { input ->
                                val header = ByteArray(256)
                                val bytes = input.read(header)
                                bytes > 0 &&
                                    header
                                        .copyOf(bytes)
                                        .toString(Charsets.ISO_8859_1)
                                        .contains("ANIM")
                            }
                    Imported(
                        destination.absolutePath,
                        options.outWidth,
                        options.outHeight,
                        count,
                        bitmap,
                        edge(bitmap),
                        if (animation) LaunchMediaKind.Animation else LaunchMediaKind.Image,
                        null,
                        null,
                    )
                }
            } catch (error: Exception) {
                destination.delete()
                poster?.delete()
                throw error
            }
        }

    suspend fun read(path: String): Bitmap = withContext(Dispatchers.IO) { decode(path) }

    private fun decode(path: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "APPEARANCE_MEDIA_UNREADABLE" }
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = 1
                while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *=
                    2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        if (android.os.Build.VERSION.SDK_INT >= 28)
            return android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(File(path)),
            ) { decoder, info, _ ->
                val scale = minOf(1.0, 2048.0 / max(info.size.width, info.size.height))
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * scale).toInt()),
                    maxOf(1, (info.size.height * scale).toInt()),
                )
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: error("APPEARANCE_MEDIA_UNREADABLE")
        val orientation = runCatching {
            android.media
                .ExifInterface(path)
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        }
            .getOrDefault(1)
        val matrix =
            android.graphics.Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> {
                        setRotate(90f)
                        postScale(-1f, 1f)
                    }
                    6 -> setRotate(90f)
                    7 -> {
                        setRotate(270f)
                        postScale(-1f, 1f)
                    }
                    8 -> setRotate(270f)
                }
            }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun edge(bitmap: Bitmap): Int? {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        fun pixel(x: Int, y: Int) {
            val argb = bitmap.getPixel(x, y)
            if ((argb ushr 24) < 128) return
            red += (argb ushr 16) and 255
            green += (argb ushr 8) and 255
            blue += argb and 255
            count++
        }
        for (index in 0..63) {
            val x = index * (bitmap.width - 1) / 63
            val y = index * (bitmap.height - 1) / 63
            pixel(x, 0)
            pixel(x, bitmap.height - 1)
            pixel(0, y)
            pixel(bitmap.width - 1, y)
        }
        return if (count == 0) null
        else
            0xFF000000.toInt() or
                ((red / count).toInt() shl 16) or
                ((green / count).toInt() shl 8) or
                (blue / count).toInt()
    }

    fun discard(path: String?, poster: String? = null) {
        listOfNotNull(path, poster).forEach { name ->
            val file = File(name)
            if (file.parentFile == File(context.filesDir, "appearance")) file.delete()
        }
    }
}

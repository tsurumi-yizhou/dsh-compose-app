package dev.fughetta.dshcompose.appearance

import android.app.PendingIntent
import android.content.*
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.*
import android.graphics.drawable.Icon
import android.os.Build
import dev.fughetta.dshcompose.icons.PictureShortcuts
import dev.fughetta.dshcompose.shortcuts.EntryActivity
import java.io.ByteArrayOutputStream

internal class AppearancePictureShortcut(
    private val context: Context,
    private val onPinned: () -> Unit,
) : AutoCloseable {
    private val action = "${context.packageName}.appearance.PINNED"
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == action) onPinned()
            }
        }

    init {
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(action))
    }

    fun pin(label: String, image: Bitmap, zoom: Float, x: Float, y: Float): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) return false
        val output = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            // Adaptive icons expose the middle 2/3. Crop against that viewport and extend into the
            // inset.
            val scale = maxOf(288f / image.width, 288f / image.height) * zoom
            val w = image.width * scale
            val h = image.height * scale
            val left = 72f - (w - 288f) * (x + 1f) / 2f
            val top = 72f - (h - 288f) * (y + 1f) / 2f
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(
                image,
                null,
                RectF(left, top, left + w, top + h),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val bytes =
                ByteArrayOutputStream()
                    .also { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    .toByteArray()
            if (bytes.size > PictureShortcuts.MAX_PNG_BYTES) return false
            val id = PictureShortcuts.id(System.currentTimeMillis())
            val info =
                ShortcutInfo.Builder(context, id)
                    .setShortLabel(PictureShortcuts.label(label))
                    .setIcon(Icon.createWithAdaptiveBitmap(output))
                    .setIntent(EntryActivity.openApp(context))
                    .build()
            val callback =
                PendingIntent.getBroadcast(
                    context,
                    id.hashCode(),
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            return manager.requestPinShortcut(info, callback.intentSender)
        } finally {
            output.recycle()
        }
    }

    override fun close() {
        context.unregisterReceiver(receiver)
    }
}

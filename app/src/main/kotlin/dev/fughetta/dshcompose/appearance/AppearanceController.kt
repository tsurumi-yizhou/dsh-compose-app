package dev.fughetta.dshcompose.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import dev.fughetta.dshcompose.icons.LauncherIconViewModel
import dev.fughetta.dshcompose.icons.LauncherIcons
import dev.fughetta.dshcompose.ui.appearance.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppearanceController(
    private val scope: CoroutineScope,
    context: Context,
    private val icons: LauncherIconViewModel,
) : AutoCloseable {
    private val app = context.applicationContext
    private val store = AppearanceStore(app)
    private val media = AppearanceMedia(app)
    private val mutable = MutableStateFlow(AppearanceState())
    val state: StateFlow<AppearanceState> = mutable.asStateFlow()
    private val eventChannel = Channel<AppearanceEffect>(16)
    val effects = eventChannel.receiveAsFlow()
    private val persistence = Mutex()
    private val retiredMedia = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var loaded = false
    private var closed = false
    private var write: Job? = null
    private var importing: Job? = null
    private var loading: Job? = null
    private val shortcut =
        AppearancePictureShortcut(app) { change { it.copy(notice = "APPEARANCE_SHORTCUT_ADDED") } }

    init {
        load()
    }

    fun action(action: AppearanceAction) {
        when (action) {
            AppearanceAction.Refresh -> refresh()
            is AppearanceAction.Preset ->
                if (action.id in appearancePresets)
                    edit { it.copy(source = AccentSource.Preset, preset = action.id) }
            is AppearanceAction.CustomColor ->
                edit {
                    it.copy(
                        source = AccentSource.Custom,
                        customArgb = action.argb or 0xFF000000.toInt(),
                    )
                }
            is AppearanceAction.Source -> edit { it.copy(source = action.source) }
            AppearanceAction.ImportLaunch ->
                eventChannel.trySend(AppearanceEffect.PickMedia(AppearanceImport.Launch))
            is AppearanceAction.LaunchEnabled ->
                edit { it.copy(launch = it.launch?.copy(enabled = action.value)) }
            is AppearanceAction.LaunchFit ->
                edit { it.copy(launch = it.launch?.copy(fit = action.value)) }
            is AppearanceAction.LaunchBackdrop ->
                edit { it.copy(launch = it.launch?.copy(backdrop = action.value)) }
            AppearanceAction.PreviewLaunch -> change { it.copy(previewLaunch = true) }
            AppearanceAction.CloseLaunchPreview -> change { it.copy(previewLaunch = false) }
            is AppearanceAction.Icon ->
                if (!state.value.busy && action.id in LauncherIcons.variants) {
                    change { it.copy(busy = true, error = null) }
                    scope.launch {
                        try {
                            val current = icons.setIcon(action.id)
                            change { it.copy(currentIcon = current) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            change { it.copy(error = "APPEARANCE_ICON_FAILED") }
                        } finally {
                            change { it.copy(busy = false) }
                        }
                    }
                }
            AppearanceAction.ImportShortcut ->
                eventChannel.trySend(AppearanceEffect.PickMedia(AppearanceImport.Shortcut))
            is AppearanceAction.ShortcutLabel -> change { it.copy(shortcutLabel = action.value) }
            is AppearanceAction.ShortcutFrame ->
                change {
                    it.copy(
                        shortcutZoom = action.zoom.coerceIn(1f, 5f),
                        shortcutX = action.x.coerceIn(-1f, 1f),
                        shortcutY = action.y.coerceIn(-1f, 1f),
                    )
                }
            AppearanceAction.PinShortcut -> pinShortcut()
            AppearanceAction.CloseShortcut ->
                change { it.copy(shortcutEditing = false, shortcutPicture = null) }
        }
    }

    fun performance(lightweight: Boolean, animations: Boolean) {}

    fun refresh() {
        if (closed) return
        val accent =
            if (Build.VERSION.SDK_INT >= 31) app.getColor(android.R.color.system_accent1_500)
            else null
        change {
            it.copy(wallpaperAccent = accent, currentIcon = LauncherIconViewModel.current(app))
        }
        publishAccent()
    }

    fun importMedia(kind: AppearanceImport, uri: Uri?) {
        if (uri == null || !loaded || state.value.busy || closed) return
        change { it.copy(busy = true, error = null) }
        importing = scope.launch {
            try {
                val imported = media.import(kind, uri)
                if (closed) {
                    media.discard(imported.path, imported.poster)
                    return@launch
                }
                val old = state.value.preferences
                when (kind) {
                    AppearanceImport.Launch -> {
                        old.launch?.let {
                            retiredMedia += it.path
                            it.poster?.let(retiredMedia::add)
                        }
                        edit {
                            it.copy(
                                launch =
                                    AppearanceLaunch(
                                        imported.kind,
                                        imported.path,
                                        imported.width,
                                        imported.height,
                                        imported.bytes,
                                        imported.poster,
                                        imported.duration,
                                        edge = imported.edge,
                                    ),
                            )
                        }
                    }
                    AppearanceImport.Shortcut -> {
                        change {
                            it.copy(
                                shortcutPicture = imported.bitmap,
                                shortcutEditing = true,
                                shortcutZoom = 1f,
                                shortcutX = 0f,
                                shortcutY = 0f,
                            )
                        }
                        media.discard(imported.path, imported.poster)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed)
                    change {
                        it.copy(
                            error =
                                error.message?.takeIf { value -> value.startsWith("APPEARANCE_") }
                                    ?: "APPEARANCE_MEDIA_UNREADABLE",
                        )
                    }
            } finally {
                if (!closed) change { it.copy(busy = false) }
            }
        }
    }

    private fun load() {
        loading = scope.launch {
            try {
                val prefs = store.load()
                if (closed) return@launch
                change { it.copy(preferences = prefs) }
                withContext(Dispatchers.IO) {
                    store.legacyWallpaperPath()?.let { path ->
                        val keep = setOfNotNull(prefs.launch?.path, prefs.launch?.poster)
                        if (
                            keep.none {
                                java.io.File(it).canonicalPath == java.io.File(path).canonicalPath
                            }
                        )
                            media.discard(path)
                        store.save(prefs)
                    }
                }
                if (closed) return@launch
                loaded = true
                change { it.copy(preferences = prefs, loading = false) }
                refresh()
                val loadedIcons =
                    withContext(Dispatchers.IO) {
                        LauncherIcons.variants
                            .map { id ->
                                val name = LauncherIcons.mipmapOf(id)
                                val resource =
                                    app.resources.getIdentifier(name, "mipmap", app.packageName)
                                val image =
                                    app.getDrawable(resource)?.let { drawable ->
                                        Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
                                            .also { bitmap ->
                                                drawable.setBounds(0, 0, 144, 144)
                                                drawable.draw(Canvas(bitmap))
                                            }
                                    }
                                AppearanceIcon(id, image)
                            }
                            .toPersistentList()
                    }
                if (!closed) change { it.copy(icons = loadedIcons) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!closed) {
                    loaded = true
                    change { it.copy(loading = false, error = "APPEARANCE_READ_FAILED") }
                    refresh()
                }
            }
        }
    }

    private fun edit(transform: (AppearancePreferences) -> AppearancePreferences) {
        if (!loaded || closed) return
        val value = transform(state.value.preferences)
        change { it.copy(preferences = value, error = null, notice = null) }
        publishAccent()
        write?.cancel()
        write = scope.launch {
            delay(150)
            try {
                persist(value)
                eventChannel.trySend(AppearanceEffect.SynchronizeLaunchTheme)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!closed) change { it.copy(error = "APPEARANCE_SAVE_FAILED") }
            }
        }
    }

    private suspend fun persist(value: AppearancePreferences) =
        withContext(NonCancellable) {
            persistence.withLock {
                store.save(value)
                val current = state.value.preferences
                val keep =
                    setOfNotNull(
                        value.launch?.path,
                        value.launch?.poster,
                        current.launch?.path,
                        current.launch?.poster,
                    )
                val garbage = retiredMedia.filter { it !in keep }
                withContext(Dispatchers.IO) { garbage.forEach { media.discard(it) } }
                retiredMedia.removeAll(garbage.toSet())
            }
        }

    private fun publishAccent() {
        val prefs = state.value.preferences
        val color =
            when (prefs.source) {
                AccentSource.Preset ->
                    appearancePresets[prefs.preset] ?: appearancePresets.getValue("mist")
                AccentSource.Custom -> appearanceColorScheme(prefs, false).primary.toArgb()
                AccentSource.Wallpaper ->
                    state.value.wallpaperAccent ?: appearancePresets.getValue("mist")
            }
        AppearanceStore.file(app).edit().putInt("notification_color", color).apply()
    }

    private fun pinShortcut() {
        val picture = state.value.shortcutPicture ?: return
        if (state.value.busy) return
        val snapshot = state.value
        change { it.copy(busy = true) }
        scope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        shortcut.pin(
                            snapshot.shortcutLabel,
                            picture,
                            snapshot.shortcutZoom,
                            snapshot.shortcutX,
                            snapshot.shortcutY,
                        )
                    }
                change {
                    it.copy(
                        shortcutEditing = !result,
                        shortcutPicture = if (result) null else it.shortcutPicture,
                        notice = if (result) "APPEARANCE_SHORTCUT_REQUESTED" else null,
                        error = if (result) null else "APPEARANCE_SHORTCUT_REFUSED",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                change { it.copy(error = "APPEARANCE_SHORTCUT_REFUSED") }
            } finally {
                change { it.copy(busy = false) }
            }
        }
    }

    private fun change(transform: (AppearanceState) -> AppearanceState) {
        mutable.value = transform(state.value).copy(revision = state.value.revision + 1)
    }

    override fun close() {
        closed = true
        loading?.cancel()
        importing?.cancel()
        write?.cancel()
        if (loaded) {
            val finalPreferences = state.value.preferences
            CoroutineScope(Dispatchers.IO).launch { runCatching { persist(finalPreferences) } }
        }
        shortcut.close()
        eventChannel.close()
    }
}

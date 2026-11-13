package dev.fughetta.dshcompose.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

internal val Context.settingsData by preferencesDataStore(name = "dsh_settings")

internal object SettingsKeys {
    val theme = stringPreferencesKey("theme")
    val dynamicColor = booleanPreferencesKey("dynamicColor")
    val autoReviewConfirmed = booleanPreferencesKey("permission_auto_review_confirmed")
    val foldCompletedTurns = booleanPreferencesKey("foldCompletedTurns")
}

internal class SettingsDataSource(private val store: DataStore<Preferences>) {
    val data = store.data

    suspend fun <T> write(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }
}

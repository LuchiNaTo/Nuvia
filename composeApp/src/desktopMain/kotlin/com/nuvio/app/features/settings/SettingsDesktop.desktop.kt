package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.mdblist_logo
import nuvio.composeapp.generated.resources.rating_tmdb
import nuvio.composeapp.generated.resources.trakt_tv_favicon
import org.jetbrains.compose.resources.painterResource

internal actual object ThemeSettingsStorage {
    private const val preferencesName = "nuvio_theme_settings"
    private const val selectedThemeKey = "selected_theme"
    private const val amoledEnabledKey = "amoled_enabled"
    private const val selectedAppLanguageKey = "selected_app_language"
    private val syncKeys = listOf(selectedThemeKey, amoledEnabledKey, selectedAppLanguageKey)

    actual fun loadSelectedTheme(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(selectedThemeKey))

    actual fun saveSelectedTheme(themeName: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(selectedThemeKey), themeName)
    }

    actual fun loadAmoledEnabled(): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, ProfileScopedKey.of(amoledEnabledKey))

    actual fun saveAmoledEnabled(enabled: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, ProfileScopedKey.of(amoledEnabledKey), enabled)
    }

    actual fun loadSelectedAppLanguage(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(selectedAppLanguageKey))

    actual fun saveSelectedAppLanguage(languageCode: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(selectedAppLanguageKey), languageCode)
    }

    actual fun applySelectedAppLanguage(languageCode: String) = Unit

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadSelectedTheme()?.let { put(selectedThemeKey, encodeSyncString(it)) }
        loadAmoledEnabled()?.let { put(amoledEnabledKey, encodeSyncBoolean(it)) }
        loadSelectedAppLanguage()?.let { put(selectedAppLanguageKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(it)) }

        payload.decodeSyncString(selectedThemeKey)?.let(::saveSelectedTheme)
        payload.decodeSyncBoolean(amoledEnabledKey)?.let(::saveAmoledEnabled)
        payload.decodeSyncString(selectedAppLanguageKey)?.let(::saveSelectedAppLanguage)
    }
}

internal actual fun LazyListScope.pluginsSettingsContent() = Unit

@Composable
internal actual fun integrationLogoPainter(logo: IntegrationLogo): Painter =
    when (logo) {
        IntegrationLogo.Tmdb -> painterResource(Res.drawable.rating_tmdb)
        IntegrationLogo.Trakt -> painterResource(Res.drawable.trakt_tv_favicon)
        IntegrationLogo.MdbList -> painterResource(Res.drawable.mdblist_logo)
    }

package app.droplink.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings as SystemSettings
import app.droplink.core.protocol.ConnectionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val deviceName: String,
    /** SAF tree Uri chosen by the user, or null for the default Download/DropLink folder. */
    val saveTreeUri: String?,
    val autoAcceptTrusted: Boolean,
    val theme: ThemeMode,
    /** Null = automatic mode selection. */
    val forcedMode: ConnectionMode?,
    val setupShown: Boolean,
)

/** Small key-value settings (SharedPreferences exposed as a StateFlow). */
class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Stable random id for this install (not tied to hardware or accounts). */
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_DEVICE_ID, it).apply()
    }

    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun read() = AppSettings(
        deviceName = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultDeviceName(),
        saveTreeUri = prefs.getString(KEY_TREE, null),
        autoAcceptTrusted = prefs.getBoolean(KEY_AUTO_ACCEPT, false),
        theme = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        forcedMode = prefs.getString(KEY_MODE, null)?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() },
        setupShown = prefs.getBoolean(KEY_SETUP_SHOWN, false),
    )

    fun setDeviceName(name: String) = prefs.edit().putString(KEY_NAME, name.trim().take(40)).apply()
    fun setSaveTree(uri: String?) = prefs.edit().putString(KEY_TREE, uri).apply()
    fun setAutoAcceptTrusted(on: Boolean) = prefs.edit().putBoolean(KEY_AUTO_ACCEPT, on).apply()
    fun setTheme(mode: ThemeMode) = prefs.edit().putString(KEY_THEME, mode.name).apply()
    fun setForcedMode(mode: ConnectionMode?) = prefs.edit().putString(KEY_MODE, mode?.name).apply()
    fun markSetupShown() = prefs.edit().putBoolean(KEY_SETUP_SHOWN, true).apply()

    private fun defaultDeviceName(): String {
        val fromSystem = runCatching {
            SystemSettings.Global.getString(appContext.contentResolver, SystemSettings.Global.DEVICE_NAME)
        }.getOrNull()
        if (!fromSystem.isNullOrBlank()) return fromSystem.take(40)
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").take(40)
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_NAME = "device_name"
        const val KEY_TREE = "save_tree"
        const val KEY_AUTO_ACCEPT = "auto_accept_trusted"
        const val KEY_THEME = "theme"
        const val KEY_MODE = "forced_mode"
        const val KEY_SETUP_SHOWN = "setup_shown"
    }
}

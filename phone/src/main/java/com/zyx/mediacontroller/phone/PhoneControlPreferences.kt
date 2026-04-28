package se.kvrgic.mediawristremote.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SkipButtonMode(
    val title: String,
    val description: String,
) {
    SEEK(
        title = "Seek back/ahead",
        description = "Prev rewinds and Next fast-forwards when the media app supports it.",
    ),
    TRACK(
        title = "Previous/next track",
        description = "Prev and Next use the media app's track navigation actions.",
    ),
}

data class PlayedApp(
    val packageName: String,
    val displayName: String,
    val lastSeenAt: Long,
    val skipButtonMode: SkipButtonMode,
    val observedActions: Long = 0L,
)

object PhoneControlPreferences {
    private const val PREFERENCES_NAME = "phone_control_preferences"
    private const val LEGACY_SKIP_BUTTON_MODE_KEY = "skip_button_mode"
    private const val APPS_KEY = "played_apps"
    private const val MODE_PREFIX = "skip_button_mode:"

    fun skipButtonMode(context: Context, packageName: String?): SkipButtonMode {
        if (packageName.isNullOrBlank()) return legacyMode(context)
        val rawValue = context.preferences().getString(MODE_PREFIX + packageName, null)
        return rawValue
            ?.let { value -> SkipButtonMode.entries.firstOrNull { it.name == value } }
            ?: playedApps(context).firstOrNull { it.packageName == packageName }?.skipButtonMode
            ?: legacyMode(context)
    }

    fun setSkipButtonMode(context: Context, packageName: String?, mode: SkipButtonMode) {
        if (packageName.isNullOrBlank()) {
            context.preferences()
                .edit()
                .putString(LEGACY_SKIP_BUTTON_MODE_KEY, mode.name)
                .apply()
            return
        }

        context.preferences()
            .edit()
            .putString(MODE_PREFIX + packageName, mode.name)
            .apply()

        val apps = playedApps(context).map { app ->
            if (app.packageName == packageName) app.copy(skipButtonMode = mode) else app
        }
        savePlayedApps(context, apps)
    }

    fun rememberPlayedApp(
        context: Context,
        packageName: String,
        displayName: String,
        observedActions: Long,
        lastSeenAt: Long = System.currentTimeMillis(),
    ) {
        if (packageName.isBlank()) return
        val mode = skipButtonMode(context, packageName)
        val updated = buildList {
            add(
                PlayedApp(
                    packageName = packageName,
                    displayName = displayName.ifBlank { packageName },
                    lastSeenAt = lastSeenAt,
                    skipButtonMode = mode,
                    observedActions = observedActions,
                ),
            )
            playedApps(context)
                .filterNot { it.packageName == packageName }
                .forEach(::add)
        }
        savePlayedApps(context, updated.take(MAX_PLAYED_APPS))
    }

    fun playedApps(context: Context): List<PlayedApp> {
        val rawValue = context.preferences().getString(APPS_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(rawValue)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val packageName = item.optString("packageName")
                PlayedApp(
                    packageName = packageName,
                    displayName = item.optString("displayName", packageName),
                    lastSeenAt = item.optLong("lastSeenAt"),
                    skipButtonMode = item.optString("skipButtonMode")
                        .let { value -> SkipButtonMode.entries.firstOrNull { it.name == value } }
                        ?: legacyMode(context),
                    observedActions = item.optLong("observedActions"),
                )
            }.filter { it.packageName.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun legacyMode(context: Context): SkipButtonMode {
        val rawValue = context.preferences().getString(LEGACY_SKIP_BUTTON_MODE_KEY, null)
        return rawValue
            ?.let { value -> SkipButtonMode.entries.firstOrNull { it.name == value } }
            ?: SkipButtonMode.SEEK
    }

    private fun savePlayedApps(context: Context, apps: List<PlayedApp>) {
        val array = JSONArray()
        apps.forEach { app ->
            array.put(
                JSONObject()
                    .put("packageName", app.packageName)
                    .put("displayName", app.displayName)
                    .put("lastSeenAt", app.lastSeenAt)
                    .put("skipButtonMode", app.skipButtonMode.name)
                    .put("observedActions", app.observedActions),
            )
        }
        context.preferences()
            .edit()
            .putString(APPS_KEY, array.toString())
            .apply()
    }

    private fun Context.preferences() = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val MAX_PLAYED_APPS = 30
}

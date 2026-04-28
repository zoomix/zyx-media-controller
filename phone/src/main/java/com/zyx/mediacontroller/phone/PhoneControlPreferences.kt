package com.zyx.mediacontroller.phone

import android.content.Context

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

object PhoneControlPreferences {
    private const val PREFERENCES_NAME = "phone_control_preferences"
    private const val SKIP_BUTTON_MODE_KEY = "skip_button_mode"

    fun skipButtonMode(context: Context): SkipButtonMode {
        val rawValue = context.preferences().getString(SKIP_BUTTON_MODE_KEY, null)
        return rawValue
            ?.let { value -> SkipButtonMode.entries.firstOrNull { it.name == value } }
            ?: SkipButtonMode.SEEK
    }

    fun setSkipButtonMode(context: Context, mode: SkipButtonMode) {
        context.preferences()
            .edit()
            .putString(SKIP_BUTTON_MODE_KEY, mode.name)
            .apply()
    }

    private fun Context.preferences() = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

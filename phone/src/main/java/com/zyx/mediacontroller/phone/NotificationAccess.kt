package com.zyx.mediacontroller.phone

import android.content.Context
import androidx.core.app.NotificationManagerCompat

object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }
}

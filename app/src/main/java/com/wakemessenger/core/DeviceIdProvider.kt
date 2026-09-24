package com.wakemessenger.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceIdProvider {
    @SuppressLint("HardwareIds")
    fun androidId(ctx: Context): String =
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: ("${Build.MANUFACTURER}-${Build.MODEL}").replace(' ', '_')
}

package com.wakemessenger.core

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Проверки исключений энергосбережения (п. 8 ТЗ).
 */
data class PowerChecks(
    val batteryOptimizationDisabled: Boolean,
    val backgroundAllowed: Boolean,
    val autostartKnownVendor: Boolean
) {
    /** Автозапуск программно проверить нельзя — учитываем только то, что проверяемо. */
    val allPassed: Boolean get() = batteryOptimizationDisabled && backgroundAllowed
}

object PowerSaveChecker {

    fun check(ctx: Context): PowerChecks = PowerChecks(
        batteryOptimizationDisabled = isIgnoringBatteryOptimizations(ctx),
        backgroundAllowed = isBackgroundAllowed(ctx),
        autostartKnownVendor = autostartIntents(ctx).isNotEmpty()
    )

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun isBackgroundAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return !am.isBackgroundRestricted
    }

    /** Системный диалог «Не оптимизировать батарею». */
    @Suppress("BatteryLife")
    fun openBatterySettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            openAppDetails(ctx); return
        }
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: ActivityNotFoundException) {
                openAppDetails(ctx)
            }
        }
    }

    fun openAppDetails(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Меню автозапуска вендоров (китайские устройства, п. 13 ТЗ).
     * Возвращает только те Intent-ы, которые реально существуют на устройстве.
     */
    fun autostartIntents(ctx: Context): List<Intent> {
        val candidates = listOf(
            // Xiaomi / Redmi / POCO
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Huawei / Honor
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            // Oppo / Realme
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // Vivo
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            // Letv / Meizu / Asus / OnePlus
            ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
            ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
            ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        )
        val pm = ctx.packageManager
        return candidates
            .map { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .filter { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
    }

    fun openAutostartSettings(ctx: Context): Boolean {
        val list = autostartIntents(ctx)
        for (i in list) {
            if (runCatching { ctx.startActivity(i) }.isSuccess) return true
        }
        return false
    }
}

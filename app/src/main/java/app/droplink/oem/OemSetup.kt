package app.droplink.oem

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import app.droplink.R

/** Phone makers whose ROMs kill background work aggressively, with their own settings screens. */
enum class Oem(@StringRes val label: Int, @StringRes val steps: Int) {
    XIAOMI(R.string.oem_xiaomi, R.string.oem_steps_xiaomi),
    HUAWEI(R.string.oem_huawei, R.string.oem_steps_huawei),
    HONOR(R.string.oem_honor, R.string.oem_steps_honor),
    OPPO(R.string.oem_oppo, R.string.oem_steps_oppo),
    VIVO(R.string.oem_vivo, R.string.oem_steps_vivo),
    SAMSUNG(R.string.oem_samsung, R.string.oem_steps_samsung),
    TRANSSION(R.string.oem_transsion, R.string.oem_steps_transsion),
    OTHER(R.string.oem_other, R.string.oem_steps_other),
}

object OemSetup {

    fun detect(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Oem {
        val m = (manufacturer + " " + brand).lowercase()
        return when {
            listOf("xiaomi", "redmi", "poco", "blackshark").any { it in m } -> Oem.XIAOMI
            "honor" in m -> Oem.HONOR
            "huawei" in m -> Oem.HUAWEI
            listOf("oppo", "realme", "oneplus").any { it in m } -> Oem.OPPO
            listOf("vivo", "iqoo").any { it in m } -> Oem.VIVO
            "samsung" in m -> Oem.SAMSUNG
            listOf("tecno", "infinix", "itel", "transsion").any { it in m } -> Oem.TRANSSION
            else -> Oem.OTHER
        }
    }

    /** Known autostart / background-launch managers. Component names differ by ROM version, so try them all. */
    private fun autostartComponents(oem: Oem): List<ComponentName> = when (oem) {
        Oem.XIAOMI -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
        )
        Oem.HUAWEI -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        )
        Oem.HONOR -> listOf(
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        Oem.OPPO -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        Oem.VIVO -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
        Oem.SAMSUNG -> listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Oem.TRANSSION -> listOf(
            ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
            ComponentName("com.itel.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
        )
        Oem.OTHER -> emptyList()
    }

    fun hasAutostartScreen(oem: Oem) = autostartComponents(oem).isNotEmpty()

    /** Opens the ROM's autostart manager; falls back to the app's system settings page. Returns true if an OEM screen opened. */
    fun openAutostart(context: Context, oem: Oem): Boolean {
        for (component in autostartComponents(oem)) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        openAppDetails(context)
        return false
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Asks the system to exempt DropLink from battery optimization (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
        } catch (_: Exception) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { openAppDetails(context) }
        }
    }
}

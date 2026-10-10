package app.xan.music.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.xan.music.constants.AppLogo

/** Keeps the existing default alias stable across app updates. */
fun setInstalledAppIcon(context: Context, logo: AppLogo) {
    val packageManager = context.packageManager
    val aliases = AppLogo.entries.associateWith {
        ComponentName(context.packageName, "app.xan.music.launcher.${it.launcherAlias}")
    }

    fun enabled(option: AppLogo): Boolean =
        when (packageManager.getComponentEnabledSetting(aliases.getValue(option))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> option == AppLogo.WHITE
            else -> false
        }

    if (AppLogo.entries.all { enabled(it) == (it == logo) }) return

    // Enable the selected alias first on Android 12 and earlier. Android 13+
    // switches atomically so exactly one launcher entry remains enabled.
    val changes = listOf(logo) + AppLogo.entries.filter { it != logo }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.setComponentEnabledSettings(
            changes.map { option ->
                PackageManager.ComponentEnabledSetting(
                    aliases.getValue(option),
                    if (option == logo) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            },
        )
    } else {
        changes.forEach { option ->
            packageManager.setComponentEnabledSetting(
                aliases.getValue(option),
                if (option == logo) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

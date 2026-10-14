package app.xan.music

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

internal fun Context.isMainApplicationProcess(): Boolean {
    val processName = if (Build.VERSION.SDK_INT >= 28) {
        Application.getProcessName()
    } else {
        getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName
    }
    return processName == packageName
}

package app.xan.music.platform.updater

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Utility functions for managing downloaded updater APKs.
 */

fun getDownloadedApksDir(context: Context): File {
    return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "xan_updates")
}

fun getUpdateApkFile(context: Context, version: String): File {
    val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
    return File(getDownloadedApksDir(context), "xan-$safeVersion.apk")
}

fun getDownloadedApkCount(context: Context): Int {
    val dir = getDownloadedApksDir(context)
    if (!dir.exists() || !dir.isDirectory) return 0
    return dir.listFiles { file ->
        file.isFile && file.name.endsWith(".apk", ignoreCase = true)
    }?.size ?: 0
}

fun clearDownloadedApks(context: Context): Boolean {
    val dir = getDownloadedApksDir(context)
    if (!dir.exists() || !dir.isDirectory) return true
    var allDeleted = true
    dir.listFiles { file ->
        file.isFile && file.name.endsWith(".apk", ignoreCase = true)
    }?.forEach { file ->
        if (!file.delete()) {
            allDeleted = false
        }
    }
    return allDeleted
}

fun autoClearOldApks(context: Context) {
    val dir = getDownloadedApksDir(context)
    if (!dir.exists() || !dir.isDirectory) return
    val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
    dir.listFiles { file ->
        file.isFile && file.name.endsWith(".apk", ignoreCase = true) && file.lastModified() < oneDayAgo
    }?.forEach { it.delete() }
}

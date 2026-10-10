package app.xan.music.platform.updater

import app.xan.music.platform.github.GitHubRelease
import app.xan.music.platform.github.GitHubReleaseAsset

internal fun compatibleUpdateApk(
    release: GitHubRelease,
    supportedAbis: List<String>,
): GitHubReleaseAsset? {
    fun assetAbi(name: String): String? = when {
        "arm64" in name -> "arm64-v8a"
        "armeabi" in name -> "armeabi-v7a"
        "x86_64" in name -> "x86_64"
        "x86" in name -> "x86"
        else -> null
    }

    return release.assets.asSequence()
        .filter { it.name.endsWith(".apk", ignoreCase = true) }
        .filter { asset ->
            val name = asset.name.lowercase()
            if (listOf("debug", "unsigned", "source").any { it in name }) {
                return@filter false
            }
            val abi = assetAbi(name)
            abi == null || abi in supportedAbis
        }
        .sortedWith(compareBy<GitHubReleaseAsset> { asset ->
            val name = asset.name.lowercase()
            assetAbi(name)?.let(supportedAbis::indexOf)
                ?: if ("universal" in name || name == "xan.apk") {
                    supportedAbis.size
                } else {
                    supportedAbis.size + 1
                }
        }.thenByDescending { asset ->
            if ("gms" in asset.name.lowercase()) 1 else 0
        }.thenBy { it.sizeBytes })
        .firstOrNull()
}

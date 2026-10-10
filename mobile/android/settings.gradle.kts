@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
}

// F-Droid doesn't support foojay-resolver plugin
// plugins {
//     id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
// }

rootProject.name = "xan"

include(":app")

fun includeModule(name: String, path: String) {
    include(":$name")
    project(":$name").projectDir = file(path)
}

// Music and metadata sources
includeModule("innertube", "modules/music-sources/innertube")
includeModule("kugou", "modules/music-sources/kugou")
includeModule("spotify", "modules/music-sources/spotify")

// Lyrics providers
includeModule("lrclib", "modules/lyrics/lrclib")
includeModule("betterlyrics", "modules/lyrics/betterlyrics")
includeModule("simpmusic", "modules/lyrics/simpmusic")
includeModule("youlyplus", "modules/lyrics/youlyplus")
includeModule("paxsenixlyrics", "modules/lyrics/paxsenixlyrics")
includeModule("musixmatchlyrics", "modules/lyrics/musixmatchlyrics")

// External services and integrations
includeModule("lastfm", "modules/services/lastfm")
includeModule("shazamkit", "modules/services/shazamkit")
includeModule("kizzy", "modules/services/kizzy")

// Visual/media modules
includeModule("canvas", "modules/visuals/canvas")
includeModule("artistvideo", "modules/visuals/artistvideo")
includeModule("applecanvas", "modules/visuals/applecanvas")
includeModule("xancanvas", "modules/visuals/xancanvas")

// Experimental modules are not included in the Gradle build by default.

// Use a local copy of NewPipe Extractor by uncommenting the lines below.
// We assume that xan and NewPipe Extractor have the same parent directory.
// If this is not the case, please change the path in includeBuild().
//
// For this to work you also need to change the implementation in
// modules/music-sources/innertube/build.gradle.kts to one which does not
// specify a version.
// From:
//      implementation(libs.newpipe.extractor)
// To:
//      implementation("com.github.teamnewpipe:NewPipeExtractor")
//includeBuild("../NewPipeExtractor") {
//    dependencySubstitution {
//        substitute(module("com.github.teamnewpipe:NewPipeExtractor")).using(project(":extractor"))
//    }
//}

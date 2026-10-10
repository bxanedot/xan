import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.net.URI

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

fun signingValue(key: String): String? =
    localProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }

val listenTogetherProperties = Properties().apply {
    rootProject.file("listen-together.properties").inputStream().use { load(it) }
}

// Prefer an external signing key when a local release builder supplies one.
// This lets the key stay in the workspace's private signing directory instead
// of requiring a keystore copy under the repository.
val releaseKeystore = signingValue("KEYSTORE_PATH")?.let(::file)
    ?: file("keystore/release.keystore")

// The version supplied by the local builder or GitHub Releases is the single
// source of truth for the APK. Supported examples: v0.5.2, v0.1.6.1, v12.34.
val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val defaultReleaseTag = "v${versionProperties.getProperty("XAN_VERSION") ?: error("XAN_VERSION is missing")}" 
val releaseTag = providers.environmentVariable("XAN_RELEASE_TAG").orElse(defaultReleaseTag).get().trim()
val appVersionName = releaseTag.removePrefix("v")

val appVersionCode = if (releaseTag == "dev") {
    1
} else {
    require(releaseTag.matches(Regex("""^v\d+(?:\.\d+){1,3}$"""))) {
        "Invalid XAN_RELEASE_TAG '$releaseTag'. Use 2-4 numeric parts, e.g. v0.5.2 or v0.1.6.1."
    }

    val parts = releaseTag.removePrefix("v").split(".").map { it.toInt() }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    // Permit a new installable revision with the same user-facing version name.
    val build = parts.getOrElse(3) {
        versionProperties.getProperty("XAN_BUILD_NUMBER")?.toIntOrNull() ?: 0
    }

    require(major in 0..2099) { "Major version must be between 0 and 2099." }
    require(minor in 0..99) { "Minor version must be between 0 and 99." }
    require(patch in 0..99) { "Patch version must be between 0 and 99." }
    require(build in 0..99) { "Fourth version component must be between 0 and 99." }

    val calculated = major * 1_000_000 + minor * 10_000 + patch * 100 + build
    require(calculated in 1..2_100_000_000) {
        "Calculated Android versionCode $calculated is outside the supported range."
    }
    val explicitVersionCodeText = versionProperties.getProperty("XAN_VERSION_CODE")
    val explicitVersionCode = explicitVersionCodeText?.toIntOrNull()
    require(explicitVersionCodeText == null || explicitVersionCode != null) {
        "XAN_VERSION_CODE must be a whole number."
    }
    val resolvedVersionCode = explicitVersionCode ?: calculated
    require(resolvedVersionCode in 1..2_100_000_000) {
        "Android versionCode $resolvedVersionCode is outside the supported range."
    }
    // XAN_VERSION_CODE is the monotonically increasing Android release number.
    // Increment it by exactly one for every published version; it intentionally
    // does not encode the semantic version. The calculated value is a fallback
    // for old tags that predate an explicit version code.
    resolvedVersionCode
}

plugins {
    id("com.android.application")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobufPlugin)
}

android {
    namespace = "app.xan.music"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "app.xan.music"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("Boolean", "CAST_AVAILABLE", "true")
        for (provider in listOf("CLOUDFLARE", "HUGGING_FACE")) {
            val key = "LISTEN_TOGETHER_${provider}_URL"
            val endpoint = (System.getenv(key) ?: listenTogetherProperties.getProperty(key, "")).trim().trimEnd('/')
            if (endpoint.isNotEmpty()) {
                val uri = URI(endpoint)
                require(uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                    "$key must be a public wss:// base URL without credentials, query, or fragment."
                }
            }
            buildConfigField("String", key, "\"${endpoint.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // LastFM API keys from local.properties or environment variables
        val lastFmKey = localProperties.getProperty("LASTFM_API_KEY") ?: System.getenv("LASTFM_API_KEY") ?: ""
        val lastFmSecret = localProperties.getProperty("LASTFM_SECRET") ?: System.getenv("LASTFM_SECRET") ?: ""

        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmKey\"")
        buildConfigField("String", "LASTFM_SECRET", "\"$lastFmSecret\"")

    }

    // Produce small per-ABI APKs for direct downloads, plus a universal APK for
    // browsers that do not expose reliable architecture hints and as a fallback.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    // Hot-swappable launcher and install icon assets. In-app branding uses the
    // separate xan_in_app_mark PNG under app/src/main/res/drawable-nodpi.
    // Import the packaged launcher image separately from the hot-swap source
    // folder, which also contains unselected source variants and profile art.
    sourceSets.getByName("main").res.srcDir(
        rootProject.file("branding/hotswap/android/res-packaged")
    )

    // One universal release contains the shared app and Google Cast support.
    // Cast initialization handles devices where Google Play Services is unavailable.

    signingConfigs {
        create("persistentDebug") {
            storeFile = file("persistent-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // Signing material comes from the environment only. Never hardcode a
            // fallback password here: this file is published, the keystore is not,
            // and a leaked release password plus a leaked keystore is an
            // unrecoverable compromise of the app's signing identity.
            // Set KEYSTORE_PATH / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD before a release build.
            // Keep these local values out of source control.
            storeFile = releaseKeystore
            storePassword = signingValue("STORE_PASSWORD")
            keyAlias = signingValue("KEY_ALIAS")
            keyPassword = signingValue("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            isDebuggable = false
            if (releaseKeystore.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = false
        checkDependencies = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            // Compress native libraries in downloaded APKs to reduce their size.
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libandroidx.graphics.path.so",
                "**/libdatastore_shared_counter.so"
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

composeCompiler {
    // :innertube ships no Compose metadata, so its models default to unstable
    // and every YouTube row/tile was unskippable. See compose_stability.conf.
    stabilityConfigurationFiles.add(
        layout.projectDirectory.file("compose_stability.conf")
    )
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn"
        )
        suppressWarnings.set(false)
    }
}

dependencies {
    // Audio-only FFmpeg build: real MP3/AAC/FLAC/WAV/Opus/Vorbis conversion.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9")
    // The fork's published POM omits this runtime dependency used by its error callbacks.
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation(libs.androidx.material3)
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)
    implementation(libs.webkit)
    implementation(libs.innertubex)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)

    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)

    implementation(libs.material3)
    implementation(libs.androidx.adaptive)
    implementation(libs.androidx.adaptive.layout)
    implementation(libs.androidx.adaptive.navigation)
    implementation(libs.palette)
    implementation(libs.materialKolor)

    implementation(libs.appcompat)
    implementation(libs.exifinterface)

    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)

    implementation(libs.ucrop)

    implementation(libs.shimmer)

    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.okhttp)

    // Google Cast is bundled in the single all-features APK. It is only usable when
    // compatible Google Play Services are available on the device.
    implementation(libs.media3.cast)
    implementation(libs.mediarouter)
    implementation(libs.cast.framework)

    implementation(libs.room.runtime)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.tinypinyin)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    implementation(libs.jsoup)
    ksp(libs.hilt.compiler)

    implementation(project(":innertube"))
    implementation(project(":kugou"))
    implementation(project(":lrclib"))
    implementation(project(":kizzy"))
    implementation(project(":lastfm"))
    implementation(project(":betterlyrics"))
    implementation(project(":simpmusic"))
    implementation(project(":youlyplus"))
    implementation(project(":canvas"))
    implementation(project(":shazamkit"))
    implementation(project(":artistvideo"))
    implementation(project(":applecanvas"))
    implementation(project(":xancanvas"))
    implementation(project(":paxsenixlyrics"))
    implementation(project(":musixmatchlyrics"))
    implementation(project(":spotify"))


    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Protobuf for message serialization (lite version for Android)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    coreLibraryDesugaring(libs.desugaring)
    implementation(libs.timber)
    implementation(libs.smoothCorner)
    implementation(libs.lottie.compose)
    implementation(libs.androidx.material.icons.extended.v178)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.core.splashscreen)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutinesGuava.get()}")
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
}

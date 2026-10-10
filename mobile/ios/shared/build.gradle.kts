import java.util.Properties
import java.io.File

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.compose") version "1.12.1"
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("io.coil-kt.coil3:coil-compose:3.6.3")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.6.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("io.ktor:ktor-client-core:3.6.0")
                implementation("io.ktor:ktor-client-websockets:3.6.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.6.0")
        }
        jvmMain {
            dependencies {
                implementation("io.ktor:ktor-client-cio:3.6.0")
            }
        }
        iosMain {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.6.0")
            }
        }
    }

    sourceSets.commonMain.get().kotlin.srcDir(layout.buildDirectory.dir("generated/sharedProtocol/commonMain/kotlin"))

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "XanShared"
            isStatic = true
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "app.xan.shared.resources"
}

val androidLogoSources = linkedMapOf(
    "white" to rootProject.file("../android/app/src/main/res/drawable-nodpi/xan_launcher_white_mark.png"),
    "gold" to rootProject.file("../android/app/src/main/res/drawable-nodpi/xan_gold_mark.png"),
    "horizon" to rootProject.file("../android/branding/hotswap/android/res-packaged/drawable-nodpi/xan_mark.png"),
    "aurora" to rootProject.file("../android/app/src/main/res/drawable-nodpi/xan_logo_aurora_mark.png"),
    "sunset" to rootProject.file("../android/app/src/main/res/drawable-nodpi/xan_logo_sunset_mark.png"),
    "green" to rootProject.file("../android/app/src/main/res/drawable-nodpi/xan_logo_green_mark.png"),
    "prism" to rootProject.file("../android/app/src/main/res/drawable-nodpi/xan_logo_prism_mark.png"),
    "lilac" to rootProject.file("../android/app/src/main/res/drawable-nodpi/xan_logo_lilac_mark.png"),
    "blue" to rootProject.file("../android/app/src/main/res/drawable-nodpi/xan_logo_blue_mark.png"),
)
val iosLogoResourceDir = rootProject.file("shared/src/commonMain/composeResources/drawable")
val iosLogoOutputs = androidLogoSources.keys.map { File(iosLogoResourceDir, "xan_logo_$it.png") } + listOf(
    File(iosLogoResourceDir, "xan_gold.png"),
    File(iosLogoResourceDir, "xan_android.png"),
)
val syncAndroidLogoLibrary = tasks.register("syncAndroidLogoLibrary") {
    group = "branding"
    description = "Copies Android's current AppLogo catalog into iOS Compose resources before compiling."
    inputs.files(androidLogoSources.values)
    outputs.files(iosLogoOutputs)
    doLast {
        androidLogoSources.forEach { (variant, source) ->
            check(source.isFile) { "Android XAN logo asset is missing: $source" }
            source.copyTo(File(iosLogoResourceDir, "xan_logo_$variant.png"), overwrite = true)
        }
        val gold = androidLogoSources.getValue("gold")
        gold.copyTo(File(iosLogoResourceDir, "xan_gold.png"), overwrite = true)
        gold.copyTo(File(iosLogoResourceDir, "xan_android.png"), overwrite = true)
    }
}

val syncSharedListenTogetherProtocol = tasks.register<Copy>("syncSharedListenTogetherProtocol") {
    from(rootProject.file("../android/app/src/main/kotlin/app/xan/music/listentogether/Protocol.kt"))
    into(layout.buildDirectory.dir("generated/sharedProtocol/commonMain/kotlin/app/xan/music/listentogether"))
}

val servicePropertiesFile = rootProject.file("../android/listen-together.properties")
val serviceProperties = Properties().apply { servicePropertiesFile.inputStream().use(::load) }
val androidVersionPropertiesFile = rootProject.file("../android/version.properties")
val androidAlignedVersion = Properties().apply { androidVersionPropertiesFile.inputStream().use(::load) }
    .getProperty("XAN_VERSION") ?: error("XAN_VERSION is missing from mobile/android/version.properties")
val serviceDefaults = listOf("CLOUDFLARE", "HUGGING_FACE").associateWith { provider ->
    val key = "LISTEN_TOGETHER_${provider}_URL"
    providers.environmentVariable(key).orElse(serviceProperties.getProperty(key, "")).get().trim().trimEnd('/')
}
val generatedServicesDir = layout.buildDirectory.dir("generated/serviceDefaults/commonMain/kotlin")
kotlin.sourceSets.commonMain.get().kotlin.srcDir(generatedServicesDir)
val generateIosServiceDefaults = tasks.register("generateIosServiceDefaults") {
    val outputFile = generatedServicesDir.map { it.file("app/xan/shared/IosServiceDefaults.kt") }
    inputs.file(servicePropertiesFile)
    inputs.properties(serviceDefaults)
    outputs.file(outputFile)
    doLast {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText("package app.xan.shared\n\n" + serviceDefaults.entries.joinToString("\n") { (provider, url) ->
            val escaped = url.replace("\\", "\\\\").replace("\"", "\\\"")
            "internal const val XAN_ROOM_${provider}_URL = \"$escaped\""
        } + "\n")
    }
}
val generateIosAppVersion = tasks.register("generateIosAppVersion") {
    val outputFile = generatedServicesDir.map { it.file("app/xan/shared/IosAppVersion.kt") }
    inputs.file(androidVersionPropertiesFile)
    inputs.property("androidAlignedVersion", androidAlignedVersion)
    outputs.file(outputFile)
    doLast {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText("package app.xan.shared\n\ninternal const val XAN_IOS_VERSION = \"$androidAlignedVersion\"\n")
    }
}

tasks.configureEach {
    if ((name.startsWith("compile") && name.contains("Kotlin")) || name.contains("resource", ignoreCase = true)) {
        dependsOn(syncAndroidLogoLibrary, syncSharedListenTogetherProtocol, generateIosServiceDefaults, generateIosAppVersion)
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("xan.liveIosSearch", providers.gradleProperty("xan.liveIosSearch").orElse("false").get())
    systemProperty("xan.liveIosPlayback", providers.gradleProperty("xan.liveIosPlayback").orElse("false").get())
    systemProperty("xan.liveIosLyrics", providers.gradleProperty("xan.liveIosLyrics").orElse("false").get())
    systemProperty("xan.liveYouLyPlus", providers.gradleProperty("xan.liveYouLyPlus").orElse("false").get())
    systemProperty("xan.liveIosPaxsenix", providers.gradleProperty("xan.liveIosPaxsenix").orElse("false").get())
    systemProperty("xan.liveIosKuGou", providers.gradleProperty("xan.liveIosKuGou").orElse("false").get())
    systemProperty("xan.liveIosMusixmatch", providers.gradleProperty("xan.liveIosMusixmatch").orElse("false").get())
    systemProperty("xan.liveIosYouTubeMusicLyrics", providers.gradleProperty("xan.liveIosYouTubeMusicLyrics").orElse("false").get())
}

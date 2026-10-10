import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.testing.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import javax.imageio.ImageIO

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.0"
    id("org.openjfx.javafxplugin") version "0.1.0"
}

javafx {
    version = "21.0.10"
    modules("javafx.media", "javafx.swing")
}

group = "app.xan"
val androidVersion = Properties().apply {
    project.file("../../mobile/android/version.properties").inputStream().use(::load)
}.getProperty("XAN_VERSION") ?: error("XAN_VERSION is missing from mobile/android/version.properties")
val desktopVersion = providers.gradleProperty("xanDesktopVersion")
    .orElse(providers.environmentVariable("XAN_RELEASE_TAG").map { it.trim().removePrefix("v") })
    .orElse(androidVersion).get()
require(desktopVersion == androidVersion) {
    "XAN Desktop version '$desktopVersion' must match Android's XAN_VERSION '$androidVersion'."
}
version = desktopVersion

val generatedVersionDir = layout.buildDirectory.dir("generated/sources/desktopVersion/main/kotlin")
val servicePropertiesFile = project.file("../../mobile/android/listen-together.properties")
val serviceProperties = Properties().apply { servicePropertiesFile.inputStream().use(::load) }
val serviceDefaults = listOf("CLOUDFLARE", "HUGGING_FACE").associateWith { provider ->
    val key = "LISTEN_TOGETHER_${provider}_URL"
    providers.environmentVariable(key).orElse(serviceProperties.getProperty(key, "")).get().trim().trimEnd('/')
}
val generateDesktopServiceDefaults = tasks.register("generateDesktopServiceDefaults") {
    val outputFile = generatedVersionDir.map { it.file("app/xan/desktop/DesktopServiceDefaults.kt") }
    inputs.file(servicePropertiesFile)
    inputs.properties(serviceDefaults)
    outputs.file(outputFile)
    doLast {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText("package app.xan.desktop\n\n" + serviceDefaults.entries.joinToString("\n") { (provider, url) ->
            val escaped = url.replace("\\", "\\\\").replace("\"", "\\\"")
            "internal const val XAN_ROOM_${provider}_URL = \"$escaped\""
        } + "\n")
    }
}
val sharedProtocolDir = layout.buildDirectory.dir("generated/sources/sharedListenTogether/main/kotlin")
val generateDesktopVersion = tasks.register("generateDesktopVersion") {
    val versionFile = generatedVersionDir.map { it.file("app/xan/desktop/DesktopVersion.kt") }
    inputs.property("desktopVersion", project.version.toString())
    outputs.file(versionFile)
    doLast {
        val output = versionFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText("package app.xan.desktop\n\ninternal const val XAN_DESKTOP_VERSION = \"${project.version}\"\n")
    }
}
val syncListenTogetherProtocol = tasks.register<Copy>("syncListenTogetherProtocol") {
    from(project.file("../../mobile/android/app/src/main/kotlin/app/xan/music/listentogether/Protocol.kt"))
    into(sharedProtocolDir.map { it.dir("app/xan/music/listentogether") })
    rename { "Protocol.kt" }
}
val androidLogoSources = linkedMapOf(
    "white" to project.file("../../mobile/android/app/src/main/res/drawable-nodpi/xan_launcher_white_mark.png"),
    "gold" to project.file("../../mobile/android/app/src/main/res/drawable-nodpi/xan_gold_mark.png"),
    "horizon" to project.file("../../mobile/android/branding/hotswap/android/res-packaged/drawable-nodpi/xan_mark.png"),
    "aurora" to project.file("../../mobile/android/app/src/main/res/drawable-nodpi/xan_logo_aurora_mark.png"),
    "sunset" to project.file("../../mobile/android/app/src/main/res/drawable-nodpi/xan_logo_sunset_mark.png"),
    "green" to project.file("../../mobile/android/app/src/main/res/drawable-nodpi/xan_logo_green_mark.png"),
    "prism" to project.file("../../mobile/android/app/src/main/res/drawable-nodpi/xan_logo_prism_mark.png"),
    "lilac" to project.file("../../mobile/android/app/src/main/res/drawable-nodpi/xan_logo_lilac_mark.png"),
    "blue" to project.file("../../mobile/android/app/src/main/res/drawable-nodpi/xan_logo_blue_mark.png"),
)
val androidDefaultAppMark = androidLogoSources.getValue("gold")
val desktopBrandFiles = androidLogoSources.keys.map { project.file("src/main/resources/brand/xan_logo_$it.png") } + listOf(
    project.file("src/main/resources/brand/xan_gold.png"),
    project.file("src/main/resources/brand/xan_android.png"),
    project.file("../windows/branding/xan-icon-pack/desktop-app-icons/icon.png"),
    project.file("../windows/branding/xan-icon-pack/desktop-app-icons/icon.ico"),
    project.file("../macos/branding/xan-icon-pack/desktop-app-icons/icon.png"),
    project.file("../macos/branding/xan-icon-pack/desktop-app-icons/icon.icns"),
    project.file("../linux/branding/xan-icon-pack/desktop-app-icons/icon.png"),
)
val syncAndroidLogoLibrary = tasks.register("syncAndroidLogoLibrary") {
    group = "branding"
    description = "Syncs the desktop logo choices and installer icons from Android's AppLogo assets."
    inputs.files(androidLogoSources.values)
    outputs.files(desktopBrandFiles)
    doLast {
        androidLogoSources.forEach { (variant, source) ->
            check(source.isFile) { "Android XAN logo asset is missing: $source" }
            source.copyTo(project.file("src/main/resources/brand/xan_logo_$variant.png"), overwrite = true)
        }
        androidLogoSources.getValue("gold").copyTo(project.file("src/main/resources/brand/xan_gold.png"), overwrite = true)
        androidDefaultAppMark.copyTo(project.file("src/main/resources/brand/xan_android.png"), overwrite = true)

        val defaultMark = ImageIO.read(androidDefaultAppMark)
            ?: error("Could not read Android's selected default XAN app mark: $androidDefaultAppMark")
        fun png(size: Int): ByteArray {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.color = Color(0x12, 0x12, 0x12)
            graphics.fillRect(0, 0, size, size)
            graphics.drawImage(defaultMark, 0, 0, size, size, null)
            graphics.dispose()
            return ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output)) { "PNG writer is unavailable in this JDK." }
                output.toByteArray()
            }
        }
        fun write(file: java.io.File, bytes: ByteArray) {
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        fun ico(sizes: List<Int>): ByteArray {
            val images = sizes.map { it to png(it) }
            val headerSize = 6 + 16 * images.size
            val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
            header.putShort(0.toShort())
            header.putShort(1.toShort())
            header.putShort(images.size.toShort())
            var offset = headerSize
            images.forEach { (size, data) ->
                header.put(if (size == 256) 0.toByte() else size.toByte())
                header.put(if (size == 256) 0.toByte() else size.toByte())
                header.put(0)
                header.put(0)
                header.putShort(1.toShort())
                header.putShort(32.toShort())
                header.putInt(data.size)
                header.putInt(offset)
                offset += data.size
            }
            return ByteArrayOutputStream().use { output ->
                output.write(header.array())
                images.forEach { (_, data) -> output.write(data) }
                output.toByteArray()
            }
        }
        fun icns(sizes: List<Pair<Int, String>>): ByteArray {
            val chunks = sizes.map { (size, type) -> type to png(size) }
            val totalSize = 8 + chunks.sumOf { (_, data) -> 8 + data.size }
            val output = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            output.put("icns".toByteArray(Charsets.US_ASCII))
            output.putInt(totalSize)
            chunks.forEach { (type, data) ->
                output.put(type.toByteArray(Charsets.US_ASCII))
                output.putInt(8 + data.size)
                output.put(data)
            }
            return output.array()
        }

        val windowsIcons = project.file("../windows/branding/xan-icon-pack/desktop-app-icons")
        val macIcons = project.file("../macos/branding/xan-icon-pack/desktop-app-icons")
        val linuxIcons = project.file("../linux/branding/xan-icon-pack/desktop-app-icons")
        write(windowsIcons.resolve("icon.png"), png(512))
        write(windowsIcons.resolve("icon.ico"), ico(listOf(16, 24, 32, 48, 64, 128, 256)))
        write(macIcons.resolve("icon.png"), png(512))
        write(macIcons.resolve("icon.icns"), icns(listOf(16 to "icp4", 32 to "icp5", 64 to "icp6", 128 to "ic07", 256 to "ic08", 512 to "ic09", 1024 to "ic10")))
        write(linuxIcons.resolve("icon.png"), png(512))
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            kotlin.srcDir(generatedVersionDir)
            kotlin.srcDir(sharedProtocolDir)
            kotlin.srcDir("../../mobile/android/modules/music-sources/innertube/src/main/kotlin")
            kotlin.srcDir("../../mobile/android/modules/music-sources/spotify/src/main/kotlin")
            kotlin.srcDir("../../mobile/android/modules/services/lastfm/src/main/kotlin")
            kotlin.srcDir("../../mobile/android/modules/services/shazamkit/src/main/kotlin")
            kotlin.srcDir("../../mobile/android/modules/lyrics/lrclib/src/main/kotlin")
            kotlin.srcDir("../../mobile/android/modules/lyrics/youlyplus/src/main/kotlin")
            kotlin.srcDir("../../mobile/android/modules/lyrics/betterlyrics/src/main/kotlin")
            kotlin.srcDir("../../mobile/android/modules/lyrics/simpmusic/src/main/kotlin")
        }
    }
}

tasks.named("compileKotlin") { dependsOn(generateDesktopVersion, generateDesktopServiceDefaults, syncListenTogetherProtocol) }
tasks.named("processResources") { dependsOn(syncAndroidLogoLibrary) }
tasks.configureEach {
    if (name.startsWith("package") || name.startsWith("createDistributable")) dependsOn(syncAndroidLogoLibrary)
}

val buildJapanesePackForTests = tasks.register<GradleBuild>("buildJapanesePackForTests") {
    dir = project.file("../features/japanese-romanization")
    tasks = listOf("jar")
}

tasks.withType<Test>().configureEach {
    dependsOn(buildJapanesePackForTests)
    systemProperty("xan.japanesePackJar", project.file("../features/japanese-romanization/build/libs/xan-feature-japanese-romanization.jar").absolutePath)
    listOf("xan.liveSmoke", "xan.liveUpdate", "xan.liveLyricsSmoke", "xan.skipNativeMediaIntegration").forEach { property ->
        providers.gradleProperty(property)
            .orElse(providers.systemProperty(property))
            .orNull
            ?.let { systemProperty(property, it) }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("io.ktor:ktor-client-encoding:3.6.0")
    implementation("org.brotli:dec:0.1.2")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:0.26.5")
    implementation("com.microsoft:credential-secure-storage:1.0.0")
    implementation("com.github.kwhat:jnativehook:2.2.2")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("de.sfuhrm:chromecast-java-api-v2:0.12.20")
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "app.xan.desktop.MainKt"

        nativeDistributions {
            // SQLite loads java.sql.Driver reflectively; jlink cannot infer
            // this module from the JDBC dependency alone.
            // Listen Together uses the JDK HTTP/WebSocket client directly.
            modules("java.sql", "java.net.http")
            targetFormats(
                TargetFormat.Msi,
                TargetFormat.Dmg,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            val appVersionParts = desktopVersion.split('.')
            require(appVersionParts.size >= 3 && appVersionParts.take(3).all { it.toIntOrNull() != null }) {
                "Desktop package version '$desktopVersion' must have at least three numeric parts (major.minor.patch)."
            }
            val nativePackageVersion = "${appVersionParts[0]}.${appVersionParts[1]}.${appVersionParts[2]}"
            // This machine has an installed XAN MSI from the previous 1.9.x
            // packaging scheme. Keep the MSI upgrade version monotonic so
            // Windows Installer upgrades it instead of rejecting 0.9.x as a downgrade.
            val windowsMsiVersion = "${appVersionParts[0].toInt() + 1}.${appVersionParts[1]}.${appVersionParts[2]}"
            // Apple's jpackage bundler rejects app-version values with a zero
            // major component. Keep the desktop app's own version Android-aligned,
            // while giving the macOS bundle a valid native package version.
            val macOsPackageVersion = if (appVersionParts[0].toInt() == 0) {
                "1.${appVersionParts[1]}.${appVersionParts[2]}"
            } else nativePackageVersion
            packageName = "XAN"
            packageVersion = nativePackageVersion
            description = "XAN Desktop — your music, your library, your way."
            vendor = "bxane"
            copyright = "© 2026 bxane — developer, author, and producer of XAN."
            licenseFile.set(project.file("../../LICENSE"))

            windows {
                msiPackageVersion = windowsMsiVersion
                iconFile.set(project.file("../windows/branding/xan-icon-pack/desktop-app-icons/icon.ico"))
                menuGroup = "XAN"
                shortcut = true
                upgradeUuid = "62e1056d-4a2f-45b9-a23d-17612d6a1c69"
                dirChooser = true
                perUserInstall = true
            }
            macOS {
                packageVersion = macOsPackageVersion
                iconFile.set(project.file("../macos/branding/xan-icon-pack/desktop-app-icons/icon.icns"))
                bundleID = "app.xan.desktop"
                dockName = "XAN"
            }
            linux {
                iconFile.set(project.file("../linux/branding/xan-icon-pack/desktop-app-icons/icon.png"))
                menuGroup = "Audio"
            }
        }
    }
}

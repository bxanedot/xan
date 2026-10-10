plugins {
    kotlin("jvm") version "2.4.21"
}

group = "app.xan.desktop.features"
version = "1.0.0"

dependencies {
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
}

tasks.jar {
    archiveFileName.set("xan-feature-japanese-romanization.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    from(configurations.runtimeClasspath.map { files ->
        files.map { dependency -> if (dependency.isDirectory) dependency else zipTree(dependency) }
    })
    manifest.attributes["XAN-Feature-Id"] = "japanese-romanization"
}

kotlin {
    jvmToolchain(21)
}

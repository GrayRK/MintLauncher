import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.0"
}

group = "mint"
version = "0.1.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

compose.desktop {
    application {
        mainClass = "mint.MainKt"
        // В режиме разработки данные лаунчера лежат в ./run
        jvmArgs += listOf("-Dmint.home=${rootDir.resolve("run").absolutePath}")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Mint"
            packageVersion = "1.0.0"
            modules("java.net.http", "jdk.crypto.ec", "jdk.zipfs")
            windows {
                menuGroup = "Mint"
                upgradeUuid = "6a3b0f0e-5d7c-4c61-9d7b-3f5a1d2e9c41"
            }
        }
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.11.1"
    kotlin("android") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
}

android {
    namespace = "org.sand.ticwatch.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.sand.ticwatch.phone"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.google.android.material:material:1.12.0")

    // Talks to the watch-app companion over the Wear OS Data Layer.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Reads sensor/activity data synced from the watch and writes it into Google Health.
    implementation("androidx.health.connect:connect-client:1.2.0-alpha02")

    // Daily purge of the transfer-history database.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // REST client for the backend module.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

/**
 * `installDebug` (from the Android Gradle Plugin) installs onto every adb-visible
 * device, not just one - when the TicWatch is also reachable over adb (wireless
 * debugging) at the same time as the phone, that pushes this phone-only APK onto the
 * watch too. This task picks the one non-Wear OS device out of whatever adb currently
 * sees (via `pm list features` / `android.hardware.type.watch`) and installs only
 * there, so it stays correct regardless of which serial the phone happens to have.
 */
tasks.register("installAppToPhone") {
    group = "install"
    description = "Installs the debug APK only onto the phone, skipping any Wear OS device also visible to adb."
    dependsOn("assembleDebug")
    // Resolved at configuration time: the configuration cache forbids Project access in doLast.
    val apkFile = layout.buildDirectory.file("outputs/apk/debug/phone-app-debug.apk")

    doLast {
        val apk = apkFile.get().asFile
        check(apk.exists()) { "APK not found at ${apk.absolutePath}, did assembleDebug run?" }

        fun adb(vararg args: String): String {
            val process = ProcessBuilder(listOf("adb") + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "adb ${args.joinToString(" ")} failed:\n$output" }
            return output
        }

        val serials = adb("devices").lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == "device") parts[0] else null
            }
            .toList()
        check(serials.isNotEmpty()) { "No adb devices connected" }

        val phoneSerial = serials.firstOrNull { serial ->
            !adb("-s", serial, "shell", "pm", "list", "features").contains("android.hardware.type.watch")
        } ?: error("No non-Wear OS device found among connected adb devices: $serials")

        logger.lifecycle("Installing ${apk.name} onto phone device $phoneSerial")
        logger.lifecycle(adb("-s", phoneSerial, "install", "-r", apk.absolutePath))
    }
}

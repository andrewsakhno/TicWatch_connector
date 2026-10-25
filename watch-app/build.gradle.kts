import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.7.0"
    kotlin("android") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
}

android {
    namespace = "org.sand.ticwatch.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.sand.ticwatch.watch"
        // TicWatch Pro 5 Enduro ships Wear OS 3.5 on Android 13 (API 33); pinning
        // minSdk there avoids API-level shims for PendingIntent.FLAG_MUTABLE etc.
        minSdk = 33
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
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear:wear-tooling-preview:1.0.0")

    // Talks to the phone-app companion over the Wear OS Data Layer.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // On-watch sensor access (heart rate, steps, calories).
    implementation("androidx.health:health-services-client:1.1.0-alpha03")
    // health-services-client's ListenableFuture-returning APIs only pull Guava in on the
    // runtime classpath transitively; without this the compiler can't resolve the type.
    implementation("com.google.guava:guava:31.1-android")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

/**
 * Builds the watch-app debug APK and adb-pushes it to Downloads/ticWatch-connector on
 * the phone - not the watch itself; the watch is frequently unreachable by adb
 * directly (see the root CLAUDE.md's "watch debug bridge" notes), so a build staged
 * there is what WatchAppInstallService picks up to bridge on to the watch over
 * Bluetooth. Reuses the same non-Wear-OS device detection as phone-app's
 * installAppToPhone task, so it still finds the phone correctly if the watch also
 * happens to be adb-visible at the same time.
 */
tasks.register("pushApkToPhoneDownloads") {
    group = "install"
    description = "Builds the watch-app debug APK and adb-pushes it to Downloads/ticWatch-connector on the phone."
    dependsOn("assembleDebug")

    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/watch-app-debug.apk").get().asFile
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

        // Android's public Downloads directory is singular ("Download"), not "Downloads".
        val remoteDir = "/sdcard/Download/ticWatch-connector"
        adb("-s", phoneSerial, "shell", "mkdir", "-p", remoteDir)

        logger.lifecycle("Pushing ${apk.name} to $phoneSerial:$remoteDir/")
        logger.lifecycle(adb("-s", phoneSerial, "push", apk.absolutePath, "$remoteDir/${apk.name}"))
    }
}

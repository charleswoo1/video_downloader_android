import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.charleswoo1.videodownloader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.charleswoo1.videodownloader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    // yt-dlp & FFmpeg runtime (PR #361 + PR #359 pinned reproducible stack)
    implementation(files("libs/common-release.aar"))
    implementation(files("libs/library-release.aar"))
    implementation(files("libs/ffmpeg-release.aar"))
    implementation(libs.commons.io)
    implementation(libs.commons.compress)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.annotations)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register("verifyThreadsPlugin") {
    group = "verification"
    description = "Verifies the SHA256 integrity of the bundled yt-dlp-threads plugin source."
    doLast {
        val pluginFile = file("src/main/assets/yt-dlp-plugins/yt_dlp_plugins/extractor/threads.py")
        val metaFile = file("src/main/assets/yt-dlp-plugins/metadata/yt-dlp-threads-source.txt")

        if (!pluginFile.exists()) {
            throw GradleException("Bundled Threads plugin file missing: ${pluginFile.absolutePath}")
        }
        if (!metaFile.exists()) {
            throw GradleException("Threads plugin metadata file missing: ${metaFile.absolutePath}")
        }

        val md = MessageDigest.getInstance("SHA-256")
        val bytes = pluginFile.readBytes()
        val calculatedHash = md.digest(bytes).joinToString("") { b -> "%02x".format(b) }

        val metaLines = metaFile.readLines()
        val recordedHash = metaLines.firstOrNull { it.startsWith("sha256=") }
            ?.substringAfter("sha256=")?.trim()?.lowercase()

        val expectedPinnedHash = "c28e410b69a0c2377c8530b36f6dca4b973484855b42e281846b97b3305b28ba"

        if (calculatedHash != expectedPinnedHash) {
            throw GradleException(
                "Threads plugin SHA256 mismatch!\n" +
                "Calculated: $calculatedHash\n" +
                "Expected pinned: $expectedPinnedHash"
            )
        }
        if (recordedHash != expectedPinnedHash) {
            throw GradleException(
                "Threads plugin metadata recorded SHA256 mismatch!\n" +
                "Recorded: $recordedHash\n" +
                "Expected pinned: $expectedPinnedHash"
            )
        }
        println("Threads plugin SHA256 verified successfully: $calculatedHash")
    }
}

tasks.named("preBuild").configure {
    dependsOn("verifyThreadsPlugin")
}

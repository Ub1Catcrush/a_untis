import org.jetbrains.kotlin.gradle.dsl.JvmTarget

apply(from = rootProject.file("dependencies.gradle"))

@Suppress("UNCHECKED_CAST")
val appVersions by extra(rootProject.extra["app_versions"] as Map<String, Any>)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
}

// True when built by F-Droid (or locally with -Pfdroid=true).
val isFdroidBuild = project.hasProperty("fdroid")

android {
    namespace = "com.webuntis.dashboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.webuntis.dashboard"
        minSdk = 26
        targetSdk = 36

        val major = (appVersions["versionMajor"] as Number).toInt()
        val minor = (appVersions["versionMinor"] as Number).toInt()
        val patch = (appVersions["versionPatch"] as Number).toInt()

        versionCode = major * 1000000 + minor * 10000 + patch

        // F-Droid builds the app itself and must not ship a self-updater (F-Droid delivers
        // updates). The F-Droid recipe (fdroid/metadata/com.webuntis.dashboard.yml) appends
        // "fdroid=true" to gradle.properties, which turns the in-app GitHub updater off.
        buildConfigField("boolean", "SELF_UPDATE", (!isFdroidBuild).toString())

        // 4. Kotlin-konforme Überprüfung für optionale Properties und korrekte Strings
        versionName = if (project.hasProperty("versionName")) {
            project.property("versionName") as String
        } else {
            "$major.$minor.$patch"
        }
    }

    // One APK per CPU architecture plus one universal APK containing all of them.
    // ABI splits must be OFF when building an AAB: with splits enabled AGP produces several
    // resource files per variant and :app:buildReleasePreBundle fails with
    // "Sequence contains more than one matching element". AABs always contain every ABI anyway
    // (Play splits them on delivery), so splits are only enabled for non-bundle builds.
    // F-Droid gets a single universal APK (one versionCode), so splits are off there too.
    val isBundleBuild = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
    splits {
        abi {
            isEnable = !isBundleBuild && !isFdroidBuild
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Disable unnecessary shrinking in debug for faster builds,
            // but enable dex optimization to reduce bytecode verification lag on emulator
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // F-Droid requires reproducible, blob-free output: no encrypted dependency metadata block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Separate Konfiguration für Kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.datastore)
    implementation(libs.security.crypto)
    implementation(libs.swiperefresh)
    implementation(libs.viewpager2)
    implementation(libs.fragment.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
}

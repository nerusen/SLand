import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// APK files come out as SLand-<buildType>.apk instead of app-<buildType>.apk
base {
    archivesName.set("SLand")
}

android {
    namespace = "io.github.odlaver.tepi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nelsencreative.sland"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "2.3.4"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "REPO", "\"nerusen/SLand\"")

        val cartoKey = providers.gradleProperty("CARTO_KEY").orNull ?: ""
        buildConfigField("String", "CARTO_KEY", "\"$cartoKey\"")
    }

    val storePath = providers.gradleProperty("SLAND_STORE").orNull
    val storePass = providers.gradleProperty("SLAND_STORE_PASSWORD").orNull
    val aliasName = providers.gradleProperty("SLAND_KEY_ALIAS").orNull ?: "sland"
    val aliasPass = providers.gradleProperty("SLAND_KEY_PASSWORD").orNull ?: storePass
    val signed = storePath != null && storePass != null && aliasPass != null
    signingConfigs {
        if (signed) create("sland") {
            storeFile = file(storePath as String)
            storePassword = storePass
            keyAlias = aliasName
            keyPassword = aliasPass
        }
    }

    buildTypes {
        debug {
            if (signed) signingConfig = signingConfigs.getByName("sland")
        }
        release {
            if (signed) signingConfig = signingConfigs.getByName("sland")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}

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
        versionCode = 17
        versionName = "2.7.0"
        // Modern phones only; keeps ML Kit's native code small
        ndk { abiFilters += listOf("arm64-v8a") }
        // Where Check for updates and Source code point; must match the
        // GitHub repo that publishes the releases
        buildConfigField("String", "REPO", "\"nerusen/SLand\"")

        // The CARTO Basemaps key for the Maps tab.
        //
        // From a Gradle property, never from a file in the repo: locally it
        // goes in ~/.gradle/gradle.properties beside the signing passwords,
        // and in CI the workflow passes it from a repository secret. An empty
        // string is a valid build — the Maps tab then says the key is missing
        // and points at the settings page, rather than drawing CARTO's
        // "API KEY REQUIRED" tiles, which is what an absent key actually
        // returns since 25 September 2026.
        //
        // The key is compiled into the APK and anyone can read it back out of
        // the file. That is expected and is why CARTO's dashboard takes a
        // package name and a signing certificate fingerprint: a key restricted
        // to com.nelsencreative.sland and this project's release certificate
        // is useless to anybody who extracts it, because their build cannot
        // present the matching signature. Restricting the key is therefore not
        // optional here — it is the thing that makes shipping it safe.
        val cartoKey = providers.gradleProperty("CARTO_KEY").orNull ?: ""
        buildConfigField("String", "CARTO_KEY", "\"$cartoKey\"")
    }

    // Signing key lives outside the repo: in ~/.gradle/gradle.properties
    // locally, or written by the workflow from repository secrets in CI.
    // Names differ from the signingConfig property names on purpose, so the
    // assignments below cannot resolve to the wrong thing.
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

// On-device translation; QR scanner runs inside Play services
dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}

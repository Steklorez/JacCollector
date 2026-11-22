import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jaccollector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jaccollector"
        minSdk = 21
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
        // Where the public list lives. Override for tests: build.cmd release -PlistUrl=http://...
        val listUrl = (project.findProperty("listUrl") as String?)
            ?: "https://raw.githubusercontent.com/Steklorez/JacCollector/main/sources.json"
        buildConfigField("String", "LIST_URL", "\"$listUrl\"")
    }
    buildFeatures {
        buildConfig = true
    }

    // Release signing comes from keystore.properties (not in git). Without it the release
    // APK is built unsigned.
    val ksFile = rootProject.file("keystore.properties")
    val ks = Properties()
    if (ksFile.exists()) ksFile.inputStream().use { ks.load(it) }
    signingConfigs {
        if (ks.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

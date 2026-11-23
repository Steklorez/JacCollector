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
        // overridable only for update tests: -PvCode=... -PvName=...
        versionCode = (project.findProperty("vCode") as String?)?.toInt() ?: 9
        versionName = (project.findProperty("vName") as String?) ?: "1.1.6"
        // Where the public list lives. Override for tests: build.cmd release -PlistUrl=http://...
        val listUrl = (project.findProperty("listUrl") as String?)
            ?: "https://raw.githubusercontent.com/Steklorez/JacCollector/main/sources.json"
        buildConfigField("String", "LIST_URL", "\"$listUrl\"")
        val ratingsUrl = (project.findProperty("ratingsUrl") as String?)
            ?: "https://raw.githubusercontent.com/Steklorez/JacCollector/stats/ratings.json"
        buildConfigField("String", "RATINGS_URL", "\"$ratingsUrl\"")
        val updateBase = (project.findProperty("updateBase") as String?)
            ?: "https://github.com/Steklorez/JacCollector"
        buildConfigField("String", "UPDATE_BASE", "\"$updateBase\"")
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
        // Test copy: installs next to the release one, with its own (empty) settings.
        debug {
            applicationIdSuffix = ".debug"
        }
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

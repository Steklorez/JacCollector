import java.util.Base64
import java.util.Properties

/** Same as Hidden.open() in the app, backwards: XOR with a rolling key, then Base64. */
fun scramble(s: String): String {
    val b = s.toByteArray(Charsets.UTF_8)
    for (i in b.indices) b[i] = (b[i].toInt() xor ((i * 31 + 0x5A) and 0xFF)).toByte()
    return Base64.getEncoder().encodeToString(b)
}

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
        versionCode = (project.findProperty("vCode") as String?)?.toInt() ?: 11
        versionName = (project.findProperty("vName") as String?) ?: "1.1.8"
        // Where the public list and the rating live. The real addresses are in list.properties
        // (not in git) and go into the APK scrambled, so they are not lying in plain sight.
        // Without that file (forks) the frozen copies in this repository are used.
        // Override for tests: build.cmd release -PlistUrl=http://...
        val lp = Properties()
        rootProject.file("list.properties").takeIf { it.exists() }?.inputStream()?.use { lp.load(it) }
        val listUrl = (project.findProperty("listUrl") as String?) ?: lp.getProperty("listUrl")
            ?: "https://raw.githubusercontent.com/Steklorez/JacCollector/main/sources.json"
        val ratingsUrl = (project.findProperty("ratingsUrl") as String?) ?: lp.getProperty("ratingsUrl")
            ?: "https://raw.githubusercontent.com/Steklorez/JacCollector/stats/ratings.json"
        buildConfigField("String", "LIST_URL", "\"${scramble(listUrl)}\"")
        buildConfigField("String", "RATINGS_URL", "\"${scramble(ratingsUrl)}\"")
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

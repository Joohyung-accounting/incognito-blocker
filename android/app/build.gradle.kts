import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing credentials come from keystore.properties, which is git-ignored.
// See the README for how to generate your own key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null &&
    rootProject.file(keystoreProps.getProperty("storeFile")).exists()

android {
    namespace = "com.incognitoblocker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.incognitoblocker"
        // 26 = Android 8.0, the floor for adaptive icons and notification channels.
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Enable every signing scheme for sideload compatibility.
                // Some OEM package installers reject an APK carrying only v2.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Framework APIs only — zero external dependencies. Small APK, offline builds.
dependencies {
}

// Fail loudly rather than shipping a debug-signed "release".
//
// A debug-signed release APK cannot upgrade a properly signed install (Android
// refuses a signature change), and the debug key's password is public, so
// anyone could forge an update. The check runs when assembleRelease actually
// executes; putting it inside the release { } block would run at configuration
// time and break assembleDebug and IDE sync on machines with no key.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        check(hasReleaseKey) {
            "No release signing key found (keystore.properties / keystore/*.jks). " +
                "A debug-signed release APK cannot upgrade an existing install. " +
                "Use assembleDebug for testing, or see the README to create a key."
        }
    }
}

import java.util.Properties

plugins {
    // NOTE: no Kotlin plugin here on purpose. AGP 9.x compiles Kotlin sources with
    // its *built-in Kotlin* support (enabled by default), and applying
    // `org.jetbrains.kotlin.android` alongside the AGP 9 new DSL is not supported.
    // `kotlin { compilerOptions { ... } }` below still works because AGP applies KGP.
    alias(libs.plugins.android.application)
}

// A *stable* signing key, so that one build can be installed over another.
//
// This matters more than it looks: AGP otherwise mints a throwaway debug
// keystore per machine (and per CI run), so every rebuild carries a different
// signature and Android refuses the upgrade with "App not installed" — the
// operator has to uninstall first, losing the relay config every time.
//
// The key is never committed. Locally it lives in keystore/ (git-ignored); in CI
// it is injected from the SIGNING_KEY_BASE64 secret. When neither is present we
// fall back to the default debug signing so a plain `./gradlew assembleDebug`
// still works for anyone who clones this repo.
val stableKeyFile = rootProject.file("keystore.properties")
val stableKeyProps = Properties()
if (stableKeyFile.exists()) {
    stableKeyFile.inputStream().use { stableKeyProps.load(it) }
}
val stableStorePath: String? = stableKeyProps.getProperty("storeFile")
val stableStorePass: String? = stableKeyProps.getProperty("storePassword")
val stableAlias: String? = stableKeyProps.getProperty("keyAlias")
val stableKeyPass: String? = stableKeyProps.getProperty("keyPassword")
// Resolve the store relative to the repo root, not to app/.
val stableStoreFile = stableStorePath?.let { rootProject.file(it) }
val hasStableKey = stableStoreFile?.exists() == true &&
    stableStorePass != null && stableAlias != null && stableKeyPass != null

android {
    namespace = "net.de5.phoneagent"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.de5.phoneagent"
        minSdk = 33
        targetSdk = 34
        versionCode = (project.findProperty("APP_VERSION_CODE") as String? ?: "2").toInt()
        versionName = project.findProperty("APP_VERSION_NAME") as String? ?: "0.2.0"

        // No native code, no ABI splits: the APK is pure DEX + resources.
    }

    signingConfigs {
        if (hasStableKey) {
            create("stable") {
                storeFile = stableStoreFile
                storePassword = stableStorePass
                keyAlias = stableAlias
                keyPassword = stableKeyPass
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            if (hasStableKey) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            isDebuggable = false
            // Keep shrinking OFF: we have no proguard rules and the APK is tiny
            // anyway. A shrunk build here only adds a way to break reflection.
            isMinifyEnabled = false
            if (hasStableKey) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // We do not ship a launcher icon; a missing icon is a warning we accept.
        disable += "MissingApplicationIcon"
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Zero third-party runtime dependencies (see gradle/libs.versions.toml).

    testImplementation("junit:junit:4.13.2")
}

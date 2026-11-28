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

// Baked-in defaults for this operator's own relay (2026-11-25).
//
// These live in local.properties, which is git-ignored, NOT in this file: the
// repository is public, so a committed secret is a published secret. A clone on
// another machine simply finds no keys here, buildConfigField falls back to "",
// and the app walks the normal "not configured yet" path that asks a human to
// type them in. That is the intended behaviour for anyone but us.
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}
val bakedRelayBase: String = localProps.getProperty("phoneagent.relayBase") ?: ""
val bakedRelaySecret: String = localProps.getProperty("phoneagent.relaySecret") ?: ""
val bakedAgentId: String = localProps.getProperty("phoneagent.agentId") ?: ""
// The VPS Fish TTS wrapper (v0.10.0-pad12): the same "git-ignored defaults" rule
// as the relay keys above. Empty on any machine but the operator's, and an empty
// value is what makes `speak` fall back to the system voice and say why.
val bakedTtsBase: String = localProps.getProperty("phoneagent.ttsBase") ?: ""
val bakedTtsSecret: String = localProps.getProperty("phoneagent.ttsSecret") ?: ""
val bakedTtsVoice: String = localProps.getProperty("phoneagent.ttsVoice") ?: ""

android {
    namespace = "net.de5.phoneagent"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.de5.phoneagent"
        minSdk = 31
        targetSdk = 34
        versionCode = (project.findProperty("APP_VERSION_CODE") as String? ?: "2").toInt()
        versionName = project.findProperty("APP_VERSION_NAME") as String? ?: "0.2.0"

        // See the localProps block above: empty on any machine but the operator's,
        // which is exactly what makes the app ask for them instead of dialling a
        // relay nobody configured.
        buildConfigField("String", "DEFAULT_RELAY_BASE", "\"${bakedRelayBase}\"")
        buildConfigField("String", "DEFAULT_RELAY_SECRET", "\"${bakedRelaySecret}\"")
        buildConfigField("String", "DEFAULT_AGENT_ID", "\"${bakedAgentId}\"")
        buildConfigField("String", "DEFAULT_TTS_BASE", "\"${bakedTtsBase}\"")
        buildConfigField("String", "DEFAULT_TTS_SECRET", "\"${bakedTtsSecret}\"")
        buildConfigField("String", "DEFAULT_TTS_VOICE", "\"${bakedTtsVoice}\"")

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

plugins {
    // NOTE: no Kotlin plugin here on purpose. AGP 9.x compiles Kotlin sources with
    // its *built-in Kotlin* support (enabled by default), and applying
    // `org.jetbrains.kotlin.android` alongside the AGP 9 new DSL is not supported.
    // `kotlin { compilerOptions { ... } }` below still works because AGP applies KGP.
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.de5.phoneagent"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.de5.phoneagent"
        minSdk = 33
        targetSdk = 34
        versionCode = (project.findProperty("APP_VERSION_CODE") as String? ?: "1").toInt()
        versionName = project.findProperty("APP_VERSION_NAME") as String? ?: "0.1.0"

        // No native code, no ABI splits: the APK is pure DEX + resources.
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            // Keep shrinking OFF: we have no proguard rules and the APK is tiny
            // anyway. A shrunk build here only adds a way to break reflection.
            isMinifyEnabled = false
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

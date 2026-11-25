plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
}

// Static analysis (code smells, complexity). `./gradlew detekt` reports.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/java", "src/debug/java", "src/release/java", "src/test/java")
    parallel = true
}

android {
    namespace = "com.exciteddev.flironepro"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.exciteddev.flironepro"
        minSdk = 29
        targetSdk = 37
        // versionCode must grow with every release (Android refuses to update to a lower or equal one).
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release signing. The key is never in the repository: the settings are the Gradle properties flirone.signing.*
    // (storeFile, storePassword, keyAlias, keyPassword) from ~/.gradle/gradle.properties; the release workflow writes the
    // same file from GitHub secrets. Without them the release build stays unsigned, so clones and pull requests still build.
    fun signingProperty(name: String) = providers.gradleProperty("flirone.signing.$name").orNull
    val signingStoreFile = signingProperty("storeFile")
    if (signingStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = signingProperty("storePassword")
                keyAlias = signingProperty("keyAlias")
                keyPassword = signingProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // A different package from the release build, so both can be installed side by side (their signing keys differ).
            applicationIdSuffix = ".debug"
        }
        release {
            if (signingStoreFile != null) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // android.jar in unit tests throws on every call; the tests of the USB and pipeline code only need Log and SystemClock to be harmless.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":radiometry"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    testImplementation(testFixtures(project(":protocol")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

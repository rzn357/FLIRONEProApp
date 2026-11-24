// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
}

// Code style: ktlint, run through Spotless. `./gradlew spotlessCheck` reports, `./gradlew spotlessApply` fixes.
spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        target("*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.kts", "*/*.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The temperature model and the decoding of the thermal image. A plain Kotlin/JVM module that depends only on :protocol (and
// coroutines): the compiler keeps Android, USB and the screen out of it, and :protocol can never depend on it back.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

// Static analysis (code smells, complexity). `./gradlew detekt` reports.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin", "src/test/kotlin")
    parallel = true
}

// The same bytecode level as the app.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    // ThermalImage works on FlirFrame, so :protocol is part of this module's API.
    api(project(":protocol"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(testFixtures(project(":protocol")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

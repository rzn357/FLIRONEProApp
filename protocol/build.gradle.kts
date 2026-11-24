import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The camera's wire format. A plain Kotlin/JVM module: it has no Android SDK on its classpath and no dependency on any other
// module, so the compiler itself keeps USB, Android and the screen out of it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    `java-test-fixtures`
}

// Static analysis (code smells, complexity). `./gradlew detekt` reports.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin", "src/test/kotlin", "src/testFixtures/kotlin")
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
    testImplementation(libs.junit)
}

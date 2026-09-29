// Pure Kotlin/JVM module: agent core (loop, model boundary, tools).
// No Android dependencies, so every test runs as a fast local JVM test.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // org.json is part of the Android runtime. compileOnly keeps it out of the APK,
    // while tests get a real JVM copy.
    compileOnly("org.json:json:20240303")

    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        showExceptions = true
        showCauses = true
    }
}

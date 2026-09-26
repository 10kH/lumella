plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":tutor-contract"))
    implementation(libs.okhttp)
    // Android ships org.json on the platform; on a plain JVM the codec needs a real one. The
    // app gets the platform's at runtime, so this is compile-time only for the library.
    compileOnly("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    // The codec builds real JSON; the Android SDK stub on the unit-test classpath throws.
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnit()
}

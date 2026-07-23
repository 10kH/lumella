plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(project(":luma-adapter"))
    testImplementation(project(":tutor-contract"))
}

tasks.test {
    useJUnitPlatform()
}

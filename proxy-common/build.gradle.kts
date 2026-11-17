plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":core"))
    api(project(":limbo-api"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.jdbc.h2)
}

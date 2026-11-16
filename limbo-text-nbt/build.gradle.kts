plugins {
    `java-library`
    kotlin("jvm")
    id("com.gradleup.shadow")
}
apply(from = rootProject.file("limbo/text-runtime.gradle"))

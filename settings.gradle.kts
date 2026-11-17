rootProject.name = "KyokouAuth"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("core", "limbo", "proxy-common", "platform-velocity", "platform-bungee", "platform-geyser-transfer", "platform-geyser-avatar")

// Stable logical project IDs keep commands/dependencies compatible; all Limbo implementation
// projects live together physically, without extra empty Gradle aggregator projects.
fun includeLimbo(name: String, directory: String) {
    include(name)
    project(":$name").projectDir = file("limbo/$directory")
}
includeLimbo("limbo-api", "api")
includeLimbo("limbo-runtime", "runtime")
(767..777).forEach { includeLimbo("limbo-protocol-$it", "protocols/$it") }
listOf("legacy", "json", "nbt").forEach { includeLimbo("limbo-text-$it", "text/$it") }

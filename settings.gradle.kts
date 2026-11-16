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

include("core", "limbo-api", "limbo", "proxy-common", "platform-velocity", "platform-bungee")
(767..777).forEach { include("limbo-protocol-$it") }
include("limbo-runtime", "limbo-text-legacy", "limbo-text-json", "limbo-text-nbt")

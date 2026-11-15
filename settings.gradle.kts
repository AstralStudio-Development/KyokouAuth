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
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        // PlaceholderAPI. Compile-time only - the runtime plugin is the server owner's business.
        maven("https://repo.extendedclip.com/releases/")
    }
}

include("core", "platform-bukkit", "floodgate-link")

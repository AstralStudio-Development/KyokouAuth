import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "moe.ryuu.kyokouauth"
    version = rootProject.file("VERSION").let { if (it.exists()) it.readText().trim() else "0.1.0-SNAPSHOT" }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        // PlaceholderAPI. Compile-time only - the runtime plugin is the server owner's business.
        // Declared here as well as in settings.gradle.kts because a project that declares its own
        // repositories wins over the settings-level ones (Gradle's default PREFER_PROJECT mode).
        maven("https://repo.extendedclip.com/releases/")
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(25)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xjdk-release=17",
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.register("printVersion") {
    doLast { println(project.version) }
}

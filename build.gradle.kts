import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
}

val java8Target = JavaVersion.VERSION_1_8
val java8Bytecode = "1.8"

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
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                "-Xjdk-release=1.8",
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(8)
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.register("printVersion") {
    doLast { println(project.version) }
}

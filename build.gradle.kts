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
        // `mavenPom()` only: PaperMC publishes with Gradle module metadata whose variants do not
        // match what this project requests, so resolution fails even though the jar is present.
        // Reading the POM is enough for every artifact taken from here - in practice velocity-api,
        // which is Apache-2.0 and therefore does not constrain how this plugin is licensed.
        maven("https://repo.papermc.io/repository/maven-public/") {
            metadataSources {
                mavenPom()
            }
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(25)
        compilerOptions {
            val release = if (project.name == "limbo" || project.name.startsWith("limbo-protocol-") || project.name.startsWith("limbo-text-")) 21 else 17
            jvmTarget.set(if (release == 21) JvmTarget.JVM_21 else JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-Xjdk-release=$release", "-opt-in=kotlin.RequiresOptIn")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(if (project.name == "limbo" || project.name.startsWith("limbo-protocol-") || project.name.startsWith("limbo-text-")) 21 else 17)
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // JUnit @TempDir and library temporary files stay inside the ignored workspace.
        val testTemp = rootProject.layout.projectDirectory.dir(".narrafork/test-tmp/${project.name}").asFile
        systemProperty("java.io.tmpdir", testTemp.absolutePath)
        doFirst { testTemp.mkdirs() }
    }
}

tasks.register("printVersion") {
    doLast { println(project.version) }
}

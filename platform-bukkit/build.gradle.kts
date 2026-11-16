plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core"))

    // Declared here as well as in `core` because `core` depends on it with `implementation`, which
    // keeps it off this module's compile classpath. The identity cache in this module needs the type
    // at compile time; the jar itself is still packed once by the shadow plugin.
    implementation(libs.caffeine)

    compileOnly("io.papermc.paper:paper-api:26.3.build.252-stable")

    // Provided by the server at runtime; the login injection needs the pipeline types at compile time.
    compileOnly(libs.netty.buffer)
    compileOnly(libs.netty.transport)

    // PlaceholderAPI's PlaceholderExpansion is an abstract class, so an expansion must be a real
    // subclass. Compile-time only: the runtime plugin is the server owner's business.
    compileOnly(libs.placeholderapi)

    // The JDBC drivers are deliberately NOT bundled: together they are about 20 MB, most of it for
    // databases this server will never open. They are downloaded on first use instead - see
    // DriverCatalog in the core module.

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)

    // Tests exercise config and message parsing, both of which go through Bukkit's YamlConfiguration.
    // The server itself is never started: these are plain objects loaded from strings and resources.
    testImplementation("io.papermc.paper:paper-api:26.3.build.252-stable")
}

// The Paper API and its Adventure dependencies are compiled for Java 25, while this module's own
// output must stay at Java 17 bytecode. Those two facts are only compatible if the *resolution*
// attribute is Java 25 while the *compiler* target stays 17 - hence the override here rather than
// on the dependency itself, which does not propagate to its transitive graph.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

listOf("compileClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        attributes {
            attribute(
                org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
                25,
            )
        }
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }

    // The Floodgate linking extension travels inside this jar, so installing KyokouAuth is the only
    // deployment step. Shipping it separately would need a second download named to match a Floodgate
    // config value - which is exactly the trap that cost us an afternoon.
    dependsOn(":floodgate-link:jar")
    from(rootProject.layout.projectDirectory.dir("floodgate-link/build/libs")) {
        include("floodgate-global-database.jar")
        into("floodgate")
    }
}

tasks.shadowJar {
    archiveBaseName.set("KyokouAuth")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    mergeServiceFiles()

    relocate("com.github.benmanes.caffeine", "moe.ryuu.kyokouauth.libs.caffeine")
    relocate("com.zaxxer.hikari", "moe.ryuu.kyokouauth.libs.hikari")
    relocate("com.google.gson", "moe.ryuu.kyokouauth.libs.gson")

    // Drivers stay where they are: they register through ServiceLoader under their own package
    // names, and relocating them breaks the JDBC lookup that the connection string relies on.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

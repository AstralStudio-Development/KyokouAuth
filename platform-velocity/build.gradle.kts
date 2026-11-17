plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":proxy-common"))
    compileOnly(project(":limbo"))
    runtimeOnly(project(path = ":limbo", configuration = "isolated"))
    testImplementation(project(":limbo"))

    // Proxy types stay unrelocated. Limbo's protocol library and serializers are separately isolated.
    compileOnly("com.velocitypowered:velocity-api:4.3.1")
    // Velocity owns the channel. Only its provided Netty interfaces cross this native adapter.
    compileOnly("io.netty:netty-transport:4.2.1.Final")
    testImplementation("io.netty:netty-transport:4.2.1.Final")

    // The API is compile-only for the plugin, but the tests compile against the very types they
    // assert on (Adventure components), so it has to be visible there too.
    testImplementation("com.velocitypowered:velocity-api:4.3.1")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("velocity-plugin.json") {
        expand(props)
    }
}

tasks.shadowJar {
    // Eleven isolated stacks can exceed the classic ZIP entry limit.
    isZip64 = true
    archiveBaseName.set("KyokouAuth-Velocity")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    mergeServiceFiles()
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }

    relocate("com.github.benmanes.caffeine", "moe.ryuu.kyokouauth.libs.caffeine")
    relocate("com.zaxxer.hikari", "moe.ryuu.kyokouauth.libs.hikari")
    relocate("com.google.gson", "moe.ryuu.kyokouauth.libs.gson")
    relocate("org.yaml.snakeyaml", "moe.ryuu.kyokouauth.libs.snakeyaml")

    // Drivers register through ServiceLoader under their own package names; relocating them breaks
    // the JDBC lookup the connection string relies on.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

val checkArtifactSize = tasks.register<Exec>("checkArtifactSize") {
    dependsOn(tasks.shadowJar)
    inputs.file(tasks.shadowJar.flatMap { it.archiveFile })
    inputs.file(rootProject.file("tools/audit-limbo-artifact.py"))
    doFirst {
        commandLine("python", rootProject.file("tools/audit-limbo-artifact.py").absolutePath,
            tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    }
}
tasks.shadowJar { finalizedBy(checkArtifactSize) }
tasks.check { dependsOn(checkArtifactSize) }
tasks.build { dependsOn(checkArtifactSize) }

// This adapter's bytecode remains Java 17; dependency resolution must accept the Java 25 Velocity
// API and the Java 21 isolated Limbo implementation. The proxy determines the actual runtime floor.
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

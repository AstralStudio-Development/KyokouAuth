import java.security.MessageDigest

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        content { includeGroup("net.md-5") }
        metadataSources { mavenPom(); artifact() }
    }
    ivy("https://ci.md-5.net/job/BungeeCord/") {
        patternLayout { artifact("[revision]/artifact/bootstrap/target/[artifact].[ext]") }
        metadataSources { artifact() }
        content { includeModule("official.bungee", "BungeeCord") }
    }
}

val bungeeDistribution by configurations.creating { isTransitive = false }
val extractBungee2111 by tasks.registering(Sync::class) {
    from({ bungeeDistribution.map { zipTree(it) } }) {
        include("META-INF/libraries/*.jar")
        eachFile { relativePath = RelativePath(true, name) }
        includeEmptyDirs = false
    }
    into(rootProject.layout.projectDirectory.dir(".narrafork/bungee-e2e/deps/libraries"))
    doFirst {
        val distribution = bungeeDistribution.singleFile
        val digest = MessageDigest.getInstance("SHA-256").digest(distribution.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == "1d9dbc31b57b22a37553f160ac1b61762717e9e4a6a1341f3dd27a1f6f2671fd") {
            "Official Bungee 2111 distribution checksum differs"
        }
    }
}
val nativeLibraries = files(fileTree(rootProject.file(".narrafork/bungee-e2e/deps/libraries")) {
    include("*.jar")
    exclude("bungeecord-api-*.jar")
}).builtBy(extractBungee2111)

val bungeeApi = "net.md-5:bungeecord-api:26.1-R0.1-20261105.104341-62"

dependencies {
    implementation(project(":proxy-common"))
    compileOnly(project(":limbo"))
    runtimeOnly(project(path = ":limbo", configuration = "isolated"))
    testImplementation(project(":limbo")) { isTransitive = false }
    bungeeDistribution("official.bungee:BungeeCord:2111@jar")
    // The API POM references floating companion snapshots: use exact 2111 companions instead.
    compileOnly(bungeeApi) { isTransitive = false }
    compileOnly(nativeLibraries)
    testImplementation(bungeeApi) { isTransitive = false }
    testImplementation(nativeLibraries)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Unit tests exercise native event/profile boundaries, not transport Engines. E2E uses shadowJar.
configurations.named("testRuntimeOnly") { setExtendsFrom(emptyList()) }

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("bungee.yml") { expand(props) }
}

tasks.shadowJar {
    isZip64 = true
    archiveBaseName.set("KyokouAuth-Bungee")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    relocate("com.github.benmanes.caffeine", "moe.ryuu.kyokouauth.libs.caffeine")
    relocate("com.zaxxer.hikari", "moe.ryuu.kyokouauth.libs.hikari")
    relocate("com.google.gson", "moe.ryuu.kyokouauth.libs.gson")
    relocate("org.yaml.snakeyaml", "moe.ryuu.kyokouauth.libs.snakeyaml")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

val checkArtifactSize by tasks.registering(Exec::class) {
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

// Adapter bytecode remains Java 17. Embedded Engines/proxy impose the real runtime floor.
java { sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
listOf("compileClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        attributes { attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
    }
}

import java.security.MessageDigest

plugins {
    `java-library`
    kotlin("jvm")
    id("com.gradleup.shadow")
}

repositories {
    maven("https://dl.google.com/dl/android/maven2/") { content { includeGroup("com.android.tools") } }
}

val prototype = providers.gradleProperty("limbo.prototype").orNull
val selectedProtocols = prototype?.split(',')?.map { it.trim().toInt() }?.distinct()?.sorted() ?: (767..777).toList()
require(selectedProtocols.isNotEmpty() && selectedProtocols.all { it in 767..777 })
val r8Tool = configurations.create("r8Tool") { isCanBeConsumed = false; isTransitive = false }
val shrinkerLibraries = configurations.create("shrinkerLibraries") { isCanBeConsumed = false }

// These are build-time model classes, never runtime dependencies of the resulting plugin.
shrinkerLibraries.exclude(group = "io.netty", module = "netty-transport-native-epoll")
shrinkerLibraries.exclude(group = "io.netty", module = "netty-transport-native-kqueue")
shrinkerLibraries.exclude(group = "io.netty", module = "netty-transport-native-io_uring")
shrinkerLibraries.exclude(group = "io.netty", module = "netty-codec-native-quic")

dependencies {
    api(project(":limbo-api"))
    selectedProtocols.forEach { protocol ->
        runtimeOnly(project(mapOf("path" to ":limbo-protocol-$protocol", "configuration" to "isolated")))
    }
    listOf("limbo-runtime", "limbo-text-legacy", "limbo-text-json", "limbo-text-nbt").forEach {
        runtimeOnly(project(mapOf("path" to ":$it", "configuration" to "isolated")))
    }
    add(r8Tool.name, "com.android.tools:r8:9.5.32")
    add(shrinkerLibraries.name, "io.netty:netty-all:4.2.1.Final")
    add(shrinkerLibraries.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    add(shrinkerLibraries.name, "org.jspecify:jspecify:1.0.0")
    add(shrinkerLibraries.name, "org.checkerframework:checker-qual:3.42.0")
    add(shrinkerLibraries.name, "com.google.errorprone:error_prone_annotations:2.27.0")
    add(shrinkerLibraries.name, "com.google.auto.service:auto-service-annotations:1.1.1")
    add(shrinkerLibraries.name, "com.google.code.findbugs:jsr305:3.0.2")
    add(shrinkerLibraries.name, "org.jetbrains:annotations:26.1.0")
    add(shrinkerLibraries.name, "org.projectlombok:lombok:1.18.36")
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }

// This is only a diagnostic intermediate. Platform artifacts consume shrinkLimbo instead.
tasks.shadowJar {
    archiveClassifier.set(if (prototype == null) "unshrunk" else "prototype-unshrunk")
    isZip64 = true
    mergeServiceFiles()
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    dependencies { exclude(dependency("org.jetbrains.kotlin:.*:.*")) }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
}

val slimJar = layout.buildDirectory.file("libs/${project.name}-${project.version}-${if (prototype == null) "isolated" else "prototype-isolated"}.jar")
val generatedRules = selectedProtocols.map {
    project(":limbo-protocol-$it").layout.buildDirectory.file("generated/limbo-codec/keep-rules.pro")
}
val javaLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

// A read-only preparation command, used once when deliberately pinning/updating the build tool.
tasks.register("inspectShrinkerTool") {
    doLast {
        val digest = MessageDigest.getInstance("SHA-256").digest(r8Tool.singleFile.readBytes()).joinToString("") { "%02x".format(it) }
        logger.lifecycle("R8 version=9.5.32 sha256=$digest path=${r8Tool.singleFile}")
    }
}

val shrinkLimbo = tasks.register<Exec>("shrinkLimbo") {
    dependsOn(tasks.shadowJar)
    inputs.file(tasks.shadowJar.flatMap { it.archiveFile })
    inputs.files(r8Tool, shrinkerLibraries)
    inputs.files(generatedRules)
    inputs.file(rootProject.file("limbo/shrink-rules.pro"))
    inputs.file(rootProject.file("tools/limbo-exporter-keep.pro"))
    inputs.file(rootProject.file("limbo/r8.sha256"))
    inputs.file(rootProject.file("tools/shrink-limbo.py"))
    outputs.file(slimJar)
    doFirst {
        val arguments = mutableListOf("python", rootProject.file("tools/shrink-limbo.py").absolutePath,
            "--input", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
            "--output", slimJar.get().asFile.absolutePath,
            "--r8", r8Tool.singleFile.absolutePath,
            "--r8-sha256", rootProject.file("limbo/r8.sha256").readText().trim(),
            "--java-home", javaLauncher.get().metadata.installationPath.asFile.absolutePath,
            "--rules", rootProject.file("limbo/shrink-rules.pro").absolutePath)
        arguments.addAll(listOf("--rules", rootProject.file("tools/limbo-exporter-keep.pro").absolutePath))
        generatedRules.forEach { arguments.addAll(listOf("--rules", it.get().asFile.absolutePath)) }
        shrinkerLibraries.files.forEach { arguments.addAll(listOf("--library", it.absolutePath)) }
        val kotlinRuntime = shrinkerLibraries.files.single { it.name == "kotlin-stdlib-2.4.21.jar" }
        arguments.addAll(listOf("--bundle-library", kotlinRuntime.absolutePath))
        commandLine(arguments)
    }
}

val isolated = configurations.create("isolated") { isCanBeConsumed = true; isCanBeResolved = false }
artifacts { add(isolated.name, slimJar) { builtBy(shrinkLimbo) } }
tasks.build { dependsOn(shrinkLimbo) }

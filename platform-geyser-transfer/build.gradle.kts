import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { java }

repositories {
    ivy("https://download.geysermc.org/v2/projects/geyser/versions/2.12.1/builds/") {
        patternLayout { artifact("[revision]/downloads/standalone") }
        metadataSources { artifact() }
        content { includeModule("official.geyser", "Geyser-Standalone") }
    }
}

// The exact official distribution supplies its public API only at compile/test time. No SDK is
// copied into the extension jar. The checksum also applies to the optional existing local fixture.
val geyserDistribution = configurations.create("geyserDistribution") { isTransitive = false }
val verifyGeyser1303 = tasks.register("verifyGeyser1303") {
    inputs.files(geyserDistribution)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256").digest(geyserDistribution.singleFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == "1b0c239e97117853cc4618753e23a0e06c7dc351c81abdd46926012b9fce6710") {
            "Official Geyser 2.12.1-b1303 checksum differs"
        }
    }
}
val geyserApi = files(geyserDistribution).builtBy(verifyGeyser1303)

dependencies {
    val fixture = rootProject.file(".narrafork/floodgate-e2e/deps/Geyser-Standalone-2.12.1-b1303.jar")
    if (fixture.isFile) add(geyserDistribution.name, files(fixture))
    else add(geyserDistribution.name, "official.geyser:Geyser-Standalone:1303@jar")
    compileOnly(geyserApi)
    testImplementation(geyserApi)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Pure Java source: the ordinary jar task includes only extension classes/resources, not stdlib
// or a shaded Geyser/Floodgate/protocol stack. The root applies Kotlin to all projects.
tasks.jar {
    archiveBaseName.set("KyokouAuth-GeyserTransfer")
    doLast {
        ZipFile(archiveFile.get().asFile).use { jar ->
            check(jar.entries().asSequence().none {
                it.name.startsWith("org/geysermc/") || it.name.startsWith("kotlin/") || it.name.startsWith("io/netty/")
            }) { "Geyser extension must not bundle its provided SDK" }
        }
        check(archiveFile.get().asFile.length() <= 128 * 1024) { "Geyser transfer extension exceeded 128KiB" }
    }
}
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("extension.yml") { expand("version" to project.version.toString()) }
}

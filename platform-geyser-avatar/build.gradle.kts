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

val geyserDistribution = configurations.create("geyserDistribution") { isTransitive = false }
val verifyGeyser1303 = tasks.register("verifyGeyser1303") {
    inputs.files(geyserDistribution)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        geyserDistribution.singleFile.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } ==
            "1b0c239e97117853cc4618753e23a0e06c7dc351c81abdd46926012b9fce6710") {
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
    testImplementation(project(":proxy-common"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.jar {
    archiveBaseName.set("KyokouAuth-GeyserAvatar")
    doLast {
        ZipFile(archiveFile.get().asFile).use { jar ->
            check(jar.entries().asSequence().none {
                it.name.startsWith("org/geysermc/") || it.name.startsWith("kotlin/") ||
                    it.name.startsWith("io/netty/") || it.name.startsWith("com/google/")
            }) { "Avatar extension must not bundle its provided SDK or dependency stacks" }
        }
        check(archiveFile.get().asFile.length() <= 128 * 1024) { "Avatar extension exceeded 128KiB" }
    }
}
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("extension.yml") { expand("version" to project.version.toString()) }
}

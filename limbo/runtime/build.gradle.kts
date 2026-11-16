plugins {
    `java-library`
    kotlin("jvm")
    id("com.gradleup.shadow")
}

repositories {
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    api("org.cloudburstmc:nbt:3.1.0.Final")
    api("org.cloudburstmc.math:api:2.0")
    api("org.cloudburstmc.math:immutable:2.0")
    listOf("fastutil-object-int-maps", "fastutil-int-object-maps", "fastutil-int-int-maps").forEach {
        api("com.nukkitx.fastutil:$it:8.5.3")
    }
    api("com.google.code.gson:gson:2.11.0")
    api("org.slf4j:slf4j-api:2.0.9")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.9")
}

tasks.shadowJar {
    archiveClassifier.set("isolated")
    mergeServiceFiles()
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    listOf("org.cloudburstmc", "it.unimi.dsi.fastutil", "com.nukkitx", "com.google.gson", "org.slf4j").forEach {
        relocate(it, "moe.ryuu.kyokouauth.limbo.shared.$it")
    }
    dependencies { exclude(dependency("org.jetbrains.kotlin:.*:.*")) }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
}
val isolated by configurations.creating { isCanBeConsumed = true; isCanBeResolved = false }
artifacts { add(isolated.name, tasks.shadowJar) }

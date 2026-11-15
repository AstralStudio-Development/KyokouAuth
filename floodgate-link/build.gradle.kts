plugins {
    java
}

/**
 * A Floodgate linking database that answers from GeyserMC's global linking service.
 *
 * Floodgate ships the loader for these (`^floodgate-[a-zA-Z0-9_]{0,16}-database.jar$` in its own
 * data directory) and loads `init.json` to find the entry point. The compile dependency is the
 * server's own floodgate.jar rather than a Maven artifact on purpose: that artifact is published
 * only as a SNAPSHOT whose class layout moves between builds, and this class has to match the
 * exact `CommonPlayerLink` the server runs.
 */
dependencies {
    compileOnly(files(rootProject.file("tools/floodgate.jar")))
}

java {
    // The host plugin targets Java 8 bytecode; matching it keeps this loadable everywhere it is.
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.jar {
    // Floodgate does not accept any name matching `floodgate-*-database.jar`: it looks for
    // `floodgate-<player-link.type>-database.jar`, so the name must equal the configured type.
    // A mismatch is silent - the plugin simply reports "Failed to find an implementation".
    archiveFileName.set("floodgate-global-database.jar")
}

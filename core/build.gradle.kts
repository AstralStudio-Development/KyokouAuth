plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.caffeine)
    implementation(libs.hikaricp)
    implementation(libs.gson)
    implementation(libs.snakeyaml)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // The repository tests run against real database files rather than a mock, so the drivers have
    // to be present on the test runtime classpath.
    testRuntimeOnly(libs.jdbc.sqlite)
    testRuntimeOnly(libs.jdbc.h2)
    // Server-backed dialects. Their integration test skips when no server is configured.
    testRuntimeOnly(libs.jdbc.mysql)
    testRuntimeOnly(libs.jdbc.mariadb)
    testRuntimeOnly(libs.jdbc.postgresql)
}

tasks.jar {
    archiveBaseName.set("kyokouauth-core")
}

tasks.test {
    // Live identity endpoints are never contacted by ordinary builds.
    systemProperty("kyokou.test.live", providers.systemProperty("kyokou.test.live").getOrElse("false"))
    // Forward DB integration settings to the forked test JVM. Without this, `-Dkyokou.test.*` set on
    // the Gradle command line would be invisible to the tests.
    listOf("postgresql", "mysql", "mariadb", "user", "password").forEach { name ->
        System.getProperty("kyokou.test.$name")?.let { systemProperty("kyokou.test.$name", it) }
    }
}

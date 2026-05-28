plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

group = "org.webservices"
version = "1.0.0"

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.logging)

    testImplementation(libs.bundles.testing)
    testImplementation("io.ktor:ktor-server-test-host:3.0.2")
}

application {
    mainClass.set("org.webservices.progression.MainKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType<Jar> {
    archiveBaseName.set("progression")
}

tasks.test {
    useJUnitPlatform()
}

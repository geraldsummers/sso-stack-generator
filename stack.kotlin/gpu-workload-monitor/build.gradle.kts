plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
    id("com.gradleup.shadow")
}

group = "org.webservices"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":pipeline-common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor.server)
    implementation(libs.okhttp)
    implementation(libs.bundles.logging)
    implementation(libs.postgres.jdbc)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    testImplementation(libs.bundles.testing)
}

application {
    mainClass.set("org.webservices.gpuworkloadmonitor.MainKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}

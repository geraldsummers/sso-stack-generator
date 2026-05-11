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
    implementation(libs.bundles.ktor.client)
    implementation(libs.bundles.logging)
    implementation("com.charleskorn.kaml:kaml:0.55.0")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation(libs.bundles.testing)
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.0.21")
    testImplementation("io.ktor:ktor-server-test-host:3.0.2")
}

application {
    mainClass.set("org.webservices.testmanager.MainKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}

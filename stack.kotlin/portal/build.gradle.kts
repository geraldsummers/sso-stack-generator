plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)
    implementation(libs.bundles.logging)

    testImplementation(libs.bundles.testing)
    testImplementation("io.ktor:ktor-server-test-host:3.0.2")
    testImplementation("io.ktor:ktor-client-mock:3.0.2")
}

application {
    mainClass.set("org.webservices.portal.MainKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType<Jar> {
    archiveBaseName.set("portal")
}


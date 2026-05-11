import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":pipeline-common"))

    
    
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    
    implementation(libs.bundles.ktor.server)


    implementation(libs.qdrant.client) {
        exclude(group = "io.grpc")
    }
    implementation(libs.protobuf.java)
    implementation("io.grpc:grpc-core:1.63.0")
    implementation("io.grpc:grpc-stub:1.63.0")
    implementation("io.grpc:grpc-protobuf:1.63.0")
    implementation("io.grpc:grpc-netty:1.63.0")
    implementation("io.grpc:grpc-services:1.63.0")

    implementation(libs.guava)

    
    implementation(libs.postgres.jdbc)

    
    implementation("com.zaxxer:HikariCP:5.1.0")

    
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    
    implementation(libs.okhttp)

    
    implementation(libs.gson)

    
    implementation(libs.bundles.logging)

    
    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.testcontainers)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
    mainClass.set("org.webservices.searchservice.MainKt")
}


tasks.shadowJar {
    mergeServiceFiles()
    transform(ServiceFileTransformer::class.java)
}

tasks.withType(Jar::class) {
    archiveBaseName.set("search-service")
}

tasks.test {

}

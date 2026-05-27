plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly("org.keycloak:keycloak-server-spi:26.6.2")
    compileOnly("org.keycloak:keycloak-server-spi-private:26.6.2")
    compileOnly("org.keycloak:keycloak-services:26.6.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveBaseName.set("keycloak-onboarding-listener")
}

tasks.shadowJar {
    archiveBaseName.set("keycloak-onboarding-listener")
}

package org.webservices.testrunner.framework

fun isolatedDockerHostFromEnv(): String {
    val explicitHost = System.getenv("ISOLATED_DOCKER_VM_DOCKER_HOST")
        ?: System.getenv("ISOLATED_DOCKER_HOST")
        ?: System.getenv("DOCKER_HOST_ISOLATED")

    if (!explicitHost.isNullOrBlank()) {
        return explicitHost
    }

    val dockerHost = System.getenv("DOCKER_HOST")
    val lowerDockerHost = dockerHost.orEmpty().lowercase()
    val hostProxy = lowerDockerHost.contains("docker-socket-proxy") ||
        lowerDockerHost.contains("docker-socket-controller-proxy") ||
        lowerDockerHost.contains("docker-proxy:2375")
    if (!dockerHost.isNullOrBlank() && !hostProxy) {
        return dockerHost
    }

    return "tcp://docker-vm-controller-proxy:2375"
}

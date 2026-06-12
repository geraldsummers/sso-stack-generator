rootProject.name = "webservices"

include(":pipeline-common")
include(":gpu-bootstrap-arbiter")
include(":gpu-workload-monitor")
include(":inference-gateway")
include(":inference-controller")
include(":test-manager")
include(":test-runner")
include(":workspace-provisioner")
include(":keycloak-onboarding-listener")
include(":chatgpt-connector")
include(":progression")
include(":portal")

project(":pipeline-common").projectDir = file("stack.kotlin/pipeline-common")
project(":gpu-bootstrap-arbiter").projectDir = file("stack.kotlin/gpu-bootstrap-arbiter")
project(":gpu-workload-monitor").projectDir = file("stack.kotlin/gpu-workload-monitor")
project(":inference-gateway").projectDir = file("stack.kotlin/inference-gateway")
project(":inference-controller").projectDir = file("stack.kotlin/inference-controller")
project(":test-manager").projectDir = file("stack.kotlin/test-manager")
project(":test-runner").projectDir = file("stack.kotlin/test-runner")
project(":workspace-provisioner").projectDir = file("stack.kotlin/workspace-provisioner")
project(":keycloak-onboarding-listener").projectDir = file("stack.kotlin/keycloak-onboarding-listener")
project(":chatgpt-connector").projectDir = file("stack.kotlin/chatgpt-connector")
project(":progression").projectDir = file("stack.kotlin/progression")
project(":portal").projectDir = file("stack.kotlin/portal")

file("out/external-modules/materialized/stack.kotlin")
    .takeIf { it.isDirectory }
    ?.listFiles()
    ?.filter { File(it, "build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { moduleDir ->
        val projectName = moduleDir.name
        include(":$projectName")
        project(":$projectName").projectDir = moduleDir
    }

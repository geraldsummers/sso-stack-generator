package org.webservices.testrunner.suites

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webservices.testrunner.framework.DockerCli
import org.webservices.testrunner.framework.TestContext
import org.webservices.testrunner.framework.TestRunner
import org.webservices.testrunner.framework.TestUser
import org.webservices.testrunner.framework.applyInternalApiAuthHeaders
import org.webservices.testrunner.framework.isolatedDockerHostFromEnv
import java.io.File
import java.util.UUID

const val AGENT_ENV_SUITE_NAME = "agent-env"
const val AGENT_EXPAND_SUITE_NAME = "agent-expand"
const val AGENT_FIXTURES_SUITE_NAME = "agent-fixtures"
const val AGENT_RUNTIME_SUITE_NAME = "agent-runtime"
const val AGENT_LAB_SUITE_NAME = "agent-lab"

private const val WORKSPACE_PROVISIONER_URL = "http://workspace-provisioner:8120"
private const val AGENT_WORKSPACE_IMAGE_TAG = "webservices/agent-workspace:workspace-build"
private const val AGENT_WORKSPACE_CONTEXT = "/app/stack.containers/agent-workspace"
private const val AGENT_BUILD_FIXTURE_CONTEXT = "/app/stack.containers/test-runner/fixtures/agent-build-demo"
private const val AGENT_LANG_FIXTURE_CONTEXT = "/app/stack.containers/test-runner/fixtures/agent-lang"
private const val AIDER_RUNTIME_FIXTURE_CONTEXT = "/app/stack.containers/test-runner/fixtures/aider-runtime"
private const val AGENT_WORKSPACE_HOME = "/home/agent"
private const val AGENT_FIXTURE_REPOSITORIES = "$AGENT_WORKSPACE_HOME/repositories/agent-fixtures"
private const val DIRECT_CPU_MODEL = "webservices-qwen2.5-coder-14b-cpu"
private const val DIRECT_GPU_MODEL = "webservices-qwen2.5-coder-14b-gpu"
private const val DIRECT_RUNTIME_ROOT_ENV = "TEST_RUNNER_RUNTIME_ROOT"
private const val DIRECT_RUNTIME_ROOT_DEFAULT = "/runtime"
private const val WORKSPACE_PROVISIONER_REQUEST_ATTEMPTS = 8
private const val WORKSPACE_PROVISIONER_REQUEST_DELAY_MS = 2000L

private val suiteJson = Json { ignoreUnknownKeys = true }
private val BASE_AGENT_PROFILES = listOf("shell", "js-ts", "python", "go", "jvm", "docker", "sql", "c-cpp")
private val OPTIONAL_AGENT_PROFILES = listOf("rust", "dotnet", "php", "ruby", "web-static")
private val BASE_AGENT_FIXTURES = listOf("shell", "js-ts", "python", "go", "java", "kotlin", "sql", "c", "cpp")
private val OPTIONAL_AGENT_FIXTURES = mapOf(
    "rust" to "rust",
    "dotnet" to "dotnet",
    "php" to "php",
    "ruby" to "ruby",
    "web-static" to "web-static"
)

private data class OwnedAgentIdentity(
    val owner: TestUser,
    val agent: TestUser
)

private data class DirectAiderProvider(
    val label: String,
    val baseUrl: String,
    val model: String,
    val backendServiceName: String,
    val residencyMode: String
)

@Serializable
private data class CreateWorkspaceRequestJson(
    val displayName: String,
    val initialDelegate: String? = null
)

@Serializable
private data class NotebookSessionJson(
    val status: String,
    val url: String,
    val basePath: String,
    val port: Int? = null,
    val lastError: String? = null
)

@Serializable
private data class WorkspaceAgentAccessJson(
    val controllerUrl: String,
    val workspaceId: String,
    val searchPath: String,
    val documentPathPrefix: String,
    val tokenExpiresAt: String,
    val scopes: List<String>
)

@Serializable
private data class WorkspaceSummaryJson(
    val id: String,
    val displayName: String,
    val ownerUsername: String,
    val status: String,
    val sshHost: String,
    val sshPort: Int,
    val sshUser: String,
    val delegates: List<String>,
    val notebook: NotebookSessionJson,
    val agentAccess: WorkspaceAgentAccessJson,
    val lastError: String? = null
)

private fun syntheticOwnedIdentity(tenantId: String): OwnedAgentIdentity {
    val ownerUsername = "dispatcher-$tenantId"
    val agentUsername = "agent-$tenantId"
    return OwnedAgentIdentity(
        owner = TestUser(username = ownerUsername, password = "unused", groups = listOf("users")),
        agent = TestUser(
            username = agentUsername,
            password = "unused",
            groups = listOf("users"),
            ownerUsername = ownerUsername,
            metadata = mapOf("employeeType" to "agent")
        )
    )
}

suspend fun TestRunner.agentEnvTests() = suite("Agent Workspace Base Environment Tests") {
    val harness = AgentWorkspaceHarness()
    val prerequisiteFailure = harness.prerequisiteFailure()
    val identity = syntheticOwnedIdentity(harness.tenantId)

    fun TestContext.requireAgentEnvPrerequisites() {
        prerequisiteFailure?.let { fail(it) }
    }

    test("Agent workspace source and fixture contexts are present") {
        requireAgentEnvPrerequisites()
        File(AGENT_WORKSPACE_CONTEXT).exists() shouldBe true
        File(AGENT_LANG_FIXTURE_CONTEXT).exists() shouldBe true
        File(AIDER_RUNTIME_FIXTURE_CONTEXT).exists() shouldBe true
    }

    test("Owned agent workspace contains the base toolchains") {
        requireAgentEnvPrerequisites()
        harness.ensureWorkspaceImageBuilt()
        harness.ensureDirectWorkspace(identity.owner, identity.agent)

        val output = harness.execInDirectWorkspace(
            "set -euo pipefail; " +
                "agent-list-profiles > /tmp/agent-profiles.txt; " +
                "agent-verify-profile ${BASE_AGENT_PROFILES.joinToString(" ")}; " +
                "aider --version >/dev/null; " +
                "echo AGENT_ENV_OK"
        )
        output.contains("AGENT_ENV_OK") shouldBe true
    }

    test("Owned agent workspace seeds the expected home layout") {
        requireAgentEnvPrerequisites()
        harness.ensureWorkspaceImageBuilt()
        harness.ensureDirectWorkspace(identity.owner, identity.agent)

        val output = harness.execInDirectWorkspace(
            "set -euo pipefail; " +
                "test -f $AGENT_WORKSPACE_HOME/AGENTS.md; " +
                "test -f $AGENT_WORKSPACE_HOME/README_FIRST.md; " +
                "test -d $AGENT_WORKSPACE_HOME/notes/projects; " +
                "test -d $AGENT_WORKSPACE_HOME/state/checkpoints; " +
                "test -d $AGENT_WORKSPACE_HOME/repositories; " +
                "echo AGENT_HOME_OK"
        )
        output.contains("AGENT_HOME_OK") shouldBe true
    }

    test("Cleanup direct agent environment resources") {
        requireAgentEnvPrerequisites()
        harness.cleanupDirectResources()
        harness.directResourcesGone() shouldBe true
    }
}

suspend fun TestRunner.agentExpandTests() = suite("Agent Workspace Optional Profile Tests") {
    val harness = AgentWorkspaceHarness()
    val prerequisiteFailure = harness.prerequisiteFailure()
    val identity = syntheticOwnedIdentity(harness.tenantId)

    fun TestContext.requireAgentExpandPrerequisites() {
        prerequisiteFailure?.let { fail(it) }
    }

    test("Optional agent profiles are advertised") {
        requireAgentExpandPrerequisites()
        harness.ensureWorkspaceImageBuilt()
        harness.ensureDirectWorkspace(identity.owner, identity.agent)

        val output = harness.execInDirectWorkspace("set -euo pipefail; agent-list-profiles")
        OPTIONAL_AGENT_PROFILES.forEach { profile ->
            output.contains(profile) shouldBe true
        }
    }

    test("Rust profile installs and builds the Rust fixture") {
        requireAgentExpandPrerequisites()
        harness.ensureWorkspaceImageBuilt()
        harness.ensureDirectWorkspace(identity.owner, identity.agent)
        harness.agentInstallProfiles("rust")
        harness.agentVerifyProfiles("rust")
        harness.installFixtureInDirectWorkspace("$AGENT_LANG_FIXTURE_CONTEXT/optional/rust", "agent-fixtures/rust")
        val output = harness.execInDirectWorkspace("set -euo pipefail; cd $AGENT_FIXTURE_REPOSITORIES/rust; ./verify.sh")
        output.contains("RUST_OK") shouldBe true
    }

    test(".NET profile installs and builds the C# fixture") {
        requireAgentExpandPrerequisites()
        harness.ensureWorkspaceImageBuilt()
        harness.ensureDirectWorkspace(identity.owner, identity.agent)
        harness.agentInstallProfiles("dotnet")
        harness.agentVerifyProfiles("dotnet")
        harness.installFixtureInDirectWorkspace("$AGENT_LANG_FIXTURE_CONTEXT/optional/dotnet", "agent-fixtures/dotnet")
        val output = harness.execInDirectWorkspace("set -euo pipefail; cd $AGENT_FIXTURE_REPOSITORIES/dotnet; ./verify.sh")
        output.contains("DOTNET_OK") shouldBe true
    }

    test("PHP, Ruby, and web-static profiles install and verify") {
        requireAgentExpandPrerequisites()
        harness.ensureWorkspaceImageBuilt()
        harness.ensureDirectWorkspace(identity.owner, identity.agent)
        harness.agentInstallProfiles("php", "ruby", "web-static")
        harness.agentVerifyProfiles("php", "ruby", "web-static")

        OPTIONAL_AGENT_FIXTURES.filterKeys { it in setOf("php", "ruby", "web-static") }.forEach { (_, fixture) ->
            harness.installFixtureInDirectWorkspace("$AGENT_LANG_FIXTURE_CONTEXT/optional/$fixture", "agent-fixtures/$fixture")
            harness.execInDirectWorkspace("set -euo pipefail; cd $AGENT_FIXTURE_REPOSITORIES/$fixture; ./verify.sh")
        }
    }

    test("Cleanup direct agent expansion resources") {
        requireAgentExpandPrerequisites()
        harness.cleanupDirectResources()
        harness.directResourcesGone() shouldBe true
    }
}

suspend fun TestRunner.agentFixtureTests() = suite("Agent Workspace Polyglot Fixture Tests") {
    val harness = AgentWorkspaceHarness()
    val prerequisiteFailure = harness.prerequisiteFailure()
    val identity = syntheticOwnedIdentity(harness.tenantId)

    fun TestContext.requireAgentFixturePrerequisites() {
        prerequisiteFailure?.let { fail(it) }
    }

    fun runFixture(name: String) {
        harness.installFixtureInDirectWorkspace("$AGENT_LANG_FIXTURE_CONTEXT/base/$name", "agent-fixtures/$name")
        harness.execInDirectWorkspace("set -euo pipefail; cd $AGENT_FIXTURE_REPOSITORIES/$name; ./verify.sh")
    }

    BASE_AGENT_FIXTURES.forEach { fixtureName ->
        test("Base fixture $fixtureName builds and runs") {
            requireAgentFixturePrerequisites()
            harness.ensureWorkspaceImageBuilt()
            harness.ensureDirectWorkspace(identity.owner, identity.agent)
            harness.agentVerifyProfiles(*BASE_AGENT_PROFILES.toTypedArray())
            runFixture(fixtureName)
        }
    }

    test("Cleanup direct agent fixture resources") {
        requireAgentFixturePrerequisites()
        harness.cleanupDirectResources()
        harness.directResourcesGone() shouldBe true
    }
}

suspend fun TestRunner.agentRuntimeTests() {
    val runner = this
    suite("Agent Workspace Aider Runtime Tests") {
        val harness = AgentWorkspaceHarness()
        val inferenceHarness = DirectInferenceHarness(runner)
        val prerequisiteMessages = listOfNotNull(
            harness.prerequisiteFailure(),
            inferenceHarness.prerequisiteFailure()
        )
        val prerequisiteFailure = prerequisiteMessages.takeIf { it.isNotEmpty() }?.joinToString("; ")
        val identity = syntheticOwnedIdentity(harness.tenantId)

        fun TestContext.requireAgentRuntimePrerequisites() {
            prerequisiteFailure?.let { fail(it) }
        }

        suspend fun TestContext.runDirectAiderRepair(provider: DirectAiderProvider) {
            val backendReady = runCatching {
                when (provider.backendServiceName) {
                    "llm-cpu-fallback" -> inferenceHarness.ensureCpuBackendReady(runner::note)
                    "vllm-gpu" -> inferenceHarness.ensureGpuBackendReady(runner::note)
                    else -> error("unsupported direct Aider backend: ${provider.backendServiceName}")
                }
            }
            backendReady.exceptionOrNull()?.let { error ->
                skip(
                    "${provider.label} direct Aider backend is not deployed/enabled: " +
                        (error.message ?: error::class.simpleName ?: "backend not reachable")
                            .lineSequence()
                            .firstOrNull()
                            ?.take(240)
                            .orEmpty()
                )
            }
            harness.ensureWorkspaceImageBuilt()
            harness.ensureDirectWorkspace(identity.owner, identity.agent)
            harness.installFixtureInDirectWorkspace("$AIDER_RUNTIME_FIXTURE_CONTEXT/real-edit-demo", "aider-real-edit-demo")

            val routeProbe = ollamaRouteProbe(provider.baseUrl, provider.model)
            harness.execInDirectWorkspace(
                "set -euo pipefail; " +
                    "cd $AGENT_WORKSPACE_HOME/repositories/aider-real-edit-demo; " +
                    "rm -rf .git repair.txt __pycache__ .pytest_cache; " +
                    "git init >/dev/null; " +
                    "git config user.name 'Aider Runtime Test'; " +
                    "git config user.email 'aider-runtime@test.local'; " +
                    "git add .; " +
                    "git commit -m init >/dev/null"
            )
            awaitWorkspaceRouteProbe(harness, provider, routeProbe)

            var lastResult: CommandResult? = null
            val maxAttempts = 2

            for (attempt in 1..maxAttempts) {
                runner.note("running ${provider.label} Aider repair attempt $attempt/$maxAttempts")
                val result = harness.execInDirectWorkspaceResult(
                    "set -euo pipefail; " +
                        "cd $AGENT_WORKSPACE_HOME/repositories/aider-real-edit-demo; " +
                        "git reset --hard HEAD >/dev/null; " +
                        "git clean -fd >/dev/null; " +
                        "rm -f repair.txt; " +
                        "export AIDER_MODEL=${shellQuote(provider.model)}; " +
                        "export AIDER_OLLAMA_API_BASE=${shellQuote(provider.baseUrl)}; " +
                        "export AIDER_EDIT_FORMAT=whole; " +
                        "python3 -c ${shellQuote(routeProbe)}; " +
                        "if ! agent-aider-run --cwd \"${'$'}PWD\" --task-file TASK.md --output-file repair.txt --file calc.py; then " +
                        "  attempt_log=$(ls -1t $AGENT_WORKSPACE_HOME/state/session-journal/aider-*.log 2>/dev/null | head -n1 || true); " +
                        "  [ -n \"${'$'}attempt_log\" ] && cat \"${'$'}attempt_log\"; " +
                        "  exit 1; " +
                        "fi; " +
                        "if ! grep -qx 'def add(a: int, b: int) -> int:' calc.py; then " +
                        "  echo 'calc.py signature mismatch'; " +
                        "  sed -n '1,120p' calc.py; " +
                        "  exit 1; " +
                        "fi; " +
                        "if ! grep -qx '    return a + b' calc.py; then " +
                        "  echo 'calc.py implementation mismatch'; " +
                        "  sed -n '1,120p' calc.py; " +
                        "  exit 1; " +
                        "fi; " +
                        "if ! python3 -m unittest >/tmp/unittest.log 2>&1; then " +
                        "  cat /tmp/unittest.log; " +
                        "  exit 1; " +
                        "fi; " +
                        "if git diff --name-only --exit-code -- calc.py >/dev/null 2>&1; then " +
                        "  echo 'calc.py unchanged after Aider run'; " +
                        "  git status --short --untracked-files=all; " +
                        "  exit 1; " +
                        "fi; " +
                        "if ! git diff --name-only | grep -qx 'calc.py'; then " +
                        "  echo 'unexpected git diff set'; " +
                        "  git diff --name-only; " +
                        "  exit 1; " +
                        "fi; " +
                        "unexpected_changes=$(git status --short --untracked-files=all | awk '{print ${'$'}2}' | grep -vE '^(calc.py|repair.txt)$' || true); " +
                        "if [ -n \"${'$'}unexpected_changes\" ]; then " +
                        "  echo 'unexpected workspace changes'; " +
                        "  printf '%s\\n' \"${'$'}unexpected_changes\"; " +
                        "  git status --short --untracked-files=all; " +
                        "  exit 1; " +
                        "fi; " +
                        "echo AIDER_REAL_TASK_OK"
                )
                lastResult = result
                if (result.exitCode == 0 && result.output.contains("AIDER_REAL_TASK_OK")) {
                    inferenceHarness.assertModelResidency(provider, runner::note)
                    return
                }

                val output = result.output
                val retryable = isRetryableAiderFailure(output)
                val terminal = isTerminalAiderFailure(output)
                runner.note(
                    "${provider.label} Aider repair attempt $attempt failed " +
                        "(exit ${result.exitCode}): ${summarizeAiderFailure(output)}"
                )
                if (terminal || !retryable || attempt == maxAttempts) {
                    fail(
                        buildString {
                            append(provider.label)
                            append(" Aider real task failed exit=")
                            append(result.exitCode)
                            append(": ")
                            append(output.ifBlank { "<no output>" })
                        }
                    )
                }

                val delayMs = retryDelayMs(attempt)
                runner.note("retrying ${provider.label} repair after ${delayMs}ms because the failure looks transient")
                delay(delayMs)
            }

            fail(
                "${provider.label} Aider real task exhausted retries: " +
                    (lastResult?.output?.ifBlank { "<no output>" } ?: "<no output>")
            )
        }

        test("Aider CLI wrapper is installed in the owned workspace") {
            requireAgentRuntimePrerequisites()
            harness.ensureWorkspaceImageBuilt()
            harness.ensureDirectWorkspace(identity.owner, identity.agent)

            val output = harness.execInDirectWorkspace(
                "set -euo pipefail; " +
                    "agent-aider-doctor; " +
                    "agent-aider-run --help >/dev/null; " +
                    "echo AIDER_WRAPPER_OK"
            )
            output.contains("AIDER_WRAPPER_OK") shouldBe true
        }

        test("Aider can complete a real workspace repair task on the CPU LLM backend") {
            requireAgentRuntimePrerequisites()
            runDirectAiderRepair(
                DirectAiderProvider(
                    label = "CPU backend",
                    baseUrl = workspaceDirectAiderBaseUrl("/ollama/cpu"),
                    model = DIRECT_CPU_MODEL,
                    backendServiceName = "llm-cpu-fallback",
                    residencyMode = "cpu"
                )
            )
        }

        test("Aider can complete a real workspace repair task on the GPU LLM backend") {
            requireAgentRuntimePrerequisites()
            runDirectAiderRepair(
                DirectAiderProvider(
                    label = "GPU backend",
                    baseUrl = workspaceDirectAiderBaseUrl("/ollama/gpu"),
                    model = DIRECT_GPU_MODEL,
                    backendServiceName = "vllm-gpu",
                    residencyMode = "gpu"
                )
            )
        }

        test("Cleanup direct agent runtime resources") {
            requireAgentRuntimePrerequisites()
            inferenceHarness.restoreManagedMode(runner::note)
            harness.cleanupDirectResources()
            harness.directResourcesGone() shouldBe true
        }
    }
}

suspend fun TestRunner.agentLabTests() = suite("Owned Agent Workspace Tests") {
    val harness = AgentWorkspaceHarness()
    val prerequisiteFailure = harness.prerequisiteFailure()
    var identity: OwnedAgentIdentity? = null
    var workspace: WorkspaceSummaryJson? = null
    var ownerAuth: WorkspaceProvisionerAuth? = null
    var agentAuth: WorkspaceProvisionerAuth? = null

    fun TestContext.requireAgentLabPrerequisites() {
        prerequisiteFailure?.let { fail(it) }
    }

    fun ensureIdentity(): OwnedAgentIdentity {
        identity?.let { return it }
        note("creating ephemeral dispatcher and owned agent Keycloak edge identities")
        return syntheticOwnedIdentity(harness.tenantId).also { identity = it }
    }

    suspend fun ensureOwnerAuth(): WorkspaceProvisionerAuth {
        ownerAuth?.let { return it }
        note("preparing workspace-provisioner API auth for dispatcher ${ensureIdentity().owner.username}")
        val auth = workspaceProvisionerAuth(ensureIdentity().owner)
        ownerAuth = auth
        return auth
    }

    suspend fun ensureAgentAuth(): WorkspaceProvisionerAuth {
        agentAuth?.let { return it }
        note("preparing workspace-provisioner API auth for delegated agent ${ensureIdentity().agent.username}")
        val auth = workspaceProvisionerAuth(ensureIdentity().agent)
        agentAuth = auth
        return auth
    }

    suspend fun ensureWorkspace(): WorkspaceSummaryJson {
        workspace?.let { return it }
        val ownedIdentity = ensureIdentity()
        note("creating owned workspace via workspace-provisioner for ${ownedIdentity.owner.username}")
        val created = createWorkspaceViaProvisioner(
            auth = ensureOwnerAuth(),
            displayName = "owned-agent-${harness.tenantId}",
            initialDelegate = ownedIdentity.agent.username
        )
        note("workspace ${created.id} created on ${created.sshHost}:${created.sshPort}")
        workspace = created
        return created
    }

    test("Owned agent identity metadata is available for the dispatcher") {
        requireAgentLabPrerequisites()
        val ownedIdentity = ensureIdentity()
        ownedIdentity.agent.username.startsWith("agent-") shouldBe true
        ownedIdentity.agent.ownerUsername shouldBe ownedIdentity.owner.username
        ownedIdentity.agent.metadata["employeeType"] shouldBe "agent"
    }

    test("Owned agent workspace starts through workspace-provisioner") {
        requireAgentLabPrerequisites()
        val ownedIdentity = ensureIdentity()
        val created = ensureWorkspace()
        note("verifying workspace ${created.id} visibility for owner and delegated agent")
        val ownerVisible = listWorkspacesViaProvisioner(ensureOwnerAuth()).any { it.id == created.id }
        val delegatedView = getWorkspaceViaProvisioner(ensureAgentAuth(), created.id)

        created.ownerUsername shouldBe ownedIdentity.owner.username
        created.delegates.contains(ownedIdentity.agent.username) shouldBe true
        created.status shouldBe "running"
        ownerVisible shouldBe true
        delegatedView.id shouldBe created.id
        delegatedView.ownerUsername shouldBe ownedIdentity.owner.username
        created.agentAccess.workspaceId shouldBe created.id
        created.agentAccess.searchPath.contains(created.id) shouldBe true
        created.agentAccess.documentPathPrefix.contains(created.id) shouldBe true

        val containerName = harness.findProvisionedWorkspaceContainer(created.id)
        note("inspecting provisioned workspace container $containerName")
        val inspect = harness.dockerExpectSuccess(
            "inspect provisioned workspace labels",
            "inspect",
            "--format",
            "{{index .Config.Labels \"webservices.workspace.owner\"}}|{{index .Config.Labels \"webservices.workspace.id\"}}|{{.Config.User}}",
            containerName
        )
        inspect.contains(ownedIdentity.owner.username) shouldBe true
        inspect.contains(created.id) shouldBe true
        inspect.lineSequence().last().contains("root") shouldBe true

        val principals = harness.execInContainer(
            containerName,
            "bash",
            "-lc",
            "cat /etc/ssh/auth_principals/agent"
        )
        val expectedOwnerPrincipal = workspacePrincipal(created.id, ownedIdentity.owner.username)
        val expectedAgentPrincipal = workspacePrincipal(created.id, ownedIdentity.agent.username)
        principals.contains(expectedOwnerPrincipal) shouldBe true
        principals.contains(expectedAgentPrincipal) shouldBe true

        val homeCheck = harness.execInContainer(
            containerName,
            "bash",
            "-lc",
            "su -s /bin/bash agent -lc " + shellQuote(
                "set -euo pipefail; " +
                    "test -f $AGENT_WORKSPACE_HOME/AGENTS.md; " +
                    "test -f $AGENT_WORKSPACE_HOME/README_FIRST.md; " +
                    "test -d $AGENT_WORKSPACE_HOME/repositories; " +
                    "test -f $AGENT_WORKSPACE_HOME/.config/webservices/agent.env; " +
                    "grep -q STACK_WORKSPACE_ID $AGENT_WORKSPACE_HOME/.config/webservices/agent.env; " +
                    "whoami"
            )
        )
        homeCheck.lineSequence().last().trim() shouldBe "agent"
    }

    test("Owned agent workspace can build software through workspace-provisioner") {
        requireAgentLabPrerequisites()
        val created = ensureWorkspace()
        note("copying build fixture into workspace ${created.id}")
        harness.installFixtureInProvisionedWorkspace(created.id, AGENT_BUILD_FIXTURE_CONTEXT, "agent-build-demo")

        note("running build fixture inside workspace ${created.id}")
        val buildOutput = harness.execInProvisionedWorkspace(
            created.id,
            "set -euo pipefail; " +
                "cd $AGENT_WORKSPACE_HOME/repositories/agent-build-demo; " +
                "make clean >/dev/null 2>&1 || true; " +
                "make; " +
                "./build/agent-build-demo"
        )
        buildOutput.contains("AGENT_BUILD_OK") shouldBe true
    }

    test("Notebook sidecar shares the owned agent home volume") {
        requireAgentLabPrerequisites()
        val ownedIdentity = ensureIdentity()
        val created = ensureWorkspace()
        note("seeding shared workspace state for notebook validation")
        harness.installFixtureInProvisionedWorkspace(created.id, AGENT_BUILD_FIXTURE_CONTEXT, "agent-build-demo")
        harness.execInProvisionedWorkspace(
            created.id,
            "set -euo pipefail; " +
                "cd $AGENT_WORKSPACE_HOME/repositories/agent-build-demo; " +
                "make >/dev/null; " +
                "printf '%s\\n' ${shellQuote(ownedIdentity.owner.username)} > $AGENT_WORKSPACE_HOME/state/discoveries/owner.txt"
        )
        note("starting notebook sidecar for workspace ${created.id}")
        val notebook = startNotebookViaProvisioner(ensureOwnerAuth(), created.id)
        val notebookContainerName = harness.findProvisionedNotebookContainer(created.id)
        val notebookBasePath = "/w/${created.id}/notebook/"

        notebook.notebook.status shouldBe "running"
        notebook.notebook.basePath shouldBe notebookBasePath

        val sharedState = harness.execInContainer(
            notebookContainerName,
            "bash",
            "-lc",
            "set -euo pipefail; " +
                "test -x $AGENT_WORKSPACE_HOME/repositories/agent-build-demo/build/agent-build-demo; " +
                "test \"$(cat $AGENT_WORKSPACE_HOME/state/discoveries/owner.txt)\" = ${shellQuote(ownedIdentity.owner.username)}; " +
                "python3 -c " + shellQuote("import urllib.request; urllib.request.urlopen('http://127.0.0.1:8888${notebookBasePath}lab', timeout=2)") + "; " +
                "echo notebook-shared-home-ok"
        )
        sharedState.contains("notebook-shared-home-ok") shouldBe true
    }

    test("Cleanup owned agent workspace resources") {
        requireAgentLabPrerequisites()
        val ownedIdentity = ensureIdentity()
        val created = ensureWorkspace()

        note("deleting workspace ${created.id} via workspace-provisioner")
        deleteWorkspaceViaProvisioner(ensureOwnerAuth(), created.id)
        harness.provisionedResourcesGone(created.id) shouldBe true

        note("owned agent identities were synthetic Keycloak edge identities; no directory cleanup required")
        identity = null
        workspace = null
        ownerAuth = null
        agentAuth = null
    }
}

private class AgentWorkspaceHarness(
    val tenantId: String = "tenant-${UUID.randomUUID().toString().substring(0, 8)}",
    private val dockerHost: String = isolatedDockerHostFromEnv()
) {
    private var workspaceImageReady = false

    private val directWorkspaceContainerName = "agent-workspace-$tenantId"
    private val directWorkspaceVolumeName = "agent-home-$tenantId"

    fun prerequisiteFailure(): String? {
        if (!File(AGENT_WORKSPACE_CONTEXT).exists()) {
            return "missing agent workspace image context at $AGENT_WORKSPACE_CONTEXT"
        }
        if (!File(AGENT_BUILD_FIXTURE_CONTEXT).exists()) {
            return "missing agent build fixture at $AGENT_BUILD_FIXTURE_CONTEXT"
        }
        if (!File(AGENT_LANG_FIXTURE_CONTEXT).exists()) {
            return "missing agent language fixtures at $AGENT_LANG_FIXTURE_CONTEXT"
        }
        if (!File(AIDER_RUNTIME_FIXTURE_CONTEXT).exists()) {
            return "missing Aider runtime fixtures at $AIDER_RUNTIME_FIXTURE_CONTEXT"
        }
        return if (!dockerHostAvailable(dockerHost)) {
            "isolated Docker host not accessible at $dockerHost"
        } else {
            null
        }
    }

    fun ensureWorkspaceImageBuilt() {
        if (workspaceImageReady) {
            progress("agent workspace image already available on isolated Docker host")
            return
        }
        progress("building agent workspace image on isolated Docker host $dockerHost")
        dockerWithEnv(mapOf("DOCKER_BUILDKIT" to "1"), "build", "-t", AGENT_WORKSPACE_IMAGE_TAG, AGENT_WORKSPACE_CONTEXT)
            .requireSuccess("build agent workspace image")
        workspaceImageReady = true
        progress("agent workspace image build complete")
    }

    fun ensureDirectWorkspace(owner: TestUser, agent: TestUser) {
        if (docker("inspect", directWorkspaceContainerName).exitCode == 0) {
            progress("direct workspace container $directWorkspaceContainerName already exists; recreating to avoid stale image/state reuse")
            cleanupDirectResources()
        }

        cleanupDirectResources()
        progress("creating direct workspace volume $directWorkspaceVolumeName")
        dockerExpectSuccess(
            "create direct agent home volume",
            "volume",
            "create",
            "--label", "webservices.test.tenant.id=$tenantId",
            "--label", "webservices.owner.username=${owner.username}",
            "--label", "webservices.agent.username=${agent.username}",
            directWorkspaceVolumeName
        )
        progress("starting direct workspace container $directWorkspaceContainerName")
        dockerExpectSuccess(
            "start direct agent workspace",
            "run",
            "-d",
            "--name", directWorkspaceContainerName,
            "--hostname", directWorkspaceContainerName,
            "--label", "webservices.test.tenant.id=$tenantId",
            "--label", "webservices.owner.username=${owner.username}",
            "--label", "webservices.agent.username=${agent.username}",
            "--label", "webservices.session.kind=workspace",
            "-e", "AGENT_OWNER_USERNAME=${owner.username}",
            "-e", "AGENT_USERNAME=${agent.username}",
            "-v", "$directWorkspaceVolumeName:/workspace-home",
            "--user", "root",
            AGENT_WORKSPACE_IMAGE_TAG,
            "bash",
            "-lc",
            directWorkspaceSeedCommand()
        )
        waitForDirectWorkspaceReady()
    }

    fun execInDirectWorkspace(script: String): String {
        val result = execInDirectWorkspaceResult(script)
        result.requireSuccess("run direct agent workspace command")
        return result.output
    }

    fun execInDirectWorkspaceResult(script: String): CommandResult {
        val result = docker(
            "exec",
            "-u",
            "agent",
            directWorkspaceContainerName,
            "bash",
            "-lc",
            script
        )
        return result
    }

    fun execInContainer(containerName: String, vararg args: String): String {
        val result = docker(buildList {
            add("exec")
            add(containerName)
            addAll(args)
        })
        result.requireSuccess("run container command in $containerName")
        return result.output
    }

    fun installFixtureInDirectWorkspace(localFixturePath: String, workspaceSubdir: String) {
        val targetDir = "$AGENT_WORKSPACE_HOME/repositories/$workspaceSubdir"
        dockerExpectSuccess(
            "prepare direct workspace fixture directory",
            "exec",
            directWorkspaceContainerName,
            "bash",
            "-lc",
            "rm -rf ${shellQuote(targetDir)} && mkdir -p ${shellQuote(targetDir)}"
        )
        dockerExpectSuccess("copy fixture into direct workspace", "cp", "$localFixturePath/.", "$directWorkspaceContainerName:$targetDir")
        dockerExpectSuccess(
            "set direct workspace fixture ownership",
            "exec",
            directWorkspaceContainerName,
            "bash",
            "-lc",
            "chown -R agent:agent ${shellQuote(targetDir)}"
        )
    }

    fun agentInstallProfiles(vararg profiles: String): String = execInDirectWorkspace(
        "set -euo pipefail; agent-install-profile ${profiles.joinToString(" ") { shellQuote(it) }}"
    )

    fun agentVerifyProfiles(vararg profiles: String): String = execInDirectWorkspace(
        "set -euo pipefail; agent-verify-profile ${profiles.joinToString(" ") { shellQuote(it) }}"
    )

    fun cleanupDirectResources() {
        docker("rm", "-f", directWorkspaceContainerName)
        docker("volume", "rm", "-f", directWorkspaceVolumeName)
    }

    fun directResourcesGone(): Boolean {
        val containers = docker("ps", "-a", "--filter", "label=webservices.test.tenant.id=$tenantId", "--format", "{{.Names}}")
        val volumes = docker("volume", "ls", "-q", "--filter", "label=webservices.test.tenant.id=$tenantId")
        return containers.output.lines().filter { it.isNotBlank() }.isEmpty() &&
            volumes.output.lines().filter { it.isNotBlank() }.isEmpty()
    }

    fun findProvisionedWorkspaceContainer(workspaceId: String): String {
        progress("resolving provisioned workspace container for workspace $workspaceId")
        val names = dockerExpectSuccess(
            "list provisioned workspace containers",
            "ps",
            "-a",
            "--filter", "label=webservices.workspace.id=$workspaceId",
            "--format", "{{.Names}}"
        ).lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("workspace-notebook-") }
        require(names.size == 1) { "expected one provisioned workspace container for $workspaceId, found: ${names.joinToString()}" }
        return names.single()
    }

    fun findProvisionedNotebookContainer(workspaceId: String): String {
        progress("resolving notebook container for workspace $workspaceId")
        val names = dockerExpectSuccess(
            "list provisioned notebook containers",
            "ps",
            "-a",
            "--filter", "label=webservices.workspace.id=$workspaceId",
            "--filter", "label=webservices.session.kind=notebook",
            "--format", "{{.Names}}"
        ).lines().map { it.trim() }.filter { it.isNotBlank() }
        require(names.size == 1) { "expected one notebook container for $workspaceId, found: ${names.joinToString()}" }
        return names.single()
    }

    fun installFixtureInProvisionedWorkspace(workspaceId: String, localFixturePath: String, workspaceSubdir: String) {
        val containerName = findProvisionedWorkspaceContainer(workspaceId)
        val targetDir = "$AGENT_WORKSPACE_HOME/repositories/$workspaceSubdir"
        progress("copying fixture $workspaceSubdir into $containerName:$targetDir")
        dockerExpectSuccess(
            "prepare provisioned workspace fixture directory",
            "exec",
            containerName,
            "bash",
            "-lc",
            "rm -rf ${shellQuote(targetDir)} && mkdir -p ${shellQuote(targetDir)}"
        )
        dockerExpectSuccess("copy fixture into provisioned workspace", "cp", "$localFixturePath/.", "$containerName:$targetDir")
        dockerExpectSuccess(
            "set provisioned workspace fixture ownership",
            "exec",
            containerName,
            "bash",
            "-lc",
            "chown -R agent:agent ${shellQuote(targetDir)}"
        )
    }

    fun execInProvisionedWorkspace(workspaceId: String, script: String): String {
        val containerName = findProvisionedWorkspaceContainer(workspaceId)
        val result = docker(
            "exec",
            containerName,
            "bash",
            "-lc",
            "su -s /bin/bash agent -lc ${shellQuote(script)}"
        )
        result.requireSuccess("run provisioned workspace command")
        return result.output
    }

    fun provisionedResourcesGone(workspaceId: String): Boolean {
        val containers = docker(
            "ps",
            "-a",
            "--filter", "label=webservices.workspace.id=$workspaceId",
            "--format", "{{.Names}}"
        )
        val volumes = docker(
            "volume",
            "ls",
            "-q",
            "--filter", "label=webservices.workspace.id=$workspaceId"
        )
        return containers.output.lines().filter { it.isNotBlank() }.isEmpty() &&
            volumes.output.lines().filter { it.isNotBlank() }.isEmpty()
    }

    fun dockerExpectSuccess(description: String, vararg args: String): String {
        val result = docker(*args)
        result.requireSuccess(description)
        return result.output
    }

    private fun waitForDirectWorkspaceReady() {
        progress("waiting for direct workspace container $directWorkspaceContainerName to become ready")
        waitFor("direct agent workspace to become ready", timeoutSeconds = 90, pollSeconds = 1) {
            val state = docker(
                "inspect",
                "--format",
                "{{.State.Status}}",
                directWorkspaceContainerName
            )
            if (state.exitCode != 0) {
                return@waitFor null
            }
            when (state.output.trim().lowercase()) {
                "running" -> {
                    val ready = docker(
                        "exec",
                        directWorkspaceContainerName,
                        "bash",
                        "-lc",
                        "test -f /workspace-home/.workspace-runtime-ready && test -L $AGENT_WORKSPACE_HOME"
                    )
                    if (ready.exitCode == 0) ready else null
                }

                "created", "restarting" -> null
                "exited", "dead" -> {
                    val logs = docker("logs", "--tail", "80", directWorkspaceContainerName)
                    error("direct agent workspace exited before becoming ready: ${logs.output.trim()}")
                }

                else -> null
            }
        }
        progress("direct workspace container $directWorkspaceContainerName is ready")
    }

    private fun docker(vararg args: String): CommandResult = execCommand(listOf("docker", "-H", dockerHost) + args)

    private fun docker(args: List<String>): CommandResult = execCommand(listOf("docker", "-H", dockerHost) + args)

    private fun dockerWithEnv(environment: Map<String, String>, vararg args: String): CommandResult {
        return execCommand(environment, listOf("docker", "-H", dockerHost) + args)
    }

    private fun directWorkspaceSeedCommand(): String = """
        set -euo pipefail
        rm -f /workspace-home/.workspace-runtime-ready
        if [ ! -f /workspace-home/.workspace-seeded ]; then
          mkdir -p /workspace-home /workspace-home/repositories
          cp -a /home/agent/. /workspace-home/
          touch /workspace-home/.workspace-seeded
        fi
        rm -rf /home/agent
        ln -s /workspace-home /home/agent
        mkdir -p /workspace-home/repositories
        chown -R agent:agent /workspace-home
        su -s /bin/bash agent -lc 'agent-sync-profile-state seed'
        touch /workspace-home/.workspace-runtime-ready
        exec tail -f /dev/null
    """.trimIndent()

    private fun progress(message: String) {
        println("      ℹ  $message")
        System.out.flush()
    }
}

private data class WorkspaceProvisionerAuth(
    val user: TestUser,
    val bearerToken: String? = null,
    val trustedProxySecret: String? = null
) {
    fun applyTo(builder: HttpRequestBuilder) {
        if (!bearerToken.isNullOrBlank()) {
            builder.bearerAuth(bearerToken)
            return
        }

        require(!trustedProxySecret.isNullOrBlank()) {
            "workspace-provisioner API tests require MODEL_CONTEXT_BEARER_TOKEN or WORKSPACE_PROXY_AUTH_SECRET"
        }
        builder.header("X-Trusted-Proxy-Secret", trustedProxySecret)
        builder.header("Remote-User", user.username)
        builder.header("Remote-Groups", user.groups.joinToString(","))
        user.email?.takeIf { it.isNotBlank() }?.let { builder.header("Remote-Email", it) }
    }
}

private suspend fun TestRunner.workspaceProvisionerAuth(user: TestUser): WorkspaceProvisionerAuth {
    System.getenv("MODEL_CONTEXT_BEARER_TOKEN")?.takeIf { it.isNotBlank() }?.let { token ->
        return WorkspaceProvisionerAuth(user = user, bearerToken = token)
    }

    val trustedProxySecret = System.getenv("WORKSPACE_PROXY_AUTH_SECRET")
        ?.takeIf { it.isNotBlank() }
        ?: System.getenv("MODEL_CONTEXT_PROXY_AUTH_SECRET")?.takeIf { it.isNotBlank() }

    return WorkspaceProvisionerAuth(user = user, trustedProxySecret = trustedProxySecret)
}

private suspend fun TestRunner.listWorkspacesViaProvisioner(auth: WorkspaceProvisionerAuth): List<WorkspaceSummaryJson> {
    val body = workspaceProvisionerGet(auth, "/api/workspaces")
    return suiteJson.decodeFromString(body)
}

private suspend fun TestRunner.getWorkspaceViaProvisioner(auth: WorkspaceProvisionerAuth, workspaceId: String): WorkspaceSummaryJson {
    val body = workspaceProvisionerGet(auth, "/api/workspaces/$workspaceId")
    return suiteJson.decodeFromString(body)
}

private suspend fun TestRunner.createWorkspaceViaProvisioner(
    auth: WorkspaceProvisionerAuth,
    displayName: String,
    initialDelegate: String? = null
): WorkspaceSummaryJson {
    val body = workspaceProvisionerPost(
        auth,
        "/api/workspaces",
        suiteJson.encodeToString(CreateWorkspaceRequestJson(displayName = displayName, initialDelegate = initialDelegate)),
        expectedStatus = HttpStatusCode.Created
    )
    return suiteJson.decodeFromString(body)
}

private suspend fun TestRunner.startNotebookViaProvisioner(auth: WorkspaceProvisionerAuth, workspaceId: String): WorkspaceSummaryJson {
    val body = workspaceProvisionerPost(auth, "/api/workspaces/$workspaceId/notebook/start", "{}")
    return suiteJson.decodeFromString(body)
}

private suspend fun TestRunner.deleteWorkspaceViaProvisioner(auth: WorkspaceProvisionerAuth, workspaceId: String) {
    workspaceProvisionerDelete(auth, "/api/workspaces/$workspaceId")
}

private suspend fun TestRunner.workspaceProvisionerGet(auth: WorkspaceProvisionerAuth, path: String): String {
    val response = requestHttpClient.get("$WORKSPACE_PROVISIONER_URL$path") {
        auth.applyTo(this)
    }
    require(response.status == HttpStatusCode.OK) {
        "GET $path failed: ${response.status} ${response.bodyAsText()}"
    }
    return response.bodyAsText()
}

private suspend fun TestRunner.workspaceProvisionerPost(
    auth: WorkspaceProvisionerAuth,
    path: String,
    body: String,
    expectedStatus: HttpStatusCode = HttpStatusCode.OK
): String {
    var lastError: String? = null
    repeat(WORKSPACE_PROVISIONER_REQUEST_ATTEMPTS) { attempt ->
        try {
            val response = requestHttpClient.post("$WORKSPACE_PROVISIONER_URL$path") {
                auth.applyTo(this)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body)
            }
            if (response.status == expectedStatus) {
                return response.bodyAsText()
            }
            val responseBody = response.bodyAsText()
            lastError = "expected $expectedStatus got ${response.status} $responseBody"
            if (response.status.value in 500..599 && attempt < WORKSPACE_PROVISIONER_REQUEST_ATTEMPTS - 1) {
                println(
                    "      ℹ  workspace-provisioner POST $path returned ${response.status} " +
                        "(attempt ${attempt + 1}/$WORKSPACE_PROVISIONER_REQUEST_ATTEMPTS); retrying"
                )
                delay(WORKSPACE_PROVISIONER_REQUEST_DELAY_MS)
                return@repeat
            }
            error("POST $path failed: $lastError")
        } catch (e: Exception) {
            lastError = e.message ?: e::class.simpleName ?: "unknown error"
            if (attempt < WORKSPACE_PROVISIONER_REQUEST_ATTEMPTS - 1) {
                println(
                    "      ℹ  workspace-provisioner POST $path request error " +
                        "(attempt ${attempt + 1}/$WORKSPACE_PROVISIONER_REQUEST_ATTEMPTS): $lastError"
                )
                delay(WORKSPACE_PROVISIONER_REQUEST_DELAY_MS)
                return@repeat
            }
            error("POST $path failed after $WORKSPACE_PROVISIONER_REQUEST_ATTEMPTS attempts: $lastError")
        }
    }
    error("POST $path failed after $WORKSPACE_PROVISIONER_REQUEST_ATTEMPTS attempts: ${lastError ?: "unknown error"}")
}

private suspend fun TestRunner.workspaceProvisionerDelete(auth: WorkspaceProvisionerAuth, path: String) {
    val response = requestHttpClient.delete("$WORKSPACE_PROVISIONER_URL$path") {
        auth.applyTo(this)
    }
    require(response.status == HttpStatusCode.OK) {
        "DELETE $path failed: ${response.status} ${response.bodyAsText()}"
    }
}

private fun dockerHostAvailable(dockerHost: String): Boolean {
    return execCommand(listOf("docker", "-H", dockerHost, "info")).exitCode == 0
}

private data class StackContainerState(
    val status: String,
    val health: String
)

private class DirectInferenceHarness(
    private val runner: TestRunner
) {
    private var activeMode: String? = null

    fun prerequisiteFailure(): String? {
        val domain = System.getenv("DOMAIN")?.trim().orEmpty()
        if (domain.isBlank()) {
            return "DOMAIN must be set for direct Aider backend routing"
        }
        val dockerInfo = DockerCli.run("info")
        if (dockerInfo.exitCode != 0) {
            return "stack Docker host unavailable to test-runner: ${dockerInfo.output.ifBlank { "<no output>" }}"
        }
        return null
    }

    suspend fun ensureCpuBackendReady(note: (String) -> Unit) {
        ensureMode("cpu_default", note, "selecting cpu-default inference mode")
        note("waiting for llm-cpu-fallback to serve ${DIRECT_CPU_MODEL}")
        awaitModelCatalog(
            serviceName = "llm-cpu-fallback",
            url = "http://llm-cpu-fallback:11434/api/tags",
            expectedModel = DIRECT_CPU_MODEL
        )
        warmModel("http://llm-cpu-fallback:11434", DIRECT_CPU_MODEL)
    }

    suspend fun ensureGpuBackendReady(note: (String) -> Unit) {
        ensureMode("gpu_llm", note, "selecting gpu-llm inference mode")
        note("waiting for vllm-gpu to serve ${DIRECT_GPU_MODEL}")
        awaitModelCatalog(
            serviceName = "vllm-gpu",
            url = "http://vllm-gpu:11434/api/tags",
            expectedModel = DIRECT_GPU_MODEL
        )
        warmModel("http://vllm-gpu:11434", DIRECT_GPU_MODEL)
    }

    suspend fun restoreManagedMode(note: (String) -> Unit) {
        if (activeMode == null) {
            return
        }
        ensureMode("cpu_default", note, "restoring inference controller to cpu-default mode")
    }

    suspend fun assertModelResidency(provider: DirectAiderProvider, note: (String) -> Unit) {
        val statusUrl = when (provider.backendServiceName) {
            "llm-cpu-fallback" -> "http://llm-cpu-fallback:11434"
            "vllm-gpu" -> "http://vllm-gpu:11434"
            else -> error("unsupported direct Aider backend: ${provider.backendServiceName}")
        }
        note("verifying ${provider.label} model residency is ${provider.residencyMode}")
        val payload = runner.requestHttpClient.get("$statusUrl/api/ps").bodyAsText()
        val normalized = payload.lowercase()
        require(normalized.contains(provider.model.lowercase())) {
            "ollama ps did not report model ${provider.model}: $payload"
        }
        when (provider.residencyMode) {
            "cpu" -> {
                require(!normalized.contains("\"size_vram\":") || normalized.contains("\"size_vram\":0")) {
                    "expected CPU-only residency for ${provider.model}: $payload"
                }
            }
            "gpu" -> {
                require(!normalized.contains("\"size_vram\":0")) {
                    "expected GPU residency for ${provider.model}: $payload"
                }
            }
        }
    }

    private suspend fun ensureMode(mode: String, note: (String) -> Unit, actionDescription: String) {
        if (activeMode == mode) {
            note("inference controller already in mode $mode")
            return
        }
        note(actionDescription)
        val response = runner.requestHttpClient.put("http://inference-controller:8110/api/mode") {
            applyInternalApiAuthHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"mode":"$mode","note":"agent advisory direct runtime request"}""")
        }
        val body = response.bodyAsText()
        require(response.status == HttpStatusCode.Accepted) {
            "unable to set inference controller mode to $mode: status=${response.status} body=$body"
        }
        note("waiting for inference controller-selected targets to become healthy")
        runner.awaitSelectedTargetsHealthyStatus(
            label = "inference-controller status",
            url = "http://inference-controller:8110/api/status",
            attempts = 36,
            delayMs = 5_000
        )
        activeMode = mode
    }

    private fun inspect(name: String): StackContainerState? {
        val result = DockerCli.run(
            "inspect",
            "-f",
            "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
            name
        )
        if (result.exitCode != 0) {
            return null
        }
        val parts = result.output.trim().split("|", limit = 2)
        return StackContainerState(
            status = parts.getOrElse(0) { "unknown" }.trim(),
            health = parts.getOrElse(1) { "none" }.trim()
        )
    }

    private suspend fun awaitModelCatalog(
        serviceName: String,
        url: String,
        expectedModel: String,
        attempts: Int = 120,
        delayMs: Long = 5_000
    ) {
        var lastFailure = "model catalog not queried"
        repeat(attempts) { attempt ->
            val state = inspect(serviceName)
            if (state == null) {
                lastFailure = "container missing"
                if (attempt < attempts - 1) {
                    delay(delayMs)
                }
                return@repeat
            }
            if (state.status in setOf("exited", "dead")) {
                error(
                    "$serviceName exited before becoming ready: " +
                        DockerCli.run("logs", "--tail", "120", serviceName).output.ifBlank { "<no logs>" }
                )
            }
            if (state.status == "running") {
                runCatching {
                    val response = runner.requestHttpClient.get(url)
                    val body = response.bodyAsText()
                    require(response.status == HttpStatusCode.OK) { "status=${response.status}" }
                    require(body.contains(expectedModel)) { "missing model '$expectedModel'" }
                }.onSuccess {
                    return
                }.onFailure { error ->
                    lastFailure = error.message ?: "model catalog check failed"
                }
            } else {
                lastFailure = "container state=${state.status} health=${state.health}"
            }

            if (attempt < attempts - 1) {
                delay(delayMs)
            }
        }
        error("timed out waiting for $serviceName to serve $expectedModel: $lastFailure")
    }

    private suspend fun warmModel(baseUrl: String, model: String) {
        val response = runner.requestHttpClient.post("${baseUrl.trimEnd('/')}/api/generate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"model":"$model","prompt":"Reply with READY.","stream":false,"options":{"num_predict":8}}""")
        }
        val body = response.bodyAsText()
        require(response.status == HttpStatusCode.OK) { "unable to warm model $model: status=${response.status} body=$body" }
        require(body.contains("response")) { "ollama warmup for $model returned unexpected body: $body" }
    }
}

private fun workspaceDirectAiderBaseUrl(path: String): String {
    val domain = System.getenv("DOMAIN")?.trim().orEmpty()
    require(domain.isNotEmpty()) { "DOMAIN must be set for direct Aider backend routing" }
    return "https://models.$domain${path.trim()}"
}

private fun ollamaRouteProbe(baseUrl: String, expectedModel: String): String {
    return """
import json
import urllib.request

request = urllib.request.Request(
    ${jsonStringLiteral("${baseUrl.trimEnd('/')}/api/tags")},
    headers={"User-Agent": "curl/8.5.0", "Accept": "application/json"}
)
with urllib.request.urlopen(request, timeout=30) as response:
    payload = json.loads(response.read().decode("utf-8"))

models = payload.get("models", [])
assert any((item.get("name") or "").split(":")[0] == ${jsonStringLiteral(expectedModel)} for item in models), payload
print("DIRECT_OLLAMA_ROUTE_OK")
""".trimIndent()
}

private suspend fun awaitWorkspaceRouteProbe(
    harness: AgentWorkspaceHarness,
    provider: DirectAiderProvider,
    routeProbe: String,
    attempts: Int = 24,
    delayMs: Long = 5_000
) {
    var lastFailure = "route probe not attempted"
    repeat(attempts) { attempt ->
        val result = harness.execInDirectWorkspaceResult(
            "set -euo pipefail; " +
                "cd $AGENT_WORKSPACE_HOME/repositories/aider-real-edit-demo; " +
                "python3 -c ${shellQuote(routeProbe)}"
        )
        if (result.exitCode == 0) {
            return
        }
        lastFailure = result.output.ifBlank { "<no output>" }
        if (attempt < attempts - 1) {
            delay(delayMs)
        }
    }
    error(
        "workspace route probe for ${provider.label} never became ready: " +
            summarizeAiderFailure(lastFailure)
    )
}

private fun jsonStringLiteral(value: String): String =
    "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

private fun isRetryableAiderFailure(output: String): Boolean {
    val lowered = output.lowercase()
    return listOf(
        "high demand",
        "reconnecting",
        "rate limit",
        "429",
        "502",
        "503",
        "504",
        "temporarily unavailable",
        "timeout",
        "timed out",
        "connection reset",
        "connection refused",
        "connection error",
        "econnreset",
        "service unavailable",
        "model is loading"
    ).any(lowered::contains)
}

private fun isTerminalAiderFailure(output: String): Boolean {
    val lowered = output.lowercase()
    return listOf(
        "validationerror",
        "input should be a valid integer",
        "schema",
        "malformed",
        "401",
        "403",
        "400",
        "model_not_found",
        "not found",
        "unsupported",
        "permission denied",
        "calc.py signature mismatch",
        "calc.py implementation mismatch",
        "calc.py unchanged after aider run",
        "unexpected git diff set",
        "unexpected workspace changes",
        "traceback (most recent call last)",
        "assertionerror",
        "syntaxerror",
        "fail:",
        "failed (failures="
    ).any(lowered::contains)
}

private fun summarizeAiderFailure(output: String): String {
    val lines = output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (lines.isEmpty()) {
        return "<no output>"
    }
    val interesting = lines
        .asReversed()
        .firstOrNull { !it.startsWith("[agent-aider-run]") }
        ?: lines.last()
    return interesting.take(240)
}

private fun retryDelayMs(attempt: Int): Long = minOf(30_000L, 2_000L * attempt * attempt)

private fun workspacePrincipal(workspaceId: String, username: String): String =
    "ws-${workspaceId.take(12)}-${username.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)}"

private data class CommandResult(
    val exitCode: Int,
    val output: String
)

private fun execCommand(command: List<String>): CommandResult {
    return execCommand(emptyMap(), command)
}

private fun execCommand(environment: Map<String, String>, command: List<String>): CommandResult {
    val process = ProcessBuilder(command)
        .apply {
            if (environment.isNotEmpty()) {
                environment().putAll(environment)
            }
        }
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    return CommandResult(exitCode = exitCode, output = output)
}

private fun CommandResult.requireSuccess(description: String) {
    if (exitCode != 0) {
        error("$description failed (exit=$exitCode): ${output.ifBlank { "<no output>" }}")
    }
}

private fun waitFor(description: String, timeoutSeconds: Int = 180, pollSeconds: Long = 2, block: () -> CommandResult?) {
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
    while (System.currentTimeMillis() < deadline) {
        val result = block()
        if (result != null) {
            return
        }
        Thread.sleep(pollSeconds * 1000)
    }
    error("Timed out waiting for $description")
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

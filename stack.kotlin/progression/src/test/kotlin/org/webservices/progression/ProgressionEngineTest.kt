package org.webservices.progression

import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProgressionEngineTest {
    private val task = TaskDefinition(
        id = "access.protect_bookstack",
        track = "access",
        stage = "secure",
        title = "Protect BookStack",
        summary = "Verify access",
        surfaces = TaskSurfaces(now = "Verify now", why = "Because access matters"),
        commands = TaskCommands(guided = listOf("stackctl verify access.bookstack")),
        evidence = EvidenceRequirement(required = listOf("verified.access.bookstack.anonymous_denied")),
        reward = RewardDefinition("First Door Secured", "Access was proven")
    )

    @Test
    fun `task remains available until evidence is verified`() {
        val dir = createTempDirectory()
        val store = StateStore(dir)
        val engine = ProgressionEngine(Registry(tasks = listOf(task), dashboards = emptyList()), store)

        val view = engine.view()

        assertEquals("available", view.tasks.single().status)
        assertFalse(view.tasks.single().rewardUnlocked)
        assertEquals(listOf("verified.access.bookstack.anonymous_denied"), view.tasks.single().missingEvidence)
    }

    @Test
    fun `reward unlocks from verified claim`() {
        val dir = createTempDirectory()
        val store = StateStore(dir)
        store.mergeVerified(mapOf("verified.access.bookstack.anonymous_denied" to true))
        val engine = ProgressionEngine(Registry(tasks = listOf(task), dashboards = emptyList()), store)

        val view = engine.view()

        assertEquals("complete", view.tasks.single().status)
        assertTrue(view.tasks.single().rewardUnlocked)
        assertEquals(listOf("First Door Secured"), view.rewardsUnlocked)
    }

    @Test
    fun `scanner parses compose services from generated compose`() {
        val compose = """
            services:
              caddy:
                image: caddy
              bookstack:
                image: bookstack

            volumes:
              bookstack_data:
        """.trimIndent()

        assertEquals(listOf("bookstack", "caddy"), parseComposeServices(compose))
    }

    @Test
    fun `scanner parses Caddy hosts with rendered domain placeholders`() {
        val caddy = """
            bookstack.{${'$'}DOMAIN} {
              reverse_proxy bookstack:80
            }

            api.bookstack.{${'$'}DOMAIN} {
              respond 404
            }

            {${'$'}DOMAIN}, homepage.{${'$'}DOMAIN} {
              reverse_proxy homepage:3000
            }
        """.trimIndent()

        assertEquals(
            listOf("apex", "api.bookstack.<domain>", "bookstack.<domain>", "homepage.<domain>"),
            parseCaddyHosts(caddy)
        )
    }

    @Test
    fun `state store merges actual facts without losing claims`() {
        val dir = createTempDirectory()
        val store = StateStore(dir)
        store.mergeActual(mapOf("actual.service.bookstack.defined" to true))
        val snapshot = store.mergeActual(
            claims = mapOf("actual.route.bookstack.exists" to true),
            facts = mapOf("route" to JsonPrimitive("bookstack.<domain>"))
        )

        assertTrue(snapshot.claims.getValue("actual.service.bookstack.defined"))
        assertTrue(snapshot.claims.getValue("actual.route.bookstack.exists"))
        assertEquals(JsonPrimitive("bookstack.<domain>"), snapshot.facts["route"])
    }

    @Test
    fun `declared progression catalog covers every sovereign compute phase`() {
        val registry = loadRepoProgressionRegistry()
        val stages = registry.tasks.map { it.stage }.toSet()

        assertTrue("use" in stages)
        assertTrue("see" in stages)
        assertTrue("secure" in stages)
        assertTrue("map-state" in stages)
        assertTrue("prove-recovery" in stages)
        assertTrue("operate-cli" in stages)
    }

    @Test
    fun `every declared progression task has evidence commands and a dashboard surface`() {
        val registry = loadRepoProgressionRegistry()
        val dashboardTaskReferences = registry.dashboards
            .flatMap { dashboard -> dashboard.foregroundWhen + dashboard.recommendedAfter }
            .toSet()

        for (declaredTask in registry.tasks) {
            assertTrue(declaredTask.title.isNotBlank(), "task ${declaredTask.id} must have a title")
            assertTrue(declaredTask.summary.isNotBlank(), "task ${declaredTask.id} must have a summary")
            assertTrue(declaredTask.surfaces.now.isNotBlank(), "task ${declaredTask.id} must have now copy")
            assertTrue(declaredTask.surfaces.why.isNotBlank(), "task ${declaredTask.id} must have why copy")
            assertTrue(declaredTask.evidence.required.isNotEmpty(), "task ${declaredTask.id} must require evidence")
            assertTrue(
                declaredTask.commands.guided.isNotEmpty() || declaredTask.commands.verify.isNotEmpty(),
                "task ${declaredTask.id} must expose guided or verify commands"
            )
            assertTrue(
                declaredTask.id in dashboardTaskReferences || declaredTask.requires.isEmpty(),
                "task ${declaredTask.id} must be referenced by a dashboard or be the root arrival task"
            )
        }
    }

    @Test
    fun `declared progression tasks transition independently as evidence arrives`() {
        val registry = loadRepoProgressionRegistry()
        val dir = createTempDirectory()
        val store = StateStore(dir)
        val engine = ProgressionEngine(registry, store)

        assertNotNull(engine.view().primaryNextTask)

        val firstTask = registry.tasks.first()
        store.mergeVerified(firstTask.evidence.required.associateWith { true })
        val afterFirstEvidence = engine.view().tasks.associateBy { it.id }

        assertEquals("complete", afterFirstEvidence.getValue(firstTask.id).status)
        assertTrue(
            afterFirstEvidence.values.any { it.status == "available" || it.status == "blocked" },
            "later tasks should remain represented after the first task completes"
        )
    }

    private fun loadRepoProgressionRegistry(): Registry {
        val root = sequenceOf(
            Path.of(""),
            Path.of("../.."),
            Path.of("../../.."),
            Path.of("../../../.."),
        )
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull { it.resolve("stack.config/progression").exists() }
            ?: error("could not locate stack.config/progression from ${Path.of("").toAbsolutePath()}")
        return RegistryLoader().load(
            ProgressionConfig(
                port = 8130,
                bundleRoot = root,
                deployRoot = root,
                runtimeDir = root.resolve("build/test-progress"),
                runtimeBuildInfo = null,
            )
        )
    }
}

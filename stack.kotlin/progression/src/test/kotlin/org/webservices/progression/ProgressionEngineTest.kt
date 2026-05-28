package org.webservices.progression

import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}

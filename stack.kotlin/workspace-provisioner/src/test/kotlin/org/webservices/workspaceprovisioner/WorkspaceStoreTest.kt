package org.webservices.workspaceprovisioner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class WorkspaceStoreTest {
    @Test
    fun `workspace delegations grant access`() {
        val tempDir = Files.createTempDirectory("workspace-store-test")
        val store = WorkspaceStore(tempDir.resolve("workspaces.sqlite"))
        val workspace = store.createWorkspace("alice", "build-lab", 47000, "agent", 14, "workspace-notebook-test", 48000, "stopped", 49000, "stopped")

        assertTrue(store.hasAccess(workspace.id, "alice"))
        assertFalse(store.hasAccess(workspace.id, "agent-alice"))

        store.addDelegation(workspace.id, "agent-alice", "alice")

        assertTrue(store.hasAccess(workspace.id, "agent-alice"))
        assertEquals(listOf("agent-alice"), store.listDelegates(workspace.id))
    }

    @Test
    fun `renew lease extends from current expiry`() {
        val tempDir = Files.createTempDirectory("workspace-store-renew")
        val store = WorkspaceStore(tempDir.resolve("workspaces.sqlite"))
        val workspace = store.createWorkspace("alice", "analysis", 47001, "agent", 14, "workspace-notebook-test", 48001, "stopped", 49001, "stopped")
        val renewed = store.renewLease(workspace.id, 7)

        assertTrue(java.time.Instant.parse(renewed.leaseExpiresAt).isAfter(java.time.Instant.parse(workspace.leaseExpiresAt)))
    }
}

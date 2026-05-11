package org.webservices.workspaceprovisioner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmallstepSshCaTest {
    @Test
    fun `certificate principal is workspace scoped`() {
        val principal = certificatePrincipal("12345678-1234-5678-1234-567812345678", "Agent.User")
        assertEquals("ws-12345678-123-agent-user", principal)
    }
}

package org.webservices.testrunner.framework

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerCliTest {
    @Test
    fun `detects top level mutating docker commands`() {
        assertTrue(DockerCli.isMutatingCommand(listOf("run", "--rm", "busybox", "echo", "ok")))
        assertTrue(DockerCli.isMutatingCommand(listOf("rm", "-f", "container-id")))
        assertTrue(DockerCli.isMutatingCommand(listOf("build", "-t", "stack/test", ".")))
    }

    @Test
    fun `detects compose mutating commands and ignores read only compose queries`() {
        assertTrue(DockerCli.isMutatingCommand(listOf("compose", "up", "-d")))
        assertTrue(DockerCli.isMutatingCommand(listOf("compose", "--project-name", "webservices", "exec", "app", "sh")))
        assertFalse(DockerCli.isMutatingCommand(listOf("compose", "ps")))
        assertFalse(DockerCli.isMutatingCommand(listOf("compose", "logs", "--tail", "20", "app")))
    }

    @Test
    fun `treats inspect and info commands as non mutating`() {
        assertFalse(DockerCli.isMutatingCommand(listOf("info")))
        assertFalse(DockerCli.isMutatingCommand(listOf("inspect", "container-id")))
        assertFalse(DockerCli.isMutatingCommand(listOf("container", "inspect", "container-id")))
        assertFalse(DockerCli.isMutatingCommand(listOf("network", "ls")))
    }
}

package org.webservices.workspaceprovisioner

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class SmallstepSshCa(
    private val config: WorkspaceProvisionerConfig
) {
    val userCaPublicKeyPath: Path = config.caUserPublicKeyPath

    fun userCaPublicKey(): String = Files.readString(userCaPublicKeyPath).trim()

    fun fingerprint(publicKey: String): String {
        val tokens = publicKey.trim().split(Regex("\\s+"))
        require(tokens.size >= 2) { "invalid SSH public key format" }
        val decoded = java.util.Base64.getDecoder().decode(tokens[1])
        val digest = MessageDigest.getInstance("SHA-256").digest(decoded)
        return "SHA256:${java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)}"
    }

    fun issueUserCertificate(publicKey: String, principal: String): IssuedUserCertificate {
        val tempDir = Files.createTempDirectory(config.dataDir, "ssh-cert-")
        return try {
            val keyFile = tempDir.resolve("id_ed25519.pub")
            Files.writeString(keyFile, publicKey.trim() + "\n")
            val certPath = tempDir.resolve("id_ed25519-cert.pub")
            val result = runStep(
                "ssh", "certificate", principal, keyFile.toString(),
                "--sign",
                "--offline",
                "--ca-config", config.caConfigPath.toString(),
                "--provisioner", config.caProvisioner,
                "--provisioner-password-file", config.caProvisionerPasswordFile.toString(),
                "--principal", principal,
                "--not-after", config.workspaceCertTtl,
                "--force"
            )
            require(result.exitCode == 0) { "step ssh certificate failed: ${result.stderr}" }
            IssuedUserCertificate(
                certificate = Files.readString(certPath).trim() + "\n",
                expiresAt = Instant.now().plus(parseDuration(config.workspaceCertTtl)).truncatedTo(ChronoUnit.SECONDS).toString()
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun runStep(vararg args: String): CommandResult {
        val process = ProcessBuilder(listOf("step", *args))
            .redirectErrorStream(true)
            .start()
        val stdout = ByteArrayOutputStream()
        process.inputStream.copyTo(stdout)
        val completed = process.waitFor(2, TimeUnit.MINUTES)
        check(completed) { "step command timed out: ${args.joinToString(" ")}" }
        return CommandResult(process.exitValue(), stdout.toString(Charsets.UTF_8), "")
    }
}

data class IssuedUserCertificate(
    val certificate: String,
    val expiresAt: String
)

fun certificatePrincipal(workspaceId: String, username: String): String =
    "ws-${workspaceId.take(12)}-${username.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)}"

private fun parseDuration(raw: String): java.time.Duration {
    val value = raw.trim()
    val match = Regex("^(\\d+)([smhd])$").matchEntire(value)
        ?: error("unsupported duration format: $raw")
    val amount = match.groupValues[1].toLong()
    return when (match.groupValues[2]) {
        "s" -> java.time.Duration.ofSeconds(amount)
        "m" -> java.time.Duration.ofMinutes(amount)
        "h" -> java.time.Duration.ofHours(amount)
        "d" -> java.time.Duration.ofDays(amount)
        else -> error("unsupported duration unit: $raw")
    }
}

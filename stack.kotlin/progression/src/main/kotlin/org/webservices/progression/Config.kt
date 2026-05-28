package org.webservices.progression

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists

data class ProgressionConfig(
    val port: Int,
    val bundleRoot: Path,
    val deployRoot: Path,
    val runtimeDir: Path,
    val runtimeBuildInfo: Path?
) {
    val progressionConfigDir: Path = bundleRoot.resolve("stack.config/progression")
}

fun loadConfig(): ProgressionConfig {
    val current = Path(System.getProperty("user.dir")).absolute()
    val bundleRoot = envPath("PROGRESSION_BUNDLE_ROOT") ?: inferBundleRoot(current)
    val deployRoot = envPath("PROGRESSION_DEPLOY_ROOT") ?: inferDeployRoot(bundleRoot)
    val runtimeDir = envPath("PROGRESSION_RUNTIME_DIR") ?: deployRoot.resolve("runtime/progression")
    val runtimeBuildInfo = envPath("PROGRESSION_RUNTIME_BUILD_INFO")
        ?: deployRoot.resolve("runtime/build-info.json").takeIf { it.exists() }
    return ProgressionConfig(
        port = envInt("PROGRESSION_PORT", 8130),
        bundleRoot = bundleRoot,
        deployRoot = deployRoot,
        runtimeDir = runtimeDir,
        runtimeBuildInfo = runtimeBuildInfo
    )
}

private fun inferBundleRoot(current: Path): Path {
    val candidates = listOf(
        current,
        current.resolve("build"),
        current.parent?.resolve("build"),
        current.parent
    ).filterNotNull()
    return candidates.firstOrNull { it.resolve("stack.config/progression").exists() } ?: current
}

private fun inferDeployRoot(bundleRoot: Path): Path {
    if (bundleRoot.fileName?.toString() == "build") {
        return bundleRoot.parent ?: bundleRoot
    }
    return bundleRoot
}

private fun envPath(name: String): Path? = System.getenv(name)
    ?.takeIf { it.isNotBlank() }
    ?.let { Path(it).absolute() }

private fun envInt(name: String, defaultValue: Int): Int = System.getenv(name)?.toIntOrNull() ?: defaultValue

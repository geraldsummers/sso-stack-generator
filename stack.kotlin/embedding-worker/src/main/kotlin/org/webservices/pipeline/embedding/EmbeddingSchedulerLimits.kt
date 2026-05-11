package org.webservices.pipeline.embedding

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

enum class EmbeddingExecutionProfile {
    GPU_BULK,
    CPU_THROTTLED,
    CPU_RESERVED
}

data class EmbeddingSchedulerLimits(
    val profile: EmbeddingExecutionProfile,
    val batchSize: Int,
    val maxConcurrentEmbeddings: Int,
    val embeddingRequestBatchSize: Int,
    val maxConcurrentBatchRequests: Int,
    val embeddingRequestMaxBytes: Int,
    val pauseBackgroundWork: Boolean = false
) {
    fun cpuThrottled(): EmbeddingSchedulerLimits = copy(
        profile = EmbeddingExecutionProfile.CPU_THROTTLED,
        batchSize = 1,
        maxConcurrentEmbeddings = 1,
        embeddingRequestBatchSize = 1,
        maxConcurrentBatchRequests = 1,
        pauseBackgroundWork = false
    )

    fun cpuReserved(): EmbeddingSchedulerLimits = copy(
        profile = EmbeddingExecutionProfile.CPU_RESERVED,
        batchSize = 1,
        maxConcurrentEmbeddings = 1,
        embeddingRequestBatchSize = 1,
        maxConcurrentBatchRequests = 1,
        pauseBackgroundWork = true
    )
}

interface EmbeddingSchedulerLimitsProvider {
    val defaultLimits: EmbeddingSchedulerLimits

    suspend fun currentLimits(): EmbeddingSchedulerLimits
}

class StaticEmbeddingSchedulerLimitsProvider(
    override val defaultLimits: EmbeddingSchedulerLimits
) : EmbeddingSchedulerLimitsProvider {
    override suspend fun currentLimits(): EmbeddingSchedulerLimits = defaultLimits
}

class InferenceControllerAwareEmbeddingSchedulerLimitsProvider(
    private val controllerStatusClient: InferenceControllerStatusClient,
    override val defaultLimits: EmbeddingSchedulerLimits,
    private val reserveCpuForInteractive: Boolean = true
) : EmbeddingSchedulerLimitsProvider {

    @Volatile
    private var lastKnownEmbeddingTarget: String? = null

    override suspend fun currentLimits(): EmbeddingSchedulerLimits {
        val target = runCatching { controllerStatusClient.fetchEmbeddingTarget() }
            .onSuccess { lastKnownEmbeddingTarget = it }
            .getOrElse { error ->
                logger.warn(error) {
                    "Unable to fetch inference controller status; using ${lastKnownEmbeddingTarget ?: "conservative cpu"} profile"
                }
                lastKnownEmbeddingTarget
            }

        return when (target ?: "embedding-cpu") {
            "embedding-gpu" -> defaultLimits
            "embedding-cpu" -> if (reserveCpuForInteractive) {
                defaultLimits.cpuReserved()
            } else {
                defaultLimits.cpuThrottled()
            }

            else -> {
                logger.warn { "Unknown embedding target '$target'; keeping configured profile" }
                defaultLimits
            }
        }
    }
}

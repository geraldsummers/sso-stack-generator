package org.webservices.pipeline

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PipelineStatePathResolutionTest {

    @Test
    fun `metadata path defaults to persistent volume`() {
        assertEquals(
            "/data/metadata",
            resolvePipelineMetadataPath(env = emptyMap()) { null }
        )
    }

    @Test
    fun `dedup path defaults to persistent volume`() {
        assertEquals(
            "/data/dedup/dedup.tsv",
            resolvePipelineDedupPath(env = emptyMap()) { null }
        )
    }

    @Test
    fun `env overrides metadata and dedup paths`() {
        val env = mapOf(
            "PIPELINE_METADATA_PATH" to "/custom/metadata",
            "PIPELINE_DEDUP_PATH" to "/custom/dedup.tsv"
        )

        assertEquals("/custom/metadata", resolvePipelineMetadataPath(env = env) { null })
        assertEquals("/custom/dedup.tsv", resolvePipelineDedupPath(env = env) { null })
    }

    @Test
    fun `system properties override defaults when env absent`() {
        assertEquals(
            "/property/metadata",
            resolvePipelineMetadataPath(env = emptyMap()) { key ->
                if (key == "pipeline.metadata.path") "/property/metadata" else null
            }
        )
        assertEquals(
            "/property/dedup.tsv",
            resolvePipelineDedupPath(env = emptyMap()) { key ->
                if (key == "pipeline.dedup.path") "/property/dedup.tsv" else null
            }
        )
    }
}

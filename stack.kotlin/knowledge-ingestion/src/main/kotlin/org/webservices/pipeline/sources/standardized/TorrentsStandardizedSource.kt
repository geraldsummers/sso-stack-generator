package org.webservices.pipeline.sources.standardized

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.webservices.pipeline.core.Chunkable
import org.webservices.pipeline.core.PresentationMetadataKeys
import org.webservices.pipeline.core.StandardizedSource
import org.webservices.pipeline.processors.Chunker
import org.webservices.pipeline.scheduling.BackfillStrategy
import org.webservices.pipeline.scheduling.ResyncStrategy
import org.webservices.pipeline.scheduling.RunMetadata
import org.webservices.pipeline.scheduling.RunType
import org.webservices.pipeline.sources.TorrentEntry
import org.webservices.pipeline.sources.TorrentsSource


data class TorrentChunkable(val entry: TorrentEntry) : Chunkable {
    override fun toText(): String = entry.toText()
    override fun getId(): String = entry.infohash
    override fun getMetadata(): Map<String, String> {
        val magnetLink = "magnet:?xt=urn:btih:${entry.infohash}"
        return mapOf(
            "title" to entry.name,
            "infohash" to entry.infohash,
            "name" to entry.name,
            "url" to magnetLink,
            PresentationMetadataKeys.URL to magnetLink,
            PresentationMetadataKeys.SEARCH_READY to "true",
            "sizeBytes" to entry.sizeBytes.toString(),
            "seeders" to entry.seeders.toString(),
            "leechers" to entry.leechers.toString()
        )
    }
}


class TorrentsStandardizedSource(
    private val dataPath: String = "https://codeberg.org/heretic/torrents-csv-data/raw/branch/main/torrents.csv",
    private val maxTorrents: Int = Int.MAX_VALUE
) : StandardizedSource<TorrentChunkable> {
    override val name = "torrents"

    override fun resyncStrategy() = ResyncStrategy.Weekly(dayOfWeek = 1, hour = 2, minute = 0)

    override fun backfillStrategy() = BackfillStrategy.FullDatasetDownload(url = dataPath)

    override fun needsChunking() = false  

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<TorrentChunkable> {
        
        
        

        val startLine = when (metadata.runType) {
            RunType.INITIAL_PULL -> 0L
            RunType.RESYNC -> 0L  
        }

        val source = TorrentsSource(
            dataPath = dataPath,
            startLine = startLine,
            maxTorrents = maxTorrents
        )

        return source.fetch().map { TorrentChunkable(it) }
    }
}

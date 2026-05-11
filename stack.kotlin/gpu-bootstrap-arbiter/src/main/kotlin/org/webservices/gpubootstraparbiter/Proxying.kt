package org.webservices.gpubootstraparbiter

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream

internal fun streamedRequestBody(
    input: InputStream,
    mediaType: MediaType?,
    contentLength: Long?
): RequestBody = object : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = contentLength ?: -1L

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        input.use { stream ->
            val output = sink.outputStream()
            stream.copyTo(output)
            output.flush()
        }
    }
}

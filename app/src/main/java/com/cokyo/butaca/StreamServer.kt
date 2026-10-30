package com.cokyo.butaca

import fi.iki.elonen.NanoHTTPD

/**
 * Servidor local (solo en el móvil) que entrega el vídeo al reproductor
 * con soporte de saltos (cabecera Range), mientras se descarga.
 */
class StreamServer(private val stream: TorrentEngine.Stream) : NanoHTTPD("127.0.0.1", 0) {

    val url: String get() = "http://127.0.0.1:$listeningPort/video"

    override fun serve(session: IHTTPSession): Response {
        val size = stream.fileSize
        val range = session.headers["range"]

        var start = 0L
        var end = size - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split("-")
            start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            end = parts.getOrNull(1)?.toLongOrNull() ?: (size - 1)
            partial = true
        }
        if (start >= size) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                .apply { addHeader("Content-Range", "bytes */$size") }
        }
        end = end.coerceAtMost(size - 1)
        val length = end - start + 1

        val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val res = newFixedLengthResponse(status, stream.mime, stream.openStream(start), length)
        res.addHeader("Accept-Ranges", "bytes")
        if (partial) res.addHeader("Content-Range", "bytes $start-$end/$size")
        return res
    }
}

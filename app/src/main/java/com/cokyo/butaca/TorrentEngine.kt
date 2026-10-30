package com.cokyo.butaca

import android.content.Context
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Motor de torrents: descarga en orden (secuencial) solo el vídeo elegido
 * y permite leerlo mientras se descarga.
 */
object TorrentEngine {

    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "wmv", "mpg", "mpeg")

    private val session = SessionManager()
    lateinit var downloadDir: File
        private set
    lateinit var torrentsDir: File
        private set

    fun init(ctx: Context) {
        downloadDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "descargas").apply { mkdirs() }
        torrentsDir = File(ctx.filesDir, "torrents").apply { mkdirs() }
    }

    @Synchronized
    private fun ensureStarted() {
        if (!session.isRunning) session.start()
    }

    class Stream(val handle: TorrentHandle, val ti: TorrentInfo, val fileIndex: Int) {
        val fileName: String = ti.files().fileName(fileIndex)
        val fileSize: Long = ti.files().fileSize(fileIndex)
        val file: File = File(downloadDir, ti.files().filePath(fileIndex))
        val hash: String = ti.infoHash().toHex()

        val mime: String
            get() = when (fileName.substringAfterLast('.', "").lowercase()) {
                "mp4", "m4v", "mov" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "ts" -> "video/mp2t"
                else -> "video/*"
            }

        fun statusText(): String {
            val st = handle.status()
            val kb = st.downloadRate() / 1024
            val pct = (st.progress() * 100).toInt()
            return "$pct% · ${st.numPeers()} conexiones · $kb KB/s"
        }

        /** Pide con urgencia las piezas a partir de [offset] (donde va a leer el reproductor). */
        fun prioritizeFrom(offset: Long) {
            val first = pieceAt(offset)
            val last = pieceAt(fileSize - 1)
            handle.clearPieceDeadlines()
            for (i in 0 until 12) {
                val p = first + i
                if (p > last) break
                handle.setPieceDeadline(p, 500 + i * 150)
            }
        }

        fun pieceAt(offset: Long): Int =
            ti.mapFile(fileIndex, offset.coerceIn(0, fileSize - 1), 1).piece()

        fun openStream(start: Long): InputStream = PieceStream(this, start)
    }

    /**
     * Abre un magnet o un .torrent y devuelve el stream del vídeo principal.
     * Bloquea (llamar en segundo plano). [onStatus] informa del progreso.
     */
    fun open(magnet: String?, torrentBytes: ByteArray?, onStatus: (String) -> Unit): Stream {
        ensureStarted()
        val bytes = torrentBytes ?: run {
            onStatus("Buscando información del torrent…")
            session.fetchMagnet(magnet!!, 90, downloadDir)
                ?: throw IOException("No se encontraron fuentes para este enlace")
        }
        val ti = TorrentInfo(bytes)
        val files = ti.files()

        // Elegimos el vídeo más grande del torrent
        val idx = (0 until ti.numFiles())
            .filter { files.fileName(it).substringAfterLast('.', "").lowercase() in VIDEO_EXT }
            .maxByOrNull { files.fileSize(it) }
            ?: (0 until ti.numFiles()).maxBy { files.fileSize(it) }

        // Guardamos el .torrent para poder retomarlo luego
        File(torrentsDir, ti.infoHash().toHex() + ".torrent").writeBytes(bytes)

        val prios = Priority.array(Priority.IGNORE, ti.numFiles())
        prios[idx] = Priority.TOP_PRIORITY
        onStatus("Conectando…")
        session.download(ti, downloadDir, null, prios, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)

        var h: TorrentHandle? = null
        for (i in 0 until 50) {
            val found = session.find(ti.infoHash())
            if (found != null && found.isValid) { h = found; break }
            Thread.sleep(100)
        }
        if (h == null) throw IOException("No se pudo iniciar el torrent")
        h.resume()
        return Stream(h, ti, idx)
    }

    /** Para la descarga de ese torrent (los datos ya bajados se conservan). */
    fun close(stream: Stream) {
        try {
            if (stream.handle.isValid) session.remove(stream.handle)
        } catch (_: Throwable) {}
    }

    fun downloadsSize(): Long = downloadDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clearDownloads() {
        downloadDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Lee el fichero esperando a que lleguen las piezas que faltan. */
    private class PieceStream(val s: Stream, start: Long) : InputStream() {
        private var pos = start
        @Volatile private var closed = false
        private var raf: RandomAccessFile? = null

        init {
            s.prioritizeFrom(start)
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) == 1) b[0].toInt() and 0xff else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("cerrado")
            if (pos >= s.fileSize) return -1
            val req = s.ti.mapFile(s.fileIndex, pos, 1)
            val piece = req.piece()

            var waited = 0
            while (!s.handle.havePiece(piece)) {
                if (closed) throw IOException("cerrado")
                if (waited % 20 == 0) s.prioritizeFrom(pos)
                Thread.sleep(100)
                waited++
            }

            // No pasarnos del final de la pieza que sí tenemos
            val leftInPiece = (s.ti.pieceLength() - req.start()).toLong()
            val n = minOf(len.toLong(), leftInPiece, s.fileSize - pos).toInt()

            val f = raf ?: run {
                var tries = 0
                while (!s.file.exists() && tries < 50) { Thread.sleep(100); tries++ }
                RandomAccessFile(s.file, "r").also { raf = it }
            }
            f.seek(pos)
            val r = f.read(b, off, n)
            if (r > 0) pos += r
            return r
        }

        override fun close() {
            closed = true
            try { raf?.close() } catch (_: Throwable) {}
        }
    }
}

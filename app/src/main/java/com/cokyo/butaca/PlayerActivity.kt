package com.cokyo.butaca

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import fi.iki.elonen.NanoHTTPD
import java.io.File
import kotlin.concurrent.thread

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SRC = "src"   // magnet:... o ruta a un .torrent
        const val EXTRA_TITLE = "title"
        const val EXTRA_YEAR = "year"
        const val EXTRA_POSTER = "poster"
    }

    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private lateinit var btnExternal: Button

    private var player: ExoPlayer? = null
    private var stream: TorrentEngine.Stream? = null
    private var server: StreamServer? = null
    private var srcForHistory: String = ""
    private val ui = Handler(Looper.getMainLooper())
    private var destroyed = false

    private val ticker = object : Runnable {
        override fun run() {
            val s = stream ?: return
            val buffering = player?.playbackState == Player.STATE_BUFFERING || overlay.visibility == View.VISIBLE
            if (buffering) statusText.text = "Cargando… ${s.statusText()}"
            savePosition()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        btnExternal = findViewById(R.id.btnExternal)
        btnExternal.setOnClickListener { openExternal() }

        val (magnet, bytes) = readSource() ?: run {
            Toast.makeText(this, "Enlace no válido", Toast.LENGTH_LONG).show()
            finish(); return
        }

        statusText.text = "Preparando…"
        thread(name = "torrent-open") {
            try {
                val s = TorrentEngine.open(magnet, bytes) { msg -> ui.post { statusText.text = msg } }
                if (destroyed) { TorrentEngine.close(s); return@thread }
                val srv = StreamServer(s)
                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                stream = s
                server = srv
                srcForHistory = magnet ?: File(TorrentEngine.torrentsDir, s.hash + ".torrent").absolutePath
                addToLibrary(s)
                ui.post { startPlayback(s, srv) }
            } catch (e: Throwable) {
                ui.post {
                    statusText.text = "Error: ${e.message}"
                    Toast.makeText(this, "No se pudo abrir: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Devuelve (magnet, bytesDelTorrent): uno de los dos. */
    private fun readSource(): Pair<String?, ByteArray?>? {
        intent.getStringExtra(EXTRA_SRC)?.let { src ->
            return if (src.startsWith("magnet:")) src to null
            else File(src).takeIf { it.exists() }?.let { null to it.readBytes() }
        }
        val data: Uri = intent.data ?: return null
        return when (data.scheme) {
            "magnet" -> data.toString() to null
            "content", "file" -> contentResolver.openInputStream(data)?.use { null to it.readBytes() }
            else -> null
        }
    }

    private fun startPlayback(s: TorrentEngine.Stream, srv: StreamServer) {
        // Más margen de buffer porque los datos llegan de la red P2P
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 120_000, 4_000, 6_000)
            .build()
        val p = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        player = p
        playerView.player = p

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) overlay.visibility = View.GONE
                if (state == Player.STATE_BUFFERING) overlay.visibility = View.VISIBLE
            }

            override fun onPlayerError(error: PlaybackException) {
                overlay.visibility = View.VISIBLE
                statusText.text = "Este formato no se puede reproducir aquí (${error.errorCodeName}).\n" +
                        "Prueba con «Abrir en otro reproductor» (VLC)."
                btnExternal.visibility = View.VISIBLE
            }
        })

        p.setMediaItem(MediaItem.fromUri(srv.url))
        Store.find(s.hash)?.let {
            val nearEnd = it.duration > 0 && it.position > it.duration - 60_000
            if (it.position > 5_000 && !nearEnd) p.seekTo(it.position - 3_000)
        }
        p.prepare()
        p.playWhenReady = true
        btnExternal.visibility = View.VISIBLE
        ui.post(ticker)
    }

    /** Abre el mismo vídeo en VLC u otro reproductor (útil si no hay sonido o falla el códec). */
    private fun openExternal() {
        val srv = server ?: return
        val s = stream ?: return
        player?.pause()
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(srv.url), "video/*")
            .putExtra("title", s.fileName)
        try {
            startActivity(Intent.createChooser(i, "Abrir con…"))
        } catch (_: Throwable) {
            Toast.makeText(this, "Instala VLC para usar esta opción", Toast.LENGTH_LONG).show()
        }
    }

    /** Mete la peli en tu biblioteca (o la actualiza) y busca su ficha si aún no la tiene. */
    private fun addToLibrary(s: TorrentEngine.Stream) {
        val existing = Store.find(s.hash)
        val givenTitle = intent.getStringExtra(EXTRA_TITLE)
        val givenPoster = intent.getStringExtra(EXTRA_POSTER)
        val item = existing?.copy(src = srcForHistory, updated = System.currentTimeMillis())
            ?: Store.Item(
                hash = s.hash,
                title = givenTitle ?: Meta.guessTitle(s.fileName).first,
                src = srcForHistory,
                fileName = s.fileName,
                year = intent.getStringExtra(EXTRA_YEAR) ?: Meta.guessTitle(s.fileName).second,
                poster = givenPoster ?: "",
                metaDone = givenPoster != null
            )
        Store.save(item)
        if (!item.metaDone) {
            try {
                Meta.tmdb(s.fileName)?.let { info ->
                    Store.update(s.hash) {
                        it.copy(
                            title = info.title, year = info.year, overview = info.overview,
                            poster = info.poster, metaDone = true
                        )
                    }
                }
            } catch (_: Throwable) { /* sin internet o sin clave: se queda con el nombre del fichero */ }
        }
    }

    private fun savePosition() {
        val s = stream ?: return
        val p = player ?: return
        if (p.currentPosition <= 0) return
        Store.update(s.hash) {
            it.copy(
                position = p.currentPosition,
                duration = p.duration.coerceAtLeast(0),
                updated = System.currentTimeMillis()
            )
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        destroyed = true
        ui.removeCallbacksAndMessages(null)
        savePosition()
        player?.release()
        player = null
        server?.stop()
        stream?.let { TorrentEngine.close(it) }
        super.onDestroy()
    }
}

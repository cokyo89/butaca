package com.cokyo.butaca

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    /** Lo que se pinta en cada carátula. */
    data class Card(
        val title: String,
        val subtitle: String,
        val poster: String,
        val progress: Float = 0f,
        val favorite: Boolean = false,
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)? = null
    )

    private lateinit var continueList: RecyclerView
    private lateinit var continueTitle: TextView
    private lateinit var libraryGrid: RecyclerView
    private lateinit var emptyLibrary: TextView
    private lateinit var archiveList: RecyclerView
    private lateinit var spanishOnly: MaterialSwitch
    private lateinit var btnClear: Button

    private val pickTorrent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startActivity(
                Intent(this, PlayerActivity::class.java).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        continueList = findViewById(R.id.continueList)
        continueTitle = findViewById(R.id.continueTitle)
        libraryGrid = findViewById(R.id.libraryGrid)
        emptyLibrary = findViewById(R.id.emptyLibrary)
        archiveList = findViewById(R.id.archiveList)
        spanishOnly = findViewById(R.id.spanishOnly)
        btnClear = findViewById(R.id.btnClear)

        continueList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        archiveList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        libraryGrid.layoutManager = GridLayoutManager(this, 3)

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        btnClear.setOnClickListener { confirmClear() }
        spanishOnly.setOnCheckedChangeListener { _, _ -> loadArchive() }

        loadArchive()
    }

    override fun onResume() {
        super.onResume()
        refreshLibrary()
    }

    // ---------------- Biblioteca ----------------

    private fun refreshLibrary() {
        val items = Store.library()

        val watching = items.filter { it.progress in 0.02f..0.95f }.sortedByDescending { it.updated }
        continueTitle.visibility = if (watching.isEmpty()) View.GONE else View.VISIBLE
        continueList.visibility = continueTitle.visibility
        continueList.adapter = PosterAdapter(watching.map { toCard(it) }, fixedWidthDp = 115)

        val sorted = items.sortedWith(compareByDescending<Store.Item> { it.favorite }.thenByDescending { it.updated })
        emptyLibrary.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        libraryGrid.adapter = PosterAdapter(sorted.map { toCard(it) }, fixedWidthDp = null)

        val mb = TorrentEngine.downloadsSize() / (1024 * 1024)
        btnClear.text = "Liberar espacio ($mb MB descargados)"
    }

    private fun toCard(it: Store.Item) = Card(
        title = it.title,
        subtitle = it.year,
        poster = it.poster,
        progress = it.progress,
        favorite = it.favorite,
        onClick = { play(it) },
        onLongClick = { showItemMenu(it) }
    )

    private fun play(item: Store.Item) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_SRC, item.src))
    }

    private fun showItemMenu(item: Store.Item) {
        val fav = if (item.favorite) "Quitar de favoritos" else "Añadir a favoritos"
        val options = arrayOf("Reproducir", "Ver ficha", fav, "Empezar desde el principio", "Quitar de la biblioteca")
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> play(item)
                    1 -> MaterialAlertDialogBuilder(this)
                        .setTitle(listOf(item.title, item.year).filter { it.isNotEmpty() }.joinToString(" · "))
                        .setMessage(item.overview.ifEmpty { "Sin sinopsis.\n\nArchivo: ${item.fileName}" })
                        .setPositiveButton("Reproducir") { _, _ -> play(item) }
                        .setNegativeButton("Cerrar", null)
                        .show()
                    2 -> { Store.update(item.hash) { it.copy(favorite = !it.favorite) }; refreshLibrary() }
                    3 -> { Store.update(item.hash) { it.copy(position = 0) }; play(item) }
                    4 -> { Store.remove(item.hash); refreshLibrary() }
                }
            }
            .show()
    }

    // ---------------- Añadir ----------------

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = "Pega aquí un enlace magnet:"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Añadir a la biblioteca")
            .setView(box)
            .setPositiveButton("Reproducir") { _, _ ->
                val text = input.text.toString().trim()
                if (text.startsWith("magnet:")) {
                    startActivity(Intent(this, PlayerActivity::class.java).setData(Uri.parse(text)))
                } else {
                    Toast.makeText(this, "Eso no parece un enlace magnet", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Abrir archivo .torrent") { _, _ ->
                pickTorrent.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Internet Archive ----------------

    private fun loadArchive() {
        val spanish = spanishOnly.isChecked
        thread {
            val films = try { Meta.archiveFilms(spanish) } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                archiveList.adapter = PosterAdapter(films.map { f ->
                    Card(f.title, f.year, f.poster, onClick = { playArchive(f) })
                }, fixedWidthDp = 115)
            }
        }
    }

    private fun playArchive(f: Meta.ArchiveFilm) {
        Toast.makeText(this, "Abriendo «${f.title}»…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val bytes = Meta.download(f.torrentUrl)
                val tmp = File(cacheDir, "ia_${f.id}.torrent").apply { writeBytes(bytes) }
                runOnUiThread {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_SRC, tmp.absolutePath)
                            .putExtra(PlayerActivity.EXTRA_TITLE, f.title)
                            .putExtra(PlayerActivity.EXTRA_YEAR, f.year)
                            .putExtra(PlayerActivity.EXTRA_POSTER, f.poster)
                    )
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "No se pudo abrir: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------------- Ajustes ----------------

    private fun showSettings() {
        val input = EditText(this).apply {
            hint = "Clave API de TMDB (v3)"
            setText(Store.tmdbKey)
            isSingleLine = true
        }
        val info = TextView(this).apply {
            text = "Con una clave gratuita de themoviedb.org la biblioteca muestra carátula, año y sinopsis en español. " +
                    "Créala en themoviedb.org → Ajustes → API."
            setTextColor(getColor(R.color.muted))
            setPadding(0, 0, 0, 16)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(info); addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ajustes")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                Store.tmdbKey = input.text.toString()
                refreshMissingMeta()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Si acabas de poner la clave, completa las fichas que faltaban. */
    private fun refreshMissingMeta() {
        thread {
            Store.library().filter { !it.metaDone && it.fileName.isNotEmpty() }.forEach { item ->
                try {
                    Meta.tmdb(item.fileName)?.let { m ->
                        Store.update(item.hash) {
                            it.copy(title = m.title, year = m.year, overview = m.overview, poster = m.poster, metaDone = true)
                        }
                    }
                } catch (_: Throwable) {}
            }
            runOnUiThread { refreshLibrary() }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Liberar espacio")
            .setMessage("Se borran los vídeos descargados. Tu biblioteca y por dónde ibas se conservan; al volver a reproducir, se descarga de nuevo.")
            .setPositiveButton("Borrar") { _, _ -> TorrentEngine.clearDownloads(); refreshLibrary() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Adaptador de carátulas ----------------

    private inner class PosterAdapter(
        private val cards: List<Card>,
        private val fixedWidthDp: Int?
    ) : RecyclerView.Adapter<PosterAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val placeholder: TextView = v.findViewById(R.id.placeholder)
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val progress: ProgressBar = v.findViewById(R.id.progress)
            val fav: TextView = v.findViewById(R.id.fav)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
            if (fixedWidthDp != null) {
                v.layoutParams = v.layoutParams.apply {
                    width = (fixedWidthDp * resources.displayMetrics.density).toInt()
                }
            }
            return VH(v)
        }

        override fun getItemCount() = cards.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val c = cards[i]
            h.title.text = c.title
            h.subtitle.text = c.subtitle
            h.placeholder.text = c.title
            h.poster.setImageDrawable(null)
            if (c.poster.isNotEmpty()) h.poster.load(c.poster) { crossfade(true) }
            h.fav.visibility = if (c.favorite) View.VISIBLE else View.GONE
            if (c.progress > 0.01f) {
                h.progress.visibility = View.VISIBLE
                h.progress.progress = (c.progress * 1000).toInt()
            } else {
                h.progress.visibility = View.GONE
            }
            h.itemView.setOnClickListener { c.onClick() }
            h.itemView.setOnLongClickListener { c.onLongClick?.invoke(); c.onLongClick != null }
        }
    }
}

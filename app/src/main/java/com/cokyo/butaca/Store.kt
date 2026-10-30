package com.cokyo.butaca

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Tu biblioteca: todo lo que abres queda guardado aquí, con su ficha y por dónde ibas. */
object Store {

    data class Item(
        val hash: String,
        val title: String,
        val src: String,             // magnet:... o ruta a un .torrent guardado
        val fileName: String = "",
        val year: String = "",
        val overview: String = "",
        val poster: String = "",
        val position: Long = 0,
        val duration: Long = 0,
        val favorite: Boolean = false,
        val metaDone: Boolean = false,
        val updated: Long = System.currentTimeMillis()
    ) {
        val progress: Float get() = if (duration > 0) position.toFloat() / duration else 0f

        fun toJson(): JSONObject = JSONObject()
            .put("hash", hash).put("title", title).put("src", src).put("fileName", fileName)
            .put("year", year).put("overview", overview).put("poster", poster)
            .put("position", position).put("duration", duration)
            .put("favorite", favorite).put("metaDone", metaDone).put("updated", updated)

        companion object {
            fun from(o: JSONObject) = Item(
                o.getString("hash"), o.getString("title"), o.getString("src"),
                o.optString("fileName"), o.optString("year"), o.optString("overview"),
                o.optString("poster"), o.optLong("position"), o.optLong("duration"),
                o.optBoolean("favorite"), o.optBoolean("metaDone"), o.optLong("updated")
            )
        }
    }

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("butaca", Context.MODE_PRIVATE)
    }

    var tmdbKey: String
        get() = prefs.getString("tmdbKey", "") ?: ""
        set(v) = prefs.edit().putString("tmdbKey", v.trim()).apply()

    @Synchronized
    fun library(): List<Item> {
        val arr = JSONArray(prefs.getString("library", "[]"))
        return (0 until arr.length()).map { Item.from(arr.getJSONObject(it)) }
    }

    fun find(hash: String): Item? = library().firstOrNull { it.hash == hash }

    @Synchronized
    fun save(item: Item) = write(library().filter { it.hash != item.hash } + item)

    /** Modifica una ficha existente (si no existe, no hace nada). */
    @Synchronized
    fun update(hash: String, change: (Item) -> Item) {
        val cur = find(hash) ?: return
        save(change(cur))
    }

    @Synchronized
    fun remove(hash: String) = write(library().filter { it.hash != hash })

    private fun write(list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("library", arr.toString()).apply()
    }
}

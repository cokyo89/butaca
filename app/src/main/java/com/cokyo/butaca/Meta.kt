package com.cokyo.butaca

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Fichas de pelis (TMDB) y catálogo legal de Internet Archive. */
object Meta {

    data class Info(val title: String, val year: String, val overview: String, val poster: String)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Butaca/1.0 (Android)")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** "La.Peli.2019.1080p.x264-GRUPO.mkv" -> ("La Peli", "2019") */
    fun guessTitle(fileName: String): Pair<String, String> {
        var name = fileName.substringBeforeLast('.')
            .replace(Regex("[._]"), " ")
            .replace(Regex("\\[.*?]|\\(.*?\\)"), " ")
        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(name)?.value ?: ""
        if (year.isNotEmpty()) name = name.substringBefore(year)
        val junk = Regex(
            "\\b(1080p|720p|2160p|4k|uhd|bluray|brrip|bdrip|webrip|web-dl|webdl|hdtv|dvdrip|x264|x265|h264|h265|hevc|" +
                    "aac|ac3|dts|castellano|spanish|español|latino|dual|subs?|micro|hdr|proper|repack|extended)\\b.*",
            RegexOption.IGNORE_CASE
        )
        name = name.replace(junk, "").replace(Regex("\\s+"), " ").trim()
        return name.ifEmpty { fileName } to year
    }

    /** Busca la ficha en TMDB, en español de España. Necesita tu clave gratuita de TMDB. */
    fun tmdb(fileName: String): Info? {
        val key = Store.tmdbKey
        if (key.isEmpty()) return null
        val (q, year) = guessTitle(fileName)
        val enc = URLEncoder.encode(q, "UTF-8")
        val yearParam = if (year.isNotEmpty()) "&year=$year" else ""
        for (kind in listOf("movie", "tv")) {
            val y = if (kind == "movie") yearParam else ""
            val json = JSONObject(
                get("https://api.themoviedb.org/3/search/$kind?api_key=$key&language=es-ES&query=$enc$y")
            )
            val r = json.optJSONArray("results")?.optJSONObject(0) ?: continue
            val title = r.optString("title").ifEmpty { r.optString("name") }
            val date = r.optString("release_date").ifEmpty { r.optString("first_air_date") }
            val poster = r.optString("poster_path").takeIf { it.isNotEmpty() && it != "null" }
                ?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
            return Info(title, date.take(4), r.optString("overview"), poster)
        }
        return null
    }

    // ---------- Internet Archive (cine de dominio público, legal) ----------

    data class ArchiveFilm(val id: String, val title: String, val year: String) {
        val poster get() = "https://archive.org/services/img/$id"
        val torrentUrl get() = "https://archive.org/download/$id/${id}_archive.torrent"
    }

    fun archiveFilms(spanishOnly: Boolean): List<ArchiveFilm> {
        val q = buildString {
            append("collection:(feature_films) AND mediatype:(movies)")
            if (spanishOnly) append(" AND language:(spa OR Spanish OR español)")
        }
        val url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, "UTF-8") +
                "&fl[]=identifier&fl[]=title&fl[]=year&sort[]=downloads+desc&rows=60&output=json"
        val docs = JSONObject(get(url)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map {
            val d = docs.getJSONObject(it)
            ArchiveFilm(d.getString("identifier"), d.optString("title"), d.optString("year"))
        }
    }

    fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Butaca/1.0 (Android)")
        return c.inputStream.use { it.readBytes() }
    }
}

package com.eddyizm.tempus.util

import com.eddyizm.tempus.App
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Piterfy: „ostatnio znane” listy albumów strony głównej. Zapisywane na dysku po każdym udanym pobraniu, żeby przy
 * następnym wejściu półki pokazały się od razu (zamiast pustej strony i kręcącego się kółka), a odświeżenie z serwera
 * podmieniło je po chwili. Klucz zawiera serwer i wybraną bibliotekę, więc po przełączeniu biblioteki nie pokaże się cudza lista.
 */
object PiterfyLastKnown {
    private const val DIR = "piterfy_last_known"
    private const val MAX_ITEMS = 40
    private val type = object : TypeToken<List<AlbumID3>>() {}.type

    /** Nazwa pliku dla listy [key] z danego serwera i biblioteki (znaki spoza liter, cyfr i „-_” zamieniane na „_”). */
    @JvmStatic
    fun fileName(server: String?, folder: String?, key: String): String =
        listOf(server ?: "", folder ?: "", key).joinToString("__") { it.replace(Regex("[^A-Za-z0-9_-]"), "_") } + ".json"

    private fun file(key: String): File? = try {
        File(File(App.getInstance().filesDir, DIR), fileName(Preferences.getServerId(), Preferences.getActiveMusicFolderId(), key))
    } catch (e: RuntimeException) { null }

    /** Ostatnio zapisana lista albumów albo null (brak pliku, uszkodzony plik, brak aplikacji). */
    @JvmStatic
    fun albums(key: String): List<AlbumID3>? {
        val f = file(key) ?: return null
        if (!f.isFile) return null
        return try {
            f.readText().let { Gson().fromJson<List<AlbumID3>>(it, type) }?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }   // uszkodzony JSON to po prostu brak pamięci podręcznej
    }

    /** Zapisuje listę (pusta nie nadpisuje poprzedniej). Błędy zapisu są ignorowane: to tylko przyspieszenie. */
    @JvmStatic
    fun saveAlbums(key: String, albums: List<AlbumID3>?) {
        if (albums.isNullOrEmpty()) return
        val f = file(key) ?: return
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(Gson().toJson(albums.take(MAX_ITEMS), type))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { /* brak miejsca albo praw: bez pamięci podręcznej */ }
    }
}

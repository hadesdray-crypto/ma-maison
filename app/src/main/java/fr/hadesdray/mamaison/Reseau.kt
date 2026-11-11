package fr.hadesdray.mamaison

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Petits outils réseau (tout se fait hors du fil de l'écran). */
object Reseau {

    fun encode(t: String): String = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

    /** Renvoie le texte, ou null si ça ne répond pas. */
    fun lire(adresse: String, delai: Int = 5000, entetes: Map<String, String> = emptyMap()): String? = try {
        val c = URL(adresse).openConnection() as HttpURLConnection
        c.connectTimeout = delai
        c.readTimeout = delai
        for ((k, v) in entetes) c.setRequestProperty(k, v)
        val r = if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        c.disconnect()
        r
    } catch (e: Exception) {
        null
    }

    /** Envoie du JSON ; renvoie le code de réponse (0 si pas de réponse). */
    fun envoyer(adresse: String, corps: String, entetes: Map<String, String> = emptyMap(), delai: Int = 8000): Int = try {
        val c = URL(adresse).openConnection() as HttpURLConnection
        c.connectTimeout = delai
        c.readTimeout = delai
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in entetes) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        try { (if (code < 400) c.inputStream else c.errorStream)?.close() } catch (e: Exception) { }
        c.disconnect()
        code
    } catch (e: Exception) {
        0
    }

    fun octets(adresse: String, delai: Int = 8000, entetes: Map<String, String> = emptyMap()): ByteArray? = try {
        val c = URL(adresse).openConnection() as HttpURLConnection
        c.connectTimeout = delai
        c.readTimeout = delai
        for ((k, v) in entetes) c.setRequestProperty(k, v)
        val r = if (c.responseCode in 200..299) c.inputStream.readBytes() else null
        c.disconnect()
        r
    } catch (e: Exception) {
        null
    }
}

package fr.hadesdray.mamaison

import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Trouve l'adresse du flux vidéo d'une caméra du réseau : on essaie les adresses habituelles
 * des grandes marques, avec l'identifiant de la caméra, jusqu'à ce que la caméra accepte.
 */
object Rtsp {

    private val CHEMINS = listOf(
        "/stream1", "/live", "/h264", "/11", "/12", "/live/ch00_0", "/onvif1", "/videoMain", "/media/video1",
        "/cam/realmonitor?channel=1&subtype=0", "/Streaming/Channels/101", "/h264Preview_01_main",
        "/live/main", "/mpeg4", "/unicast", "/av0_0", "/ch0_0.h264", "/video1", "/1", "/"
    )

    class Resultat(val lien: String?, val raison: String)

    /** À appeler hors du fil de l'écran. */
    fun trouver(ip: String, port: Int, utilisateur: String, mdp: String): Resultat {
        var refuse = false
        for (ch in CHEMINS) {
            val url = "rtsp://$ip:$port$ch"
            when (decrire(url, utilisateur, mdp)) {
                200 -> {
                    val id = if (utilisateur.isEmpty()) "" else enc(utilisateur) + ":" + enc(mdp) + "@"
                    return Resultat("rtsp://$id$ip:$port$ch", "")
                }
                401 -> refuse = true
                -1 -> return Resultat(null, "la caméra ne répond pas")
            }
        }
        return Resultat(null, if (refuse) "identifiant ou mot de passe de la caméra refusé" else "adresse du flux introuvable pour cette marque de caméra")
    }

    private fun enc(t: String) = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

    private fun md5(t: String) = MessageDigest.getInstance("MD5").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Envoie DESCRIBE (avec identification simple ou « digest » si la caméra la demande). Renvoie le code, -1 sans réponse. */
    private fun decrire(url: String, u: String, p: String): Int {
        val hote = url.removePrefix("rtsp://").substringBefore("/")
        val ip = hote.substringBefore(":")
        val port = hote.substringAfter(":", "554").toIntOrNull() ?: 554
        var auth: String? = null
        for (essai in 0..1) {
            val (code, entetes) = try { envoyer(ip, port, url, essai + 1, auth) } catch (e: Exception) { return -1 }
            if (code != 401 || u.isEmpty() || essai == 1) return code
            val defi = entetes["www-authenticate"] ?: return code
            auth = if (defi.startsWith("Digest", true)) {
                val realm = Regex("realm=\"([^\"]*)\"").find(defi)?.groupValues?.get(1) ?: ""
                val nonce = Regex("nonce=\"([^\"]*)\"").find(defi)?.groupValues?.get(1) ?: ""
                val rep = md5(md5("$u:$realm:$p") + ":" + nonce + ":" + md5("DESCRIBE:$url"))
                "Digest username=\"$u\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$url\", response=\"$rep\""
            } else "Basic " + Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
        }
        return 401
    }

    private fun envoyer(ip: String, port: Int, url: String, seq: Int, auth: String?): Pair<Int, Map<String, String>> {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 2500)
            s.soTimeout = 3000
            val d = StringBuilder("DESCRIBE $url RTSP/1.0\r\nCSeq: $seq\r\nAccept: application/sdp\r\nUser-Agent: MaMaison\r\n")
            if (auth != null) d.append("Authorization: $auth\r\n")
            d.append("\r\n")
            s.getOutputStream().write(d.toString().toByteArray())
            val r = s.getInputStream().bufferedReader()
            val premiere = r.readLine() ?: return Pair(-1, emptyMap())
            val code = premiere.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            val e = HashMap<String, String>()
            while (true) {
                val l = r.readLine() ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) {
                    val k = l.substring(0, i).trim().lowercase()
                    val v = l.substring(i + 1).trim()
                    // Plusieurs défis possibles : on préfère « Digest »
                    if (k != "www-authenticate" || e[k] == null || v.startsWith("Digest", true)) e[k] = v
                }
            }
            return Pair(code, e)
        }
    }
}

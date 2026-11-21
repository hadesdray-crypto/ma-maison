package fr.hadesdray.mamaison

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Tout petit client MQTT (version 3.1, celle des appareils Dyson), sans bibliothèque en plus.
 * Une connexion courte par action : on se connecte, on fait, on se déconnecte.
 */
class Mqtt private constructor(private val s: Socket) {

    private val entree: InputStream = s.getInputStream()
    private val sortie: OutputStream = s.getOutputStream()
    private var numero = 1

    companion object {
        /** Résultat de la connexion : 0 = accepté, 4/5 = clé refusée, -1 = injoignable. */
        fun connecter(hote: String, port: Int, utilisateur: String, motDePasse: String, delai: Int = 4000): Pair<Int, Mqtt?> {
            val s = Socket()
            return try {
                s.connect(InetSocketAddress(hote, port), delai)
                s.soTimeout = delai
                val m = Mqtt(s)
                val rc = m.envoyerConnexion(utilisateur, motDePasse)
                if (rc == 0) Pair(0, m) else { m.fermer(); Pair(rc, null) }
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) { }
                Pair(-1, null)
            }
        }

        /** Le port répond-il ? (balayage rapide du réseau) */
        fun portOuvert(hote: String, port: Int, delai: Int): Boolean {
            val s = Socket()
            return try { s.connect(InetSocketAddress(hote, port), delai); true } catch (e: Exception) { false } finally { try { s.close() } catch (_: Exception) { } }
        }
    }

    private fun chaine(o: ByteArrayOutputStream, t: String) {
        val b = t.toByteArray(Charsets.UTF_8)
        o.write(b.size shr 8 and 0xff); o.write(b.size and 0xff); o.write(b)
    }

    private fun paquet(entete: Int, corps: ByteArray) {
        val o = ByteArrayOutputStream()
        o.write(entete)
        var reste = corps.size
        do {
            var octet = reste % 128
            reste /= 128
            if (reste > 0) octet = octet or 0x80
            o.write(octet)
        } while (reste > 0)
        o.write(corps)
        sortie.write(o.toByteArray())
        sortie.flush()
    }

    private fun lireOctet(): Int {
        val b = entree.read()
        if (b < 0) throw java.io.EOFException()
        return b
    }

    /** Lit un paquet : (type, corps). */
    private fun lirePaquet(): Pair<Int, ByteArray> {
        val entete = lireOctet()
        var mult = 1
        var longueur = 0
        var tours = 0
        do {
            val b = lireOctet()
            longueur += (b and 0x7f) * mult
            mult *= 128
            tours++
        } while ((b and 0x80) != 0 && tours < 4)
        val corps = ByteArray(longueur)
        var lu = 0
        while (lu < longueur) {
            val n = entree.read(corps, lu, longueur - lu)
            if (n < 0) throw java.io.EOFException()
            lu += n
        }
        return Pair(entete, corps)
    }

    private fun envoyerConnexion(utilisateur: String, motDePasse: String): Int {
        val o = ByteArrayOutputStream()
        chaine(o, "MQIsdp")
        o.write(3)                 // version 3.1
        o.write(0xC2)              // nom + mot de passe + session neuve
        o.write(0); o.write(30)    // 30 secondes
        chaine(o, ("maison" + System.currentTimeMillis()).take(23))
        chaine(o, utilisateur)
        chaine(o, motDePasse)
        paquet(0x10, o.toByteArray())
        val (type, corps) = lirePaquet()
        if ((type and 0xF0) != 0x20 || corps.size < 2) return -1
        return corps[1].toInt() and 0xff
    }

    fun abonner(sujet: String) {
        val o = ByteArrayOutputStream()
        val n = numero++
        o.write(n shr 8 and 0xff); o.write(n and 0xff)
        chaine(o, sujet)
        o.write(0)
        paquet(0x82, o.toByteArray())
    }

    fun publier(sujet: String, message: String, qos1: Boolean = false) {
        val o = ByteArrayOutputStream()
        chaine(o, sujet)
        if (qos1) { val n = numero++; o.write(n shr 8 and 0xff); o.write(n and 0xff) }
        o.write(message.toByteArray(Charsets.UTF_8))
        paquet(if (qos1) 0x32 else 0x30, o.toByteArray())
    }

    /**
     * Écoute les messages jusqu'à ce que [assez] réponde vrai ou que le temps soit écoulé.
     * Renvoie la liste (sujet, texte).
     */
    fun ecouter(dureeMs: Long, assez: (List<Pair<String, String>>) -> Boolean): List<Pair<String, String>> {
        val recus = ArrayList<Pair<String, String>>()
        val fin = System.currentTimeMillis() + dureeMs
        while (System.currentTimeMillis() < fin) {
            try {
                s.soTimeout = (fin - System.currentTimeMillis()).coerceIn(200, 4000).toInt()
                val (entete, corps) = lirePaquet()
                if ((entete and 0xF0) == 0x30 && corps.size >= 2) {
                    val qos = (entete shr 1) and 3
                    val lg = ((corps[0].toInt() and 0xff) shl 8) or (corps[1].toInt() and 0xff)
                    if (2 + lg > corps.size) continue
                    val sujet = String(corps, 2, lg, Charsets.UTF_8)
                    var debut = 2 + lg
                    if (qos > 0 && debut + 2 <= corps.size) {
                        // accusé de réception
                        paquet(0x40, byteArrayOf(corps[debut], corps[debut + 1]))
                        debut += 2
                    }
                    recus.add(Pair(sujet, String(corps, debut, corps.size - debut, Charsets.UTF_8)))
                    if (assez(recus)) break
                }
            } catch (e: SocketTimeoutException) {
                // on continue jusqu'à la fin du temps
            } catch (e: Exception) {
                break
            }
        }
        return recus
    }

    fun fermer() {
        try { paquet(0xE0, ByteArray(0)) } catch (_: Exception) { }
        try { s.close() } catch (_: Exception) { }
    }
}

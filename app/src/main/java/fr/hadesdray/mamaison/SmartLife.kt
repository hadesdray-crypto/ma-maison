package fr.hadesdray.mamaison

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Appareils Smart Life (Tuya), par le service officiel de Tuya pour les particuliers.
 * La personne relie une fois son compte Smart Life à son propre projet gratuit
 * (identifiant + secret), que l'appli garde seulement sur cet appareil.
 */
class SmartLife(ctx: Context) {

    companion object {
        val REGIONS = linkedMapOf(
            "Europe" to "openapi.tuyaeu.com",
            "Europe de l'Ouest" to "openapi-weaz.tuyaeu.com",
            "Amérique" to "openapi.tuyaus.com",
            "Chine" to "openapi.tuyacn.com",
            "Inde" to "openapi.tuyain.com"
        )
        @Volatile private var jeton: String? = null
        @Volatile private var finJeton = 0L
        /** Dernier état connu de chaque appareil : identifiant Tuya -> état brut. */
        private val etats = HashMap<String, JSONArray>()
    }

    private val prefs = ctx.getSharedPreferences("maison", Context.MODE_PRIVATE)

    var identifiant: String
        get() = prefs.getString("tuya_id", "") ?: ""
        set(v) { prefs.edit().putString("tuya_id", v.trim()).apply(); jeton = null }
    var secret: String
        get() = prefs.getString("tuya_secret", "") ?: ""
        set(v) { prefs.edit().putString("tuya_secret", v.trim()).apply(); jeton = null }
    var hote: String
        get() = prefs.getString("tuya_hote", "openapi.tuyaeu.com") ?: "openapi.tuyaeu.com"
        set(v) { prefs.edit().putString("tuya_hote", v).apply(); jeton = null }

    val pret: Boolean get() = identifiant.isNotBlank() && secret.isNotBlank()

    /** Dernière erreur lisible, pour l'afficher à la personne. */
    var erreur: String = ""
        private set

    private fun sha256(t: String): String =
        MessageDigest.getInstance("SHA-256").digest(t.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Signature officielle de Tuya (HMAC-SHA256, en majuscules). */
    fun signer(cle: String, secretCle: String, jetonAcces: String, temps: String, methode: String, chemin: String, corps: String): String {
        val aSigner = methode + "\n" + sha256(corps) + "\n" + "\n" + chemin
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretCle.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal((cle + jetonAcces + temps + aSigner).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
    }

    private fun requete(methode: String, chemin: String, corps: String = "", avecJeton: Boolean = true): JSONObject? {
        val t = System.currentTimeMillis().toString()
        val j = if (avecJeton) (jeton ?: "") else ""
        val signature = signer(identifiant, secret, j, t, methode, chemin, corps)
        return try {
            val c = URL("https://$hote$chemin").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.requestMethod = methode
            c.setRequestProperty("client_id", identifiant)
            c.setRequestProperty("sign", signature)
            c.setRequestProperty("t", t)
            c.setRequestProperty("sign_method", "HMAC-SHA256")
            if (j.isNotEmpty()) c.setRequestProperty("access_token", j)
            if (corps.isNotEmpty()) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
            }
            val flux = if (c.responseCode < 400) c.inputStream else c.errorStream
            val texte = flux?.bufferedReader()?.readText() ?: ""
            c.disconnect()
            JSONObject(texte)
        } catch (e: Exception) {
            erreur = "Pas de connexion à Smart Life (Internet coupé ?)."
            null
        }
    }

    private fun connecter(): Boolean {
        if (!pret) return false
        if (jeton != null && System.currentTimeMillis() < finJeton) return true
        val r = requete("GET", "/v1.0/token?grant_type=1", avecJeton = false) ?: return false
        if (!r.optBoolean("success")) {
            erreur = expliquer(r)
            return false
        }
        val res = r.optJSONObject("result") ?: return false
        jeton = res.optString("access_token")
        finJeton = System.currentTimeMillis() + (res.optLong("expire_time", 3600) - 120) * 1000
        return true
    }

    private fun expliquer(r: JSONObject): String {
        val code = r.optInt("code")
        val msg = r.optString("msg")
        return when (code) {
            1004 -> "Signature refusée : vérifie l'identifiant et le secret."
            1106 -> "Accès refusé : le compte Smart Life n'est pas relié au projet."
            1010, 1011 -> "Connexion expirée, nouvel essai nécessaire."
            28841002, 28841105 -> "L'essai gratuit du service Tuya est terminé : il faut le prolonger sur le site Tuya (gratuit)."
            2406 -> "Le projet Tuya n'est pas dans la bonne région."
            else -> "Smart Life a répondu : $msg ($code)"
        }
    }

    /** Liste des appareils Smart Life, convertis pour l'appli. Hors du fil de l'écran. */
    fun appareils(): List<Appareil>? {
        if (!connecter()) return null
        val l = ArrayList<Appareil>()
        var cleSuivante = ""
        var tours = 0
        do {
            val q = "/v1.0/iot-01/associated-users/devices?" +
                (if (cleSuivante.isNotEmpty()) "last_row_key=" + URLEncoder.encode(cleSuivante, "UTF-8") + "&" else "") + "size=50"
            val r = requete("GET", q) ?: return null
            if (!r.optBoolean("success")) {
                erreur = expliquer(r)
                if (r.optInt("code") in setOf(1010, 1011)) jeton = null
                return null
            }
            val res = r.optJSONObject("result") ?: break
            val tab = res.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until tab.length()) convertir(tab.getJSONObject(i))?.let { l.add(it) }
            cleSuivante = res.optString("last_row_key", "")
            tours++
        } while (res.optBoolean("has_more") && tours < 20)
        return l.sortedBy { it.nom.lowercase() }
    }

    private fun domaine(categorie: String): String = when (categorie) {
        "dj", "dd", "fwd", "xdd", "dc", "tyndj", "fsd", "fwl", "tgq", "tgkg", "sxd", "gyd", "mbd", "hxd" -> "light"
        "cz", "pc", "kg", "tdq", "wkcz", "ckmkzq", "dlq", "znjdq" -> "switch"
        "cl", "clkg", "mc", "jdcljqr" -> "cover"
        "ms", "jtmspro", "jtmsbh" -> "lock"
        "sp", "dghsxj", "sxt" -> "camera"
        "wsdcg", "co2bj", "pm2.5", "pm25", "hjjcy", "ywbj", "rqbj", "sj", "ldcg", "zd", "wsdzcg" -> "sensor"
        "mcs", "pir", "hps", "sgbj", "jwbj", "cobj" -> "binary_sensor"
        "fs", "fsd2" -> "fan"
        "kt", "wk", "wkf", "qn", "rs" -> "climate"
        "zndb", "dlq2" -> "sensor"
        else -> "switch"
    }

    private fun convertir(o: JSONObject): Appareil? {
        val id = o.optString("id")
        if (id.isEmpty()) return null
        val cat = o.optString("category")
        val dom = domaine(cat)
        val statut = o.optJSONArray("status") ?: JSONArray()
        synchronized(etats) { etats[id] = statut }
        val attributs = JSONObject().put("tuya_id", id).put("categorie", cat).put("en_ligne", o.optBoolean("online"))
        var etat = if (o.optBoolean("online")) "off" else "unavailable"
        var unite = ""
        var genre = ""
        for (i in 0 until statut.length()) {
            val s = statut.optJSONObject(i) ?: continue
            val code = s.optString("code")
            val v = s.opt("value")
            when {
                code in setOf("switch_led", "switch_1", "switch", "switch_on") && v is Boolean && o.optBoolean("online") ->
                    if (dom in setOf("light", "switch", "fan", "climate")) etat = if (v) "on" else "off"
                code == "control" && dom == "cover" -> etat = when (v) { "open" -> "open"; "close" -> "closed"; else -> "open" }
                code == "percent_state" && dom == "cover" -> etat = if ((v as? Number)?.toInt() ?: 0 > 0) "open" else "closed"
                (code == "va_temperature" || code == "temp_current") && dom == "sensor" -> {
                    val n = (v as? Number)?.toDouble() ?: 0.0
                    etat = (if (n > 100) n / 10 else n).toString(); unite = "°C"; genre = "temperature"
                }
                (code == "va_humidity" || code == "humidity_value") && dom == "sensor" && genre.isEmpty() -> {
                    etat = v.toString(); unite = "%"; genre = "humidity"
                }
                code == "co2_value" && dom == "sensor" -> { etat = v.toString(); unite = "ppm"; genre = "carbon_dioxide" }
                code == "pm25_value" && dom == "sensor" -> { etat = v.toString(); unite = "µg/m³"; genre = "pm25" }
                code == "cur_power" -> attributs.put("puissance", ((v as? Number)?.toDouble() ?: 0.0) / 10)
                code == "doorcontact_state" -> { etat = if (v == true) "on" else "off"; genre = "door" }
                code == "pir" -> { etat = if (v == "pir") "on" else "off"; genre = "motion" }
            }
        }
        if (!o.optBoolean("online")) etat = "unavailable"
        return Appareil("$dom.tuya_$id", o.optString("name", id), etat, unite, genre, attributs)
    }

    fun estSmartLife(a: Appareil) = a.attributs.has("tuya_id")

    private fun commander(idTuya: String, code: String, valeur: Any): Boolean {
        if (!connecter()) return false
        val corps = JSONObject().put("commands", JSONArray().put(JSONObject().put("code", code).put("value", valeur))).toString()
        val r = requete("POST", "/v1.0/devices/$idTuya/commands", corps) ?: return false
        if (!r.optBoolean("success")) erreur = expliquer(r)
        return r.optBoolean("success")
    }

    private fun codeInterrupteur(idTuya: String): String? {
        val st = synchronized(etats) { etats[idTuya] } ?: return null
        for (c in listOf("switch_led", "switch_1", "switch", "switch_on")) {
            for (i in 0 until st.length()) {
                val s = st.optJSONObject(i) ?: continue
                if (s.optString("code") == c && s.opt("value") is Boolean) return c
            }
        }
        return null
    }

    /** allumer : true = allumer/ouvrir, false = éteindre/fermer, null = inverser. */
    fun agir(a: Appareil, allumer: Boolean?): Boolean {
        val idTuya = a.attributs.optString("tuya_id")
        if (a.domaine == "cover") {
            val ouvrir = allumer ?: (a.etat != "open")
            return commander(idTuya, "control", if (ouvrir) "open" else "close")
        }
        val code = codeInterrupteur(idTuya) ?: run { erreur = "Cet appareil ne s'allume pas depuis l'appli."; return false }
        return commander(idTuya, code, allumer ?: (a.etat != "on"))
    }
}

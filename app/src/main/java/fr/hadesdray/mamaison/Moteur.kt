package fr.hadesdray.mamaison

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un appareil de la maison, tel que le moteur le décrit. */
data class Appareil(
    val id: String,
    val nom: String,
    val etat: String,
    val unite: String,
    val genre: String,
    val attributs: JSONObject
) {
    val domaine: String get() = id.substringBefore('.')
    val allume: Boolean get() = etat == "on" || etat == "open" || etat == "playing" || etat == "unlocked" || etat == "home"
}

/**
 * Le moteur caché de la maison (sur le PC) : il connaît les marques et modèles.
 * L'appli lui parle en local ; tant qu'il n'est pas installé, les modules attendent.
 */
class Moteur(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("maison", Context.MODE_PRIVATE)

    var adresse: String
        get() = prefs.getString("moteur_adresse", "") ?: ""
        set(v) = prefs.edit().putString("moteur_adresse", v.trim().trimEnd('/')).apply()
    var jeton: String
        get() = prefs.getString("moteur_jeton", "") ?: ""
        set(v) = prefs.edit().putString("moteur_jeton", v.trim()).apply()

    val pret: Boolean get() = adresse.isNotBlank() && jeton.isNotBlank()

    private fun entetes() = mapOf("Authorization" to "Bearer $jeton")

    /** Récupère adresse et jeton depuis la passerelle du PC (hors du fil de l'écran). */
    fun recupererDepuisPasserelle(base: String?) {
        if (base == null) return
        val t = Reseau.lire("$base/moteur", 3000) ?: return
        try {
            val o = JSONObject(t)
            val a = o.optString("adresse")
            val j = o.optString("jeton")
            if (a.isNotBlank() && j.isNotBlank()) {
                adresse = a
                jeton = j
            }
        } catch (e: Exception) { }
    }

    fun repond(): Boolean = pret && Reseau.lire("$adresse/api/", 3000, entetes()) != null

    fun appareils(): List<Appareil>? {
        if (!pret) return null
        val t = Reseau.lire("$adresse/api/states", 8000, entetes()) ?: return null
        return try {
            val tab = JSONArray(t)
            val l = ArrayList<Appareil>()
            for (i in 0 until tab.length()) {
                val o = tab.getJSONObject(i)
                val at = o.optJSONObject("attributes") ?: JSONObject()
                val id = o.optString("entity_id")
                l.add(
                    Appareil(
                        id,
                        at.optString("friendly_name", id),
                        o.optString("state"),
                        at.optString("unit_of_measurement", ""),
                        at.optString("device_class", ""),
                        at
                    )
                )
            }
            l.sortedBy { it.nom.lowercase() }
        } catch (e: Exception) {
            null
        }
    }

    fun service(domaine: String, service: String, donnees: JSONObject): Boolean =
        Reseau.envoyer("$adresse/api/services/$domaine/$service", donnees.toString(), entetes()) in 200..299

    fun basculer(a: Appareil): Boolean {
        val d = JSONObject().put("entity_id", a.id)
        return when (a.domaine) {
            "cover" -> service("cover", if (a.etat == "open") "close_cover" else "open_cover", d)
            "lock" -> service("lock", if (a.etat == "locked") "unlock" else "lock", d)
            "media_player" -> service("media_player", "media_play_pause", d)
            "scene", "script" -> service(a.domaine, "turn_on", d)
            "button" -> service("button", "press", d)
            else -> service("homeassistant", "toggle", d)
        }
    }

    fun image(a: Appareil): ByteArray? = Reseau.octets("$adresse/api/camera_proxy/${a.id}", 8000, entetes())

    /** Crée un planning : à l'heure dite, chaque jour (ou les jours choisis), on agit sur un appareil. */
    fun creerPlanning(nom: String, heure: String, jours: List<String>, cible: Appareil, allumer: Boolean): Boolean {
        val ident = "maison_" + System.currentTimeMillis()
        val declencheur = JSONObject().put("platform", "time").put("at", "$heure:00")
        val conditions = JSONArray()
        if (jours.isNotEmpty() && jours.size < 7) {
            conditions.put(JSONObject().put("condition", "time").put("weekday", JSONArray(jours)))
        }
        val service = when (cible.domaine) {
            "cover" -> if (allumer) "cover.open_cover" else "cover.close_cover"
            "lock" -> if (allumer) "lock.unlock" else "lock.lock"
            "scene", "script" -> cible.domaine + ".turn_on"
            else -> if (allumer) "homeassistant.turn_on" else "homeassistant.turn_off"
        }
        val action = JSONObject().put("service", service).put("target", JSONObject().put("entity_id", cible.id))
        val corps = JSONObject()
            .put("alias", nom)
            .put("description", "Créé par l'appli Ma Maison")
            .put("trigger", JSONArray().put(declencheur))
            .put("condition", conditions)
            .put("action", JSONArray().put(action))
            .put("mode", "single")
        val ok = Reseau.envoyer("$adresse/api/config/automation/config/$ident", corps.toString(), entetes()) in 200..299
        if (ok) service("automation", "reload", JSONObject())
        return ok
    }
}

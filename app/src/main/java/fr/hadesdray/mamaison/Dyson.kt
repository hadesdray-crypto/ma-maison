package fr.hadesdray.mamaison

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Un appareil Dyson relié à Ma Maison. */
data class DysonMachine(
    val serie: String,
    val nom: String,
    val types: List<String>,   // types possibles pour les messages (le bon est trouvé tout seul)
    val cle: String,           // clé locale de l'appareil ("" = pas pilotable directement)
    val ip: String,
    val modele: String
) {
    val robot: Boolean get() = types.any { it in setOf("N223", "276", "277") }
    val ancienModele: Boolean get() = types.any { it in setOf("475", "469", "455") }   // gamme « Link »
    val pilotable: Boolean get() = cle.isNotEmpty() && types.isNotEmpty()

    fun json(): JSONObject = JSONObject().put("serie", serie).put("nom", nom).put("types", JSONArray(types))
        .put("cle", cle).put("ip", ip).put("modele", modele)

    companion object {
        fun lire(o: JSONObject): DysonMachine {
            val t = o.optJSONArray("types") ?: JSONArray()
            return DysonMachine(o.optString("serie"), o.optString("nom"), (0 until t.length()).map { t.getString(it) },
                o.optString("cle"), o.optString("ip"), o.optString("modele"))
        }
    }
}

/** État lu sur l'appareil. */
class DysonEtat(val produit: JSONObject, val environnement: JSONObject) {
    fun champ(o: JSONObject, nom: String): String {
        val v = o.opt(nom) ?: return ""
        return if (v is JSONArray) v.optString(v.length() - 1) else v.toString()
    }
    fun p(nom: String) = champ(produit, nom)
    fun e(nom: String) = champ(environnement, nom)
}

/**
 * Dyson dans Ma Maison : le compte MyDyson sert une seule fois à récupérer les appareils et leurs clés,
 * ensuite tout se pilote par le Wi-Fi de la maison, directement avec l'appareil.
 */
class Dyson(private val ctx: Context) {

    companion object {
        private const val HOTE = "https://appapi.cp.dyson.com"
        private const val PORT_RECEPTION = 8791
        private val TYPES_CONNUS = mapOf(
            "360 Eye" to "N223", "360EYE" to "N223", "N223" to "N223",
            "360 Heurist" to "276", "360HEURIST" to "276", "276" to "276",
            "360 Vis Nav" to "277", "360VIS" to "277", "277" to "277",
            "TP01" to "475", "TP02" to "475", "475" to "475",
            "DP01" to "469", "DP02" to "469", "469" to "469",
            "TP04" to "438", "TP07" to "438", "TP09" to "438", "TP11" to "438", "PC1" to "438", "438" to "438",
            "438K" to "438K", "438E" to "438E", "438M" to "438M",
            "AM06" to "520", "520" to "520",
            "HP02" to "455", "455" to "455",
            "HP04" to "527", "HP07" to "527", "HP09" to "527", "527" to "527", "527K" to "527K", "527E" to "527E", "527M" to "527M",
            "PH01" to "358", "PH02" to "358", "PH03" to "358", "PH04" to "358", "358" to "358", "358K" to "358K", "358E" to "358E", "358M" to "358M",
            "BP02" to "664", "BP03" to "664", "BP04" to "664", "664" to "664"
        )
        @Volatile private var reception: ServerSocket? = null
    }

    private val prefs = ctx.getSharedPreferences("maison", Context.MODE_PRIVATE)

    var erreur: String = ""
        private set

    var email: String
        get() = prefs.getString("dyson_email", "") ?: ""
        set(v) = prefs.edit().putString("dyson_email", v.trim()).apply()

    var machines: List<DysonMachine>
        get() = try {
            val t = JSONArray(prefs.getString("dyson_machines", "[]"))
            (0 until t.length()).map { DysonMachine.lire(t.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
        set(l) { prefs.edit().putString("dyson_machines", JSONArray(l.map { it.json() }).toString()).apply() }

    val relie: Boolean get() = machines.isNotEmpty()

    // ---------------- compte MyDyson (une seule fois) ----------------

    private fun appel(methode: String, chemin: String, corps: JSONObject? = null, jeton: String? = null): Pair<Int, String> = try {
        val c = URL(HOTE + chemin).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.requestMethod = methode
        c.setRequestProperty("User-Agent", "android client")
        c.setRequestProperty("Accept", "application/json")
        if (jeton != null) c.setRequestProperty("Authorization", "Bearer $jeton")
        if (corps != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(corps.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val texte = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        Pair(code, texte)
    } catch (e: Exception) {
        Pair(0, "")
    }

    /** Étape 1 : Dyson envoie un code par e-mail. Renvoie l'identifiant de la demande, ou null. */
    fun demanderCode(adresse: String): String? {
        erreur = ""
        val mail = adresse.trim()
        if (!mail.contains("@")) { erreur = "Adresse e-mail incomplète."; return null }
        appel("GET", "/v1/provisioningservice/application/Android/version")
        val (c1, t1) = appel("POST", "/v3/userregistration/email/userstatus?country=FR", JSONObject().put("email", mail))
        if (c1 == 0) { erreur = "Pas de connexion à Dyson (Internet coupé ?)."; return null }
        val statut = try { JSONObject(t1).optString("accountStatus") } catch (e: Exception) { "" }
        if (statut.isNotEmpty() && statut != "ACTIVE") { erreur = "Dyson ne trouve pas de compte actif pour cette adresse."; return null }
        val (c2, t2) = appel("POST", "/v3/userregistration/email/auth?country=FR&culture=fr-FR", JSONObject().put("email", mail))
        if (c2 == 429) { erreur = "Trop de demandes de code : attends quelques minutes puis réessaie."; return null }
        val id = try { JSONObject(t2).optString("challengeId") } catch (e: Exception) { "" }
        if (id.isEmpty()) { erreur = "Dyson a refusé la demande de code ($c2)."; return null }
        email = mail
        return id
    }

    /** Étape 2 : code reçu + mot de passe Dyson → appareils et clés enregistrés sur cet appareil. */
    fun valider(motDePasse: String, demande: String, code: String): Boolean {
        erreur = ""
        val (c, t) = appel("POST", "/v3/userregistration/email/verify", JSONObject()
            .put("email", email).put("password", motDePasse).put("challengeId", demande).put("otpCode", code.trim()))
        if (c == 0) { erreur = "Pas de connexion à Dyson (Internet coupé ?)."; return false }
        if (c == 400 || c == 401 || c == 403) { erreur = "Code ou mot de passe refusé par Dyson."; return false }
        val jeton = try { JSONObject(t).optString("token") } catch (e: Exception) { "" }
        if (jeton.isEmpty()) { erreur = "Dyson n'a pas ouvert la session ($c)."; return false }
        appel("GET", "/v1/provisioningservice/application/Android/version")
        val (c3, t3) = appel("GET", "/v2/provisioningservice/manifest", jeton = jeton)
        val tab = try { JSONArray(t3) } catch (e: Exception) { null }
        if (tab == null) { erreur = "Impossible de lire la liste des appareils ($c3)."; return false }
        val anciens = machines.associateBy { it.serie }
        val l = ArrayList<DysonMachine>()
        for (i in 0 until tab.length()) {
            val o = tab.optJSONObject(i) ?: continue
            val serie = o.optString("Serial")
            if (serie.isEmpty()) continue
            val types = typesPossibles(o.optString("ProductType"), o.optString("type"), o.optString("variant"))
            val cle = o.optString("LocalCredentials").let { if (it.isEmpty() || it == "null") "" else dechiffrer(it) }
            val nom = o.optString("Name").ifEmpty { "Dyson" }
            l.add(DysonMachine(serie, nom, types, cle, anciens[serie]?.ip ?: "", o.optString("ProductType")))
        }
        machines = l
        if (l.isEmpty()) erreur = "Aucun appareil dans ce compte Dyson."
        return true
    }

    private fun typesPossibles(produit: String, type: String, variante: String): List<String> {
        val l = ArrayList<String>()
        val v = variante.trim().uppercase().let { if (it == "NULL") "" else it }
        if (type.isNotEmpty() && type != "null") TYPES_CONNUS[type]?.let { l.add(it) }
        if (produit in setOf("438", "527", "358") && v.isNotEmpty()) l.add(produit + v)
        if (v.isNotEmpty()) TYPES_CONNUS[v]?.let { l.add(it) }
        TYPES_CONNUS[produit]?.let { l.add(it) }
        if (produit.isNotEmpty()) l.add(produit)
        return l.distinct()
    }

    /** La clé locale est chiffrée avec une clé fixe connue (méthode des programmes libres pour Dyson). */
    private fun dechiffrer(texte: String): String = try {
        val cle = ByteArray(32) { (it + 1).toByte() }
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(cle, "AES"), IvParameterSpec(ByteArray(16)))
        val brut = c.doFinal(Base64.decode(texte, Base64.DEFAULT))
        val fin = brut.size - (brut.last().toInt() and 0xff).coerceIn(0, 16)
        JSONObject(String(brut, 0, fin, Charsets.UTF_8)).optString("apPasswordHash")
    } catch (e: Exception) { "" }

    /** Autre voie : l'étiquette collée sur l'appareil (nom et mot de passe Wi-Fi de l'appareil). */
    fun ajouterParEtiquette(nomWifi: String, motDePasseWifi: String): Boolean {
        erreur = ""
        val ssid = nomWifi.trim().uppercase()
        var serie = ""
        var type = ""
        Regex("^(360EYE-)?([0-9A-Z]{3}-[A-Z]{2}-[0-9A-Z]{8,})$").find(ssid)?.let { serie = it.groupValues[2]; type = "N223" }
        if (serie.isEmpty()) Regex("^DYSON-([0-9A-Z]{3}-[A-Z]{2}-[0-9A-Z]{8,})-([0-9]{3}[A-Z]?)$").find(ssid)?.let {
            serie = it.groupValues[1]; type = if (it.groupValues[2] == "455A") "455" else it.groupValues[2]
        }
        if (serie.isEmpty()) { erreur = "Nom de Wi-Fi non reconnu : recopie-le exactement comme sur l'étiquette (ex. DYSON-…)."; return false }
        val cle = Base64.encodeToString(MessageDigest.getInstance("SHA-512").digest(motDePasseWifi.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val l = machines.filter { it.serie != serie }.toMutableList()
        l.add(DysonMachine(serie, "Dyson $type", typesPossibles(type, "", ""), cle, "", type))
        machines = l
        return true
    }

    fun delier() { machines = emptyList() }

    // ---------------- trouver l'appareil sur le Wi-Fi ----------------

    private fun prefixeReseau(): String? {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}"
        } catch (e: Exception) { }
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val p = a.hostAddress?.split(".") ?: continue
                        if (p.size == 4) return "${p[0]}.${p[1]}.${p[2]}"
                    }
                }
            }
        } catch (e: Exception) { }
        return null
    }

    private fun accepte(ip: String, m: DysonMachine): Boolean {
        val (rc, c) = Mqtt.connecter(ip, 1883, m.serie, m.cle, 2500)
        c?.fermer()
        return rc == 0
    }

    /** Recherche par le service d'annonce de l'appareil sur le Wi-Fi. */
    private fun parAnnonce(m: DysonMachine): String? {
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val trouve = AtomicReference<String?>(null)
        val fini = CountDownLatch(1)
        val services = listOf(if (m.robot) "_360eye_mqtt._tcp" else "_dyson_mqtt._tcp")
        val ecouteurs = ArrayList<NsdManager.DiscoveryListener>()
        for (svc in services) {
            val e = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, c: Int) {}
                override fun onStopDiscoveryFailed(t: String, c: Int) {}
                override fun onServiceLost(i: NsdServiceInfo) {}
                override fun onServiceFound(i: NsdServiceInfo) {
                    if (!i.serviceName.uppercase().contains(m.serie.uppercase())) return
                    try {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(i, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(s: NsdServiceInfo, c: Int) {}
                            override fun onServiceResolved(s: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                s.host?.hostAddress?.let { trouve.compareAndSet(null, it); fini.countDown() }
                            }
                        })
                    } catch (e: Exception) { }
                }
            }
            try { nsd.discoverServices(svc, NsdManager.PROTOCOL_DNS_SD, e); ecouteurs.add(e) } catch (x: Exception) { }
        }
        try { fini.await(6, TimeUnit.SECONDS) } catch (e: Exception) { }
        for (e in ecouteurs) try { nsd.stopServiceDiscovery(e) } catch (x: Exception) { }
        return trouve.get()
    }

    /** Recherche en cascade : adresse retenue, annonce Wi-Fi, puis tout le réseau de la maison. */
    fun trouver(m: DysonMachine): String? {
        if (m.ip.isNotEmpty() && accepte(m.ip, m)) return m.ip
        parAnnonce(m)?.let { if (accepte(it, m)) return garderIp(m, it) }
        val prefixe = prefixeReseau() ?: return null
        val trouve = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) pool.execute {
            if (trouve.get() == null) {
                val ip = "$prefixe.$i"
                if (Mqtt.portOuvert(ip, 1883, 500) && accepte(ip, m)) trouve.compareAndSet(null, ip)
            }
        }
        pool.shutdown()
        try { pool.awaitTermination(20, TimeUnit.SECONDS) } catch (e: Exception) { }
        pool.shutdownNow()
        return trouve.get()?.let { garderIp(m, it) }
    }

    private fun garderIp(m: DysonMachine, ip: String): String {
        machines = machines.map { if (it.serie == m.serie) it.copy(ip = ip) else it }
        return ip
    }

    private fun garderType(m: DysonMachine, bon: String) {
        if (m.types.firstOrNull() == bon) return
        machines = machines.map { if (it.serie == m.serie) it.copy(types = listOf(bon) + it.types.filter { t -> t != bon }) else it }
    }

    private fun heure(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    private fun sujetEtat(type: String, m: DysonMachine) = if (m.robot) "$type/${m.serie}/status" else "$type/${m.serie}/status/current"

    // ---------------- lire et commander ----------------

    /** Lit l'état de l'appareil (hors du fil de l'écran). */
    fun etat(m: DysonMachine): DysonEtat? {
        erreur = ""
        if (!m.pilotable) { erreur = "Cet appareil se pilote seulement dans l'appli MyDyson."; return null }
        val ip = trouver(m) ?: run { erreur = "Introuvable sur le Wi-Fi de la maison (éteint, ou tu n'es pas chez toi ?)."; return null }
        val (rc, c) = Mqtt.connecter(ip, 1883, m.serie, m.cle)
        if (c == null) { erreur = if (rc == 4 || rc == 5) "Clé refusée : relie à nouveau ton compte Dyson." else "L'appareil ne répond pas."; return null }
        try {
            for (type in m.types) {
                c.abonner(sujetEtat(type, m))
                c.publier("$type/${m.serie}/command", JSONObject().put("msg", "REQUEST-CURRENT-STATE").put("time", heure()).toString())
                if (!m.robot) c.publier("$type/${m.serie}/command", JSONObject().put("msg", "REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA").put("time", heure()).toString())
                val recus = c.ecouter(4000) { l ->
                    val etatOk = l.any { it.second.contains("CURRENT-STATE") }
                    etatOk && (m.robot || l.any { it.second.contains("ENVIRONMENTAL-CURRENT-SENSOR-DATA") })
                }
                var produit: JSONObject? = null
                var env = JSONObject()
                for ((_, texte) in recus) {
                    val o = try { JSONObject(texte) } catch (e: Exception) { continue }
                    when (o.optString("msg")) {
                        "CURRENT-STATE", "STATE-CHANGE" -> produit = if (m.robot) o else (o.optJSONObject("product-state") ?: o)
                        "ENVIRONMENTAL-CURRENT-SENSOR-DATA" -> env = o.optJSONObject("data") ?: JSONObject()
                    }
                }
                if (produit != null) { garderType(m, type); return DysonEtat(produit, env) }
            }
            erreur = "L'appareil n'a pas donné son état."
            return null
        } finally { c.fermer() }
    }

    private fun envoyer(m: DysonMachine, message: JSONObject): Boolean {
        erreur = ""
        if (!m.pilotable) { erreur = "Cet appareil se pilote seulement dans l'appli MyDyson."; return false }
        val ip = trouver(m) ?: run { erreur = "Introuvable sur le Wi-Fi de la maison (éteint, ou tu n'es pas chez toi ?)."; return false }
        val (rc, c) = Mqtt.connecter(ip, 1883, m.serie, m.cle)
        if (c == null) { erreur = if (rc == 4 || rc == 5) "Clé refusée : relie à nouveau ton compte Dyson." else "L'appareil ne répond pas."; return false }
        return try {
            c.publier("${m.types.first()}/${m.serie}/command", message.put("time", heure()).toString(), true)
            c.ecouter(600) { false }   // laisse le temps à l'appareil de recevoir
            true
        } catch (e: Exception) { erreur = "L'appareil n'a pas reçu l'ordre."; false } finally { c.fermer() }
    }

    private fun reglerVentilo(m: DysonMachine, donnees: JSONObject) =
        envoyer(m, JSONObject().put("msg", "STATE-SET").put("mode-reason", "LAPP").put("data", donnees))

    fun allume(m: DysonMachine, e: DysonEtat): Boolean =
        if (m.ancienModele) e.p("fmod") in setOf("FAN", "AUTO") else e.p("fpwr") == "ON"

    fun allumer(m: DysonMachine, oui: Boolean) =
        reglerVentilo(m, if (m.ancienModele) JSONObject().put("fmod", if (oui) "FAN" else "OFF") else JSONObject().put("fpwr", if (oui) "ON" else "OFF"))

    fun vitesse(m: DysonMachine, n: Int): Boolean {
        val v = String.format(Locale.US, "%04d", n.coerceIn(1, 10))
        return reglerVentilo(m, if (m.ancienModele) JSONObject().put("fmod", "FAN").put("fnsp", v) else JSONObject().put("fpwr", "ON").put("auto", "OFF").put("fnsp", v))
    }

    fun auto(m: DysonMachine, oui: Boolean) =
        reglerVentilo(m, if (m.ancienModele) JSONObject().put("fmod", if (oui) "AUTO" else "FAN") else JSONObject().put("fpwr", "ON").put("auto", if (oui) "ON" else "OFF"))

    fun oscillation(m: DysonMachine, e: DysonEtat, oui: Boolean): Boolean {
        val actuel = e.p("oson")
        val d = JSONObject()
        if (m.ancienModele) d.put("oson", if (oui) "ON" else "OFF")
        else {
            d.put("oson", if (actuel in setOf("OION", "OIOF")) (if (oui) "OION" else "OIOF") else (if (oui) "ON" else "OFF"))
            if (oui) {
                d.put("fpwr", "ON")
                val bas = e.p("osal"); val haut = e.p("osau")
                if (bas.isNotEmpty() && haut.isNotEmpty() && !m.types.first().startsWith("358")) d.put("ancp", "CUST").put("osal", bas).put("osau", haut)
                val angle = e.p("ancp")
                if (m.types.first().startsWith("358") && angle.isNotEmpty()) d.put("ancp", angle)
            }
        }
        return reglerVentilo(m, d)
    }

    fun nuit(m: DysonMachine, oui: Boolean) = reglerVentilo(m, JSONObject().put("nmod", if (oui) "ON" else "OFF"))

    fun robot(m: DysonMachine, ordre: String): Boolean {
        val o = JSONObject().put("msg", ordre)
        val eye = m.types.first() == "N223"
        if (!eye) o.put("mode-reason", "LAPP")
        if (ordre == "START") {
            o.put("fullCleanType", "immediate")
            if (!eye) o.put("cleaningMode", "global")
        }
        return envoyer(m, o)
    }

    // ---------------- partager les appareils avec la télé (Wi-Fi de la maison) ----------------

    /** La télé (ou tout autre appareil) attend les réglages envoyés par le téléphone. Bloquant, hors de l'écran. */
    fun recevoir(dureeMs: Int = 180000): Int {
        erreur = ""
        try { reception?.close() } catch (e: Exception) { }
        val serveur = try { ServerSocket(PORT_RECEPTION) } catch (e: Exception) { erreur = "Impossible d'attendre sur cet appareil."; return -1 }
        reception = serveur
        val fin = System.currentTimeMillis() + dureeMs
        try {
            while (System.currentTimeMillis() < fin) {
                serveur.soTimeout = (fin - System.currentTimeMillis()).coerceAtLeast(1).toInt()
                val s = try { serveur.accept() } catch (e: Exception) { break }
                s.soTimeout = 5000
                try {
                    val entree = s.getInputStream()
                    fun ligneBrute(): String {
                        val o = java.io.ByteArrayOutputStream()
                        while (true) {
                            val b = entree.read()
                            if (b < 0 || b == '\n'.code) break
                            if (b != '\r'.code) o.write(b)
                            if (o.size() > 8000) break
                        }
                        return o.toString("UTF-8")
                    }
                    val ligne = ligneBrute()
                    var longueur = 0
                    while (true) {
                        val h = ligneBrute()
                        if (h.isEmpty()) break
                        if (h.lowercase().startsWith("content-length:")) longueur = h.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    val sortie = s.getOutputStream()
                    if (ligne.startsWith("GET")) {
                        val r = JSONObject().put("nom", "maison-reception").toString().toByteArray()
                        sortie.write("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${r.size}\r\n\r\n".toByteArray()); sortie.write(r); sortie.flush()
                        continue
                    }
                    val buf = ByteArray(longueur.coerceIn(0, 500000))
                    var lu = 0
                    while (lu < buf.size) { val n = entree.read(buf, lu, buf.size - lu); if (n < 0) break; lu += n }
                    val tab = JSONArray(String(buf, 0, lu, Charsets.UTF_8))
                    val l = (0 until tab.length()).map { DysonMachine.lire(tab.getJSONObject(it)) }
                    sortie.write("HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray()); sortie.flush()
                    if (l.isNotEmpty()) { machines = l; return l.size }
                } catch (e: Exception) {
                } finally { try { s.close() } catch (_: Exception) { } }
            }
        } finally { try { serveur.close() } catch (_: Exception) { }; if (reception === serveur) reception = null }
        erreur = "Rien reçu : sur le téléphone, ouvre Ma Maison, Dyson, « Envoyer à la télé »."
        return 0
    }

    fun arreterReception() { try { reception?.close() } catch (_: Exception) { } }

    /** Le téléphone cherche l'appareil qui attend sur le Wi-Fi et lui envoie les réglages. */
    fun envoyerALaTele(): Boolean {
        erreur = ""
        if (!relie) { erreur = "Relie d'abord ton compte Dyson sur ce téléphone."; return false }
        val prefixe = prefixeReseau() ?: run { erreur = "Pas de Wi-Fi."; return false }
        val trouve = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) pool.execute {
            if (trouve.get() == null) {
                val b = "http://$prefixe.$i:$PORT_RECEPTION"
                val t = Reseau.lire("$b/ping", 700)
                if (t != null && t.contains("maison-reception")) trouve.compareAndSet(null, b)
            }
        }
        pool.shutdown()
        try { pool.awaitTermination(15, TimeUnit.SECONDS) } catch (e: Exception) { }
        pool.shutdownNow()
        val b = trouve.get() ?: run { erreur = "Aucune télé en attente : sur la télé, ouvre Ma Maison, Dyson, « Recevoir du téléphone »."; return false }
        val code = Reseau.envoyer("$b/dyson", JSONArray(machines.map { it.json() }).toString())
        if (code !in 200..299) { erreur = "La télé n'a pas reçu les réglages."; return false }
        return true
    }
}

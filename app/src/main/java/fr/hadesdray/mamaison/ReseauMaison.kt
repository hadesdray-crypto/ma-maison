package fr.hadesdray.mamaison

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Un appareil vu sur le Wi-Fi de la maison. */
class AppareilReseau(
    var cle: String,
    var ip: String,
    var nom: String,
    var genre: String,          // pc, tele, enceinte, freebox, camera, serveur, telephone, imprimante, appareil
    var ports: MutableSet<Int> = HashSet(),
    var dlna: String = "",      // adresse de commande du serveur de médias (si l'appareil en a un)
    var marque: String = "",
    var vu: Long = 0,
    var present: Boolean = false
) {
    fun json(): JSONObject = JSONObject()
        .put("cle", cle).put("ip", ip).put("nom", nom).put("genre", genre)
        .put("ports", JSONArray(ports.toList())).put("dlna", dlna).put("marque", marque).put("vu", vu)

    companion object {
        fun de(o: JSONObject): AppareilReseau {
            val p = HashSet<Int>()
            val a = o.optJSONArray("ports") ?: JSONArray()
            for (i in 0 until a.length()) p.add(a.optInt(i))
            return AppareilReseau(o.optString("cle"), o.optString("ip"), o.optString("nom"), o.optString("genre"),
                p, o.optString("dlna"), o.optString("marque"), o.optLong("vu"))
        }
    }
}

/**
 * Recherche de tout ce qui est branché sur le Wi-Fi de la maison, en cascade :
 * 1) annonces des appareils (SSDP / UPnP : télés, Freebox, serveurs de médias),
 * 2) annonces Bonjour (enceintes Google, téléphones avec Ma Maison, imprimantes),
 * 3) balayage de toutes les adresses du réseau sur les portes connues (PC Windows, caméras, serveurs),
 * 4) nom Windows des PC (NetBIOS).
 * Les appareils déjà vus restent connus (affichés « non connecté » s'ils ne répondent plus).
 */
class ReseauMaison(private val ctx: Context) {

    companion object {
        // 445 partage Windows, 139 ancien partage, 8009 diffusion Google, 554 caméra, 8790 passerelle Ma Maison,
        // 8791 téléphone Ma Maison, 8096 Jellyfin, 8200 serveur de médias, 32400 Plex, 631 imprimante, 80 page web
        val PORTS = intArrayOf(445, 139, 8009, 554, 8790, 8791, 8096, 8200, 32400, 631, 80, 9100)
    }

    private val prefs = ctx.getSharedPreferences("maison", Context.MODE_PRIVATE)

    /** Appareils déjà vus (gardés sur ce téléphone). */
    fun connus(): List<AppareilReseau> {
        val a = try { JSONArray(prefs.getString("reseau_connus", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { AppareilReseau.de(a.getJSONObject(it)) }
    }

    private fun garder(l: Collection<AppareilReseau>) {
        val a = JSONArray()
        for (x in l) a.put(x.json())
        prefs.edit().putString("reseau_connus", a.toString()).apply()
    }

    /** Adresse de ce téléphone sur le Wi-Fi (jamais celle des données mobiles). */
    fun monIp(): String? {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (e: Exception) { }
        var autre: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) {
                    if (ni.name.startsWith("wlan") || ni.name.startsWith("eth")) return a.hostAddress
                    if (autre == null && !ni.name.startsWith("rmnet") && !ni.name.startsWith("ccmni")) autre = a.hostAddress
                }
            }
        } catch (e: Exception) { }
        return autre
    }

    /** Toutes les adresses de ce téléphone (pour ne pas se trouver soi-même). */
    private fun mesIps(): Set<String> {
        val l = HashSet<String>()
        try { for (ni in NetworkInterface.getNetworkInterfaces()) for (a in ni.inetAddresses) a.hostAddress?.let { l.add(it) } } catch (e: Exception) { }
        return l
    }

    private fun ouvert(ip: String, port: Int, delai: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), delai); true }
    } catch (e: Exception) { false }

    /** L'appareil répond-il encore ? (vérification rapide, sans tout rebalayer) */
    fun repond(a: AppareilReseau): Boolean {
        val essais = if (a.ports.isEmpty()) PORTS.toList() else a.ports.toList()
        return essais.any { ouvert(a.ip, it, 700) }
    }

    /** Recherche complète (environ 10 à 15 secondes). À appeler hors du fil de l'écran. */
    fun chercher(): List<AppareilReseau> {
        val moi = monIp() ?: return connus().onEach { it.present = false }
        val prefixe = moi.substringBeforeLast(".")
        val trouves = ConcurrentHashMap<String, AppareilReseau>()   // par adresse
        fun pour(ip: String): AppareilReseau = trouves.getOrPut(ip) { AppareilReseau(ip, ip, "", "appareil", present = true) }

        val pool = Executors.newFixedThreadPool(64)
        // 1) annonces UPnP / SSDP
        pool.execute { try { ssdp(::pour) } catch (e: Throwable) { } }
        // 2) annonces Bonjour
        val nsd = try { bonjour(::pour) } catch (e: Throwable) { null }
        // 3) balayage
        val miennes = mesIps() + moi
        for (i in 1..254) {
            val ip = "$prefixe.$i"
            if (ip in miennes) continue
            pool.execute {
                for (p in PORTS) if (ouvert(ip, p, 350)) pour(ip).ports.add(p)
            }
        }
        pool.shutdown()
        pool.awaitTermination(40, TimeUnit.SECONDS)
        try { nsd?.invoke() } catch (e: Throwable) { }

        // 4) noms et genres
        val fin = Executors.newFixedThreadPool(16)
        for (a in trouves.values) fin.execute { try { completer(a) } catch (e: Throwable) { } }
        fin.shutdown()
        fin.awaitTermination(20, TimeUnit.SECONDS)

        // Fusion avec les appareils déjà connus (par nom quand on l'a, sinon par adresse)
        val maintenant = System.currentTimeMillis()
        val anciens = connus().associateBy { it.cle }.toMutableMap()
        val resultat = ArrayList<AppareilReseau>()
        for (a in trouves.values) {
            if (a.ip in miennes) continue
            if (a.nom.isEmpty()) a.nom = when (a.genre) { "pc" -> "PC"; else -> "Appareil" } + " (" + a.ip + ")"
            a.cle = if (a.nom.contains(a.ip)) a.ip else a.nom.lowercase()
            val ancien = anciens.remove(a.cle) ?: anciens.values.firstOrNull { it.ip == a.ip && it.nom.contains(it.ip) }?.also { anciens.remove(it.cle) }
            if (ancien != null) {
                if (a.genre == "appareil" && ancien.genre != "appareil") a.genre = ancien.genre
                if (a.dlna.isEmpty()) a.dlna = ancien.dlna
                if (a.marque.isEmpty()) a.marque = ancien.marque
            }
            a.vu = maintenant
            a.present = true
            resultat.add(a)
        }
        for (a in anciens.values) { a.present = false; resultat.add(a) }
        garder(resultat)
        return trier(resultat)
    }

    fun trier(l: List<AppareilReseau>): List<AppareilReseau> {
        val ordre = listOf("pc", "telephone", "serveur", "freebox", "tele", "enceinte", "camera", "imprimante", "appareil")
        return l.sortedWith(compareBy<AppareilReseau>({ !it.present }, { ordre.indexOf(it.genre).let { i -> if (i < 0) 99 else i } }, { it.nom.lowercase() }))
    }

    // ---------- SSDP (télés, Freebox, serveurs de médias) ----------

    private fun ssdp(pour: (String) -> AppareilReseau) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val verrou = wm.createMulticastLock("maison-ssdp")
        verrou.setReferenceCounted(false)
        try { verrou.acquire() } catch (e: Exception) { }
        val emplacements = HashMap<String, MutableSet<String>>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 800
                val demande = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
                val groupe = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(demande, demande.size, groupe, 1900)) }
                val fin = System.currentTimeMillis() + 3500
                val tampon = ByteArray(2048)
                while (System.currentTimeMillis() < fin) {
                    val p = DatagramPacket(tampon, tampon.size)
                    try { s.receive(p) } catch (e: Exception) { continue }
                    val t = String(p.data, 0, p.length)
                    val loc = Regex("(?im)^location:\\s*(\\S+)").find(t)?.groupValues?.get(1) ?: continue
                    emplacements.getOrPut(p.address.hostAddress ?: continue) { HashSet() }.add(loc)
                }
            }
        } finally {
            try { verrou.release() } catch (e: Exception) { }
        }
        for ((ip, locs) in emplacements) {
            val a = pour(ip)
            for (l in locs) try { description(a, l) } catch (e: Throwable) { }
        }
    }

    /** Lit la fiche UPnP d'un appareil : nom, marque, et adresse du serveur de médias s'il en a un. */
    fun description(a: AppareilReseau, lien: String) {
        val xml = Reseau.lire(lien, 3000) ?: return
        val p = XmlPullParserFactory.newInstance().also { it.isNamespaceAware = false }.newPullParser()
        p.setInput(StringReader(xml))
        var balise = ""
        var nom = ""; var marque = ""; var modele = ""; var genre = ""
        var serviceType = ""; var controle = ""
        var base = ""
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> balise = p.name.substringAfter(':')
                XmlPullParser.TEXT -> {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) when (balise) {
                        "friendlyName" -> if (nom.isEmpty()) nom = t
                        "manufacturer" -> if (marque.isEmpty()) marque = t
                        "modelName" -> if (modele.isEmpty()) modele = t
                        "deviceType" -> if (genre.isEmpty()) genre = t
                        "URLBase" -> base = t
                        "serviceType" -> serviceType = t
                        "controlURL" -> controle = t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name.substringAfter(':') == "service") {
                        if (serviceType.contains("ContentDirectory") && controle.isNotEmpty() && a.dlna.isEmpty())
                            a.dlna = URL(URL(if (base.isNotEmpty()) base else lien), controle).toString()
                        serviceType = ""; controle = ""
                    }
                    balise = ""
                }
            }
            p.next()
        }
        if (nom.isNotEmpty() && (a.nom.isEmpty() || a.nom.contains(a.ip))) a.nom = nom
        if (marque.isNotEmpty()) a.marque = listOf(marque, modele).filter { it.isNotEmpty() }.joinToString(" ")
        val g = (genre + " " + marque + " " + modele + " " + nom).lowercase()
        a.genre = when {
            "freebox" in g -> "freebox"
            "mediaserver" in g && a.genre == "appareil" -> "serveur"
            "camera" in g || "ipcam" in g -> "camera"
            "mediarenderer" in g || "tv" in g || "samsung" in g || "lg electronics" in g -> if (a.genre == "appareil" || a.genre == "serveur") "tele" else a.genre
            "printer" in g -> "imprimante"
            else -> a.genre
        }
    }

    // ---------- Bonjour (enceintes Google, téléphones Ma Maison, imprimantes) ----------

    /** Lance la découverte ; la fonction renvoyée l'arrête. */
    private fun bonjour(pour: (String) -> AppareilReseau): () -> Unit {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val types = listOf("_googlecast._tcp", "_mamaison._tcp", "_smb._tcp", "_ipp._tcp", "_rtsp._tcp", "_airplay._tcp")
        val ecouteurs = ArrayList<NsdManager.DiscoveryListener>()
        val file = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        for (t in types) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(s: String) {}
                override fun onDiscoveryStopped(s: String) {}
                override fun onStartDiscoveryFailed(s: String, e: Int) {}
                override fun onStopDiscoveryFailed(s: String, e: Int) {}
                override fun onServiceLost(i: NsdServiceInfo) {}
                override fun onServiceFound(i: NsdServiceInfo) { file.add(i) }
            }
            try { nsd.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, l); ecouteurs.add(l) } catch (e: Exception) { }
        }
        // Résolution une par une (Android n'en accepte qu'une à la fois)
        val resolveur = Thread {
            val fin = System.currentTimeMillis() + 9000
            while (System.currentTimeMillis() < fin) {
                val i = file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val fini = java.util.concurrent.CountDownLatch(1)
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, e: Int) { fini.countDown() }
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            try {
                                @Suppress("DEPRECATION") val ip = s.host?.hostAddress
                                if (ip != null && !ip.contains(":")) {
                                    val a = pour(ip)
                                    a.ports.add(s.port)
                                    val type = s.serviceType.lowercase()
                                    val fn = s.attributes["fn"]?.let { String(it) } ?: ""
                                    when {
                                        "_mamaison" in type -> { a.genre = "telephone"; a.nom = s.serviceName }
                                        "_googlecast" in type -> {
                                            if (fn.isNotEmpty()) a.nom = fn
                                            val md = (s.attributes["md"]?.let { String(it) } ?: "").lowercase()
                                            a.marque = s.attributes["md"]?.let { String(it) } ?: a.marque
                                            a.genre = if ("mini" in md || "home" in md || "audio" in md || "group" in md || "speaker" in md) "enceinte" else if (a.genre == "appareil") "tele" else a.genre
                                        }
                                        "_ipp" in type -> { a.genre = "imprimante"; if (a.nom.isEmpty()) a.nom = s.serviceName }
                                        "_rtsp" in type -> { a.genre = "camera"; if (a.nom.isEmpty()) a.nom = s.serviceName }
                                        "_smb" in type -> { if (a.nom.isEmpty()) a.nom = s.serviceName }
                                        else -> if (a.nom.isEmpty()) a.nom = s.serviceName
                                    }
                                }
                            } catch (e: Throwable) { }
                            fini.countDown()
                        }
                    })
                } catch (e: Throwable) { fini.countDown() }
                fini.await(3, TimeUnit.SECONDS)
            }
        }
        resolveur.start()
        return {
            resolveur.join(10000)
            for (l in ecouteurs) try { nsd.stopServiceDiscovery(l) } catch (e: Exception) { }
        }
    }

    // ---------- noms des PC, genres ----------

    private fun completer(a: AppareilReseau) {
        if (445 in a.ports || 139 in a.ports) {
            netbios(a.ip)?.let { if (a.nom.isEmpty() || a.nom.contains(a.ip)) a.nom = it }
            if (a.genre == "appareil") a.genre = "pc"
        }
        // Serveurs de médias sans annonce (MiniDLNA, etc.) : fiche à l'adresse habituelle
        if (a.dlna.isEmpty() && 8200 in a.ports) try { description(a, "http://${a.ip}:8200/rootDesc.xml") } catch (e: Throwable) { }
        if (8096 in a.ports && a.genre == "appareil") { a.genre = "serveur"; if (a.nom.isEmpty()) a.nom = "Jellyfin" }
        if (32400 in a.ports && a.genre == "appareil") { a.genre = "serveur"; if (a.nom.isEmpty()) a.nom = "Plex" }
        if (8790 in a.ports && (a.genre == "appareil" || a.genre == "pc")) a.genre = "pc"
        if (554 in a.ports && a.genre == "appareil") a.genre = "camera"
        if ((631 in a.ports || 9100 in a.ports) && a.genre == "appareil") a.genre = "imprimante"
        if (8791 in a.ports && a.genre == "appareil") {
            a.genre = "telephone"
            Reseau.lire("http://${a.ip}:8791/partage/ping", 2000)?.let { t ->
                try { JSONObject(t).optString("nom").takeIf { it.isNotEmpty() }?.let { a.nom = it } } catch (e: Exception) { }
            }
        }
        if (a.dlna.isNotEmpty() && a.genre == "appareil") a.genre = "serveur"
    }

    /** Nom Windows d'un PC (question NetBIOS directe, sans annonce). */
    private fun netbios(ip: String): String? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 1200
            val q = byteArrayOf(0x4d, 0x41, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x20, 0x43, 0x4b) +
                ByteArray(30) { 0x41 } + byteArrayOf(0, 0, 0x21, 0, 1)
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val r = ByteArray(1024)
            val p = DatagramPacket(r, r.size)
            s.receive(p)
            // Réponse : après l'en-tête (12) + nom (34) + type/classe/durée/longueur (10) vient le nombre de noms
            val debut = 56
            val n = r[debut].toInt() and 0xff
            var nom: String? = null
            for (i in 0 until n) {
                val o = debut + 1 + i * 18
                if (o + 18 > p.length) break
                val t = String(r, o, 15, Charsets.US_ASCII).trim()
                val suffixe = r[o + 15].toInt() and 0xff
                val groupe = (r[o + 16].toInt() and 0x80) != 0
                if (suffixe == 0 && !groupe && t.isNotEmpty()) { nom = t; break }
            }
            nom
        }
    } catch (e: Exception) { null }
}

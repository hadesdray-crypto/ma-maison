package fr.hadesdray.mamaison

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * La passerelle tourne sur le PC de la maison (port 8790).
 * On la cherche en cascade : adresse retenue, puis tout le réseau de la maison.
 */
class Passerelle(private val ctx: Context) {

    companion object {
        const val PORT = 8790
        @Volatile var adresse: String? = null
    }

    private val prefs = ctx.getSharedPreferences("maison", Context.MODE_PRIVATE)

    private fun repond(base: String, delai: Int): Boolean {
        val t = Reseau.lire("$base/ping", delai) ?: return false
        return try { JSONObject(t).optString("nom") == "passerelle-maison" } catch (e: Exception) { false }
    }

    /** À appeler hors du fil de l'écran. */
    fun trouver(): String? {
        adresse?.let { if (repond(it, 1500)) return it }
        val retenue = prefs.getString("passerelle", null)
        if (retenue != null && repond(retenue, 2000)) return garder(retenue)
        val manuelle = prefs.getString("passerelle_manuelle", null)
        if (!manuelle.isNullOrBlank()) {
            val b = if (manuelle.startsWith("http")) manuelle.trimEnd('/') else "http://$manuelle:$PORT"
            if (repond(b, 2500)) return garder(b)
        }
        val prefixe = prefixeReseau() ?: return null
        val trouve = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            pool.execute {
                if (trouve.get() == null) {
                    val b = "http://$prefixe.$i:$PORT"
                    if (repond(b, 700)) trouve.compareAndSet(null, b)
                }
            }
        }
        pool.shutdown()
        try { pool.awaitTermination(12, TimeUnit.SECONDS) } catch (e: Exception) { }
        pool.shutdownNow()
        return trouve.get()?.let { garder(it) }
    }

    private fun garder(b: String): String {
        adresse = b
        prefs.edit().putString("passerelle", b).apply()
        return b
    }

    private fun prefixeReseau(): String? {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}"
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

    fun lienFichier(base: String, chemin: String) = "$base/fichier?chemin=" + Reseau.encode(chemin)
}

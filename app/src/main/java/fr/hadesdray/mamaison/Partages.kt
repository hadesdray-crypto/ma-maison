package fr.hadesdray.mamaison

import android.content.Context
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

/**
 * Dossiers partagés des PC Windows du réseau.
 * L'identifiant et le mot de passe Windows sont demandés une fois par PC et restent sur ce téléphone.
 */
class Partages(ctx: Context) {

    class Entree(val nom: String, val chemin: String, val dossier: Boolean, val taille: Long)

    private val prefs = ctx.getSharedPreferences("maison", Context.MODE_PRIVATE)

    private val base: BaseContext by lazy {
        val p = Properties()
        p["jcifs.smb.client.minVersion"] = "SMB202"
        p["jcifs.smb.client.maxVersion"] = "SMB311"
        p["jcifs.smb.client.responseTimeout"] = "15000"
        p["jcifs.smb.client.soTimeout"] = "20000"
        p["jcifs.smb.client.connTimeout"] = "6000"
        p["jcifs.resolveOrder"] = "DNS"
        BaseContext(PropertyConfiguration(p))
    }

    fun identifiant(ip: String): Pair<String, String>? {
        val u = prefs.getString("smb_u_$ip", null) ?: return null
        return Pair(u, prefs.getString("smb_p_$ip", "") ?: "")
    }

    fun retenir(ip: String, u: String, mdp: String) {
        prefs.edit().putString("smb_u_$ip", u).putString("smb_p_$ip", mdp).apply()
    }

    fun oublier(ip: String) {
        prefs.edit().remove("smb_u_$ip").remove("smb_p_$ip").apply()
    }

    private fun contexte(ip: String) = identifiant(ip).let { id ->
        if (id == null || id.first.isEmpty()) base.withGuestCrendentials()
        else {
            // « DOMAINE\nom » est accepté ; un compte Microsoft s'écrit avec son adresse
            val (dom, nom) = if (id.first.contains("\\")) id.first.substringBefore("\\") to id.first.substringAfter("\\") else "" to id.first
            base.withCredentials(NtlmPasswordAuthenticator(dom, nom, id.second))
        }
    }

    fun fichier(chemin: String): SmbFile {
        val ip = chemin.removePrefix("smb://").substringBefore("/")
        return SmbFile(chemin, contexte(ip))
    }

    /** Partages d'un PC (sans les partages cachés de Windows). Lance une erreur si l'accès est refusé. */
    fun partages(ip: String): List<Entree> =
        fichier("smb://$ip/").list().filter { !it.trimEnd('/').endsWith("$") }
            .map { Entree(it.trimEnd('/'), "smb://$ip/$it", true, 0) }
            .sortedBy { it.nom.lowercase() }

    /** Contenu d'un dossier partagé : dossiers puis fichiers du type demandé. */
    fun lister(chemin: String, extensions: Set<String>): List<Entree> {
        val f = fichier(if (chemin.endsWith("/")) chemin else "$chemin/")
        val l = ArrayList<Entree>()
        for (x in f.listFiles()) {
            val nom = x.name.trimEnd('/')
            if (nom.startsWith(".") || nom.startsWith("$") || x.isHidden) continue
            if (x.isDirectory) l.add(Entree(nom, x.canonicalPath, true, 0))
            else if (extensions.isEmpty() || nom.substringAfterLast('.', "").lowercase() in extensions) l.add(Entree(nom, x.canonicalPath, false, x.length()))
        }
        return l.sortedWith(compareBy<Entree>({ !it.dossier }, { it.nom.lowercase() }))
    }

    /** Message clair pour une erreur de partage Windows. */
    fun raison(e: Throwable): String {
        val t = (e.message ?: e.javaClass.simpleName).lowercase()
        return when {
            "logon failure" in t || "access_denied" in t || "access is denied" in t || "0xc000006d" in t ->
                "identifiant ou mot de passe Windows refusé"
            "timed out" in t || "timeout" in t || "failed to connect" in t || "connect" in t -> "le PC ne répond pas (éteint, ou partage désactivé)"
            "bad_network_name" in t || "network name" in t -> "ce partage n'existe plus"
            else -> e.message ?: "erreur inconnue"
        }
    }

    companion object {
        val EXT_VIDEOS = setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "ts", "3gp", "wmv", "flv", "mpg", "mpeg")
        val EXT_PHOTOS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
        val EXT_MUSIQUE = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma")
        fun extensions(type: String) = when (type) { "videos" -> EXT_VIDEOS; "photos" -> EXT_PHOTOS; "musique" -> EXT_MUSIQUE; else -> EXT_VIDEOS + EXT_PHOTOS + EXT_MUSIQUE }
    }
}

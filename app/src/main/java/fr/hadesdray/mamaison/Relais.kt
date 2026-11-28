package fr.hadesdray.mamaison

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import jcifs.smb.SmbRandomAccessFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Petit serveur de l'appli, sur le Wi-Fi de la maison uniquement (port 8791) :
 * - relaie les fichiers des partages Windows (pour les lecteurs de l'appli et la télé) — protégé par une clé ;
 * - partage les photos, vidéos et musiques de ce téléphone, si on l'a autorisé — protégé par un code.
 */
object Relais {

    const val PORT = 8791
    @Volatile private var serveur: ServerSocket? = null
    private lateinit var app: Context
    private val erreurs = HashMap<String, MutableList<Long>>()

    private fun prefs() = app.getSharedPreferences("maison", Context.MODE_PRIVATE)

    /** Clé secrète de ce téléphone pour les liens relayés (créée une fois). */
    fun cle(ctx: Context): String {
        val p = ctx.getSharedPreferences("maison", Context.MODE_PRIVATE)
        p.getString("relais_cle", null)?.let { return it }
        val c = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p.edit().putString("relais_cle", c).apply()
        return c
    }

    fun partageActif(ctx: Context) = ctx.getSharedPreferences("maison", Context.MODE_PRIVATE).getBoolean("partage_actif", false)

    /** Code à donner aux autres appareils pour voir ce téléphone (6 chiffres, créé une fois). */
    fun codePartage(ctx: Context): String {
        val p = ctx.getSharedPreferences("maison", Context.MODE_PRIVATE)
        p.getString("partage_code", null)?.let { return it }
        val c = (100000 + SecureRandom().nextInt(900000)).toString()
        p.edit().putString("partage_code", c).apply()
        return c
    }

    @Synchronized
    fun demarrer(ctx: Context) {
        app = ctx.applicationContext
        if (serveur?.isClosed == false) return
        val s = try { ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0")) } catch (e: Exception) { return }
        serveur = s
        thread(isDaemon = true, name = "relais") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { try { traiter(c) } catch (e: Throwable) { } finally { try { c.close() } catch (e: Exception) { } } }
            }
        }
    }

    /** Adresse à donner à un lecteur (ou à la télé) pour lire un fichier d'un partage Windows. */
    fun lienSmb(ctx: Context, ip: String?, chemin: String): String =
        "http://${ip ?: "127.0.0.1"}:$PORT/smb?k=${cle(ctx)}&u=" + Reseau.encode(chemin)

    // ---------- requêtes ----------

    private fun adresseLocale(a: InetAddress): Boolean = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress

    private fun traiter(c: Socket) {
        c.soTimeout = 30000
        if (!adresseLocale(c.inetAddress)) { repondre(c.getOutputStream(), 403, "text/plain", "interdit".toByteArray()); return }
        val entree = c.getInputStream()
        val premiere = ligne(entree) ?: return
        val entetes = HashMap<String, String>()
        while (true) {
            val l = ligne(entree) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) entetes[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val parts = premiere.split(" ")
        if (parts.size < 2) return
        val methode = parts[0]
        val cible = parts[1]
        val chemin = cible.substringBefore('?')
        val q = HashMap<String, String>()
        for (x in cible.substringAfter('?', "").split('&')) {
            if (x.isEmpty()) continue
            q[URLDecoder.decode(x.substringBefore('='), "UTF-8")] = URLDecoder.decode(x.substringAfter('=', ""), "UTF-8")
        }
        val sortie = c.getOutputStream()
        when (chemin) {
            "/smb" -> {
                if (q["k"] != cle(app)) return repondre(sortie, 403, "text/plain", "clé refusée".toByteArray())
                smb(sortie, q["u"] ?: "", entetes["range"], methode == "HEAD")
            }
            "/partage/ping" -> repondre(sortie, 200, "application/json",
                JSONObject().put("nom", nomTelephone()).put("partage", partageActif(app)).toString().toByteArray())
            "/partage/liste", "/partage/fichier" -> {
                if (!partageActif(app)) return repondre(sortie, 403, "application/json", "{\"erreur\":\"partage arrêté\"}".toByteArray())
                if (!codeBon(c.inetAddress.hostAddress ?: "", q["code"] ?: "")) return repondre(sortie, 401, "application/json", "{\"erreur\":\"code refusé\"}".toByteArray())
                if (chemin == "/partage/liste") repondre(sortie, 200, "application/json", liste(q["type"] ?: "videos").toString().toByteArray())
                else media(sortie, q["type"] ?: "videos", q["id"]?.toLongOrNull() ?: -1, entetes["range"], methode == "HEAD")
            }
            else -> repondre(sortie, 404, "text/plain", "introuvable".toByteArray())
        }
    }

    fun nomTelephone(): String = prefs().getString("partage_nom", null)?.takeIf { it.isNotBlank() }
        ?: ("Téléphone " + Build.MODEL)

    /** Au plus 10 codes faux par minute et par appareil. */
    @Synchronized
    private fun codeBon(ip: String, code: String): Boolean {
        val l = erreurs.getOrPut(ip) { ArrayList() }
        val maintenant = System.currentTimeMillis()
        l.removeAll { maintenant - it > 60000 }
        if (l.size >= 10) return false
        val bon = code.isNotEmpty() && code == codePartage(app)
        if (!bon) l.add(maintenant)
        return bon
    }

    private fun ligne(e: InputStream): String? {
        val b = StringBuilder()
        while (true) {
            val x = e.read()
            if (x < 0) return if (b.isEmpty()) null else b.toString()
            if (x == '\n'.code) return b.toString().trimEnd('\r')
            b.append(x.toChar())
            if (b.length > 8192) return null
        }
    }

    private fun repondre(s: OutputStream, code: Int, type: String, corps: ByteArray) {
        val t = "HTTP/1.1 $code ${if (code < 300) "OK" else "Erreur"}\r\nContent-Type: $type\r\nContent-Length: ${corps.size}\r\nConnection: close\r\n\r\n"
        s.write(t.toByteArray())
        s.write(corps)
        s.flush()
    }

    /** Envoie un morceau de fichier, avec reprise à n'importe quel endroit (pour avancer dans une vidéo). */
    private fun envoyerFlux(s: OutputStream, taille: Long, type: String, plage: String?, tete: Boolean, lireA: (Long, ByteArray, Int) -> Int) {
        var debut = 0L
        var fin = taille - 1
        var code = 200
        if (plage != null) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(plage)
            if (m != null) {
                val a = m.groupValues[1]; val b = m.groupValues[2]
                if (a.isNotEmpty()) debut = a.toLong()
                if (b.isNotEmpty()) fin = minOf(b.toLong(), taille - 1)
                if (a.isEmpty() && b.isNotEmpty()) { debut = maxOf(0, taille - b.toLong()); fin = taille - 1 }
                code = 206
            }
        }
        if (taille == 0L) { repondre(s, 200, type, ByteArray(0)); return }
        if (debut > fin || debut >= taille) {
            s.write("HTTP/1.1 416 Erreur\r\nContent-Range: bytes */$taille\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
        }
        val longueur = fin - debut + 1
        val e = StringBuilder("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "OK"}\r\nContent-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: $longueur\r\n")
        if (code == 206) e.append("Content-Range: bytes $debut-$fin/$taille\r\n")
        e.append("Connection: close\r\n\r\n")
        s.write(e.toString().toByteArray())
        if (tete) { s.flush(); return }
        val tampon = ByteArray(256 * 1024)
        var pos = debut
        while (pos <= fin) {
            val n = lireA(pos, tampon, minOf(tampon.size.toLong(), fin - pos + 1).toInt())
            if (n <= 0) break
            s.write(tampon, 0, n)
            pos += n
        }
        s.flush()
    }

    private fun typeDe(nom: String): String {
        val x = nom.substringAfterLast('.', "").lowercase()
        return when (x) {
            "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"; "ts" -> "video/mp2t"; "3gp" -> "video/3gpp"; "wmv" -> "video/x-ms-wmv"; "mpg", "mpeg" -> "video/mpeg"
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "heic" -> "image/heic"
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "ogg", "opus" -> "audio/ogg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun smb(s: OutputStream, chemin: String, plage: String?, tete: Boolean) {
        val f = try { Partages(app).fichier(chemin) } catch (e: Throwable) { return repondre(s, 400, "text/plain", "chemin refusé".toByteArray()) }
        val taille = try { f.length() } catch (e: Throwable) { return repondre(s, 404, "text/plain", "introuvable".toByteArray()) }
        val r = try { SmbRandomAccessFile(f, "r") } catch (e: Throwable) { return repondre(s, 404, "text/plain", "illisible".toByteArray()) }
        try {
            envoyerFlux(s, taille, typeDe(f.name), plage, tete) { pos, t, n -> r.seek(pos); r.read(t, 0, n) }
        } finally { try { r.close() } catch (e: Exception) { } }
    }

    // ---------- partage des médias de ce téléphone ----------

    private fun collection(type: String) = when (type) {
        "photos" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "musique" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    /** Liste des médias du téléphone, avec leur dossier (Appareil photo, WhatsApp…). */
    private fun liste(type: String): JSONObject {
        val l = JSONArray()
        val dossierCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.BUCKET_DISPLAY_NAME else "bucket_display_name"
        val colonnes = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, dossierCol)
        try {
            app.contentResolver.query(collection(type), colonnes, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { k ->
                while (k.moveToNext()) {
                    l.put(JSONObject().put("id", k.getLong(0)).put("nom", k.getString(1) ?: "").put("taille", k.getLong(2))
                        .put("dossier", (if (type == "musique") null else k.getString(3)) ?: "Musique"))
                }
            }
        } catch (e: SecurityException) {
            return JSONObject().put("erreur", "autorisation manquante sur ce téléphone").put("fichiers", l)
        }
        return JSONObject().put("fichiers", l)
    }

    private fun media(s: OutputStream, type: String, id: Long, plage: String?, tete: Boolean) {
        val uri = ContentUris.withAppendedId(collection(type), id)
        val pfd = try { app.contentResolver.openFileDescriptor(uri, "r") } catch (e: Throwable) { null }
            ?: return repondre(s, 404, "text/plain", "introuvable".toByteArray())
        val nom = try {
            app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { k -> if (k.moveToFirst()) k.getString(0) else null }
        } catch (e: Throwable) { null } ?: ""
        pfd.use {
            val canal = java.io.FileInputStream(it.fileDescriptor).channel
            envoyerFlux(s, it.statSize, typeDe(nom), plage, tete) { pos, t, n -> canal.read(java.nio.ByteBuffer.wrap(t, 0, n), pos) }
        }
    }
}

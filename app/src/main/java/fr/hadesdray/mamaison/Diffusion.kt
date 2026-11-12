package fr.hadesdray.mamaison

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider

/** Réglages de l'envoi vers les enceintes et télés (lecteur standard de Google). */
class OptionsDiffusion : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Envoie les vidéos, musiques et photos du PC vers une enceinte Google, une Freebox Player
 * ou une télé qui accepte la diffusion, sur le Wi-Fi de la maison.
 */
class Diffusion(private val activite: Activity) {

    private val ui = Handler(Looper.getMainLooper())

    private fun contexte(): CastContext? = try {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(activite)
    } catch (e: Throwable) {
        null
    }

    private fun message(t: String) = Toast.makeText(activite, t, Toast.LENGTH_LONG).show()

    private fun typeDe(lien: String, genre: String): String {
        val nom = Uri.decode(lien).lowercase()
        return when {
            nom.endsWith(".mp4") || nom.endsWith(".m4v") || nom.endsWith(".mov") -> "video/mp4"
            nom.endsWith(".webm") -> "video/webm"
            nom.endsWith(".mkv") -> "video/x-matroska"
            nom.endsWith(".mp3") -> "audio/mpeg"
            nom.endsWith(".m4a") || nom.endsWith(".aac") -> "audio/mp4"
            nom.endsWith(".ogg") || nom.endsWith(".opus") -> "audio/ogg"
            nom.endsWith(".flac") -> "audio/flac"
            nom.endsWith(".wav") -> "audio/wav"
            nom.endsWith(".png") -> "image/png"
            nom.endsWith(".webp") -> "image/webp"
            nom.endsWith(".gif") -> "image/gif"
            nom.endsWith(".jpg") || nom.endsWith(".jpeg") -> "image/jpeg"
            genre == "photos" -> "image/jpeg"
            genre == "musique" -> "audio/mpeg"
            else -> "video/mp4"
        }
    }

    /** Cherche les appareils pendant 4 secondes, puis propose la liste. */
    fun envoyer(liens: Array<String>, noms: Array<String>, depart: Int, genre: String) {
        val cc = contexte()
        if (cc == null) {
            message("L'envoi vers une enceinte ou une télé demande les services Google, absents de cet appareil.")
            return
        }
        val routeur = MediaRouter.getInstance(activite)
        val selecteur = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
        val rappel = object : MediaRouter.Callback() {}
        routeur.addCallback(selecteur, rappel, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        val attente = AlertDialog.Builder(activite)
            .setTitle("Recherche des enceintes et télés…")
            .setMessage("Sur le Wi-Fi de la maison.")
            .setNegativeButton("Annuler", null)
            .show()
        ui.postDelayed({
            if (activite.isFinishing) return@postDelayed
            try { attente.dismiss() } catch (e: Exception) { }
            val routes = routeur.routes.filter { !it.isDefault && it.matchesSelector(selecteur) && it.isEnabled }
            routeur.removeCallback(rappel)
            if (routes.isEmpty()) {
                message("Aucune enceinte ni télé trouvée. Elles doivent être allumées et sur le même Wi-Fi.")
                return@postDelayed
            }
            AlertDialog.Builder(activite)
                .setTitle("Envoyer sur…")
                .setItems(routes.map { it.name }.toTypedArray()) { _, w ->
                    lancer(cc, routeur, routes[w], liens, noms, depart, genre)
                }
                .setNegativeButton("Annuler", null)
                .show()
        }, 4000)
    }

    private fun lancer(
        cc: CastContext, routeur: MediaRouter, route: MediaRouter.RouteInfo,
        liens: Array<String>, noms: Array<String>, depart: Int, genre: String
    ) {
        val gestion = cc.sessionManager
        val dejaLa = gestion.currentCastSession
        if (dejaLa != null && dejaLa.isConnected && dejaLa.castDevice?.friendlyName == route.name) {
            charger(dejaLa, liens, noms, depart, genre, route.name)
            return
        }
        var fait = false
        val ecoute = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(s: CastSession, id: String) {
                if (fait) return
                fait = true
                gestion.removeSessionManagerListener(this, CastSession::class.java)
                charger(s, liens, noms, depart, genre, route.name)
            }
            override fun onSessionStartFailed(s: CastSession, e: Int) {
                gestion.removeSessionManagerListener(this, CastSession::class.java)
                message("${route.name} n'a pas accepté la connexion.")
            }
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionEnded(s: CastSession, e: Int) {}
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionResumed(s: CastSession, b: Boolean) {
                onSessionStarted(s, "")
            }
            override fun onSessionResumeFailed(s: CastSession, e: Int) {}
            override fun onSessionSuspended(s: CastSession, r: Int) {}
        }
        gestion.addSessionManagerListener(ecoute, CastSession::class.java)
        message("Connexion à ${route.name}…")
        routeur.selectRoute(route)
    }

    private fun charger(s: CastSession, liens: Array<String>, noms: Array<String>, depart: Int, genre: String, appareil: String) {
        val client = s.remoteMediaClient ?: run { message("$appareil ne répond pas."); return }
        val elements = liens.indices.map { i ->
            val type = typeDe(liens[i], genre)
            val meta = MediaMetadata(
                when {
                    type.startsWith("audio") -> MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
                    type.startsWith("image") -> MediaMetadata.MEDIA_TYPE_PHOTO
                    else -> MediaMetadata.MEDIA_TYPE_MOVIE
                }
            )
            meta.putString(MediaMetadata.KEY_TITLE, noms.getOrElse(i) { "" })
            val info = MediaInfo.Builder(liens[i])
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(type)
                .setMetadata(meta)
                .build()
            MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(10.0).build()
        }
        @Suppress("DEPRECATION")
        client.queueLoad(elements.toTypedArray(), depart.coerceIn(0, elements.size - 1), MediaStatus.REPEAT_MODE_REPEAT_OFF, null)
            .setResultCallback { r ->
                if (r.status.isSuccess) telecommande(s, appareil)
                else message("$appareil ne sait pas lire ce fichier (format non pris en charge).")
            }
    }

    /** Petite télécommande : pause, précédent, suivant, volume, arrêter. */
    fun telecommande(s: CastSession? = contexte()?.sessionManager?.currentCastSession, appareil: String? = null) {
        val session = s
        if (session == null || !session.isConnected) {
            message("Rien n'est en cours d'envoi.")
            return
        }
        val nom = appareil ?: session.castDevice?.friendlyName ?: "l'appareil"
        val client = session.remoteMediaClient
        val actions = arrayOf("Lecture / pause", "Précédent", "Suivant", "Volume +", "Volume −", "Arrêter l'envoi")
        AlertDialog.Builder(activite)
            .setTitle("En cours sur $nom")
            .setItems(actions) { _, w ->
                try {
                    when (w) {
                        0 -> client?.togglePlayback()
                        1 -> client?.queuePrev(null)
                        2 -> client?.queueNext(null)
                        3 -> session.volume = (session.volume + 0.1).coerceAtMost(1.0)
                        4 -> session.volume = (session.volume - 0.1).coerceAtLeast(0.0)
                        5 -> contexte()?.sessionManager?.endCurrentSession(true)
                    }
                } catch (e: Exception) {
                    message("$nom n'a pas répondu.")
                }
                if (w != 5) ui.postDelayed({ telecommande(session, nom) }, 300)
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    fun enCours(): Boolean = contexte()?.sessionManager?.currentCastSession?.isConnected == true
}

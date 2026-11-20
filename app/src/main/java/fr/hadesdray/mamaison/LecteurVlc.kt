package fr.hadesdray.mamaison

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Lecteur de secours « tous formats » (bibliothèque VLC) pour les vidéos qu'ExoPlayer ne sait pas lire :
 * vieux .avi (XviD/DivX), .wmv, .flv, .mpg, .mkv avec son AC3/DTS, etc.
 * Il prend la place du lecteur normal dans le même cadre et continue la liste jusqu'au bout.
 * Toucher l'écran ou OK = pause/lecture ; flèches gauche/droite = recul/avance de 10 secondes.
 */
class LecteurVlc(
    private val activite: Activity,
    private val conteneur: FrameLayout,
    private val liens: Array<String>,
    private val noms: Array<String>,
    depart: Int,
    positionMs: Long,
    private val titre: TextView?
) {
    // Moteur VLC : on ne saute pas d'images (vieux fichiers mal découpés) et on garde 1,5 s de réserve réseau
    private val libVlc = LibVLC(
        activite,
        arrayListOf("--no-drop-late-frames", "--no-skip-frames", "--network-caching=1500")
    )
    private val lecteur = MediaPlayer(libVlc)
    private val image = VLCVideoLayout(activite)
    private val toucher = View(activite)
    private val barre = TextView(activite)
    private val minuteur = Handler(Looper.getMainLooper())
    private var index = depart
    private var vueAttachee = false
    private var libere = false

    // La barre du bas disparaît 3 secondes après la dernière action
    private val cacherBarre = Runnable { barre.visibility = View.GONE }

    // Tant que la barre est visible, on met le temps à jour deux fois par seconde
    private val rafraichir = object : Runnable {
        override fun run() {
            if (libere || barre.visibility != View.VISIBLE) return
            majBarre()
            minuteur.postDelayed(this, 500)
        }
    }

    init {
        // L'image VLC tout au fond (le titre existant reste par-dessus)
        conteneur.addView(image, 0, FrameLayout.LayoutParams(-1, -1))

        // Couche transparente qui reçoit les touchers (pas « cliquable » pour ne pas recevoir le cadre télé)
        toucher.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) pauseOuLecture()
            true
        }
        conteneur.addView(toucher, FrameLayout.LayoutParams(-1, -1))

        // Petite barre en bas : nom + position / durée
        barre.setTextColor(Color.WHITE)
        barre.textSize = 16f
        barre.setBackgroundColor(0x99000000.toInt())
        barre.setPadding(40, 20, 40, 20)
        barre.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        barre.visibility = View.GONE
        val lpBarre = FrameLayout.LayoutParams(-1, -2)
        lpBarre.gravity = Gravity.BOTTOM
        conteneur.addView(barre, lpBarre)

        // Événements du lecteur (ils arrivent sur le fil principal)
        lecteur.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(e: MediaPlayer.Event) {
                if (libere) return
                when (e.type) {
                    MediaPlayer.Event.EndReached -> suivante()
                    MediaPlayer.Event.EncounteredError -> {
                        Toast.makeText(
                            activite,
                            "Ce fichier ne peut pas être lu (fichier abîmé ou PC éteint). On passe au suivant.",
                            Toast.LENGTH_LONG
                        ).show()
                        suivante()
                    }
                }
            }
        })

        attacher()
        if (liens.isNotEmpty()) jouer(index.coerceIn(0, liens.size - 1), positionMs)
    }

    /** Lance la vidéo n° i, à partir de debutMs millisecondes. */
    private fun jouer(i: Int, debutMs: Long) {
        index = i
        val media = Media(libVlc, Uri.parse(liens[i]))
        media.setHWDecoderEnabled(true, false) // décodage par la puce si possible, sinon par le processeur
        if (debutMs > 1000) media.addOption(":start-time=" + (debutMs / 1000))
        lecteur.setMedia(media)
        media.release() // le lecteur garde sa propre référence
        lecteur.play()
        titre?.text = noms.getOrElse(i) { "" }
        montrerBarre()
    }

    /** Fin de la vidéo (ou erreur) : on passe à la suivante s'il y en a une, sinon on s'arrête. */
    private fun suivante() {
        // On sort du traitement de l'événement avant de changer de vidéo
        minuteur.post {
            if (libere) return@post
            if (index + 1 < liens.size) jouer(index + 1, 0) else montrerBarre()
        }
    }

    private fun pauseOuLecture() {
        if (lecteur.isPlaying) lecteur.pause() else lecteur.play()
        montrerBarre()
    }

    /** Avance (delta positif) ou recule (delta négatif) de delta millisecondes. */
    private fun deplacer(delta: Long) {
        val duree = lecteur.length
        var cible = lecteur.time + delta
        if (cible < 0) cible = 0
        if (duree > 0 && cible > duree - 1000) cible = duree - 1000
        lecteur.setTime(cible)
        montrerBarre()
    }

    private fun montrerBarre() {
        majBarre()
        barre.visibility = View.VISIBLE
        minuteur.removeCallbacks(cacherBarre)
        minuteur.removeCallbacks(rafraichir)
        minuteur.postDelayed(cacherBarre, 3000)
        minuteur.postDelayed(rafraichir, 500)
    }

    private fun majBarre() {
        val etat = if (lecteur.isPlaying) "" else "En pause   "
        val nom = noms.getOrElse(index) { "" }
        barre.text = etat + temps(lecteur.time) + " / " + temps(lecteur.length) + "   " + nom
    }

    /** Touches de la télécommande. Renvoie true si la touche a été utilisée ici. */
    fun touche(e: KeyEvent): Boolean {
        if (libere) return false
        val code = e.keyCode
        val connue = when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        }
        if (!connue) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true // le relâchement est avalé aussi
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (e.repeatCount == 0) pauseOuLecture()
            KeyEvent.KEYCODE_MEDIA_PLAY -> { lecteur.play(); montrerBarre() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { lecteur.pause(); montrerBarre() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> deplacer(-10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> deplacer(10_000)
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (e.repeatCount == 0 && index + 1 < liens.size) jouer(index + 1, 0)
            else -> montrerBarre() // haut/bas : on affiche simplement où on en est
        }
        return true
    }

    /** Branche l'image sur l'écran (au départ et au retour dans l'appli). */
    fun attacher() {
        if (libere || vueAttachee) return
        lecteur.attachViews(image, null, true, false)
        vueAttachee = true
    }

    /** L'appli passe en arrière-plan : pause et on débranche l'image. */
    fun arreter() {
        if (libere) return
        lecteur.pause()
        if (vueAttachee) {
            lecteur.detachViews()
            vueAttachee = false
        }
    }

    /** Libère tout proprement (à appeler une seule fois, à la fermeture). */
    fun liberer() {
        if (libere) return
        libere = true
        minuteur.removeCallbacksAndMessages(null)
        lecteur.setEventListener(null as MediaPlayer.EventListener?)
        lecteur.stop()
        if (vueAttachee) lecteur.detachViews()
        vueAttachee = false
        lecteur.release()
        libVlc.release()
    }

    companion object {
        /** Formats qu'on envoie tout de suite à VLC, sans essayer ExoPlayer. */
        private val FORMATS_VLC = setOf("avi", "wmv", "flv", "mpg", "mpeg", "divx", "vob", "rmvb", "3gp")

        /** Vrai si le fichier (lien de la passerelle ou nom) a une extension de la liste VLC. */
        fun pourVlc(lien: String, nom: String): Boolean = extension(lien, nom) in FORMATS_VLC

        /** Extension en minuscules : d'abord le chemin du PC (?chemin=...), puis le nom, puis le lien. */
        fun extension(lien: String, nom: String): String {
            val u = Uri.parse(lien)
            val chemin = try { u.getQueryParameter("chemin") } catch (_: Throwable) { null } ?: (u.path ?: "")
            for (t in listOf(chemin, nom, lien)) {
                val e = t.substringAfterLast('.', "").lowercase()
                if (e.length in 2..5 && e.all { it.isLetterOrDigit() }) return e
            }
            return ""
        }

        /** 754000 ms -> « 12:34 » ; plus d'une heure -> « 1:02:03 ». */
        fun temps(ms: Long): String {
            if (ms <= 0) return "0:00"
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
        }
    }
}

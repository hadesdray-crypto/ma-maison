package fr.hadesdray.mamaison

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.ui.StyledPlayerView

/**
 * Lecteur des vidéos et musiques du PC (et des flux de caméras). Flèche retour = on revient.
 * Lecture en cascade : d'abord ExoPlayer ; s'il échoue (erreur, ou son sans image au bout de 6 s),
 * ou si le format est un vieux format (.avi, .wmv...), on passe au lecteur VLC (LecteurVlc) au même endroit.
 */
class LecteurActivity : Activity() {

    private var lecteur: ExoPlayer? = null
    private var vlc: LecteurVlc? = null
    private var vue: StyledPlayerView? = null
    private lateinit var cadre: FrameLayout
    private lateinit var titre: TextView
    private var liens: Array<String> = arrayOf()
    private var noms: Array<String> = arrayOf()
    private var musique = false
    private var boutonCadrage: Button? = null

    // Cadrage de l'image, retenu : 0 = image normale, 1 = remplir l'écran, 2 = étirer
    private val nomsCadrage = arrayOf("Image normale", "Remplir l'écran", "Étirer")
    private var cadrage: Int
        get() = getSharedPreferences("maison", MODE_PRIVATE).getInt("cadrage", 0).coerceIn(0, 2)
        set(v) { getSharedPreferences("maison", MODE_PRIVATE).edit().putInt("cadrage", v).apply() }

    // Surveillance « son sans image » : vrai dès qu'ExoPlayer a affiché une première image
    private val minuteur = Handler(Looper.getMainLooper())
    private var imageVue = false
    private val verifierImage = Runnable { verifierImageExo() }

    // Coupures de connexion : on reprend au même endroit au lieu d'abandonner
    private var reprises = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Tv.attach(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        liens = intent.getStringArrayExtra("liens") ?: arrayOf()
        noms = intent.getStringArrayExtra("noms") ?: arrayOf()
        val depart = if (liens.isEmpty()) 0 else intent.getIntExtra("index", 0).coerceIn(0, liens.size - 1)
        musique = intent.getBooleanExtra("musique", false)

        cadre = FrameLayout(this)
        cadre.setBackgroundColor(Color.BLACK)
        val v = StyledPlayerView(this)
        vue = v
        v.setShowNextButton(true)
        v.setShowPreviousButton(true)
        cadre.addView(v, FrameLayout.LayoutParams(-1, -1))
        titre = TextView(this)
        titre.setTextColor(Color.WHITE)
        titre.textSize = if (musique) 24f else 16f
        titre.setPadding(40, 40, 40, 40)
        titre.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        cadre.addView(titre, FrameLayout.LayoutParams(-1, -2))
        if (!musique) {
            // Bouton « Cadrage » en haut à droite : image normale, remplir l'écran, étirer
            val bc = Button(this)
            bc.isAllCaps = false
            bc.textSize = 14f
            bc.setTextColor(Color.WHITE)
            val fond = GradientDrawable()
            fond.setColor(Color.argb(150, 0, 0, 0))
            fond.cornerRadius = 18 * resources.displayMetrics.density
            bc.background = fond
            val d = resources.displayMetrics.density
            bc.setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
            bc.text = "Cadrage : " + nomsCadrage[cadrage]
            bc.setOnClickListener {
                cadrage = (cadrage + 1) % 3
                bc.text = "Cadrage : " + nomsCadrage[cadrage]
                appliquerCadrage()
                Toast.makeText(this, nomsCadrage[cadrage], Toast.LENGTH_SHORT).show()
            }
            val lpc = FrameLayout.LayoutParams(-2, -2)
            lpc.gravity = Gravity.TOP or Gravity.END
            lpc.setMargins(0, (14 * d).toInt(), (14 * d).toInt(), 0)
            cadre.addView(bc, lpc)
            boutonCadrage = bc
            // Avec ExoPlayer, le bouton apparaît et disparaît avec les commandes du lecteur
            v.setControllerVisibilityListener(StyledPlayerView.ControllerVisibilityListener { vis ->
                if (vlc == null) bc.visibility = vis
            })
        }
        setContentView(cadre)
        appliquerCadrage()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        if (liens.isEmpty()) return
        titre.text = noms.getOrElse(depart) { "" }

        // Vieux format connu : directement VLC (plus rapide que d'attendre l'échec d'ExoPlayer)
        if (LecteurVlc.pourVlc(liens[depart], noms.getOrElse(depart) { "" })) {
            basculerVlc(depart, 0)
            return
        }

        // Grosse réserve (jusqu'à 2 min d'avance) : la vidéo continue pendant les petites coupures
        val reserve = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 120000, 2500, 5000)
            .build()
        val p = ExoPlayer.Builder(this).setLoadControl(reserve).build()
        lecteur = p
        v.player = p
        if (musique) v.controllerShowTimeoutMs = 0
        for (i in liens.indices) {
            val nom = noms.getOrElse(i) { "" }
            p.addMediaItem(
                MediaItem.Builder().setUri(liens[i])
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(nom).build()).build()
            )
        }
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                titre.text = item?.mediaMetadata?.title ?: ""
                val i = p.currentMediaItemIndex
                // La vidéo suivante est un vieux format : on passe à VLC pour elle
                if (i in liens.indices && LecteurVlc.pourVlc(liens[i], noms.getOrElse(i) { "" })) {
                    basculerVlc(i, 0)
                } else {
                    surveillerImage()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Son (AC3/DTS...) ou image présents mais illisibles ici : film muet ou noir -> VLC
                if (tracks.isEmpty) return
                val sonKo = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                val imageKo = tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
                if (sonKo || imageKo) basculerVlc(p.currentMediaItemIndex, p.currentPosition)
            }

            override fun onRenderedFirstFrame() {
                imageVue = true
            }

            override fun onPlaybackStateChanged(etat: Int) {
                Log.i("MaisonLecteur", "etat=$etat position=${p.currentPosition} reserve=${p.bufferedPosition}")
                if (etat == Player.STATE_READY) {
                    reprises = 0
                    titre.text = p.currentMediaItem?.mediaMetadata?.title ?: ""
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Coupure de connexion (et non fichier illisible) : on reprend au même endroit, 8 essais
                val reseau = error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_TIMEOUT
                )
                Log.i("MaisonLecteur", "erreur=${error.errorCode} reseau=$reseau reprises=$reprises")
                if (reseau && reprises < 8) {
                    reprises++
                    titre.text = "Connexion coupée : reprise… ($reprises/8)"
                    minuteur.postDelayed({
                        if (!isFinishing && vlc == null && lecteur === p) { p.prepare(); p.play() }
                    }, 3000L * reprises)
                    return
                }
                // ExoPlayer ne sait pas lire ce fichier : VLC essaie le même, au même endroit
                basculerVlc(p.currentMediaItemIndex, p.currentPosition)
            }
        })
        p.seekTo(depart, 0)
        p.prepare()
        p.play()
        surveillerImage()
    }

    /** Relance l'attente de 6 s pour vérifier qu'ExoPlayer affiche bien une image. */
    private fun surveillerImage() {
        imageVue = false
        minuteur.removeCallbacks(verifierImage)
        if (!musique) minuteur.postDelayed(verifierImage, 6000)
    }

    /** Au bout de 6 s : le son ou la durée sont là mais aucune image -> VLC. */
    private fun verifierImageExo() {
        val p = lecteur ?: return
        if (imageVue || vlc != null) return
        if (p.playbackState != Player.STATE_READY) {
            // Encore en chargement (PC lent) : on regarde de nouveau un peu plus tard
            if (p.playbackState == Player.STATE_BUFFERING) minuteur.postDelayed(verifierImage, 3000)
            return
        }
        val dureeLa = p.duration != C.TIME_UNSET && p.duration > 0
        val sonLa = p.currentTracks.containsType(C.TRACK_TYPE_AUDIO)
        if (dureeLa || sonLa) basculerVlc(p.currentMediaItemIndex, p.currentPosition)
    }

    /** Remplace ExoPlayer par VLC pour la vidéo n° index, à la position donnée. */
    private fun basculerVlc(index: Int, positionMs: Long) {
        if (vlc != null || isFinishing) return
        minuteur.removeCallbacks(verifierImage)
        val i = index.coerceIn(0, liens.size - 1)
        val pos = if (positionMs > 0) positionMs else 0L
        // On sort d'abord du rappel d'ExoPlayer avant de le libérer
        minuteur.post {
            if (vlc != null || isFinishing || isDestroyed) return@post
            lecteur?.release()
            lecteur = null
            vue?.player = null
            vue?.let { cadre.removeView(it) }
            vue = null
            vlc = LecteurVlc(this, cadre, liens, noms, i, pos, titre)
            appliquerCadrage()
            // Le bouton reste par-dessus l'image de VLC, toujours visible
            boutonCadrage?.let { it.visibility = View.VISIBLE; cadre.bringChildToFront(it) }
        }
    }

    /** Applique le cadrage choisi au lecteur en cours (ExoPlayer ou VLC). */
    private fun appliquerCadrage() {
        if (musique) return
        vue?.resizeMode = when (cadrage) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        vlc?.cadrage(cadrage)
    }

    // Télécommande : quand VLC lit, c'est lui qui reçoit OK et les flèches (la flèche retour reste normale)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val v = vlc
        if (v != null && v.touche(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        vlc?.attacher()
    }

    override fun onStop() {
        super.onStop()
        lecteur?.pause()
        vlc?.arreter()
    }

    override fun onDestroy() {
        minuteur.removeCallbacksAndMessages(null)
        lecteur?.release()
        lecteur = null
        vlc?.liberer()
        vlc = null
        super.onDestroy()
    }
}

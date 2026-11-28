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
import com.google.android.exoplayer2.text.CueGroup
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
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
    private var boutonReglages: Button? = null
    private var reglages: Reglages? = null
    private var indexReglages = -1

    // Cadrage de l'image, retenu : 0 = image normale, 1 = zoom (remplir en coupant), 2 = étirer (se choisit dans Réglages)
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
            // Un seul bouton « Réglages » en haut à droite : langue, sous-titres, vitesse, format de l'image
            val rg = Reglages(this, cadre)
            reglages = rg
            rg.lireCadrage = { cadrage }
            rg.poserCadrage = { cadrage = it; appliquerCadrage() }
            rg.pilote = exoPilote
            val d = resources.displayMetrics.density
            val bc = Button(this)
            bc.isAllCaps = false
            bc.textSize = 16f
            bc.text = "Réglages"
            bc.setTextColor(Color.WHITE)
            val fond = GradientDrawable()
            fond.setColor(Color.argb(170, 0, 0, 0))
            fond.cornerRadius = 20 * d
            bc.background = fond
            bc.minimumHeight = (48 * d).toInt()
            bc.minHeight = (48 * d).toInt()
            bc.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            bc.setOnClickListener { rg.ouvrir() }
            val lpc = FrameLayout.LayoutParams(-2, -2)
            lpc.gravity = Gravity.TOP or Gravity.END
            lpc.setMargins(0, (12 * d).toInt(), (12 * d).toInt(), 0)
            cadre.addView(bc, lpc)
            boutonReglages = bc
            titre.setPadding(40, 40, (150 * d).toInt(), 40)
            // Le menu de l'ancien lecteur (roue crantée, bouton sous-titres) est remplacé par « Réglages »
            v.setShowSubtitleButton(false)
            v.subtitleView?.visibility = View.GONE
            v.findViewById<View>(com.google.android.exoplayer2.ui.R.id.exo_settings)?.visibility = View.GONE
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
        debutVideo(depart)

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
        reglages?.pilote = exoPilote
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
                debutVideo(i)
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
                if (sonKo || imageKo) { basculerVlc(p.currentMediaItemIndex, p.currentPosition); return }
                // Langue préférée et sous-titres : choisis tout seuls au début de chaque vidéo
                if (!musique) reglages?.pistesPretes()
            }

            override fun onCues(cueGroup: CueGroup) {
                reglages?.texteIntegre(cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n"))
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
            debutVideo(i)
            vlc = LecteurVlc(this, cadre, liens, noms, i, pos, titre, reglages)
            appliquerCadrage()
            // Le bouton reste par-dessus l'image de VLC, toujours visible
            boutonReglages?.let { it.visibility = View.VISIBLE; cadre.bringChildToFront(it) }
        }
    }

    /** Une nouvelle vidéo commence (une seule fois par vidéo) : sous-titres en fichier à chercher, langue à choisir. */
    private fun debutVideo(i: Int) {
        if (musique || i == indexReglages || i !in liens.indices) return
        indexReglages = i
        reglages?.nouvelleVideo(liens[i], noms.getOrElse(i) { "" })
    }

    /** Ce que le menu Réglages sait faire avec ExoPlayer. */
    private val exoPilote = object : Pilote {
        private val formatsImage = listOf("pgs", "dvb", "vobsub", "dvd")

        private fun pistes(type: Int): List<Piste> {
            val p = lecteur ?: return emptyList()
            val out = ArrayList<Piste>()
            var n = 0
            p.currentTracks.groups.forEachIndexed { gi, g ->
                if (g.type != type || g.length == 0) return@forEachIndexed
                n++
                val f = g.getTrackFormat(0)
                var ok = g.isTrackSupported(0)
                if (type == C.TRACK_TYPE_TEXT) {
                    val mime = (f.sampleMimeType ?: "").lowercase()
                    if (formatsImage.any { mime.contains(it) }) ok = false
                }
                out.add(Reglages.piste("g$gi", f.language, f.label, n, ok))
            }
            return out
        }

        override fun pistesAudio(): List<Piste> = pistes(C.TRACK_TYPE_AUDIO)

        override fun audioActuel(): String? {
            val p = lecteur ?: return null
            p.currentTracks.groups.forEachIndexed { gi, g ->
                if (g.type == C.TRACK_TYPE_AUDIO && g.length > 0 && g.isTrackSelected(0)) return "g$gi"
            }
            return null
        }

        override fun choisirAudio(id: String) {
            val p = lecteur ?: return
            val g = p.currentTracks.groups.getOrNull(id.removePrefix("g").toIntOrNull() ?: return) ?: return
            if (!g.isTrackSupported(0)) {
                // Cette langue n'est pas lisible par ExoPlayer : le lecteur de secours la lit, au même endroit
                basculerVlc(p.currentMediaItemIndex, p.currentPosition)
                return
            }
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
        }

        override fun pistesSousTitres(): List<Piste> = pistes(C.TRACK_TYPE_TEXT)

        override fun choisirSousTitre(id: String?) {
            val p = lecteur ?: return
            val b = p.trackSelectionParameters.buildUpon()
            if (id == null) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                val g = p.currentTracks.groups.getOrNull(id.removePrefix("g").toIntOrNull() ?: return) ?: return
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            }
            p.trackSelectionParameters = b.build()
        }

        override fun position(): Long = lecteur?.currentPosition ?: 0L
        override fun vitesse(): Float = lecteur?.playbackParameters?.speed ?: 1f
        override fun changerVitesse(v: Float) { lecteur?.setPlaybackSpeed(v) }
        override fun styleSousTitresChange() { }
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
        val rg = reglages
        // Touche « menu » : ouvre Réglages
        if (rg != null && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) rg.ouvrir()
            return true
        }
        val v = vlc
        val bouton = boutonReglages
        if (v != null && bouton != null) {
            if (bouton.hasFocus()) {
                // Le bouton Réglages est choisi : OK l'ouvre, « bas » rend la main à la vidéo
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { if (event.action == KeyEvent.ACTION_DOWN) bouton.clearFocus(); return true }
                return super.dispatchKeyEvent(event)
            }
            // « Haut » à la télécommande : va au bouton Réglages
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.action == KeyEvent.ACTION_DOWN) { bouton.visibility = View.VISIBLE; bouton.requestFocus() }
                return true
            }
        }
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
        reglages?.liberer()
        lecteur?.release()
        lecteur = null
        vlc?.liberer()
        vlc = null
        super.onDestroy()
    }
}

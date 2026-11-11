package fr.hadesdray.mamaison

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView

/** Lecteur des vidéos et musiques du PC (et des flux de caméras). Flèche retour = on revient. */
class LecteurActivity : Activity() {

    private var lecteur: ExoPlayer? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val liens = intent.getStringArrayExtra("liens") ?: arrayOf()
        val noms = intent.getStringArrayExtra("noms") ?: arrayOf()
        val depart = intent.getIntExtra("index", 0)
        val musique = intent.getBooleanExtra("musique", false)

        val cadre = FrameLayout(this)
        cadre.setBackgroundColor(Color.BLACK)
        val vue = StyledPlayerView(this)
        vue.setShowNextButton(true)
        vue.setShowPreviousButton(true)
        cadre.addView(vue, FrameLayout.LayoutParams(-1, -1))
        val titre = TextView(this)
        titre.setTextColor(Color.WHITE)
        titre.textSize = if (musique) 24f else 16f
        titre.setPadding(40, 40, 40, 40)
        titre.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        cadre.addView(titre, FrameLayout.LayoutParams(-1, -2))
        setContentView(cadre)

        val p = ExoPlayer.Builder(this).build()
        lecteur = p
        vue.player = p
        if (musique) vue.controllerShowTimeoutMs = 0
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
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@LecteurActivity,
                    "Ce fichier ne peut pas être lu ici (format non pris en charge ou PC éteint). On passe au suivant.",
                    Toast.LENGTH_LONG
                ).show()
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            }
        })
        if (liens.isNotEmpty()) {
            p.seekTo(depart.coerceIn(0, liens.size - 1), 0)
            titre.text = noms.getOrElse(depart) { "" }
            p.prepare()
            p.play()
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onStop() {
        super.onStop()
        lecteur?.pause()
    }

    override fun onDestroy() {
        lecteur?.release()
        lecteur = null
        super.onDestroy()
    }
}

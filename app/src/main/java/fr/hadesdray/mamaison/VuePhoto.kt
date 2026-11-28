package fr.hadesdray.mamaison

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Photo qu'on peut tourner (quart de tour), agrandir à deux doigts ou d'un double appui,
 * déplacer du doigt une fois agrandie, et faire glisser à gauche/droite pour changer de photo.
 */
class VuePhoto(c: Context) : ImageView(c) {
    var suivante: () -> Unit = {}
    var precedente: () -> Unit = {}
    /** Appui simple (sans zoom ni glissement) : afficher/cacher les boutons. */
    var appuiSimple: () -> Unit = {}

    private var bmp: Bitmap? = null
    private var quarts = 0            // 0..3 : nombre de quarts de tour vers la droite
    private var zoom = 1f              // 1 = photo entière à l'écran
    private var dx = 0f
    private var dy = 0f
    val agrandie: Boolean get() = zoom > 1.01f

    private val pinch = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val avant = zoom
            zoom = min(6f, max(1f, zoom * d.scaleFactor))
            // On agrandit autour des doigts
            val f = zoom / avant
            val cx = d.focusX - width / 2f
            val cy = d.focusY - height / 2f
            dx = cx - (cx - dx) * f
            dy = cy - (cy - dy) * f
            appliquer()
            return true
        }
    })

    private val gestes = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { appuiSimple(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (agrandie) { zoom = 1f; dx = 0f; dy = 0f } else {
                zoom = 2.5f
                dx = -(e.x - width / 2f) * 1.5f
                dy = -(e.y - height / 2f) * 1.5f
            }
            appliquer()
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, sx: Float, sy: Float): Boolean {
            if (!agrandie) return false
            dx -= sx; dy -= sy
            appliquer()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (agrandie || e1 == null) return false
            val ecart = e2.x - e1.x
            if (abs(ecart) > width / 6f && abs(vx) > abs(vy)) {
                if (ecart < 0) suivante() else precedente()
                return true
            }
            return false
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    fun montrer(b: Bitmap?, quartsDeTour: Int) {
        bmp = b
        quarts = ((quartsDeTour % 4) + 4) % 4
        zoom = 1f; dx = 0f; dy = 0f
        setImageBitmap(b)
        appliquer()
    }

    /** Bouton « Agrandir » (utile à la télécommande) : agrandit le centre, ou revient à la photo entière. */
    fun basculerZoom() {
        if (agrandie) zoom = 1f else zoom = 2.5f
        dx = 0f; dy = 0f
        appliquer()
    }

    /** Un quart de tour (+1 vers la droite, -1 vers la gauche). Renvoie le nouveau nombre de quarts. */
    fun tourner(sens: Int): Int {
        quarts = (((quarts + sens) % 4) + 4) % 4
        zoom = 1f; dx = 0f; dy = 0f
        appliquer()
        return quarts
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        appliquer()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        pinch.onTouchEvent(e)
        if (!pinch.isInProgress) gestes.onTouchEvent(e)
        return true
    }

    private fun appliquer() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        val couche = quarts % 2 == 1
        val lw = if (couche) bh else bw
        val lh = if (couche) bw else bh
        val base = min(width / lw, height / lh)
        val ech = base * zoom
        // Pas de déplacement au-delà des bords de la photo
        val mx = max(0f, (lw * ech - width) / 2f)
        val my = max(0f, (lh * ech - height) / 2f)
        dx = min(mx, max(-mx, dx))
        dy = min(my, max(-my, dy))
        val m = Matrix()
        m.postTranslate(-bw / 2f, -bh / 2f)
        m.postRotate(quarts * 90f)
        m.postScale(ech, ech)
        m.postTranslate(width / 2f + dx, height / 2f + dy)
        imageMatrix = m
        invalidate()
    }
}

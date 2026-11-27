package fr.hadesdray.mamaison

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * Télé (Freebox Player et toute box Android TV) : tout ce qui se touche devient atteignable
 * avec les flèches de la télécommande, avec un cadre blanc bien visible sur l'élément choisi.
 */
object Tv {
    private const val MARK = 0x7f0b7e11

    fun isTv(c: Context): Boolean = try {
        (c.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            c.packageManager.hasSystemFeature("android.software.leanback")
    } catch (_: Throwable) { false }

    fun attach(a: Activity) {
        if (!isTv(a)) return
        val root = a.window.decorView
        root.viewTreeObserver.addOnGlobalLayoutListener { walk(root, a) }
        root.post {
            walk(root, a)
            if (a.currentFocus == null) firstFocusable(root)?.requestFocus()
        }
    }

    private fun frame(c: Context): StateListDrawable {
        val d = c.resources.displayMetrics.density
        val on = GradientDrawable().apply {
            setColor(Color.argb(40, 255, 255, 255)); cornerRadius = 12 * d; setStroke((3 * d).toInt(), Color.WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), on)
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun walk(v: View, c: Context) {
        if (v.isClickable && v.visibility == View.VISIBLE && v !is EditText && v !is android.widget.AbsListView) {
            if (!v.isFocusable) v.isFocusable = true
            if (Build.VERSION.SDK_INT >= 23 && v.getTag(MARK) == null && v.foreground == null) {
                v.foreground = frame(c); v.setTag(MARK, true)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), c)
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE && v.isClickable) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }
}

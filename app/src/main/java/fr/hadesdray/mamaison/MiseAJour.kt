package fr.hadesdray.mamaison

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour sur place : au lancement (ou sur demande), l'appli lit la liste des changements,
 * l'affiche, télécharge la nouvelle version avec une barre de chargement, puis Android
 * propose d'installer par-dessus l'ancienne (même signature : les réglages sont conservés).
 * Sans connexion, rien ne s'affiche et l'appli marche normalement.
 */
class MiseAJour(private val activite: Activity) {

    companion object {
        const val ADRESSE = "https://raw.githubusercontent.com/hadesdray-crypto/ma-maison/main/version.json"
    }

    private val ui = Handler(Looper.getMainLooper())
    private var enCours = false

    private fun dp(v: Int): Int = (v * activite.resources.displayMetrics.density).toInt()

    private fun versionInstallee(): Int = try {
        val info = activite.packageManager.getPackageInfo(activite.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (e: Exception) {
        0
    }

    /** manuel = true : on répond aussi quand il n'y a rien de nouveau ou pas de connexion. */
    fun verifier(manuel: Boolean) {
        if (enCours) return
        enCours = true
        Thread {
            var resultat: JSONObject? = null
            var erreur = false
            try {
                val c = URL(ADRESSE + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                if (c.responseCode == 200) {
                    resultat = JSONObject(c.inputStream.bufferedReader().readText())
                } else {
                    erreur = true
                }
                c.disconnect()
            } catch (e: Exception) {
                erreur = true
            }
            ui.post {
                enCours = false
                if (activite.isFinishing) return@post
                val r = resultat
                if (r == null || erreur) {
                    if (manuel) Toast.makeText(activite, "Impossible de vérifier : pas de connexion.", Toast.LENGTH_LONG).show()
                } else if (r.optInt("code", 0) > versionInstallee()) {
                    proposer(r)
                } else if (manuel) {
                    Toast.makeText(activite, "Tu as déjà la dernière version.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun proposer(r: JSONObject) {
        val liste = StringBuilder()
        val tab = r.optJSONArray("changements")
        if (tab != null) {
            for (i in 0 until tab.length()) liste.append("• ").append(tab.optString(i)).append("\n\n")
        }
        val t = TextView(activite)
        t.text = if (liste.isEmpty()) "Nouvelle version disponible." else liste.toString().trim()
        t.textSize = 16f
        t.setPadding(dp(20), dp(12), dp(20), dp(12))
        val defil = ScrollView(activite)
        defil.addView(t)
        AlertDialog.Builder(activite)
            .setTitle("Mise à jour " + r.optString("nom", ""))
            .setView(defil)
            .setPositiveButton("Mettre à jour") { _, _ -> telecharger(r.optString("apk")) }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private fun telecharger(adresse: String) {
        if (adresse.isEmpty()) return
        val barre = ProgressBar(activite, null, android.R.attr.progressBarStyleHorizontal)
        barre.max = 100
        val texte = TextView(activite)
        texte.text = "Téléchargement…"
        texte.textSize = 16f
        val boite = LinearLayout(activite)
        boite.orientation = LinearLayout.VERTICAL
        boite.gravity = Gravity.CENTER_HORIZONTAL
        boite.setPadding(dp(24), dp(16), dp(24), dp(8))
        boite.addView(texte)
        boite.addView(barre, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val fenetre = AlertDialog.Builder(activite)
            .setTitle("Mise à jour en cours")
            .setView(boite)
            .setCancelable(false)
            .show()
        Thread {
            var fichier: File? = null
            try {
                val dossier = File(activite.cacheDir, "maj")
                dossier.mkdirs()
                dossier.listFiles()?.forEach { it.delete() }
                val f = File(dossier, "ma-maison.apk")
                val c = URL(adresse).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                val total = c.contentLength
                var lu = 0L
                c.inputStream.use { entree ->
                    f.outputStream().use { sortie ->
                        val tampon = ByteArray(16384)
                        while (true) {
                            val n = entree.read(tampon)
                            if (n < 0) break
                            sortie.write(tampon, 0, n)
                            lu += n
                            if (total > 0) {
                                val p = (lu * 100 / total).toInt()
                                ui.post {
                                    barre.progress = p
                                    texte.text = "Téléchargement… $p %"
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                if (f.length() > 100000) fichier = f
            } catch (e: Exception) {
                fichier = null
            }
            ui.post {
                try {
                    fenetre.dismiss()
                } catch (e: Exception) {
                    // fenêtre déjà fermée
                }
                if (fichier == null) {
                    Toast.makeText(activite, "Le téléchargement a échoué. Réessaie avec une meilleure connexion.", Toast.LENGTH_LONG).show()
                } else {
                    installer(fichier)
                }
            }
        }.start()
    }

    private fun installer(f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activite.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activite, "Autorise Ma Maison à installer des mises à jour, puis reviens et relance la mise à jour.", Toast.LENGTH_LONG).show()
            try {
                activite.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activite.packageName))
                )
            } catch (e: Exception) {
                // réglage introuvable
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activite, activite.packageName + ".fichiers", f)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activite.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(activite, "Impossible de lancer l'installation.", Toast.LENGTH_LONG).show()
        }
    }
}

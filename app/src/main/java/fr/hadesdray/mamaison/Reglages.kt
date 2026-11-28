package fr.hadesdray.mamaison

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Une piste de la vidéo (son ou sous-titres). code = langue normalisée sur 2 lettres (« fr »), ou vide si inconnue. */
class Piste(val id: String, val code: String, val nom: String, val supporte: Boolean = true)

/** Ce que chaque lecteur (ExoPlayer, VLC) sait faire ; le menu Réglages est le même pour les deux. */
interface Pilote {
    fun pistesAudio(): List<Piste>
    fun audioActuel(): String?
    fun choisirAudio(id: String)
    /** Sous-titres intégrés à la vidéo ; null = aucun sous-titre intégré. */
    fun pistesSousTitres(): List<Piste>
    fun choisirSousTitre(id: String?)
    fun position(): Long
    fun vitesse(): Float
    fun changerVitesse(v: Float)
    /** La taille ou la couleur des sous-titres vient de changer. */
    fun styleSousTitresChange()
}

/** Langues : code ISO (fra, eng, jpn…) -> nom en clair en français. */
object Langues {
    // code sur 2 lettres, nom français, puis les autres écritures possibles (codes sur 3 lettres, noms)
    private val table: List<Array<String>> = listOf(
        arrayOf("fr", "Français", "fra", "fre", "francais", "français", "french", "vf", "vff", "vfq", "vostfr"),
        arrayOf("en", "Anglais", "eng", "anglais", "english"),
        arrayOf("ja", "Japonais", "jpn", "japonais", "japanese"),
        arrayOf("de", "Allemand", "deu", "ger", "allemand", "german", "deutsch"),
        arrayOf("es", "Espagnol", "spa", "espagnol", "spanish", "castellano", "español"),
        arrayOf("it", "Italien", "ita", "italien", "italian", "italiano"),
        arrayOf("pt", "Portugais", "por", "portugais", "portuguese", "português"),
        arrayOf("ru", "Russe", "rus", "russe", "russian"),
        arrayOf("zh", "Chinois", "zho", "chi", "chinois", "chinese", "mandarin", "cantonese", "cmn", "yue"),
        arrayOf("ko", "Coréen", "kor", "coréen", "coreen", "korean"),
        arrayOf("ar", "Arabe", "ara", "arabe", "arabic"),
        arrayOf("nl", "Néerlandais", "nld", "dut", "néerlandais", "neerlandais", "dutch"),
        arrayOf("pl", "Polonais", "pol", "polonais", "polish"),
        arrayOf("tr", "Turc", "tur", "turc", "turkish"),
        arrayOf("hi", "Hindi", "hin"),
        arrayOf("sv", "Suédois", "swe", "suédois", "suedois", "swedish"),
        arrayOf("no", "Norvégien", "nor", "nob", "nno", "norvégien", "norvegien", "norwegian"),
        arrayOf("da", "Danois", "dan", "danois", "danish"),
        arrayOf("fi", "Finnois", "fin", "finnois", "finnish"),
        arrayOf("cs", "Tchèque", "ces", "cze", "tchèque", "tcheque", "czech"),
        arrayOf("el", "Grec", "ell", "gre", "grec", "greek"),
        arrayOf("he", "Hébreu", "heb", "hébreu", "hebreu", "hebrew"),
        arrayOf("hu", "Hongrois", "hun", "hongrois", "hungarian"),
        arrayOf("ro", "Roumain", "ron", "rum", "roumain", "romanian"),
        arrayOf("uk", "Ukrainien", "ukr", "ukrainien", "ukrainian"),
        arrayOf("th", "Thaï", "tha", "thaï", "thai"),
        arrayOf("vi", "Vietnamien", "vie", "vietnamien", "vietnamese"),
        arrayOf("id", "Indonésien", "ind", "indonésien", "indonesien", "indonesian"),
        arrayOf("ca", "Catalan", "cat", "catalan"),
        arrayOf("la", "Latin", "lat", "latin"),
        arrayOf("fa", "Persan", "fas", "per", "persan", "persian"),
        arrayOf("bg", "Bulgare", "bul", "bulgare", "bulgarian"),
        arrayOf("hr", "Croate", "hrv", "croate", "croatian"),
        arrayOf("sr", "Serbe", "srp", "serbe", "serbian"),
        arrayOf("sk", "Slovaque", "slk", "slo", "slovaque", "slovak"),
        arrayOf("sl", "Slovène", "slv", "slovène", "slovene", "slovenian"),
        arrayOf("ms", "Malais", "msa", "may", "malais", "malay"),
        arrayOf("tl", "Philippin", "fil", "tgl", "philippin", "filipino", "tagalog"),
        arrayOf("is", "Islandais", "isl", "ice", "islandais", "icelandic"),
        arrayOf("et", "Estonien", "est", "estonien", "estonian"),
        arrayOf("lv", "Letton", "lav", "letton", "latvian"),
        arrayOf("lt", "Lituanien", "lit", "lituanien", "lithuanian"),
        arrayOf("eu", "Basque", "eus", "baq", "basque"),
        arrayOf("br", "Breton", "bre", "breton")
    )

    /** Code ISO (« fr », « fra », « fr-FR », « eng »…) -> code sur 2 lettres, ou null si inconnu ou non précisé. */
    fun code(c: String?): String? {
        if (c == null) return null
        val t = c.trim().lowercase().replace('_', '-').substringBefore('-')
        if (t.isEmpty() || t == "und" || t == "mul" || t == "zxx" || t == "mis") return null
        for (l in table) if (t == l[0] || l.indexOf(t) >= 2) return l[0]
        return null
    }

    /** Cherche un nom de langue dans un texte libre (« Track 2 - [Japanese] », « Anglais 5.1 »). */
    fun deviner(texte: String?): String? {
        if (texte.isNullOrBlank()) return null
        for (tok in texte.lowercase().split(Regex("[^\\p{L}]+"))) {
            if (tok.length < 4 && tok != "vf") continue
            for (l in table) if (l.indexOf(tok) >= 2) return l[0]
        }
        return null
    }

    fun nom(code: String): String = table.firstOrNull { it[0] == code }?.get(1) ?: code
}

/** Une réplique de sous-titre (fichier .srt, .vtt, .ass). */
class Replique(val debut: Long, val fin: Long, val texte: String)

/** Un fichier de sous-titres trouvé à côté de la vidéo. */
class Externe(val code: String, val nom: String, val repliques: List<Replique>)

/** Lecture des fichiers de sous-titres. */
object Sous {
    fun decoder(octets: ByteArray): String {
        var s = String(octets, Charsets.UTF_8)
        if (s.indexOf('�') >= 0) s = try { String(octets, charset("windows-1252")) } catch (e: Exception) { String(octets, Charsets.ISO_8859_1) }
        return s.removePrefix("﻿")
    }

    private val heure = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val heureAss = Regex("""(\d+):(\d{2}):(\d{2})[.,](\d{1,3})""")

    private fun ms(m: MatchResult, ass: Boolean): Long {
        val h = m.groupValues[1].ifEmpty { "0" }.toLong()
        val mi = m.groupValues[2].toLong()
        val se = m.groupValues[3].toLong()
        val fr = m.groupValues[4]
        // .srt/.vtt : millisecondes ; .ass : centièmes de seconde
        val milli = if (ass && fr.length <= 2) fr.padEnd(2, '0').toLong() * 10 else fr.padEnd(3, '0').toLong()
        return ((h * 60 + mi) * 60 + se) * 1000 + milli
    }

    private fun nettoyer(t: String): String =
        t.replace(Regex("""\{[^}]*\}"""), "").replace(Regex("<[^>]*>"), "")
            .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()

    fun lire(octets: ByteArray, ext: String): List<Replique> {
        val s = decoder(octets).replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<Replique>()
        if (ext == "ass" || ext == "ssa") {
            for (l in s.split('\n')) {
                if (!l.startsWith("Dialogue:")) continue
                val c = l.substringAfter(':').split(',', limit = 10)
                if (c.size < 10) continue
                val a = heureAss.find(c[1].trim()) ?: continue
                val b = heureAss.find(c[2].trim()) ?: continue
                val t = nettoyer(c[9])
                if (t.isNotEmpty()) out.add(Replique(ms(a, true), ms(b, true), t))
            }
        } else {
            for (bloc in s.split(Regex("\n{2,}"))) {
                val lignes = bloc.split('\n')
                val i = lignes.indexOfFirst { it.contains("-->") }
                if (i < 0) continue
                val tps = heure.findAll(lignes[i]).toList()
                if (tps.size < 2) continue
                val t = nettoyer(lignes.drop(i + 1).joinToString("\n"))
                if (t.isNotEmpty()) out.add(Replique(ms(tps[0], false), ms(tps[1], false), t))
            }
        }
        out.sortBy { it.debut }
        return out
    }

    /** Adresses où chercher les sous-titres d'une vidéo (PC de la maison ou partage Windows) : adresse, suffixe, extension. */
    fun candidats(lien: String): List<Triple<String, String, String>> {
        val u = try { Uri.parse(lien) } catch (e: Throwable) { return emptyList() }
        val cle = when {
            lien.contains("/fichier?") -> "chemin"
            lien.contains("/smb?") -> "u"
            else -> return emptyList()
        }
        val valeur = try { u.getQueryParameter(cle) } catch (e: Throwable) { null } ?: return emptyList()
        if (!valeur.contains('.')) return emptyList()
        val base = valeur.substringBeforeLast('.')
        val out = ArrayList<Triple<String, String, String>>()
        for (suf in listOf("", ".fr", ".fra", ".fre", ".en", ".eng")) {
            for (ext in listOf("srt", "ass", "vtt")) {
                val b = u.buildUpon().clearQuery()
                for (n in u.queryParameterNames) {
                    if (n == cle) b.appendQueryParameter(n, "$base$suf.$ext")
                    else for (v in u.getQueryParameters(n)) b.appendQueryParameter(n, v)
                }
                out.add(Triple(b.build().toString(), suf, ext))
            }
        }
        return out
    }
}

/**
 * Menu « Réglages » du lecteur (le même pour ExoPlayer et VLC) : Langue, Sous-titres, taille et couleur des
 * sous-titres, Vitesse, Format de l'image. Gère aussi le choix automatique de la langue, la mémoire du choix
 * et l'affichage des sous-titres (fichiers à côté de la vidéo, et intégrés quand c'est ExoPlayer qui lit).
 */
class Reglages(private val act: Activity, private val cadre: FrameLayout) {
    var pilote: Pilote? = null
    var lireCadrage: () -> Int = { 0 }
    var poserCadrage: (Int) -> Unit = { }
    /** Vitesse voulue (VLC la remet à chaque début de vidéo). */
    var vitesseVoulue = 1f

    private val prefs = act.getSharedPreferences("maison", Context.MODE_PRIVATE)
    private val th = Theme.choisi(prefs)
    private val d = act.resources.displayMetrics.density
    private val ech = if (act.resources.configuration.smallestScreenWidthDp >= 600) 1.25f else 1f
    private val minuteur = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * d).toInt()

    // ---- état de la vidéo en cours ----
    private var generation = 0
    private var externes: List<Externe> = emptyList()
    private var choix = "?"                // « none », « i:<id> » (intégré), « x:<n> » (fichier)
    private var audioFait = false
    private var audioForce: String? = null
    private var audioEff: String? = null
    private var sousForce: String? = null
    private var texteInt = ""
    private var menu: Dialog? = null
    private var pageSous = false
    private var libere = false

    // ---- affichage des sous-titres ----
    private val sur = TextView(act)
    private val tailles = intArrayOf(14, 18, 24, 32)
    private val nomsTaille = arrayOf("Petite", "Moyenne", "Grande", "Très grande")
    private val couleurs = intArrayOf(Color.WHITE, Color.parseColor("#FFE000"), Color.parseColor("#7CFC00"), Color.parseColor("#00E5FF"))
    private val nomsCouleur = arrayOf("Blanc", "Jaune", "Vert", "Bleu clair")
    private val vitesses = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private val nomsCadrage = arrayOf("Adapter", "Zoom", "Remplir")
    private val aideCadrage = arrayOf("Image entière, bords noirs si besoin", "Remplit l'écran en coupant les bords", "Remplit l'écran en étirant l'image")

    private var taille: Int
        get() = prefs.getInt("sous_taille", 1).coerceIn(0, 3)
        set(v) { prefs.edit().putInt("sous_taille", v).apply() }
    private var couleur: Int
        get() = prefs.getInt("sous_couleur", 0).coerceIn(0, 3)
        set(v) { prefs.edit().putInt("sous_couleur", v).apply() }

    /** Réglages de style pour VLC (sous-titres intégrés dessinés par VLC). */
    fun vlcTaille(): Int = intArrayOf(20, 16, 12, 9)[taille]
    fun vlcCouleur(): Int = couleurs[couleur] and 0xFFFFFF

    private val tic = object : Runnable {
        override fun run() {
            if (libere) return
            majSur()
            minuteur.postDelayed(this, 250)
        }
    }

    init {
        sur.gravity = Gravity.CENTER
        sur.setPadding(dp(10), dp(4), dp(10), dp(4))
        sur.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        val fond = GradientDrawable()
        fond.setColor(Color.argb(120, 0, 0, 0))
        fond.cornerRadius = dp(8).toFloat()
        sur.background = fond
        sur.visibility = View.GONE
        val lp = FrameLayout.LayoutParams(-2, -2)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.setMargins(dp(16), 0, dp(16), dp(78))
        cadre.addView(sur, lp)
        styleSur()
        minuteur.post(tic)
    }

    private fun styleSur() {
        sur.textSize = tailles[taille] * ech
        sur.setTextColor(couleurs[couleur])
    }

    private fun majSur() {
        val txt = when {
            choix.startsWith("i:") -> texteInt
            choix.startsWith("x:") -> {
                val e = externes.getOrNull(choix.substring(2).toIntOrNull() ?: -1)
                val pos = pilote?.position() ?: 0L
                if (e == null) "" else e.repliques.filter { pos >= it.debut && pos < it.fin }.joinToString("\n") { it.texte }
            }
            else -> ""
        }
        if (txt != sur.text.toString()) sur.text = txt
        val v = if (txt.isEmpty()) View.GONE else View.VISIBLE
        if (sur.visibility != v) sur.visibility = v
    }

    /** Texte du sous-titre intégré affiché en ce moment (donné par ExoPlayer). */
    fun texteIntegre(t: String) {
        texteInt = t
        majSur()
    }

    // ---------- début de vidéo, choix automatique ----------

    /** Une nouvelle vidéo commence : on repart de zéro et on cherche les fichiers de sous-titres à côté. */
    fun nouvelleVideo(lien: String, nom: String) {
        generation++
        externes = emptyList()
        choix = "?"
        audioFait = false
        audioForce = null
        audioEff = null
        sousForce = null
        texteInt = ""
        majSur()
        val g = generation
        val cands = Sous.candidats(lien)
        if (cands.isEmpty()) return
        Thread {
            val pool = Executors.newFixedThreadPool(6)
            val trouves = ArrayList<Externe>()
            try {
                val taches = cands.map { c ->
                    pool.submit(Callable<Externe?> {
                        val o = Reseau.octets(c.first, 3000) ?: return@Callable null
                        if (o.isEmpty() || o.size > 3_000_000) return@Callable null
                        val r = Sous.lire(o, c.third)
                        if (r.isEmpty()) return@Callable null
                        val code = Langues.code(c.second.removePrefix(".")) ?: ""
                        Externe(code, if (code.isEmpty()) "Fichier de sous-titres" else Langues.nom(code) + " (fichier)", r)
                    })
                }
                for (t in taches) { val e = try { t.get() } catch (x: Exception) { null }; if (e != null) trouves.add(e) }
            } catch (e: Throwable) { } finally { pool.shutdownNow() }
            trouves.sortBy { if (it.code == "fr") 0 else 1 }
            minuteur.post {
                if (!libere && g == generation && trouves.isNotEmpty()) {
                    externes = trouves
                    Log.i("MaisonLecteur", "sous-titres en fichier : " + trouves.joinToString { it.nom })
                    appliquerAuto()
                }
            }
        }.start()
    }

    /** Le lecteur change (ExoPlayer -> VLC au milieu de la même vidéo) : le choix est réappliqué. */
    fun changerPilote(p: Pilote) {
        pilote = p
        audioFait = false
        audioForce = null
        audioEff = null
        if (sousForce?.startsWith("i:") == true) sousForce = null
        choix = "?"
        texteInt = ""
    }

    /** VLC vient de relancer la vidéo (après un changement de style) : on remet les choix. */
    fun redemarre() {
        audioFait = false
        choix = "?"
    }

    /** Les pistes de la vidéo sont connues (ou ont changé) : on applique la langue préférée. */
    fun pistesPretes() {
        minuteur.post { if (!libere) appliquerAuto() }
    }

    private fun audioAuto(audios: List<Piste>): Piste? {
        val pref = prefs.getString("lang_audio", "") ?: ""
        if (pref.isNotEmpty()) audios.firstOrNull { it.code == pref }?.let { return it }
        return audios.firstOrNull { it.code == "fr" }
    }

    private fun sousAuto(audioCode: String, subs: List<Piste>): String {
        val ps = prefs.getString("lang_sous", null)
        fun trouver(code: String): String? {
            subs.firstOrNull { it.code == code && it.supporte }?.let { return "i:" + it.id }
            val x = externes.indexOfFirst { it.code == code }
            return if (x >= 0) "x:$x" else null
        }
        if (ps == "none") return "none"
        if (!ps.isNullOrEmpty()) return trouver(ps) ?: "none"
        if (audioCode == "fr") return "none"
        return trouver("fr") ?: "none"
    }

    private fun appliquerAuto() {
        val p = pilote ?: return
        val audios = p.pistesAudio()
        if (audios.isNotEmpty() && !audioFait) {
            audioFait = true
            val voulu = audioForce?.let { f -> audios.firstOrNull { it.id == f } } ?: audioAuto(audios)
            val actuel = p.audioActuel()
            audioEff = voulu?.id ?: actuel
            if (voulu != null && voulu.id != actuel) {
                Log.i("MaisonLecteur", "langue automatique : " + voulu.nom)
                p.choisirAudio(voulu.id)
            }
        }
        val subs = p.pistesSousTitres()
        val sf = sousForce
        if (sf == null) {
            val codeAudio = audios.firstOrNull { it.id == (audioEff ?: p.audioActuel()) }?.code ?: ""
            val cible = sousAuto(codeAudio, subs)
            if (cible != choix) { Log.i("MaisonLecteur", "sous-titres automatiques : $cible"); appliquerSous(cible) }
        } else if (sf != choix) {
            appliquerSous(sf)
        }
    }

    private fun appliquerSous(c: String) {
        choix = c
        texteInt = ""
        val p = pilote
        if (c.startsWith("i:")) p?.choisirSousTitre(c.substring(2)) else p?.choisirSousTitre(null)
        majSur()
    }

    // ---------- noms affichés ----------

    private fun etiquettes(l: List<Piste>): List<String> {
        val vus = HashMap<String, Int>()
        val total = HashMap<String, Int>()
        for (x in l) total[x.nom] = (total[x.nom] ?: 0) + 1
        return l.map { x ->
            if ((total[x.nom] ?: 0) > 1) { val n = (vus[x.nom] ?: 0) + 1; vus[x.nom] = n; x.nom + " (" + n + ")" } else x.nom
        }
    }

    private fun nomAudio(): String {
        val l = pilote?.pistesAudio() ?: return "Non disponible"
        if (l.isEmpty()) return "Une seule langue"
        val e = etiquettes(l)
        val id = pilote?.audioActuel()
        val i = l.indexOfFirst { it.id == id }
        return if (i >= 0) e[i] else e[0]
    }

    private fun nomSous(): String {
        if (choix.startsWith("x:")) return externes.getOrNull(choix.substring(2).toIntOrNull() ?: -1)?.nom ?: "Aucun"
        if (choix.startsWith("i:")) {
            val l = pilote?.pistesSousTitres() ?: return "Aucun"
            val e = etiquettes(l)
            val i = l.indexOfFirst { it.id == choix.substring(2) }
            return if (i >= 0) e[i] else "Aucun"
        }
        return "Aucun"
    }

    private fun nomVitesse(v: Float): String =
        if (v == 1f) "Normale" else v.toString().replace('.', ',').removeSuffix(",0") + "x"

    // ---------- choix de l'utilisateur ----------

    private fun choisirAudio(p: Piste) {
        audioForce = p.id
        audioEff = p.id
        audioFait = true
        if (p.code.isNotEmpty()) prefs.edit().putString("lang_audio", p.code).apply()
        Log.i("MaisonLecteur", "langue choisie : " + p.nom)
        pilote?.choisirAudio(p.id)
        // sans choix de sous-titres de ta part, ils suivent la règle (voix française = pas de sous-titres)
        if (sousForce == null) appliquerAuto()
    }

    private fun choisirSous(c: String, code: String) {
        sousForce = c
        val e = prefs.edit()
        if (c == "none") e.putString("lang_sous", "none") else if (code.isNotEmpty()) e.putString("lang_sous", code) else e.remove("lang_sous")
        e.apply()
        Log.i("MaisonLecteur", "sous-titres choisis : $c")
        appliquerSous(c)
    }

    // ---------- le menu ----------

    private class Ligne(val titre: String, val valeur: String = "", val choisi: Boolean = false, val action: (() -> Unit)? = null)

    private class DefilePlafonne(c: Context, val maxH: Int) : ScrollView(c) {
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    fun estOuvert(): Boolean = menu != null

    fun ouvrir() {
        if (menu != null || libere || act.isFinishing) return
        val dlg = Dialog(act, android.R.style.Theme_Material_Dialog_NoActionBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setCanceledOnTouchOutside(true)
        dlg.setOnDismissListener { menu = null }
        dlg.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK && pageSous) {
                if (ev.action == KeyEvent.ACTION_UP) pagePrincipale()
                true
            } else false
        }
        menu = dlg
        pagePrincipale()
        dlg.show()
    }

    fun fermer() { menu?.dismiss() }

    private fun fondBouton(c: Int, contour: Int, epaisseur: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(c)
        g.cornerRadius = dp(12).toFloat()
        if (epaisseur > 0) g.setStroke(epaisseur, contour)
        return g
    }

    /** Fond avec cadre bien visible quand la ligne est choisie à la télécommande ou au clavier. */
    private fun fondFocus(c: Int, haut: Int): StateListDrawable {
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_focused), fondBouton(haut, th.accent, dp(3)))
        s.addState(intArrayOf(android.R.attr.state_pressed), fondBouton(haut, th.accent, dp(3)))
        s.addState(intArrayOf(), fondBouton(c, c, 0))
        return s
    }

    private fun afficher(titre: String, lignes: List<Ligne>, retour: Boolean) {
        val dlg = menu ?: return
        pageSous = retour
        val ecran = act.resources.displayMetrics
        val largeur = minOf((ecran.widthPixels * 0.92f).toInt(), dp(540))
        val hautMax = (ecran.heightPixels * 0.9f).toInt()

        val racine = LinearLayout(act)
        racine.orientation = LinearLayout.VERTICAL
        racine.background = fondBouton(th.fond, th.carteHaute, dp(2))
        racine.setPadding(dp(14), dp(12), dp(14), dp(12))

        val t = TextView(act)
        t.text = titre
        t.setTextColor(th.texte)
        t.textSize = 20f * ech
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        t.setPadding(dp(4), dp(2), dp(4), dp(8))
        racine.addView(t, LinearLayout.LayoutParams(-1, -2))

        val liste = LinearLayout(act)
        liste.orientation = LinearLayout.VERTICAL
        var premier: View? = null
        var choisiVue: View? = null
        for (l in lignes) {
            val r = LinearLayout(act)
            r.orientation = LinearLayout.VERTICAL
            r.minimumHeight = dp(58)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(14), dp(8), dp(14), dp(8))
            val a = TextView(act)
            a.text = l.titre
            a.setTextColor(if (l.choisi) th.accent else th.texte)
            a.textSize = 18f * ech
            if (l.choisi) a.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            r.addView(a, LinearLayout.LayoutParams(-1, -2))
            val txtVal = if (l.choisi && l.valeur.isEmpty()) "Choisi" else l.valeur
            if (txtVal.isNotEmpty()) {
                val b = TextView(act)
                b.text = txtVal
                b.setTextColor(if (l.choisi) th.accent else th.gris)
                b.textSize = 14f * ech
                r.addView(b, LinearLayout.LayoutParams(-1, -2))
            }
            if (l.action != null) {
                r.isClickable = true
                r.isFocusable = true
                r.background = fondFocus(th.carte, th.carteHaute)
                r.setOnClickListener { l.action?.invoke() }
                if (premier == null) premier = r
                if (l.choisi) choisiVue = r
            }
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(dp(2), dp(4), dp(2), dp(4))
            liste.addView(r, lp)
        }

        val reserve = dp(150)
        val defile = DefilePlafonne(act, hautMax - reserve)
        defile.isFillViewport = false
        defile.addView(liste, FrameLayout.LayoutParams(-1, -2))
        racine.addView(defile, LinearLayout.LayoutParams(-1, -2))

        val pied = LinearLayout(act)
        pied.orientation = LinearLayout.HORIZONTAL
        fun bouton(txt: String, action: () -> Unit) {
            val b = Button(act)
            b.text = txt
            b.isAllCaps = false
            b.textSize = 17f * ech
            b.setTextColor(th.texteBouton)
            b.minimumHeight = dp(54)
            // cadre bien visible quand le bouton est choisi à la télécommande
            val s = StateListDrawable()
            s.addState(intArrayOf(android.R.attr.state_focused), fondBouton(th.accent, th.texte, dp(4)))
            s.addState(intArrayOf(android.R.attr.state_pressed), fondBouton(th.accent, th.texte, dp(4)))
            s.addState(intArrayOf(), fondBouton(th.accent, th.accent, 0))
            b.background = s
            b.isFocusable = true
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(dp(4), dp(8), dp(4), 0)
            pied.addView(b, lp)
        }
        if (retour) bouton("Retour") { pagePrincipale() }
        bouton("Fermer") { fermer() }
        racine.addView(pied, LinearLayout.LayoutParams(-1, -2))

        dlg.setContentView(racine, android.view.ViewGroup.LayoutParams(largeur, -2))
        dlg.window?.setLayout(largeur, -2)
        val cible = choisiVue ?: premier
        cible?.post { cible.requestFocus() }
    }

    private fun pagePrincipale() {
        val p = pilote
        val lignes = ArrayList<Ligne>()
        lignes.add(Ligne("Langue", nomAudio()) { pageLangue() })
        lignes.add(Ligne("Sous-titres", nomSous()) { pageSousTitres() })
        lignes.add(Ligne("Taille des sous-titres", nomsTaille[taille]) { pageTaille() })
        lignes.add(Ligne("Couleur des sous-titres", nomsCouleur[couleur]) { pageCouleur() })
        lignes.add(Ligne("Vitesse", nomVitesse(p?.vitesse() ?: vitesseVoulue)) { pageVitesse() })
        lignes.add(Ligne("Format de l'image", nomsCadrage[lireCadrage().coerceIn(0, 2)]) { pageFormat() })
        afficher("Réglages", lignes, false)
    }

    private fun pageLangue() {
        val l = pilote?.pistesAudio() ?: emptyList()
        val e = etiquettes(l)
        val id = pilote?.audioActuel()
        val lignes = ArrayList<Ligne>()
        if (l.isEmpty()) lignes.add(Ligne("Cette vidéo n'a qu'une seule langue", "Rien à changer"))
        for (i in l.indices) {
            val x = l[i]
            lignes.add(Ligne(e[i], if (x.supporte) "" else "Lu avec le lecteur de secours", x.id == (audioEff ?: id)) {
                choisirAudio(x)
                pagePrincipale()
            })
        }
        afficher("Langue de la vidéo", lignes, true)
    }

    private fun pageSousTitres() {
        val lignes = ArrayList<Ligne>()
        lignes.add(Ligne("Aucun", "", choix == "none" || choix == "?") { choisirSous("none", ""); pagePrincipale() })
        val l = (pilote?.pistesSousTitres() ?: emptyList()).filter { it.supporte }
        val e = etiquettes(l)
        for (i in l.indices) {
            val x = l[i]
            lignes.add(Ligne(e[i], "", choix == "i:" + x.id) { choisirSous("i:" + x.id, x.code); pagePrincipale() })
        }
        for (i in externes.indices) {
            val x = externes[i]
            lignes.add(Ligne(x.nom, "", choix == "x:$i") { choisirSous("x:$i", x.code); pagePrincipale() })
        }
        afficher("Sous-titres", lignes, true)
    }

    private fun pageTaille() {
        val lignes = nomsTaille.indices.map { i ->
            Ligne(nomsTaille[i], "", taille == i) { taille = i; styleSur(); pilote?.styleSousTitresChange(); pagePrincipale() }
        }
        afficher("Taille des sous-titres", lignes, true)
    }

    private fun pageCouleur() {
        val lignes = nomsCouleur.indices.map { i ->
            Ligne(nomsCouleur[i], "", couleur == i) { couleur = i; styleSur(); pilote?.styleSousTitresChange(); pagePrincipale() }
        }
        afficher("Couleur des sous-titres", lignes, true)
    }

    private fun pageVitesse() {
        val actuelle = pilote?.vitesse() ?: vitesseVoulue
        val lignes = vitesses.map { v ->
            Ligne(nomVitesse(v), "", Math.abs(actuelle - v) < 0.01f) {
                vitesseVoulue = v
                pilote?.changerVitesse(v)
                pagePrincipale()
            }
        }
        afficher("Vitesse de lecture", lignes, true)
    }

    private fun pageFormat() {
        val lignes = nomsCadrage.indices.map { i ->
            Ligne(nomsCadrage[i], aideCadrage[i], lireCadrage() == i) { poserCadrage(i); pagePrincipale() }
        }
        afficher("Format de l'image", lignes, true)
    }

    fun liberer() {
        libere = true
        generation++
        minuteur.removeCallbacksAndMessages(null)
        try { menu?.dismiss() } catch (e: Throwable) { }
        menu = null
    }

    companion object {
        /** Fabrique une piste : langue lue d'abord dans le code, puis devinée dans les textes. */
        fun piste(id: String, langue: String?, label: String?, numero: Int, supporte: Boolean): Piste {
            val c = Langues.code(langue) ?: Langues.deviner(langue) ?: Langues.deviner(label) ?: ""
            val nom = if (c.isNotEmpty()) Langues.nom(c) else "Piste $numero"
            return Piste(id, c, nom, supporte)
        }
    }
}

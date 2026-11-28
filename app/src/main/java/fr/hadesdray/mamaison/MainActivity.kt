package fr.hadesdray.mamaison

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ListView
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Ma Maison : une seule appli, en français, pour toute la maison.
 * Vidéos, photos et musique du PC ; lumières, volets, caméras, capteurs,
 * consommation, plannings, télé et enceintes par le moteur caché du PC.
 */
class MainActivity : Activity() {

    private companion object {
        const val GRANDE = 2
        const val MOYENNE = 1
        const val PETITE = 0
    }

    // Couleurs : celles du thème choisi dans Réglages > Apparence (« Nuit », le visuel d'origine, par défaut)
    private val th: Theme get() = Theme.choisi(prefs)
    private val cFond get() = th.fond
    private val cCarte get() = th.carte
    private val cCarteHaute get() = th.carteHaute
    private val cCarteActive get() = th.carteActive
    private val cAccent get() = th.accent
    private val cTexte get() = th.texte
    private val cGris get() = th.gris
    // Vert et rouge plus foncés sur le thème clair, pour rester lisibles sur fond blanc
    private val cVert get() = if (th.clair) Color.parseColor("#1E8E4E") else Color.parseColor("#6FCF97")
    private val cRouge get() = if (th.clair) Color.parseColor("#C62828") else Color.parseColor("#EB5757")

    private val ui = Handler(Looper.getMainLooper())
    private val pile = ArrayList<() -> Unit>()
    private var ecranActuel = "Accueil"
    private lateinit var passerelle: Passerelle
    private lateinit var moteur: Moteur
    private lateinit var smartLife: SmartLife
    private lateinit var dyson: Dyson
    private lateinit var diffusion: Diffusion
    private lateinit var miseAJour: MiseAJour
    private lateinit var reseau: ReseauMaison
    private lateinit var partages: Partages
    @Volatile private var dernierScan: List<AppareilReseau>? = null
    private val prefs by lazy { getSharedPreferences("maison", Context.MODE_PRIVATE) }
    private var minuteur: Runnable? = null
    private var appareilsCache: List<Appareil>? = null

    // Sens du changement de page : 1 = on avance, -1 = on revient, 0 = on reste sur place (pas d'animation)
    private var sens = 0
    // Dossiers du PC déjà ouverts : réaffichés tout de suite, puis revérifiés en fond
    private class Connu(val base: String, val texte: String)
    private val dossiersConnus = HashMap<String, Connu>()
    private val positions = HashMap<String, IntArray>()

    // Voyants des tuiles : -1 = vérification en cours, 0 = non connecté, 1 = connecté
    private var etatPc = -1
    private var etatAppareils = -1
    private val voyants = HashMap<String, View>()

    private val modeTablette: Boolean
        get() = prefs.getBoolean("tablette", resources.configuration.smallestScreenWidthDp >= 600)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun taille(t: Float): Float = if (modeTablette) t * 1.3f else t

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        appliquerBarres()
        passerelle = Passerelle(this)
        moteur = Moteur(this)
        smartLife = SmartLife(this)
        dyson = Dyson(this)
        diffusion = Diffusion(this)
        miseAJour = MiseAJour(this)
        reseau = ReseauMaison(this)
        partages = Partages(this)
        // Petit serveur de l'appli (relais des partages Windows, partage de ce téléphone)
        Relais.demarrer(this)
        if (Relais.partageActif(this)) demarrerPartage()
        demanderAccesUneFois()
        ouvrir("Accueil", ::accueil, racine = true)
        Tv.attach(this)   // télé : flèches de la télécommande, cadre blanc sur la tuile choisie
        miseAJour.verifier(false)
        // Sur la télé : YouPub TV arrive avec Ma Maison (proposée une seule fois, sinon par la tuile YouPub).
        val pr = getSharedPreferences("maison", MODE_PRIVATE)
        if (Tv.isTv(this) && packageManager.getLaunchIntentForPackage("fr.hadesdray.youpub.tv") == null && !pr.getBoolean("youpub_tv_propose", false)) {
            pr.edit().putBoolean("youpub_tv_propose", true).apply()
            ui.postDelayed({ if (!isFinishing) { message("Ma Maison installe YouPub TV : valide quand Android le demande."); miseAJour.installerYouPubTv() } }, 4000)
        }
    }

    // ---------- navigation ----------

    private fun ouvrir(nom: String, ecran: () -> Unit, racine: Boolean = false) {
        arreterMinuteur()
        if (recepteurBt != null) arreterRechercheBt()
        dyson.arreterReception()
        if (racine) pile.clear()
        pile.add(ecran)
        ecranActuel = nom
        sens = if (racine) 0 else 1
        ecran()
        sens = 0
    }

    @Deprecated("Flèche retour d'Android")
    override fun onBackPressed() {
        arreterMinuteur()
        if (recepteurBt != null) arreterRechercheBt()
        dyson.arreterReception()
        if (pile.size > 1) {
            pile.removeAt(pile.size - 1)
            sens = -1
            rafraichir()
            sens = 0
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Réaffiche l'écran du dessus de la pile. */
    private fun rafraichir() {
        if (pile.isNotEmpty()) {
            val e: () -> Unit = pile[pile.size - 1]
            e()
        }
    }

    private fun arreterMinuteur() {
        minuteur?.let { ui.removeCallbacks(it) }
        minuteur = null
    }

    private var relancer = false

    override fun onPause() {
        super.onPause()
        relancer = minuteur != null
        arreterMinuteur()
    }

    override fun onResume() {
        super.onResume()
        if (relancer && pile.isNotEmpty()) rafraichir()
        relancer = false
        if (prefs.getBoolean("allume", false) && modeTablette) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Barres du haut et du bas aux couleurs du thème (icônes foncées sur le thème clair). */
    private fun appliquerBarres() {
        window.statusBarColor = cFond
        window.navigationBarColor = cFond
        if (Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION")
            var f = window.decorView.systemUiVisibility and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            if (th.clair) {
                f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = f
        }
    }

    private fun demanderAccesUneFois() {
        if (prefs.getBoolean("acces_demandes", false)) return
        prefs.edit().putBoolean("acces_demandes", true).apply()
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    // ---------- briques d'écran ----------

    /** Page avec en-tête (titre, retour, petit « ? ») et un contenu défilant. */
    private fun page(titre: String, sousTitre: String? = null, defilant: Boolean = true): LinearLayout {
        arreterMinuteur()
        val racine = LinearLayout(this)
        racine.orientation = LinearLayout.VERTICAL
        racine.setBackgroundColor(cFond)

        val entete = LinearLayout(this)
        entete.orientation = LinearLayout.HORIZONTAL
        entete.gravity = Gravity.CENTER_VERTICAL
        entete.setPadding(dp(12), dp(10), dp(8), dp(6))
        if (pile.size > 1) {
            val retour = TextView(this)
            retour.text = "‹ Retour"
            retour.setTextColor(cAccent)
            retour.textSize = taille(17f)
            retour.setPadding(dp(6), dp(8), dp(14), dp(8))
            retour.setOnClickListener { onBackPressed() }
            entete.addView(retour)
        }
        val t = TextView(this)
        t.text = titre
        t.setTextColor(cTexte)
        t.textSize = taille(22f)
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        entete.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val aide = TextView(this)
        aide.text = "?"
        aide.setTextColor(cGris)
        aide.textSize = 14f
        aide.gravity = Gravity.CENTER
        aide.background = rond(cCarte, dp(14))
        aide.contentDescription = "Signaler un problème sur cette page"
        aide.setOnClickListener { signaler() }
        entete.addView(aide, LinearLayout.LayoutParams(dp(28), dp(28)))
        racine.addView(entete)
        if (sousTitre != null) {
            val s = TextView(this)
            s.text = sousTitre
            s.setTextColor(cGris)
            s.textSize = taille(14f)
            s.setPadding(dp(18), 0, dp(18), dp(8))
            racine.addView(s)
        }
        val contenu = LinearLayout(this)
        contenu.orientation = LinearLayout.VERTICAL
        if (defilant) {
            val defil = ScrollView(this)
            defil.isFillViewport = true
            contenu.setPadding(dp(12), dp(4), dp(12), dp(24))
            defil.addView(contenu)
            racine.addView(defil, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            // Page à longue liste : c'est la liste elle-même qui défile (elle ne fabrique que les lignes visibles)
            contenu.setPadding(dp(12), dp(4), dp(12), 0)
            racine.addView(contenu, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(racine)
        // Passage en douceur d'une page à l'autre
        val s = sens
        sens = 0
        if (s != 0) {
            racine.alpha = 0f
            racine.translationX = dp(26) * s.toFloat()
            racine.animate().alpha(1f).translationX(0f).setDuration(170).start()
        }
        return contenu
    }

    /** Petit retour visuel : l'élément s'enfonce légèrement sous le doigt. */
    private fun effetAppui(v: View) {
        v.setOnTouchListener { vue, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> vue.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.85f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> vue.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(130).start()
            }
            false
        }
    }

    private fun rond(couleur: Int, rayon: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(couleur)
        g.cornerRadius = rayon.toFloat()
        return g
    }

    private fun texte(parent: LinearLayout, t: String, tailleTexte: Float = 15f, couleur: Int = cGris): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(couleur)
        v.textSize = taille(tailleTexte)
        v.setPadding(dp(6), dp(6), dp(6), dp(6))
        v.setLineSpacing(0f, 1.15f)
        parent.addView(v)
        return v
    }

    private fun bouton(parent: LinearLayout, t: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = taille(16f)
        b.setTextColor(th.texteBouton)
        b.background = rond(cAccent, dp(14))
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.setOnClickListener { action() }
        effetAppui(b)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(dp(4), dp(8), dp(4), dp(4))
        parent.addView(b, lp)
        return b
    }

    /** Tuile : titre + information. Trois tailles : grande, moyenne (par défaut), petite (titre seul). */
    private fun tuile(titre: String, info: String, active: Boolean = false, format: Int = MOYENNE, voyant: Int? = null, action: () -> Unit): LinearLayout {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        val a = TextView(this)
        a.text = titre
        a.setTextColor(if (active) cAccent else cTexte)
        a.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        when (format) {
            GRANDE -> {
                val fond = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(if (active) cCarteActive else cCarteHaute, if (active) cCarteActive else cCarte))
                fond.cornerRadius = dp(22).toFloat()
                t.background = fond
                t.setPadding(dp(18), dp(16), dp(18), dp(16))
                t.minimumHeight = dp(if (modeTablette) 176 else 132)
                val trait = View(this)
                trait.background = rond(cAccent, dp(2))
                val lpt = LinearLayout.LayoutParams(dp(30), dp(4))
                lpt.setMargins(0, dp(2), 0, dp(12))
                t.addView(trait, lpt)
                a.textSize = taille(23f)
            }
            PETITE -> {
                t.background = rond(if (active) cCarteActive else cCarte, dp(14))
                t.setPadding(dp(6), dp(8), dp(6), dp(8))
                t.minimumHeight = dp(if (modeTablette) 72 else 56)
                t.gravity = Gravity.CENTER
                a.textSize = taille(13f)
                a.gravity = Gravity.CENTER
                a.maxLines = 2
            }
            else -> {
                t.background = rond(if (active) cCarteActive else cCarte, dp(18))
                t.setPadding(dp(16), dp(16), dp(16), dp(16))
                t.minimumHeight = dp(if (modeTablette) 130 else 96)
                a.textSize = taille(18f)
            }
        }
        if (voyant != null && format != PETITE) {
            // Titre + voyant rond à droite (vert connecté, rouge non connecté, gris en vérification)
            val ligne = LinearLayout(this)
            ligne.orientation = LinearLayout.HORIZONTAL
            ligne.gravity = Gravity.CENTER_VERTICAL
            ligne.addView(a, LinearLayout.LayoutParams(0, -2, 1f))
            val v = View(this)
            v.tag = "voyant"
            colorerVoyant(v, voyant)
            val lpv = LinearLayout.LayoutParams(dp(12), dp(12))
            lpv.setMargins(dp(8), 0, 0, 0)
            ligne.addView(v, lpv)
            t.addView(ligne)
        } else t.addView(a)
        if (format != PETITE) {
            val b = TextView(this)
            b.text = info
            b.setTextColor(cGris)
            b.textSize = taille(if (format == GRANDE) 14f else 13f)
            b.setPadding(0, dp(6), 0, 0)
            t.addView(b)
        } else t.contentDescription = "$titre. $info"
        t.isClickable = true
        t.setOnClickListener { action() }
        effetAppui(t)
        return t
    }

    private fun colorerVoyant(v: View, etat: Int) {
        v.background = rond(when (etat) { 1 -> cVert; 0 -> cRouge; else -> cGris }, dp(6))
        v.alpha = if (etat < 0) 0.6f else 1f
        v.contentDescription = when (etat) { 1 -> "connecté"; 0 -> "non connecté"; else -> "vérification en cours" }
    }

    /** Range des vues en rangées égales ; la dernière rangée est complétée par du vide. */
    private fun rangees(parent: LinearLayout, vues: List<View>, parRangee: Int) {
        var i = 0
        while (i < vues.size) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            for (j in 0 until parRangee) {
                val lp = LinearLayout.LayoutParams(0, -1, 1f)
                lp.setMargins(dp(5), dp(5), dp(5), dp(5))
                if (i + j < vues.size) r.addView(vues[i + j], lp) else r.addView(View(this), lp)
            }
            parent.addView(r, LinearLayout.LayoutParams(-1, -2))
            i += parRangee
        }
    }

    private fun grille(parent: LinearLayout, tuiles: List<View>) {
        val colonnes = when {
            modeTablette && resources.configuration.screenWidthDp >= 900 -> 4
            modeTablette -> 3
            resources.configuration.screenWidthDp >= 600 -> 3
            else -> 2
        }
        val g = GridLayout(this)
        g.columnCount = colonnes
        for (v in tuiles) {
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            lp.setMargins(dp(5), dp(5), dp(5), dp(5))
            g.addView(v, lp)
        }
        parent.addView(g, LinearLayout.LayoutParams(-1, -2))
    }

    private fun attente(parent: LinearLayout, t: String): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        l.setPadding(dp(8), dp(16), dp(8), dp(16))
        l.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(32), dp(32)))
        val v = TextView(this)
        v.text = t
        v.setTextColor(cGris)
        v.textSize = taille(15f)
        v.setPadding(dp(12), 0, 0, 0)
        l.addView(v)
        parent.addView(l)
        return l
    }

    private fun message(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    /** Fait le travail hors de l'écran, puis revient afficher si on est toujours sur la même page. */
    private fun <T> enFond(travail: () -> T, ensuite: (T) -> Unit) {
        val ecran = ecranActuel
        val profondeur = pile.size
        thread {
            val r = travail()
            ui.post { if (!isFinishing && ecran == ecranActuel && profondeur == pile.size) ensuite(r) }
        }
    }

    // ---------- accueil ----------

    private fun accueil() {
        val c = page("Ma Maison")
        if (modeTablette) {
            val h = TextView(this)
            h.setTextColor(cTexte)
            h.textSize = 54f
            h.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            h.setPadding(dp(8), 0, dp(8), 0)
            c.addView(h)
            val d = TextView(this)
            d.setTextColor(cGris)
            d.textSize = 20f
            d.setPadding(dp(10), 0, dp(10), dp(12))
            c.addView(d)
            val r = object : Runnable {
                override fun run() {
                    val now = Date()
                    h.text = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
                    d.text = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now).replaceFirstChar { it.uppercase() }
                    ui.postDelayed(this, 10000)
                }
            }
            minuteur = r
            r.run()
        }
        val resume = texte(c, "Recherche de la maison…", 14f)

        val parler = bouton(c, "Parler à la maison") { ecouter() }
        parler.contentDescription = "Donner un ordre à la voix"

        voyants.clear()
        val premiere = mosaique(c, cases().filter { lieu(it) == "accueil" })
        // Télé : la télécommande part de la première grande tuile (pas du petit « ? » en haut à droite)
        if (Tv.isTv(this)) premiere?.let { v -> v.post { v.requestFocus() } }
        texte(c, "Appui long sur une tuile : changer sa taille ou sa place.", 12f)

        enFond({
            val p = passerelle.trouver()
            if (!moteur.pret) moteur.recupererDepuisPasserelle(p)
            Pair(p, appareilsMaison())
        }) { (p, l) ->
            val parties = ArrayList<String>()
            parties.add(if (p != null) "PC de la maison : connecté" else "PC de la maison : introuvable (allumé ? même Wi-Fi ?)")
            parties.add(
                when {
                    !moteur.pret && !smartLife.pret -> "Appareils de la maison : pas encore reliés (va dans « Ajouter un appareil »)"
                    l == null -> "Appareils de la maison : pas de réponse" + (if (smartLife.erreur.isNotEmpty()) " — " + smartLife.erreur else "")
                    else -> "Appareils de la maison : ${l.size} reliés"
                }
            )
            val pcs = reseau.connus().filter { it.genre == "pc" }
            if (pcs.isNotEmpty()) parties.add("Ordinateurs : vérification…")
            val temp = l?.firstOrNull { it.genre == "temperature" && it.etat.toDoubleOrNull() != null }
            if (temp != null) parties.add("Température : ${temp.etat} ${temp.unite}")
            resume.text = parties.joinToString("\n")
            resume.setTextColor(if (p != null) cVert else cGris)
            etatPc = if (p != null) 1 else 0
            etatAppareils = if (l != null) 1 else 0
            majVoyants()
            // Chaque ordinateur déjà vu sur le Wi-Fi : allumé ou éteint
            if (pcs.isNotEmpty()) enFond({ pcs.map { it.nom to reseau.repond(it) } }) { etats ->
                val ligne = "Ordinateurs : " + etats.joinToString(" · ") { (n, ok) -> "$n " + (if (ok) "allumé" else "éteint") }
                resume.text = resume.text.toString().replace("Ordinateurs : vérification…", ligne)
            }
            if (l != null && appareilsCache == null) {
                appareilsCache = l
                accueil()
            } else if (l != null) appareilsCache = l
        }
    }

    private fun infoReseau(): String {
        val l = dernierScan ?: reseau.connus()
        if (l.isEmpty()) return "Tout ce qui est sur le Wi-Fi"
        val n = l.count { it.present }
        return if (dernierScan == null) "${l.size} appareil(s) connu(s)" else "$n appareil(s) connecté(s)"
    }

    private fun etatReseau(): Int = if (etatPc == 0 && dernierScan == null) -1 else if (reseau.monIp() == null) 0 else 1

    private fun majVoyants() {
        val toutes = cases()
        for ((id, v) in voyants) {
            val e = toutes.firstOrNull { it.id == id }?.etat?.invoke() ?: continue
            colorerVoyant(v, e)
        }
    }

    // ---------- tuiles de l'accueil : tailles, ordre, tuile « Maison » ----------

    private class Case(val id: String, val titre: String, val court: String, val info: String,
                       val formatDefaut: Int, val lieuDefaut: String, val action: () -> Unit) {
        var etat: (() -> Int)? = null
        fun avec(e: () -> Int): Case { etat = e; return this }
    }

    // Appareils de la maison : rouge si rien n'est relié, sinon selon la dernière réponse
    private fun etatMaison(): Int = if (!moteur.pret && !smartLife.pret) 0 else etatAppareils

    private fun compte(dom: Set<String>, allumes: Boolean = false): String {
        val l = appareilsCache ?: return if (moteur.pret || smartLife.pret) "…" else "À relier"
        val n = l.filter { it.domaine in dom }
        if (n.isEmpty()) return "Aucun appareil branché"
        return if (allumes) "${n.count { it.allume }} allumé(s) sur ${n.size}" else "${n.size} appareil(s)"
    }

    private fun format(k: Case): Int = prefs.getInt("acc_format_" + k.id, k.formatDefaut)
    private fun lieu(k: Case): String = prefs.getString("acc_lieu_" + k.id, k.lieuDefaut) ?: k.lieuDefaut

    /** Toutes les tuiles, dans l'ordre choisi (celui de départ tant que rien n'a été déplacé). */
    private fun cases(): List<Case> {
        val l = arrayListOf(
            Case("videos", "Vidéos", "Vidéos", "Films et vidéos du PC", GRANDE, "accueil") { ouvrir("Vidéos", { medias("videos", "Vidéos") }) }.avec { etatPc },
            Case("jeux", "Jeux", "Jeux", "GeForce Now, Amazon, Xbox…", GRANDE, "accueil") { ouvrir("Jeux", { jeux() }) },
            Case("musique", "Musique", "Musique", "Musiques du PC", GRANDE, "accueil") { ouvrir("Musique", { medias("musique", "Musique") }) }.avec { etatPc },
            Case("photos", "Photos", "Photos", "Images du PC", GRANDE, "accueil") { ouvrir("Photos", { medias("photos", "Photos") }) }.avec { etatPc },
            Case("lumieres", "Lumières", "Lumières", compte(setOf("light"), true), MOYENNE, "accueil") { ouvrir("Lumières", { appareilsDe("Lumières", setOf("light"), "lumière") }) }.avec { etatMaison() },
            Case("tele", "Télé et enceintes", "Télé", if (diffusion.enCours()) "Envoi en cours — télécommande" else "Nest Mini, Freebox, télé", MOYENNE, "accueil") { ouvrir("Télé", { teleEnceintes() }) },
            Case("youpub", "YouPub", "YouPub", "Vidéos, musique et télé sans pub", MOYENNE, "accueil") { ouvrirYouPub() },
            Case("cameras", "Caméras", "Caméras", compte(setOf("camera")), MOYENNE, "accueil") { ouvrir("Caméras", { cameras() }) }.avec { etatMaison() },
            Case("dyson", "Dyson", "Dyson", if (dyson.relie) "${dyson.machines.size} appareil(s) : ventilateur, aspirateur" else "Relier ton compte Dyson", MOYENNE, "accueil") { ouvrir("Dyson", { dysonAccueil() }) }.avec { if (dyson.relie) 1 else 0 },
            Case("reseau", "Réseau", "Réseau", infoReseau(), MOYENNE, "accueil") { ouvrir("Réseau", { ecranReseau() }) }.avec { etatReseau() },
            Case("maison", "Maison", "Maison", "", MOYENNE, "accueil") { ouvrir("Maison", { groupeMaison() }) }.avec { etatMaison() },
            Case("prises", "Prises", "Prises", compte(setOf("switch"), true), MOYENNE, "maison") { ouvrir("Prises", { appareilsDe("Prises", setOf("switch"), "prise") }) }.avec { etatMaison() },
            Case("volets", "Volets et portes", "Volets", compte(setOf("cover", "lock")), MOYENNE, "maison") { ouvrir("Volets", { appareilsDe("Volets et portes", setOf("cover", "lock"), "volet ou porte") }) }.avec { etatMaison() },
            Case("capteurs", "Capteurs", "Capteurs", compte(setOf("sensor", "binary_sensor", "weather", "climate")), MOYENNE, "maison") { ouvrir("Capteurs", { capteurs() }) }.avec { etatMaison() },
            Case("conso", "Consommation", "Conso.", "Électricité, eau, gaz", MOYENNE, "maison") { ouvrir("Consommation", { consommation() }) },
            Case("ambiances", "Ambiances", "Ambiances", compte(setOf("scene", "script")), MOYENNE, "maison") { ouvrir("Ambiances", { appareilsDe("Ambiances", setOf("scene", "script"), "ambiance") }) }.avec { etatMaison() },
            Case("plannings", "Plannings", "Plannings", "Allumer, éteindre à heure fixe", MOYENNE, "maison") { ouvrir("Plannings", { plannings() }) },
            Case("smartlife", "Smart Life", "Smart Life", if (smartLife.pret) "Relié — ouvrir l'appli Smart Life" else "Ouvrir l'appli Smart Life", PETITE, "accueil") { ouvrirAppli("com.tuya.smartlife", "Smart Life") },
            Case("googlehome", "Google Home", "Google Home", "Ouvrir l'appli Google Home", PETITE, "accueil") { ouvrirAppli("com.google.android.apps.chromecast.app", "Google Home") },
            Case("ajouter", "Ajouter un appareil", "Ajouter", if (smartLife.pret) "Brancher un nouvel appareil" else "Relier Smart Life à Ma Maison", PETITE, "accueil") { ouvrir("Ajouter", { ajouter() }) },
            Case("reglages", "Réglages", "Réglages", "Mode tablette, PC, mises à jour", PETITE, "accueil") { ouvrir("Réglages", { reglages() }) }
        )
        val ordre = (prefs.getString("acc_ordre", "") ?: "").split(",").filter { it.isNotEmpty() }
        if (ordre.isEmpty()) return l
        val rang = HashMap<String, Int>()
        ordre.forEachIndexed { i, id -> rang[id] = i }
        return l.sortedBy { rang[it.id] ?: (1000 + l.indexOf(it)) }
    }

    /** Affiche les tuiles : les grandes d'abord, puis les moyennes, puis les petites. */
    private fun mosaique(parent: LinearLayout, liste: List<Case>): View? {
        var premiere: View? = null
        val toutes = cases()
        val dansMaison = toutes.filter { lieu(it) == "maison" }
        val large = resources.configuration.screenWidthDp
        for (f in intArrayOf(GRANDE, MOYENNE, PETITE)) {
            val groupe = liste.filter { format(it) == f && !(it.id == "maison" && dansMaison.isEmpty()) }
            if (groupe.isEmpty()) continue
            val vues = groupe.map { k ->
                val info = if (k.id == "maison") dansMaison.joinToString(", ") { it.court }.lowercase(Locale.FRANCE).replaceFirstChar { it.uppercase() } else k.info
                val v = tuile(if (f == PETITE) k.court else k.titre, info, false, f, k.etat?.invoke()) { k.action() }
                if (k.etat != null) v.findViewWithTag<View>("voyant")?.let { voyants[k.id] = it }
                v.isLongClickable = true
                v.setOnLongClickListener { reglerCase(k); true }
                v as View
            }
            val parRangee = when (f) {
                GRANDE -> if (large >= 900) 4 else 2
                MOYENNE -> if (large >= 900) 5 else if (large >= 600) 3 else 2
                else -> if (large >= 900) 8 else if (large >= 600) 6 else 4
            }.let { if (f == PETITE) maxOf(4, minOf(it, vues.size)) else it }
            if (premiere == null) premiere = vues.firstOrNull()
            // Sur un écran étroit, une grande tuile toute seule prend toute la largeur
            if (f == GRANDE && parRangee == 2 && vues.size % 2 == 1) {
                rangees(parent, vues.subList(0, 1), 1)
                rangees(parent, vues.subList(1, vues.size), 2)
            } else rangees(parent, vues, parRangee)
        }
        return premiere
    }

    /** Appui long sur une tuile : taille, place, rangement dans la tuile « Maison ». */
    private fun reglerCase(k: Case) {
        val f = format(k)
        val ici = lieu(k)
        val choix = ArrayList<Pair<String, () -> Unit>>()
        fun choixTaille(nom: String, valeur: Int) {
            choix.add(Pair(if (f == valeur) "$nom (taille actuelle)" else "Taille : $nom") { prefs.edit().putInt("acc_format_" + k.id, valeur).apply() })
        }
        choixTaille("Grande", GRANDE)
        choixTaille("Moyenne", MOYENNE)
        choixTaille("Petite", PETITE)
        choix.add(Pair("Avancer d'une place") { deplacer(k, -1) })
        choix.add(Pair("Reculer d'une place") { deplacer(k, 1) })
        if (k.id != "maison" && k.id != "reglages") {
            if (ici == "accueil") choix.add(Pair("Ranger dans la tuile « Maison »") { prefs.edit().putString("acc_lieu_" + k.id, "maison").apply() })
            else choix.add(Pair("Mettre sur l'accueil") { prefs.edit().putString("acc_lieu_" + k.id, "accueil").apply() })
        }
        choix.add(Pair("Tout remettre comme au départ") {
            val e = prefs.edit()
            for (cle in prefs.all.keys) if (cle.startsWith("acc_")) e.remove(cle)
            e.apply()
        })
        AlertDialog.Builder(this)
            .setTitle(k.titre)
            .setItems(choix.map { it.first }.toTypedArray()) { _, i -> choix[i].second(); rafraichir() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Échange la tuile avec sa voisine (même endroit, même taille). */
    private fun deplacer(k: Case, pas: Int) {
        val toutes = cases()
        val voisines = toutes.filter { lieu(it) == lieu(k) && format(it) == format(k) }
        val i = voisines.indexOfFirst { it.id == k.id }
        val j = i + pas
        if (i < 0 || j < 0 || j >= voisines.size) { message(if (pas < 0) "Cette tuile est déjà la première." else "Cette tuile est déjà la dernière."); return }
        val ids = ArrayList(toutes.map { it.id })
        val a = ids.indexOf(k.id)
        val b = ids.indexOf(voisines[j].id)
        ids[a] = voisines[j].id
        ids[b] = k.id
        prefs.edit().putString("acc_ordre", ids.joinToString(",")).apply()
    }

    private fun groupeMaison() {
        val c = page("Maison", "Le reste de la maison, rangé ici")
        val l = cases().filter { lieu(it) == "maison" }
        if (l.isEmpty()) texte(c, "Rien n'est rangé ici. Sur l'accueil, fais un appui long sur une tuile puis « Ranger dans la tuile Maison ».")
        mosaique(c, l)
        texte(c, "Appui long sur une tuile : changer sa taille, sa place, ou la remettre sur l'accueil.", 12f)
    }

    // ---------- médias du PC ----------

    private fun medias(type: String, titre: String) {
        val c = page(titre, "Sur le PC de la maison, disque par disque")
        val cle = "racines|$type"
        val connu = dossiersConnus[cle]
        var affiche: String? = null
        fun montrer(t: String) {
            affiche = t
            c.removeAllViews()
            val tab = (try { JSONObject(t).optJSONArray("racines") } catch (e: Exception) { null }) ?: JSONArray()
            if (tab.length() == 0) texte(c, "Aucun dossier ni disque trouvé sur le PC pour cette rubrique.")
            // Regroupement par disque, dans l'ordre d'arrivée
            val groupes = LinkedHashMap<String, ArrayList<JSONObject>>()
            for (i in 0 until tab.length()) {
                val o = tab.getJSONObject(i)
                groupes.getOrPut(o.optString("disque", "")) { ArrayList() }.add(o)
            }
            for ((lettre, liste) in groupes) {
                val nomDisque = liste.firstOrNull { it.optString("nomDisque").isNotEmpty() }?.optString("nomDisque") ?: ""
                val entier = liste.firstOrNull { it.optBoolean("entier") }
                val branche = if (entier != null) entier.optBoolean("connecte", true) else liste.any { it.optBoolean("connecte", true) }
                // En-tête du disque : nom + voyant
                val tete = LinearLayout(this)
                tete.orientation = LinearLayout.HORIZONTAL
                tete.gravity = Gravity.CENTER_VERTICAL
                tete.setPadding(dp(6), dp(16), dp(6), dp(4))
                val v = View(this)
                colorerVoyant(v, if (branche) 1 else 0)
                tete.addView(v, LinearLayout.LayoutParams(dp(12), dp(12)))
                val n = TextView(this)
                n.text = (if (lettre.isEmpty()) "Ce PC" else "Disque $lettre") + (if (nomDisque.isNotEmpty()) " — $nomDisque" else "")
                n.setTextColor(cTexte)
                n.textSize = taille(17f)
                n.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                n.setPadding(dp(10), 0, dp(10), 0)
                tete.addView(n, LinearLayout.LayoutParams(0, -2, 1f))
                val e = TextView(this)
                e.text = if (branche) "connecté" else "non connecté"
                e.setTextColor(if (branche) cVert else cRouge)
                e.textSize = taille(13f)
                tete.addView(e)
                c.addView(tete)
                val tuiles = ArrayList<View>()
                for (o in liste) {
                    val nom = o.optString("nom")
                    val chemin = o.optString("chemin")
                    val ok = o.optBoolean("connecte", true)
                    val raison = o.optString("raison")
                    val tu = if (ok) tuile(nom, if (o.optBoolean("entier")) "Tous les dossiers du disque" else "Dossier") {
                        ouvrir("$titre/$nom", { dossier(type, nom, chemin) })
                    } else tuile(nom, "Non connecté" + (if (raison.isNotEmpty()) " — $raison" else "")) {
                        message("$nom : non connecté." + (if (raison.isNotEmpty()) "\n$raison." else "") + "\nBranche ou allume le disque, puis touche « Vérifier de nouveau ».")
                    }
                    if (!ok) tu.alpha = 0.5f
                    tuiles.add(tu)
                }
                grille(c, tuiles)
            }
            sourcesReseau(c, type)
            bouton(c, "Vérifier de nouveau") { dossiersConnus.remove(cle); rafraichir() }
        }
        if (connu != null) montrer(connu.texte) else attente(c, "Connexion au PC et réveil des disques…")
        enFond({
            val p = passerelle.trouver()
            Pair(p, p?.let { Reseau.lire("$it/racines?type=$type", 25000) })
        }) { (p, t) ->
            if (p == null || t == null) {
                c.removeAllViews()
                pcIntrouvable(c)
                return@enFond
            }
            dossiersConnus[cle] = Connu(p, t)
            if (t != affiche) montrer(t)
        }
    }

    // ---------- le réseau Wi-Fi de la maison ----------

    private val GENRES = linkedMapOf(
        "pc" to "Ordinateurs", "telephone" to "Téléphones et tablettes (avec Ma Maison)", "serveur" to "Serveurs de vidéos et musique",
        "freebox" to "Freebox", "tele" to "Télés", "enceinte" to "Enceintes", "camera" to "Caméras", "imprimante" to "Imprimantes",
        "appareil" to "Autres appareils"
    )

    /** En-tête d'un groupe avec voyant. */
    private fun entete(c: LinearLayout, titre: String, etat: Int?, droite: String = "") {
        val tete = LinearLayout(this)
        tete.orientation = LinearLayout.HORIZONTAL
        tete.gravity = Gravity.CENTER_VERTICAL
        tete.setPadding(dp(6), dp(16), dp(6), dp(4))
        if (etat != null) {
            val v = View(this)
            colorerVoyant(v, etat)
            tete.addView(v, LinearLayout.LayoutParams(dp(12), dp(12)))
        }
        val n = TextView(this)
        n.text = titre
        n.setTextColor(cTexte)
        n.textSize = taille(17f)
        n.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        n.setPadding(dp(10), 0, dp(10), 0)
        tete.addView(n, LinearLayout.LayoutParams(0, -2, 1f))
        if (droite.isNotEmpty()) {
            val e = TextView(this)
            e.text = droite
            e.setTextColor(if (etat == 1) cVert else if (etat == 0) cRouge else cGris)
            e.textSize = taille(13f)
            tete.addView(e)
        }
        c.addView(tete)
    }

    private fun ecranReseau() {
        val c = page("Réseau", "Tout ce qui est branché sur le Wi-Fi de la maison")
        fun montrer(l: List<AppareilReseau>, enCours: Boolean) {
            c.removeAllViews()
            if (enCours) attente(c, "Recherche sur tout le Wi-Fi… (environ 15 secondes)")
            if (l.isEmpty() && !enCours) texte(c, "Aucun appareil trouvé. Ce téléphone est-il bien sur le Wi-Fi de la maison ?", 16f, cTexte)
            for ((g, titre) in GENRES) {
                val groupe = l.filter { it.genre == g }
                if (groupe.isEmpty()) continue
                val n = groupe.count { it.present }
                if (enCours) entete(c, titre, -1, "vérification…") else entete(c, titre, if (n > 0) 1 else 0, "$n / ${groupe.size} connecté(s)")
                grille(c, groupe.map { a ->
                    // Pendant la recherche, les appareils déjà connus restent ouvrables (vérification en cours)
                    val etat = if (enCours) "Vérification…" else if (a.present) "Connecté" else "Non connecté"
                    val info = etat + " · " + a.ip + (if (a.marque.isNotEmpty()) " · " + a.marque else "") + "\n" + capacite(a)
                    val t = tuile(a.nom, info, voyant = if (enCours) -1 else if (a.present) 1 else 0) {
                        if (enCours) a.present = true
                        ouvrirAppareil(a)
                    }
                    if (!enCours && !a.present) t.alpha = 0.55f
                    t
                })
            }
            if (!enCours) {
                bouton(c, "Rechercher de nouveau") { dernierScan = null; rafraichir() }
                if (l.any { !it.present }) bouton(c, "Oublier les appareils non connectés") {
                    prefs.edit().putString("reseau_connus", org.json.JSONArray(l.filter { it.present }.map { it.json() }).toString()).apply()
                    dernierScan = l.filter { it.present }
                    rafraichir()
                }
                texte(c, "Un téléphone ou une tablette n'apparaît ici que si Ma Maison y est installée et que « Partager ce téléphone » y est activé (dans Réglages). Sans ça, Android ne laisse rien voir.", 13f)
                texte(c, "Les appareils qui ne partagent rien (prises, ampoules, la plupart des télés) apparaissent avec leur voyant, mais il n'y a rien à ouvrir dedans.", 13f)
            }
        }
        val deja = dernierScan
        if (deja != null) { montrer(deja, false); return }
        montrer(reseau.trier(reseau.connus().onEach { it.present = false }), true)
        enFond({ reseau.chercher() }) { l -> dernierScan = l; montrer(l, false) }
    }

    /** Ce qu'on peut faire avec l'appareil, en une ligne. */
    private fun capacite(a: AppareilReseau): String = when {
        a.genre == "telephone" -> "Ses photos, vidéos et musiques (avec son code)"
        a.dlna.isNotEmpty() -> "Ses vidéos, photos et musiques"
        a.genre == "pc" && (445 in a.ports || 139 in a.ports) -> "Ses dossiers partagés" + (if (8790 in a.ports) " + Ma Maison" else "")
        a.genre == "pc" && 8790 in a.ports -> "PC de Ma Maison"
        a.genre == "camera" -> "Image en direct (avec son identifiant)"
        8009 in a.ports || a.genre == "enceinte" -> "Reçoit ce que tu envoies (bouton « Envoyer »)"
        else -> "Sur le Wi-Fi, ne partage rien"
    }

    private fun ouvrirAppareil(a: AppareilReseau, type: String = "tout") {
        if (!a.present) { message("${a.nom} n'est pas connecté en ce moment (éteint, ou hors du Wi-Fi). Touche « Rechercher de nouveau » quand il est allumé."); return }
        // Un même appareil peut avoir plusieurs choses à ouvrir (ex. un PC avec partages et serveur de médias) : on propose
        val choix = ArrayList<Pair<String, () -> Unit>>()
        if (a.dlna.isNotEmpty()) choix.add("Serveur de vidéos et musique" to { ouvrir(a.nom, { dossierDlna(a, type, a.nom, "0") }) })
        if (445 in a.ports || 139 in a.ports) choix.add("Dossiers partagés" to { ouvrir(a.nom, { partagesPc(a, type) }) })
        if (8790 in a.ports) choix.add("Disques du PC (Ma Maison)" to { ouvrir("Vidéos", { medias(if (type == "tout") "videos" else type, "Vidéos") }) })
        if (a.genre != "telephone" && choix.size > 1) {
            AlertDialog.Builder(this).setTitle(a.nom)
                .setItems(choix.map { it.first }.toTypedArray()) { _, w -> choix[w].second() }
                .setNegativeButton("Annuler", null).show()
            return
        }
        when {
            a.genre == "telephone" -> ouvrir(a.nom, { telephoneDistant(a, type, "") })
            a.dlna.isNotEmpty() -> ouvrir(a.nom, { dossierDlna(a, type, a.nom, "0") })
            a.genre == "pc" && (445 in a.ports || 139 in a.ports) -> ouvrir(a.nom, { partagesPc(a, type) })
            a.genre == "pc" && 8790 in a.ports -> ouvrir("Vidéos", { medias("videos", "Vidéos") })
            a.genre == "camera" -> ouvrir(a.nom, { cameraReseau(a) })
            8009 in a.ports || a.genre == "enceinte" -> message("${a.nom} reçoit ce que tu envoies : ouvre Vidéos, Musique ou Photos, puis « Envoyer ».")
            else -> message("${a.nom} est bien sur le Wi-Fi, mais il ne partage aucun fichier.")
        }
    }

    /** Dans Vidéos, Photos et Musique : les autres sources du réseau. */
    private fun sourcesReseau(c: LinearLayout, type: String) {
        val l = (dernierScan ?: reseau.connus()).filter { it.dlna.isNotEmpty() || it.genre == "telephone" || (it.genre == "pc" && (445 in it.ports || 139 in it.ports)) }
        if (l.isEmpty()) {
            entete(c, "Sur le réseau", null)
            texte(c, "Rien d'autre pour l'instant. Ouvre la tuile Réseau pour chercher les autres PC, la Freebox, les serveurs et les téléphones.", 13f)
            return
        }
        entete(c, "Sur le réseau", if (l.any { it.present || dernierScan == null }) 1 else 0)
        val vues = l.map { a ->
            val t = tuile(a.nom, capacite(a), voyant = if (dernierScan == null) -1 else if (a.present) 1 else 0) {
                if (dernierScan == null) a.present = true
                ouvrirAppareil(a, type)
            }
            if (dernierScan != null && !a.present) t.alpha = 0.55f
            t
        }
        grille(c, vues)
        // Vérification rapide en fond (sans relancer toute la recherche)
        enFond({ l.map { reseau.repond(it) } }) { etats ->
            l.forEachIndexed { i, a -> a.present = etats[i] }
            vues.forEachIndexed { i, v ->
                v.findViewWithTag<View>("voyant")?.let { colorerVoyant(it, if (etats[i]) 1 else 0) }
                v.alpha = if (etats[i]) 1f else 0.55f
            }
        }
    }

    /** Dossier générique (serveur de médias, partage Windows, téléphone) : mêmes listes rapides que pour le PC. */
    private fun dossierSource(type: String, nom: String, cle: String, charger: () -> String?, erreur: String,
                              fabriqueLien: (String) -> String, sous: (String, String) -> Unit) {
        val c = page(nom, defilant = false)
        val connu = dossiersConnus[cle]
        if (connu != null) remplirDossier(c, type, cle, connu.texte, fabriqueLien, sous) else attente(c, "Lecture…")
        enFond({ try { charger() } catch (e: Throwable) { null } }) { t ->
            if (t == null) {
                c.removeAllViews()
                texte(c, erreur, 16f, cTexte)
                bouton(c, "Réessayer") { rafraichir() }
                return@enFond
            }
            if (connu?.texte == t) return@enFond
            dossiersConnus[cle] = Connu("", t)
            c.removeAllViews()
            remplirDossier(c, type, cle, t, fabriqueLien, sous)
        }
    }

    private fun garderType(type: String, nom: String, mime: String): Boolean {
        if (type == "tout") return true
        val x = nom.substringAfterLast('.', "").lowercase()
        if (x in Partages.extensions(type)) return true
        return when (type) { "videos" -> mime.startsWith("video"); "photos" -> mime.startsWith("image"); "musique" -> mime.startsWith("audio"); else -> true }
    }

    // ---- serveurs de médias (Freebox, Jellyfin…) ----

    private fun dossierDlna(a: AppareilReseau, type: String, nom: String, objet: String) {
        dossierSource(type, nom, "dlna|${a.dlna}|$objet|$type", {
            val l = Dlna.parcourir(a.dlna, objet) ?: return@dossierSource null
            val d = org.json.JSONArray(); val f = org.json.JSONArray()
            for (x in l) {
                if (x.dossier) d.put(JSONObject().put("nom", x.titre).put("chemin", x.id))
                else if (garderType(type, x.titre, x.type)) f.put(JSONObject().put("nom", x.titre).put("chemin", x.lien).put("taille", x.taille))
            }
            JSONObject().put("dossiers", d).put("fichiers", f).toString()
        }, "${a.nom} ne répond pas (éteint, ou serveur de médias arrêté).", { it }) { n, id -> dossierDlna(a, type, n, id) }
    }

    // ---- partages Windows ----

    private fun partagesPc(a: AppareilReseau, type: String) {
        val c = page(a.nom, "Dossiers partagés de ce PC")
        attente(c, "Connexion au PC…")
        enFond({ try { Pair(partages.partages(a.ip), null) } catch (e: Throwable) { Pair(null, partages.raison(e)) } }) { (l, err) ->
            c.removeAllViews()
            if (l == null) {
                texte(c, "Connexion refusée : $err.", 16f, cTexte)
                texte(c, "Entre une fois l'identifiant et le mot de passe Windows de ce PC (ceux qu'on tape pour ouvrir la session). Ils restent seulement sur ce téléphone.", 14f)
                val u = champ(c, "Identifiant Windows (ou adresse du compte Microsoft)", partages.identifiant(a.ip)?.first ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                val m = champ(c, "Mot de passe Windows", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                bouton(c, "Se connecter") { partages.retenir(a.ip, u.text.toString().trim(), m.text.toString()); rafraichir() }
                bouton(c, "Essayer sans mot de passe") { partages.retenir(a.ip, "", ""); rafraichir() }
                return@enFond
            }
            if (l.isEmpty()) texte(c, "Ce PC ne partage aucun dossier. Sur le PC : clic droit sur un dossier > Propriétés > Partage.", 15f, cTexte)
            grille(c, l.map { p -> tuile(p.nom, "Dossier partagé") { ouvrir("${a.nom}/${p.nom}", { dossierSmb(type, p.nom, p.chemin) }) } })
            bouton(c, "Changer d'identifiant Windows") { partages.oublier(a.ip); partages.retenir(a.ip, "?", "?"); rafraichir() }
        }
    }

    private fun dossierSmb(type: String, nom: String, chemin: String) {
        val ip = reseau.monIp()
        dossierSource(type, nom, "smb|$chemin|$type", {
            val l = partages.lister(chemin, Partages.extensions(type))
            val d = org.json.JSONArray(); val f = org.json.JSONArray()
            for (x in l) if (x.dossier) d.put(JSONObject().put("nom", x.nom).put("chemin", x.chemin))
                         else f.put(JSONObject().put("nom", x.nom).put("chemin", x.chemin).put("taille", x.taille))
            JSONObject().put("dossiers", d).put("fichiers", f).toString()
        }, "Ce dossier partagé ne répond pas (PC éteint, ou accès refusé).", { Relais.lienSmb(this, ip, it) }) { n, ch -> dossierSmb(type, n, ch) }
    }

    // ---- téléphones avec Ma Maison ----

    private fun telephoneDistant(a: AppareilReseau, type: String, dossierChoisi: String) {
        val code = prefs.getString("code_tel_${a.cle}", "") ?: ""
        if (code.isEmpty()) {
            val c = page(a.nom)
            texte(c, "Ce téléphone partage ses photos et vidéos avec un code. Le code s'affiche sur ce téléphone, dans Ma Maison > Réglages > Partager ce téléphone.", 15f, cTexte)
            val e = champ(c, "Code du téléphone (6 chiffres)", "", InputType.TYPE_CLASS_NUMBER)
            bouton(c, "Valider") { prefs.edit().putString("code_tel_${a.cle}", e.text.toString().trim()).apply(); rafraichir() }
            return
        }
        val types = if (type == "tout") listOf("photos", "videos", "musique") else listOf(type)
        if (type == "tout") {
            val c = page(a.nom)
            grille(c, listOf(tuile("Photos", "Photos du téléphone") { ouvrir("${a.nom}/Photos", { telephoneDistant(a, "photos", "") }) },
                tuile("Vidéos", "Vidéos du téléphone") { ouvrir("${a.nom}/Vidéos", { telephoneDistant(a, "videos", "") }) },
                tuile("Musique", "Musiques du téléphone") { ouvrir("${a.nom}/Musique", { telephoneDistant(a, "musique", "") }) }))
            bouton(c, "Changer le code") { prefs.edit().remove("code_tel_${a.cle}").apply(); rafraichir() }
            return
        }
        val base = "http://${a.ip}:${Relais.PORT}/partage"
        dossierSource(type, if (dossierChoisi.isEmpty()) a.nom else dossierChoisi, "tel|${a.ip}|$type|$dossierChoisi", {
            val t = Reseau.lire("$base/liste?type=${types[0]}&code=" + Reseau.encode(code), 20000)
            if (t == null) {
                // Code refusé ? on le redemandera
                if (Reseau.lire("$base/ping", 3000) != null) prefs.edit().remove("code_tel_${a.cle}").apply()
                return@dossierSource null
            }
            val o = JSONObject(t)
            val l = o.optJSONArray("fichiers") ?: org.json.JSONArray()
            val d = org.json.JSONArray(); val f = org.json.JSONArray()
            val vus = LinkedHashSet<String>()
            for (i in 0 until l.length()) {
                val x = l.getJSONObject(i)
                val dos = x.optString("dossier")
                if (dossierChoisi.isEmpty()) { if (vus.add(dos)) d.put(JSONObject().put("nom", dos).put("chemin", dos)) }
                else if (dos == dossierChoisi) f.put(JSONObject().put("nom", x.optString("nom")).put("chemin", x.optLong("id").toString()).put("taille", x.optLong("taille")))
            }
            JSONObject().put("dossiers", d).put("fichiers", f).toString()
        }, "Ce téléphone ne répond pas, ou son code a changé (redemandé à la prochaine ouverture). Vérifie que son partage est activé.",
            { id -> "$base/fichier?type=${types[0]}&id=$id&code=" + Reseau.encode(code) }) { n, _ -> telephoneDistant(a, type, n) }
    }

    // ---- caméras du réseau ----

    private fun cameraReseau(a: AppareilReseau) {
        val c = page(a.nom, "Caméra du réseau")
        val u0 = prefs.getString("cam_u_${a.ip}", null)
        if (u0 == null) {
            texte(c, "Entre une fois l'identifiant de la caméra (souvent sur l'étiquette, ou celui de son appli). Il reste seulement sur ce téléphone.", 15f, cTexte)
            val u = champ(c, "Identifiant de la caméra (souvent admin)", "admin", InputType.TYPE_CLASS_TEXT)
            val m = champ(c, "Mot de passe de la caméra", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            bouton(c, "Voir la caméra") { prefs.edit().putString("cam_u_${a.ip}", u.text.toString().trim()).putString("cam_p_${a.ip}", m.text.toString()).apply(); rafraichir() }
            return
        }
        attente(c, "Recherche du flux vidéo de la caméra…")
        val port = if (554 in a.ports) 554 else (a.ports.firstOrNull { it == 8554 } ?: 554)
        enFond({ Rtsp.trouver(a.ip, port, u0, prefs.getString("cam_p_${a.ip}", "") ?: "") }) { r ->
            c.removeAllViews()
            if (r.lien != null) {
                texte(c, "Flux trouvé.", 15f, cVert)
                bouton(c, "Voir en direct") { lire(arrayOf(r.lien), arrayOf(a.nom), 0, false) }
                lire(arrayOf(r.lien), arrayOf(a.nom), 0, false)
            } else {
                texte(c, "Impossible d'afficher la caméra : ${r.raison}.", 16f, cTexte)
                bouton(c, "Changer l'identifiant") { prefs.edit().remove("cam_u_${a.ip}").remove("cam_p_${a.ip}").apply(); rafraichir() }
                bouton(c, "Réessayer") { rafraichir() }
            }
        }
    }

    // ---- partager ce téléphone ----

    private fun demarrerPartage() {
        val i = Intent(this, PartageService::class.java)
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i) } catch (e: Exception) { }
    }

    private fun permissionsMedias(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
        arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", "android.permission.POST_NOTIFICATIONS")
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun partagerCeTelephone() {
        val c = page("Partager ce téléphone", "Ses photos, vidéos et musiques, visibles sur le Wi-Fi de la maison")
        val actif = Relais.partageActif(this)
        if (actif) {
            texte(c, "Partage activé.", 17f, cVert)
            texte(c, "Nom sur le réseau : " + Relais.nomTelephone(), 15f, cTexte)
            texte(c, "Code à entrer sur les autres appareils :", 15f, cTexte)
            texte(c, Relais.codePartage(this), 40f, cAccent)
            texte(c, "Sans ce code, personne ne voit rien. Une notification « Partage actif » reste affichée tant que c'est activé.", 13f)
            bouton(c, "Changer le nom") { saisir("Nom de ce téléphone sur le réseau", Relais.nomTelephone()) { prefs.edit().putString("partage_nom", it.trim()).apply(); rafraichir() } }
            bouton(c, "Arrêter le partage") {
                prefs.edit().putBoolean("partage_actif", false).apply()
                stopService(Intent(this, PartageService::class.java))
                rafraichir()
            }
        } else {
            texte(c, "Partage arrêté.", 17f, cTexte)
            texte(c, "En l'activant, Android te demande une fois l'autorisation de lire les photos, vidéos et musiques de ce téléphone. Les autres appareils de la maison les voient ensuite dans Réseau, avec un code.", 14f)
            bouton(c, "Activer le partage") {
                val manquantes = permissionsMedias().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (Build.VERSION.SDK_INT >= 23 && manquantes.isNotEmpty()) requestPermissions(manquantes.toTypedArray(), 77)
                prefs.edit().putBoolean("partage_actif", true).apply()
                Relais.codePartage(this)
                demarrerPartage()
                rafraichir()
            }
        }
    }

    private fun pcIntrouvable(c: LinearLayout) {
        texte(c, "Le PC de la maison ne répond pas.", 17f, cTexte)
        texte(c, "Vérifie que le PC est allumé et que ce téléphone est sur le Wi-Fi de la maison. L'appli cherche toute seule sur tout le réseau.")
        bouton(c, "Réessayer") { rafraichir() }
    }

    private fun dossier(type: String, nom: String, chemin: String) {
        val c = page(nom, defilant = false)
        val cle = "$type|$chemin"
        val connu = dossiersConnus[cle]
        var affiche: Connu? = null
        fun montrer(k: Connu) {
            affiche = k
            c.removeAllViews()
            remplirDossier(c, type, cle, k.texte, { ch -> passerelle.lienFichier(k.base, ch) }) { n, ch -> dossier(type, n, ch) }
        }
        // Dossier déjà ouvert : affiché tout de suite, puis revérifié en fond
        if (connu != null) montrer(connu) else attente(c, "Lecture du dossier…")
        enFond({
            val p = passerelle.trouver()
            Pair(p, p?.let { Reseau.lire("$it/liste?type=$type&chemin=" + Reseau.encode(chemin), 15000) })
        }) { (p, t) ->
            if (p == null || t == null) {
                c.removeAllViews()
                pcIntrouvable(c)
                return@enFond
            }
            val neuf = Connu(p, t)
            dossiersConnus[cle] = neuf
            if (affiche?.texte != t || affiche?.base != p) montrer(neuf)
        }
    }

    // ---------- âge conseillé des films (pour que les enfants sachent si un film fait peur) ----------

    /** Codes d'âge : texte affiché et couleur de la pastille (classement français : tous publics, -10, -12, -16, -18). */
    private val ages = listOf(
        Triple("tous", "Tous publics", "#3FBF6F"),
        Triple("10", "Moins de 10 ans : déconseillé", "#3FA7E0"),
        Triple("12", "Moins de 12 ans : déconseillé", "#E8C23A"),
        Triple("16", "Moins de 16 ans : interdit", "#F08A30"),
        Triple("18", "Moins de 18 ans : interdit", "#EB5757")
    )

    private fun ageDe(nom: String): Triple<String, String, String>? {
        val code = prefs.getString("age_$nom", null) ?: return null
        return ages.firstOrNull { it.first == code }
    }

    private fun choisirAge(nom: String, ensuite: () -> Unit) {
        val choix = ages.map { it.second }.toMutableList()
        choix.add("Enlever l'indication")
        AlertDialog.Builder(this)
            .setTitle("Âge conseillé pour « $nom »")
            .setItems(choix.toTypedArray()) { _, w ->
                if (w < ages.size) prefs.edit().putString("age_$nom", ages[w].first).apply()
                else prefs.edit().remove("age_$nom").apply()
                ensuite()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Contenu d'un dossier : deux boutons fixes en haut, puis une liste qui ne fabrique que les lignes visibles. */
    private fun remplirDossier(c: LinearLayout, type: String, cle: String, t: String, fabriqueLien: (String) -> String, sous: (String, String) -> Unit) {
        val o = try { JSONObject(t) } catch (e: Exception) { JSONObject() }
        val dossiers = o.optJSONArray("dossiers") ?: JSONArray()
        val fichiers = o.optJSONArray("fichiers") ?: JSONArray()
        val nd = dossiers.length()
        val nf = fichiers.length()
        if (nd == 0 && nf == 0) { texte(c, "Ce dossier est vide."); return }
        val noms = Array(nf) { fichiers.getJSONObject(it).optString("nom") }
        val liens = Array(nf) { fabriqueLien(fichiers.getJSONObject(it).optString("chemin")) }
        val poids = LongArray(nf) { fichiers.getJSONObject(it).optLong("taille") / (1024 * 1024) }
        val nomsDossiers = Array(nd) { dossiers.getJSONObject(it).optString("nom") }
        val cheminsDossiers = Array(nd) { dossiers.getJSONObject(it).optString("chemin") }

        if (nf > 0) {
            val haut = LinearLayout(this)
            haut.orientation = LinearLayout.HORIZONTAL
            fun petit(b: Button) {
                b.textSize = taille(14f)
                val lp = LinearLayout.LayoutParams(0, -1, 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(8))
                b.layoutParams = lp
            }
            if (type != "photos") petit(bouton(haut, if (type == "musique") "Tout écouter ici" else "Tout lire ici à la suite") { lire(liens, noms, 0, type == "musique") })
            petit(bouton(haut, if (type == "musique") "Envoyer sur une enceinte ou la télé" else "Envoyer sur la télé") { diffusion.envoyer(liens, noms, 0, type) })
            c.addView(haut, LinearLayout.LayoutParams(-1, -2))
        }

        if (type == "videos") texte(c, "Appui long sur un film ou un dossier : indiquer l'âge conseillé (pastille de couleur).", 12f)

        val liste = ListView(this)
        liste.divider = null
        liste.dividerHeight = 0
        liste.cacheColorHint = Color.TRANSPARENT
        liste.clipToPadding = false
        liste.setPadding(0, 0, 0, dp(24))
        liste.isFastScrollEnabled = nd + nf > 80
        // Ligne choisie : voile clair ; sur la télé, cadre blanc bien visible
        val choisi = GradientDrawable()
        choisi.cornerRadius = dp(12).toFloat()
        choisi.setColor(if (th.clair) Color.argb(30, 0, 0, 0) else Color.argb(40, 255, 255, 255))
        if (Tv.isTv(this)) choisi.setStroke(dp(3), if (th.clair) cTexte else Color.WHITE)
        liste.selector = choisi
        // Télé : le cadre de la liste n'apparaît que quand la télécommande est dans la liste (jamais deux cadres à la fois)
        if (Tv.isTv(this)) {
            choisi.alpha = 0
            liste.setOnFocusChangeListener { _, dedans -> choisi.alpha = if (dedans) 255 else 0; liste.invalidate() }
        }
        liste.setDrawSelectorOnTop(true)
        liste.adapter = object : BaseAdapter() {
            override fun getCount() = nd + nf
            override fun getItem(i: Int): Any = i
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, ancienne: View?, parent: ViewGroup?): View {
                val vue = (ancienne as? LinearLayout) ?: LinearLayout(this@MainActivity).also { ext ->
                    ext.orientation = LinearLayout.VERTICAL
                    ext.setPadding(dp(4), dp(4), dp(4), dp(4))
                    ext.layoutParams = AbsListView.LayoutParams(-1, -2)
                    val carte = LinearLayout(this@MainActivity)
                    carte.orientation = LinearLayout.VERTICAL
                    carte.background = rond(cCarte, dp(12))
                    carte.setPadding(dp(14), dp(12), dp(14), dp(12))
                    val a = TextView(this@MainActivity)
                    a.setTextColor(cTexte)
                    a.textSize = taille(16f)
                    carte.addView(a)
                    val b = TextView(this@MainActivity)
                    b.setTextColor(cGris)
                    b.textSize = taille(12f)
                    carte.addView(b)
                    ext.addView(carte, LinearLayout.LayoutParams(-1, -2))
                }
                val carte = vue.getChildAt(0) as LinearLayout
                val a = carte.getChildAt(0) as TextView
                val b = carte.getChildAt(1) as TextView
                val nomLigne = if (i < nd) nomsDossiers[i] else noms[i - nd]
                val age = if (type == "videos") ageDe(nomLigne) else null
                val infos = ArrayList<String>()
                if (age != null) infos.add("●  " + age.second)
                if (i < nd) {
                    a.text = "▸  " + nomsDossiers[i]
                    infos.add("Dossier")
                } else {
                    val f = i - nd
                    a.text = noms[f]
                    if (poids[f] > 0) infos.add("${poids[f]} Mo")
                }
                b.text = infos.joinToString("   ·   ")
                b.setTextColor(if (age != null) Color.parseColor(age.third) else cGris)
                b.visibility = if (infos.isEmpty()) View.GONE else View.VISIBLE
                return vue
            }
        }
        liste.setOnItemClickListener { _, _, i, _ ->
            if (i < nd) ouvrir(ecranActuel + "/" + nomsDossiers[i], { sous(nomsDossiers[i], cheminsDossiers[i]) })
            else {
                val f = i - nd
                if (type == "photos") ouvrir("Photo", { photo(liens, noms, f) }) else lire(liens, noms, f, type == "musique")
            }
        }
        if (type == "videos") liste.setOnItemLongClickListener { _, _, i, _ ->
            choisirAge(if (i < nd) nomsDossiers[i] else noms[i - nd]) { (liste.adapter as BaseAdapter).notifyDataSetChanged() }
            true
        }
        // On retrouve la liste là où on l'avait laissée
        positions[cle]?.let { liste.setSelectionFromTop(it[0], it[1]) }
        liste.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, etat: Int) {}
            override fun onScroll(v: AbsListView?, premier: Int, visibles: Int, total: Int) {
                if (visibles > 0) positions[cle] = intArrayOf(premier, liste.getChildAt(0)?.top ?: 0)
            }
        })
        c.addView(liste, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun ligne(parent: LinearLayout, titre: String, info: String, action: () -> Unit): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.background = rond(cCarte, dp(12))
        l.setPadding(dp(14), dp(12), dp(14), dp(12))
        val a = TextView(this)
        a.text = titre
        a.setTextColor(cTexte)
        a.textSize = taille(16f)
        l.addView(a)
        if (info.isNotEmpty()) {
            val b = TextView(this)
            b.text = info
            b.setTextColor(cGris)
            b.textSize = taille(12f)
            l.addView(b)
        }
        l.setOnClickListener { action() }
        effetAppui(l)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        parent.addView(l, lp)
        return l
    }

    private fun lire(liens: Array<String>, noms: Array<String>, index: Int, musique: Boolean) {
        val i = Intent(this, LecteurActivity::class.java)
        i.putExtra("liens", liens)
        i.putExtra("noms", noms)
        i.putExtra("index", index)
        i.putExtra("musique", musique)
        startActivity(i)
    }

    private fun photo(liens: Array<String>, noms: Array<String>, depart: Int) {
        var index = depart
        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        val image = VuePhoto(this)
        racine.addView(image, FrameLayout.LayoutParams(-1, -1))
        val nom = TextView(this)
        nom.setTextColor(Color.WHITE)
        nom.textSize = 15f
        nom.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        nom.setPadding(dp(16), dp(16), dp(16), dp(16))
        racine.addView(nom, FrameLayout.LayoutParams(-1, -2))
        val bas = LinearLayout(this)
        bas.orientation = LinearLayout.VERTICAL
        bas.setPadding(dp(12), dp(8), dp(12), dp(16))
        fun rangee(): LinearLayout {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            bas.addView(r, LinearLayout.LayoutParams(-1, -2))
            return r
        }
        fun btn(r: LinearLayout, t: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = t
            b.isAllCaps = false
            b.textSize = 13f
            b.maxLines = 1
            b.setPadding(dp(2), dp(10), dp(2), dp(10))
            b.setTextColor(Color.WHITE)
            b.background = rond(Color.argb(170, 40, 44, 52), dp(12))
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            r.addView(b, lp)
            return b
        }
        // La rotation choisie est retenue pour chaque photo (par son nom)
        fun cleRotation() = "rotation_" + noms[index]
        val r1 = rangee()
        btn(r1, "Tourner à gauche") { prefs.edit().putInt(cleRotation(), image.tourner(-1)).apply() }
        btn(r1, "Agrandir") { image.basculerZoom() }
        btn(r1, "Tourner à droite") { prefs.edit().putInt(cleRotation(), image.tourner(1)).apply() }
        val r2 = rangee()
        val prec = btn(r2, "‹ Précédente") { }
        btn(r2, "Sur la télé") { diffusion.envoyer(liens, noms, index, "photos") }
        val suiv = btn(r2, "Suivante ›") { }
        val lpb = FrameLayout.LayoutParams(-1, -2)
        lpb.gravity = Gravity.BOTTOM
        racine.addView(bas, lpb)
        val aide = TextView(this)
        aide.text = "Deux doigts ou double appui : agrandir. Glisser : photo suivante. Un appui : cacher les boutons."
        aide.setTextColor(Color.argb(200, 255, 255, 255))
        aide.textSize = 12f
        aide.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        aide.setPadding(dp(16), 0, dp(16), 0)
        bas.addView(aide, 0)
        val roue = ProgressBar(this)
        val lpr = FrameLayout.LayoutParams(dp(48), dp(48))
        lpr.gravity = Gravity.CENTER
        racine.addView(roue, lpr)
        setContentView(racine)
        image.appuiSimple = {
            val v = if (bas.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            bas.visibility = v
            nom.visibility = v
        }

        val largeur = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        fun charger() {
            nom.text = "${noms[index]}   (${index + 1}/${noms.size})"
            roue.visibility = View.VISIBLE
            val demande = index
            thread {
                val o = Reseau.octets(liens[demande], 20000)
                val bmp = try {
                    if (o == null) null else {
                        val opt = BitmapFactory.Options()
                        opt.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(o, 0, o.size, opt)
                        // Assez de détails pour agrandir, sans dépasser la mémoire
                        var ech = 1
                        while (opt.outWidth / ech > largeur * 3 / 2 || opt.outHeight / ech > largeur * 3 / 2) ech *= 2
                        val o2 = BitmapFactory.Options()
                        o2.inSampleSize = ech
                        BitmapFactory.decodeByteArray(o, 0, o.size, o2)
                    }
                } catch (e: Throwable) { null }
                ui.post {
                    if (demande != index || isFinishing) return@post
                    roue.visibility = View.GONE
                    if (bmp == null) message("Cette image ne peut pas s'afficher.")
                    image.montrer(bmp, prefs.getInt(cleRotation(), 0))
                }
            }
        }
        val avant = { if (index > 0) { index--; charger() } else message("C'est la première photo.") }
        val apres = { if (index < liens.size - 1) { index++; charger() } else message("C'est la dernière photo.") }
        prec.setOnClickListener { avant() }
        suiv.setOnClickListener { apres() }
        image.precedente = avant
        image.suivante = apres
        charger()
    }

    // ---------- appareils de la maison ----------

    private fun moteurAbsent(c: LinearLayout) {
        texte(c, "Le moteur de la maison n'est pas encore installé sur le PC.", 17f, cTexte)
        texte(c, "Ce module est prêt : dès que le moteur sera installé et tes appareils branchés, ils apparaîtront ici tout seuls.")
    }

    /** Tous les appareils connus : moteur du PC (s'il existe) + Smart Life. Hors du fil de l'écran. */
    private fun appareilsMaison(): List<Appareil>? {
        if (!moteur.pret) moteur.recupererDepuisPasserelle(passerelle.trouver())
        val l = ArrayList<Appareil>()
        var unOk = false
        if (moteur.pret) moteur.appareils()?.let { l.addAll(it); unOk = true }
        if (smartLife.pret) smartLife.appareils()?.let { l.addAll(it); unOk = true }
        return if (unOk) l.sortedBy { it.nom.lowercase() } else null
    }

    /** Allume / éteint / inverse, quel que soit le système de l'appareil. */
    private fun agir(a: Appareil, allumer: Boolean?): Boolean {
        if (smartLife.estSmartLife(a)) return smartLife.agir(a, allumer)
        val d = JSONObject().put("entity_id", a.id)
        return when (allumer) {
            null -> moteur.basculer(a)
            true -> if (a.domaine == "cover") moteur.service("cover", "open_cover", d) else moteur.service("homeassistant", "turn_on", d)
            false -> if (a.domaine == "cover") moteur.service("cover", "close_cover", d) else moteur.service("homeassistant", "turn_off", d)
        }
    }

    /** YouPub : la version télé si elle est installée (Freebox), sinon YouPub normale. */
    private fun ouvrirYouPub() {
        for (p in listOf("fr.hadesdray.youpub.tv", "fr.hadesdray.youpub")) {
            packageManager.getLaunchIntentForPackage(p)?.let { startActivity(it); return }
        }
        // Pas encore là : Ma Maison l'installe elle-même (la version télé sur une télé, sinon la version télé aussi, plus légère).
        message("Installation de YouPub TV : valide quand Android le demande.")
        miseAJour.installerYouPubTv()
    }

    private fun ouvrirAppli(paquet: String, nom: String) {
        val i = packageManager.getLaunchIntentForPackage(paquet)
        if (i != null) { startActivity(i); return }
        // 1) le magasin d'applis, 2) sa page dans le navigateur
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$paquet")))
        } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$paquet"))) }
            catch (e2: Exception) { message("L'appli $nom n'est pas installée sur cet appareil.") }
        }
    }

    private fun chargerAppareils(c: LinearLayout, suite: (List<Appareil>) -> Unit) {
        val att = attente(c, "Chargement des appareils…")
        enFond({ appareilsMaison() }) { l ->
            c.removeView(att)
            when {
                l != null -> { appareilsCache = l; suite(l) }
                !moteur.pret && !smartLife.pret -> {
                    texte(c, "Aucun système d'appareils n'est encore relié.", 17f, cTexte)
                    texte(c, "Relie ton compte Smart Life : c'est à faire une seule fois.")
                    bouton(c, "Relier Smart Life") { ouvrir("Relier Smart Life", { relierSmartLife() }) }
                }
                else -> {
                    texte(c, "Les appareils ne répondent pas.", 17f, cTexte)
                    if (smartLife.erreur.isNotEmpty()) texte(c, smartLife.erreur)
                    bouton(c, "Réessayer") { rafraichir() }
                }
            }
        }
    }

    private fun libelleEtat(a: Appareil): String = when (a.etat) {
        "on" -> "Allumé"
        "off" -> "Éteint"
        "open" -> "Ouvert"
        "closed" -> "Fermé"
        "opening" -> "S'ouvre…"
        "closing" -> "Se ferme…"
        "locked" -> "Verrouillé"
        "unlocked" -> "Déverrouillé"
        "playing" -> "En lecture"
        "paused" -> "En pause"
        "idle", "standby" -> "En veille"
        "unavailable" -> "Injoignable"
        "unknown" -> "Inconnu"
        "home" -> "À la maison"
        "not_home" -> "Absent"
        else -> a.etat + (if (a.unite.isNotEmpty()) " " + a.unite else "")
    }

    private fun appareilsDe(titre: String, domaines: Set<String>, motUnitaire: String) {
        val c = page(titre)
        chargerAppareils(c) { tous ->
            val l = tous.filter { it.domaine in domaines }
            if (l.isEmpty()) {
                texte(c, "Aucun $motUnitaire branché pour l'instant.", 16f, cTexte)
                texte(c, "Va dans « Ajouter un appareil » depuis l'accueil.")
                return@chargerAppareils
            }
            val tuiles = l.map { a ->
                tuile(a.nom, libelleEtat(a), a.allume) {
                    if (a.etat == "unavailable") { message("${a.nom} est injoignable (éteint ou hors Wi-Fi)."); return@tuile }
                    enFond({ agir(a, null) }) { ok ->
                        if (!ok) message(if (smartLife.estSmartLife(a) && smartLife.erreur.isNotEmpty()) smartLife.erreur else "L'appareil n'a pas répondu.")
                        ui.postDelayed({ if (pile.isNotEmpty()) rafraichir() }, 700)
                    }
                }
            }
            grille(c, tuiles)
            if (domaines.contains("light")) bouton(c, "Tout éteindre") {
                enFond({ l.filter { it.allume }.forEach { agir(it, false) } }) { ui.postDelayed({ rafraichir() }, 900) }
            }
            if (domaines.contains("media_player")) {
                texte(c, "Touche un appareil pour lecture ou pause. Pour le volume :")
                for (a in l) {
                    val rangee = LinearLayout(this)
                    rangee.orientation = LinearLayout.HORIZONTAL
                    val n = TextView(this)
                    n.text = a.nom
                    n.setTextColor(cTexte)
                    n.textSize = taille(15f)
                    rangee.addView(n, LinearLayout.LayoutParams(0, -2, 1f))
                    for ((lib, srv) in listOf("Volume −" to "volume_down", "Volume +" to "volume_up")) {
                        val b = Button(this)
                        b.text = lib
                        b.isAllCaps = false
                        b.setOnClickListener { enFond({ moteur.service("media_player", srv, JSONObject().put("entity_id", a.id)) }) { } }
                        rangee.addView(b)
                    }
                    c.addView(rangee)
                }
            }
            minuteur = Runnable { rafraichir() }.also { ui.postDelayed(it, 15000) }
        }
    }

    // ---------- jeux en streaming ----------

    /** Services connus : nom, appli Android, adresse de la version navigateur. */
    private val servicesJeux = listOf(
        Triple("GeForce Now", "com.nvidia.geforcenow", "https://play.geforcenow.com"),
        Triple("Xbox (jeux dans le nuage)", "com.gamepass", "https://www.xbox.com/fr-FR/play"),
        Triple("Amazon Luna", "com.amazon.luna", "https://luna.amazon.fr"),
        Triple("Boosteroid", "com.boosteroid.streaming", "https://cloud.boosteroid.com")
    )

    private fun ouvrirJeu(nom: String, paquet: String, web: String) {
        // 1) l'appli si elle est installée, 2) la version navigateur, 3) le magasin
        val i = if (paquet.isNotEmpty()) packageManager.getLaunchIntentForPackage(paquet) else null
        if (i != null) { startActivity(i); return }
        if (web.isNotEmpty()) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web))); return } catch (e: Exception) { }
        }
        if (paquet.isNotEmpty()) ouvrirAppli(paquet, nom) else message("Impossible d'ouvrir $nom.")
    }

    private fun jeux() {
        val c = page("Jeux", "Tes jeux en streaming : tes comptes et tes parties restent chez chaque service, sur toutes les tablettes.")
        // Jeux installés sur tes PC (ROG, PC fixe), joués à distance par l'appli gratuite Moonlight
        val moonlight = packageManager.getLaunchIntentForPackage("com.limelight") != null
        val pcs = ArrayList<View>()
        pcs.add(tuile("Jeux des PC", if (moonlight) "Tes jeux du ROG et du PC fixe, ici — par l'appli Moonlight" else "Installer l'appli gratuite Moonlight (une fois)", format = GRANDE, voyant = if (moonlight) 1 else 0) {
            if (moonlight) ouvrirJeu("Moonlight", "com.limelight", "") else ouvrirAppli("com.limelight", "Moonlight")
        })
        rangees(c, pcs, 1)
        texte(c, if (moonlight) "Le PC doit être allumé. La première fois, Moonlight affiche un code à 4 chiffres à valider sur le PC." else "Il faudra aussi le programme gratuit Sunshine sur chaque PC : à installer quand ton PC sera relié à Claude.", 12f)
        val tuiles = ArrayList<View>()
        for ((nom, paquet, web) in servicesJeux) {
            val installee = packageManager.getLaunchIntentForPackage(paquet) != null
            tuiles.add(tuile(nom, if (installee) "Appli installée" else "S'ouvre dans le navigateur") { ouvrirJeu(nom, paquet, web) })
        }
        val ajoutes = (prefs.getString("jeux_ajoutes", "") ?: "").split("\n").filter { it.contains("|") }
        for (ligneJeu in ajoutes) {
            val (nom, paquet) = ligneJeu.split("|", limit = 2)
            tuiles.add(tuile(nom, "Ajouté par toi — appui long pour retirer") { ouvrirJeu(nom, paquet, "") }.also { v ->
                v.setOnLongClickListener {
                    val reste = ajoutes.filter { it != ligneJeu }.joinToString("\n")
                    prefs.edit().putString("jeux_ajoutes", reste).apply()
                    message("$nom retiré de la page Jeux.")
                    rafraichir()
                    true
                }
            })
        }
        grille(c, tuiles)
        bouton(c, "Ajouter une autre appli de jeu de cette tablette") { choisirAppliJeu() }
        texte(c, "La première fois sur chaque tablette, connecte-toi à ton compte dans le service. Ensuite c'est retenu.")
        texte(c, "Amazon Luna a fortement réduit son service en 2026 : certains jeux n'y sont plus.")
    }

    private fun choisirAppliJeu() {
        val principal = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val applis = packageManager.queryIntentActivities(principal, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        if (applis.isEmpty()) { message("Aucune appli trouvée."); return }
        AlertDialog.Builder(this)
            .setTitle("Quelle appli de jeu ajouter ?")
            .setItems(applis.map { it.first }.toTypedArray()) { _, w ->
                val (nom, paquet) = applis[w]
                val actuel = prefs.getString("jeux_ajoutes", "") ?: ""
                prefs.edit().putString("jeux_ajoutes", (if (actuel.isEmpty()) "" else actuel + "\n") + "$nom|$paquet").apply()
                message("$nom ajouté à la page Jeux.")
                rafraichir()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun teleEnceintes() {
        val c = page("Télé et enceintes")
        texte(c, "Envoyer tes vidéos, musiques et photos du PC", 18f, cAccent)
        texte(c, "Ouvre Vidéos, Musique ou Photos, choisis un dossier, puis touche « Envoyer sur la télé » ou « Envoyer sur une enceinte ou la télé ». Ça marche avec l'enceinte Google, la Freebox Player et les télés qui acceptent la diffusion Google.", 15f, cTexte)
        bouton(c, "Télécommande de l'envoi en cours") { diffusion.telecommande() }
        bouton(c, "Vidéos") { ouvrir("Vidéos", { medias("videos", "Vidéos") }) }
        bouton(c, "Musique") { ouvrir("Musique", { medias("musique", "Musique") }) }
        texte(c, "Autres réglages des enceintes et télés", 18f, cAccent)
        bouton(c, "Ouvrir Google Home") { ouvrirAppli("com.google.android.apps.chromecast.app", "Google Home") }
        if (moteur.pret) {
            bouton(c, "Appareils du moteur de la maison") { ouvrir("Télé moteur", { appareilsDe("Télé et enceintes", setOf("media_player"), "télé ou enceinte") }) }
        }
    }

    private fun cameras() {
        val c = page("Caméras")
        chargerAppareils(c) { tous ->
            val l = tous.filter { it.domaine == "camera" }
            if (l.isEmpty()) {
                texte(c, "Aucune caméra branchée pour l'instant.", 16f, cTexte)
                texte(c, "Va dans « Ajouter un appareil » depuis l'accueil.")
                return@chargerAppareils
            }
            grille(c, l.map { a ->
                if (smartLife.estSmartLife(a)) tuile(a.nom, "S'ouvre dans Smart Life") { ouvrirAppli("com.tuya.smartlife", "Smart Life") }
                else tuile(a.nom, libelleEtat(a)) { ouvrir("Caméra", { camera(a) }) }
            })
        }
    }

    private fun camera(a: Appareil) {
        arreterMinuteur()
        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        racine.addView(image, FrameLayout.LayoutParams(-1, -1))
        val nom = TextView(this)
        nom.text = a.nom + "  ·  en direct"
        nom.setTextColor(Color.WHITE)
        nom.textSize = 16f
        nom.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        nom.setPadding(dp(16), dp(16), dp(16), dp(16))
        racine.addView(nom, FrameLayout.LayoutParams(-1, -2))
        setContentView(racine)
        val r = object : Runnable {
            var occupe = false
            override fun run() {
                if (!occupe) {
                    occupe = true
                    thread {
                        val o = moteur.image(a)
                        val bmp = try { o?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (e: Throwable) { null }
                        ui.post {
                            occupe = false
                            if (bmp != null) image.setImageBitmap(bmp)
                            else nom.text = a.nom + "  ·  pas d'image"
                        }
                    }
                }
                ui.postDelayed(this, 1000)
            }
        }
        minuteur = r
        r.run()
    }

    private fun capteurs() {
        val c = page("Capteurs", "Température, humidité, qualité de l'air, ouvertures, présence…")
        chargerAppareils(c) { tous ->
            val l = tous.filter { it.domaine in setOf("sensor", "binary_sensor", "weather", "climate") && it.etat != "unavailable" }
            if (l.isEmpty()) {
                texte(c, "Aucun capteur branché pour l'instant.", 16f, cTexte)
                return@chargerAppareils
            }
            val ordre = listOf("temperature", "humidity", "carbon_dioxide", "pm25", "pm10", "volatile_organic_compounds", "aqi", "illuminance", "pressure", "battery")
            val noms = mapOf(
                "temperature" to "Température", "humidity" to "Humidité", "carbon_dioxide" to "Qualité de l'air (CO₂)",
                "pm25" to "Particules fines", "pm10" to "Particules", "volatile_organic_compounds" to "Polluants de l'air",
                "aqi" to "Indice de l'air", "illuminance" to "Lumière", "pressure" to "Pression", "battery" to "Piles et batteries",
                "door" to "Portes", "window" to "Fenêtres", "motion" to "Mouvements", "occupancy" to "Présence",
                "smoke" to "Fumée", "moisture" to "Fuites d'eau", "" to "Autres"
            )
            val groupes = l.groupBy { if (it.domaine == "weather") "meteo" else it.genre }
            val cles = groupes.keys.sortedBy { k -> ordre.indexOf(k).let { if (it < 0) 99 else it } }
            for (k in cles) {
                texte(c, if (k == "meteo") "Météo" else (noms[k] ?: k), 17f, cAccent)
                grille(c, groupes[k]!!.map { a ->
                    val v = when {
                        a.domaine == "binary_sensor" -> if (a.etat == "on") "Détecté / ouvert" else "Rien / fermé"
                        a.domaine == "weather" -> a.attributs.optString("temperature", "") + " °  " + a.etat
                        a.domaine == "climate" -> "Consigne " + a.attributs.optString("temperature", "?") + " °"
                        else -> libelleEtat(a)
                    }
                    tuile(a.nom, v, a.domaine == "binary_sensor" && a.etat == "on") { }
                })
            }
            minuteur = Runnable { rafraichir() }.also { ui.postDelayed(it, 30000) }
        }
    }

    private fun consommation() {
        val c = page("Consommation")
        chargerAppareils(c) { tous ->
            val prises = tous.filter { it.attributs.has("puissance") }
                .map { Appareil(it.id, it.nom, "%.1f".format(Locale.FRANCE, it.attributs.optDouble("puissance")), "W", "power", it.attributs) }
            val l = tous.filter { it.domaine == "sensor" && it.genre in setOf("energy", "power", "gas", "water", "current", "voltage") } + prises
            if (l.isEmpty()) {
                texte(c, "Aucun compteur ni prise mesurante branché pour l'instant.", 16f, cTexte)
                texte(c, "Dès qu'une prise qui mesure, un compteur ou un module de consommation sera branché, ses chiffres s'afficheront ici.")
                return@chargerAppareils
            }
            val maintenant = l.filter { it.genre == "power" }
            if (maintenant.isNotEmpty()) {
                val total = maintenant.mapNotNull { it.etat.replace(',', '.').toDoubleOrNull() }.sum()
                texte(c, "En ce moment : %.0f W".format(Locale.FRANCE, total), 26f, cTexte)
            }
            val noms = mapOf("energy" to "Électricité consommée", "power" to "Puissance en ce moment", "gas" to "Gaz", "water" to "Eau", "current" to "Courant", "voltage" to "Tension")
            for ((k, g) in l.groupBy { it.genre }) {
                texte(c, noms[k] ?: k, 17f, cAccent)
                grille(c, g.map { tuile(it.nom, libelleEtat(it)) { } })
            }
            minuteur = Runnable { rafraichir() }.also { ui.postDelayed(it, 30000) }
        }
    }

    // ---------- plannings ----------

    private fun plannings() {
        val c = page("Plannings", "Exemple : chaque jour à 17 h, allumer la lumière du salon.")
        chargerAppareils(c) { tous ->
            val pilotables = tous.filter { !smartLife.estSmartLife(it) && it.domaine in setOf("light", "switch", "cover", "lock", "media_player", "scene", "script", "fan", "climate") }
            if (pilotables.isNotEmpty() || moteur.pret) bouton(c, "Nouveau planning") {
                if (pilotables.isEmpty()) message("Aucun appareil à programmer pour l'instant.") else nouveauPlanning(pilotables)
            }
            if (tous.any { smartLife.estSmartLife(it) }) {
                texte(c, "Appareils Smart Life", 17f, cAccent)
                texte(c, "Leurs plannings se règlent dans l'appli Smart Life : ils marchent même quand ton téléphone est éteint.")
                bouton(c, "Créer un planning dans Smart Life") { ouvrirAppli("com.tuya.smartlife", "Smart Life") }
            }
            val l = tous.filter { it.domaine == "automation" }
            if (l.isEmpty() && moteur.pret) texte(c, "Aucun planning pour l'instant.")
            for (a in l) {
                val actif = a.etat == "on"
                ligne(c, a.nom, if (actif) "Actif — toucher pour mettre en pause" else "En pause — toucher pour activer") {
                    enFond({ moteur.service("automation", if (actif) "turn_off" else "turn_on", JSONObject().put("entity_id", a.id)) }) {
                        ui.postDelayed({ rafraichir() }, 600)
                    }
                }
            }
        }
    }

    private fun nouveauPlanning(pilotables: List<Appareil>) {
        val boite = LinearLayout(this)
        boite.orientation = LinearLayout.VERTICAL
        boite.setPadding(dp(16), dp(8), dp(16), dp(8))
        val heure = TimePicker(this)
        heure.setIs24HourView(true)
        heure.hour = 17
        heure.minute = 0
        boite.addView(heure)
        val jours = listOf("mon" to "Lun", "tue" to "Mar", "wed" to "Mer", "thu" to "Jeu", "fri" to "Ven", "sat" to "Sam", "sun" to "Dim")
        val cases = jours.map { (_, lib) -> CheckBox(this).also { it.text = lib; it.isChecked = true } }
        val rang1 = LinearLayout(this)
        val rang2 = LinearLayout(this)
        cases.forEachIndexed { i, cb -> (if (i < 4) rang1 else rang2).addView(cb) }
        boite.addView(rang1)
        boite.addView(rang2)
        val defil = ScrollView(this)
        defil.addView(boite)
        var choix = 0
        AlertDialog.Builder(this)
            .setTitle("Quel appareil ?")
            .setSingleChoiceItems(pilotables.map { it.nom }.toTypedArray(), 0) { _, w -> choix = w }
            .setPositiveButton("Suivant") { _, _ ->
                val cible = pilotables[choix]
                val allumerLib = if (cible.domaine == "cover") "Ouvrir" else if (cible.domaine == "lock") "Déverrouiller" else "Allumer"
                val eteindreLib = if (cible.domaine == "cover") "Fermer" else if (cible.domaine == "lock") "Verrouiller" else "Éteindre"
                AlertDialog.Builder(this)
                    .setTitle("Quand ? (${cible.nom})")
                    .setView(defil)
                    .setPositiveButton(allumerLib) { _, _ -> enregistrerPlanning(cible, heure, cases, jours, true, allumerLib) }
                    .setNeutralButton(eteindreLib) { _, _ -> enregistrerPlanning(cible, heure, cases, jours, false, eteindreLib) }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun enregistrerPlanning(cible: Appareil, h: TimePicker, cases: List<CheckBox>, jours: List<Pair<String, String>>, allumer: Boolean, verbe: String) {
        val hh = "%02d:%02d".format(h.hour, h.minute)
        val choisis = jours.filterIndexed { i, _ -> cases[i].isChecked }.map { it.first }
        if (choisis.isEmpty()) { message("Choisis au moins un jour."); return }
        val nom = "$verbe ${cible.nom} à $hh" + if (choisis.size < 7) " (" + jours.filterIndexed { i, _ -> cases[i].isChecked }.joinToString(" ") { it.second } + ")" else " (tous les jours)"
        enFond({ moteur.creerPlanning(nom, hh, choisis, cible, allumer) }) { ok ->
            message(if (ok) "Planning enregistré : $nom" else "Le planning n'a pas pu être enregistré.")
            rafraichir()
        }
    }

    // ---------- ajouter un appareil ----------

    private fun ajouter() {
        val c = page("Ajouter un appareil")
        texte(c, "Appareils Smart Life", 18f, cAccent)
        if (smartLife.pret) {
            texte(c, "Ton compte Smart Life est relié. Pour un nouvel appareil : installe-le dans l'appli Smart Life, il apparaîtra tout seul ici dans la bonne rubrique.", 15f, cTexte)
            bouton(c, "Ouvrir Smart Life pour ajouter un appareil") { ouvrirAppli("com.tuya.smartlife", "Smart Life") }
            bouton(c, "Revoir la liaison Smart Life") { ouvrir("Relier Smart Life", { relierSmartLife() }) }
        } else {
            texte(c, "Relie une seule fois ton compte Smart Life : tous ses appareils arrivent dans Ma Maison.", 15f, cTexte)
            bouton(c, "Relier Smart Life") { ouvrir("Relier Smart Life", { relierSmartLife() }) }
        }
        texte(c, "Appareils Google Home", 18f, cAccent)
        texte(c, "Les appareils Smart Life que tu vois dans Google Home arrivent ici par la liaison Smart Life. Pour les autres appareils Google (enceintes, télé), utilise la tuile Google Home de l'accueil.")
        bouton(c, "Ouvrir Google Home") { ouvrirAppli("com.google.android.apps.chromecast.app", "Google Home") }
        texte(c, "Plus tard", 18f, cAccent)
        texte(c, "Quand le boîtier de la maison sera installé, il ajoutera des milliers d'autres marques, sans Internet.")
    }

    /** Assistant pas à pas pour relier le compte Smart Life (une seule fois). */
    private fun relierSmartLife() {
        val c = page("Relier Smart Life", "À faire une seule fois, sur le site gratuit de Tuya (le fabricant derrière Smart Life).")
        val etapes = listOf(
            "1. Crée un compte gratuit sur le site de Tuya pour les particuliers (bouton ci-dessous). Ce n'est pas le même que ton compte Smart Life.",
            "2. Va dans « Cloud », puis « Créer un projet de développement ». Choisis « Maison intelligente » et le centre de données « Europe centrale ».",
            "3. Dans le projet, onglet « Appareils », puis « Lier le compte de l'appli » : un code apparaît. Scanne-le avec l'appli Smart Life (bouton de scan en haut à droite de l'accueil) et confirme.",
            "4. Dans l'onglet « Aperçu » du projet, copie l'identifiant d'accès et le secret d'accès, puis colle-les ici."
        )
        for (e in etapes) texte(c, e, 15f, cTexte)
        bouton(c, "Ouvrir le site de Tuya") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://iot.tuya.com"))) } catch (e: Exception) { message("Aucun navigateur trouvé.") }
        }
        texte(c, "Identifiant d'accès", 15f, cAccent)
        val champId = EditText(this)
        champId.setText(smartLife.identifiant)
        champId.setTextColor(cTexte)
        champId.isSingleLine = true
        c.addView(champId)
        texte(c, "Secret d'accès", 15f, cAccent)
        val champSecret = EditText(this)
        champSecret.setText(smartLife.secret)
        champSecret.setTextColor(cTexte)
        champSecret.isSingleLine = true
        champSecret.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        c.addView(champSecret)
        texte(c, "Région du projet", 15f, cAccent)
        val regions = SmartLife.REGIONS.keys.toList()
        var choix = SmartLife.REGIONS.values.indexOf(smartLife.hote).coerceAtLeast(0)
        val boutonRegion = bouton(c, "Région : " + regions[choix]) { }
        boutonRegion.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Région du projet")
                .setSingleChoiceItems(regions.toTypedArray(), choix) { d, w -> choix = w; boutonRegion.text = "Région : " + regions[w]; d.dismiss() }
                .show()
        }
        val resultat = texte(c, "", 16f, cTexte)
        bouton(c, "Relier et tester") {
            smartLife.identifiant = champId.text.toString()
            smartLife.secret = champSecret.text.toString()
            smartLife.hote = SmartLife.REGIONS.getValue(regions[choix])
            if (!smartLife.pret) { resultat.text = "Remplis l'identifiant et le secret."; return@bouton }
            resultat.text = "Test en cours…"
            enFond({ smartLife.appareils() }) { l ->
                if (l == null) {
                    resultat.text = "Ça ne marche pas encore : " + smartLife.erreur
                    resultat.setTextColor(cAccent)
                } else {
                    appareilsCache = null
                    resultat.text = "C'est relié : ${l.size} appareil(s) trouvé(s)." + (if (l.isEmpty()) " As-tu bien scanné le code avec Smart Life (étape 3) ?" else "")
                    resultat.setTextColor(cVert)
                }
            }
        }
        if (smartLife.pret) bouton(c, "Délier Smart Life de cet appareil") {
            smartLife.identifiant = ""
            smartLife.secret = ""
            appareilsCache = null
            message("Smart Life n'est plus relié sur cet appareil.")
            rafraichir()
        }
    }

    // ---------- Dyson (ventilateur, aspirateur) ----------

    private fun champ(c: LinearLayout, titre: String, valeur: String, type: Int): EditText {
        texte(c, titre, 15f, cAccent)
        val e = EditText(this)
        e.setText(valeur)
        e.setTextColor(cTexte)
        e.setHintTextColor(cGris)
        e.isSingleLine = true
        e.inputType = type
        c.addView(e)
        return e
    }

    private fun ouvrirMyDyson() {
        packageManager.getLaunchIntentForPackage("com.dyson.mobile.android")?.let { startActivity(it); return }
        try {
            val lanceur = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (r in packageManager.queryIntentActivities(lanceur, 0)) {
                val p = r.activityInfo?.packageName ?: continue
                if (p == packageName) continue
                if (r.loadLabel(packageManager).toString().lowercase().contains("dyson")) {
                    packageManager.getLaunchIntentForPackage(p)?.let { startActivity(it); return }
                }
            }
        } catch (e: Exception) { }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=MyDyson")))
        } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=MyDyson"))) } catch (x: Exception) { message("L'appli MyDyson n'est pas installée sur cet appareil.") }
        }
    }

    private fun dysonAccueil() {
        val c = page("Dyson", "Ventilateur et aspirateur, par le Wi-Fi de la maison")
        val tele = Tv.isTv(this)
        if (!dyson.relie) {
            texte(c, "Relie une seule fois ton compte MyDyson : tes appareils arrivent ici tout seuls, sans nouvel appairage.", 16f, cTexte)
            if (tele) bouton(c, "Recevoir du téléphone (rien à taper)") { ouvrir("Recevoir Dyson", { recevoirDyson() }) }
            bouton(c, "Relier mon compte Dyson") { ouvrir("Relier Dyson", { relierDyson() }) }
            if (!tele) bouton(c, "Recevoir d'un autre téléphone") { ouvrir("Recevoir Dyson", { recevoirDyson() }) }
            bouton(c, "Autre façon : l'étiquette de l'appareil") { ouvrir("Étiquette Dyson", { dysonEtiquette() }) }
            bouton(c, "Ouvrir l'appli MyDyson") { ouvrirMyDyson() }
            return
        }
        val tuiles = dyson.machines.map { m ->
            val info = when {
                !m.pilotable -> "Se pilote dans l'appli MyDyson"
                m.robot -> "Aspirateur robot"
                else -> "Ventilateur, purificateur"
            }
            tuile(m.nom, info) { if (m.pilotable) ouvrir("Dyson " + m.serie, { dysonMachine(m.serie) }) else ouvrirMyDyson() }
        }
        grille(c, tuiles)
        texte(c, "Le pilotage direct marche sur le Wi-Fi de la maison. Ailleurs, utilise l'appli MyDyson.")
        bouton(c, "Ouvrir l'appli MyDyson") { ouvrirMyDyson() }
        if (!tele) {
            val etat = TextView(this)
            bouton(c, "Envoyer à la télé (ou à une tablette)") {
                etat.visibility = View.VISIBLE
                etat.text = "Recherche de la télé qui attend…"
                enFond({ dyson.envoyerALaTele() }) { ok ->
                    etat.text = if (ok) "C'est envoyé : la télé a tes appareils Dyson." else dyson.erreur
                    etat.setTextColor(if (ok) cVert else cAccent)
                }
            }
            etat.visibility = View.GONE
            etat.setTextColor(cGris)
            etat.textSize = taille(15f)
            etat.setPadding(dp(6), dp(6), dp(6), dp(6))
            c.addView(etat)
        }
        bouton(c, "Recevoir du téléphone") { ouvrir("Recevoir Dyson", { recevoirDyson() }) }
        bouton(c, "Relier à nouveau (nouvel appareil Dyson)") { ouvrir("Relier Dyson", { relierDyson() }) }
        bouton(c, "Délier Dyson de cet appareil") {
            AlertDialog.Builder(this)
                .setTitle("Délier Dyson ?")
                .setMessage("Tes appareils Dyson ne seront plus pilotés depuis Ma Maison sur cet appareil. Ils restent dans l'appli MyDyson.")
                .setPositiveButton("Délier") { _, _ -> dyson.delier(); rafraichir() }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun relierDyson() {
        val c = page("Relier Dyson", "Une seule fois. Tes appareils restent appairés dans MyDyson.")
        if (dyson.relie) texte(c, "Déjà relié : " + dyson.machines.joinToString(", ") { it.nom } + ". Pas besoin de recommencer, sauf pour ajouter un nouvel appareil Dyson.", 15f, cVert)
        texte(c, "1. Écris l'e-mail de ton compte MyDyson, puis touche « Recevoir le code ».", 15f, cTexte)
        val champMail = champ(c, "E-mail du compte MyDyson", dyson.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        var demande: String? = null
        val etape1 = texte(c, "", 15f, cTexte)
        bouton(c, "Recevoir le code par e-mail") {
            etape1.text = "Demande en cours…"
            etape1.setTextColor(cGris)
            val mail = champMail.text.toString()
            enFond({ dyson.demanderCode(mail) }) { id ->
                demande = id
                etape1.text = if (id != null) "Code envoyé : regarde tes e-mails (et les indésirables)." else dyson.erreur
                etape1.setTextColor(if (id != null) cVert else cAccent)
            }
        }
        texte(c, "2. Recopie le code reçu et ton mot de passe Dyson, puis touche « Relier ».", 15f, cTexte)
        val champCode = champ(c, "Code reçu par e-mail", "", InputType.TYPE_CLASS_NUMBER)
        val champMdp = champ(c, "Mot de passe Dyson", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val resultat = texte(c, "", 16f, cTexte)
        bouton(c, "Relier") {
            val d = demande
            if (d == null) { resultat.text = "Touche d'abord « Recevoir le code par e-mail » : chaque liaison demande un nouveau code."; resultat.setTextColor(cAccent); return@bouton }
            val code = champCode.text.toString()
            val mdp = champMdp.text.toString()
            if (code.isBlank() || mdp.isBlank()) { resultat.text = "Remplis le code et le mot de passe."; resultat.setTextColor(cAccent); return@bouton }
            resultat.text = "Liaison en cours…"
            resultat.setTextColor(cGris)
            enFond({ dyson.valider(mdp, d, code) }) { ok ->
                champMdp.setText("")
                if (!ok) {
                    if (dyson.codeUse) { demande = null; champCode.setText(""); etape1.text = "" }
                    resultat.text = dyson.erreur; resultat.setTextColor(cAccent); return@enFond
                }
                demande = null
                champCode.setText("")
                etape1.text = ""
                val l = dyson.machines
                resultat.text = if (l.isEmpty()) dyson.erreur else "C'est relié : " + l.joinToString(", ") { it.nom } + "."
                resultat.setTextColor(if (l.isEmpty()) cAccent else cVert)
                if (l.isNotEmpty()) bouton(c, "Voir mes appareils Dyson") { onBackPressed() }
            }
        }
        texte(c, "Ton mot de passe n'est pas gardé : seules les clés de tes appareils restent sur cet appareil.")
        texte(c, "Tu te connectes à MyDyson avec Google ou Apple, sans mot de passe ? Utilise plutôt l'étiquette de l'appareil.")
        bouton(c, "Utiliser l'étiquette de l'appareil") { ouvrir("Étiquette Dyson", { dysonEtiquette() }) }
    }

    private fun dysonEtiquette() {
        val c = page("Étiquette de l'appareil", "Sans compte : avec l'étiquette Wi-Fi collée sur l'appareil")
        texte(c, "Sur l'appareil (souvent dessous ou derrière le filtre) et dans son manuel, une étiquette donne le nom du Wi-Fi de l'appareil (il commence par DYSON- ou 360EYE-) et son mot de passe.", 15f, cTexte)
        val ssid = champ(c, "Nom du Wi-Fi de l'appareil", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val mdp = champ(c, "Mot de passe Wi-Fi de l'appareil", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val resultat = texte(c, "", 16f, cTexte)
        bouton(c, "Ajouter l'appareil") {
            if (dyson.ajouterParEtiquette(ssid.text.toString(), mdp.text.toString())) {
                message("Appareil Dyson ajouté.")
                onBackPressed()
            } else {
                resultat.text = dyson.erreur
                resultat.setTextColor(cAccent)
            }
        }
    }

    private fun recevoirDyson() {
        val c = page("Recevoir du téléphone", "Tes appareils Dyson arrivent ici sans rien taper")
        texte(c, "Sur ton téléphone (sur le Wi-Fi de la maison) : ouvre Ma Maison, puis Dyson, puis « Envoyer à la télé ». Cet écran attend 3 minutes.", 16f, cTexte)
        val att = attente(c, "En attente du téléphone…")
        enFond({ dyson.recevoir() }) { n ->
            c.removeView(att)
            if (n > 0) {
                message("Reçu : $n appareil(s) Dyson.")
                onBackPressed()
            } else {
                texte(c, dyson.erreur, 16f, cAccent)
                bouton(c, "Attendre encore") { rafraichir() }
            }
        }
    }

    private fun nombre(t: String): Double? = t.trim().toDoubleOrNull()

    private fun etatRobot(e: DysonEtat): String {
        val s = e.p("state").ifEmpty { e.p("newstate") }
        return when {
            s.startsWith("FAULT") -> "Problème signalé : regarde l'appli MyDyson"
            s in setOf("FULL_CLEAN_RUNNING", "FULL_CLEAN_TRAVERSING", "FULL_CLEAN_DISCOVERING", "FULL_CLEAN_INITIATED", "MAPPING_RUNNING", "MAPPING_INITIATED") -> "Ménage en cours"
            s.endsWith("PAUSED") -> "En pause"
            s.endsWith("NEEDS_CHARGE") -> "Batterie faible : il retourne à la base"
            s.endsWith("CHARGING") -> "En charge sur la base"
            s in setOf("INACTIVE_CHARGED", "FULL_CLEAN_FINISHED", "MAPPING_FINISHED") -> "Sur la base, prêt"
            s.endsWith("ABORTED") || s.endsWith("ABANDONED") -> "Ménage arrêté"
            s == "MACHINE_OFF" -> "Éteint"
            s.isEmpty() -> "État inconnu"
            else -> s
        }
    }

    private fun dysonMachine(serie: String) {
        val m0 = dyson.machines.firstOrNull { it.serie == serie } ?: run { onBackPressed(); return }
        val c = page(m0.nom, if (m0.robot) "Aspirateur robot Dyson" else "Ventilateur Dyson")
        val att = attente(c, "Lecture de l'appareil…")
        enFond({ dyson.etat(m0) }) { e ->
            c.removeView(att)
            fun m() = dyson.machines.firstOrNull { it.serie == serie } ?: m0
            fun agir(fait: String, action: () -> Boolean) {
                message("Envoi…")
                enFond({ action() }) { ok ->
                    message(if (ok) fait else dyson.erreur)
                    if (ok) ui.postDelayed({ if (!isFinishing && ecranActuel == "Dyson $serie") rafraichir() }, 1500)
                }
            }
            if (e == null) {
                texte(c, dyson.erreur, 16f, cAccent)
                bouton(c, "Réessayer") { rafraichir() }
                bouton(c, "Ouvrir l'appli MyDyson") { ouvrirMyDyson() }
                return@enFond
            }
            if (m().robot) {
                texte(c, etatRobot(e), 20f, cTexte)
                val batterie = e.p("batteryChargeLevel")
                if (batterie.isNotEmpty()) texte(c, "Batterie : $batterie %", 16f, cTexte)
                bouton(c, "Lancer le ménage") { agir("Ménage lancé.") { dyson.robot(m(), "START") } }
                bouton(c, "Pause") { agir("En pause.") { dyson.robot(m(), "PAUSE") } }
                bouton(c, "Reprendre") { agir("Ménage repris.") { dyson.robot(m(), "RESUME") } }
                bouton(c, "Arrêter et retourner à la base") { agir("Retour à la base.") { dyson.robot(m(), "ABORT") } }
            } else {
                val allume = dyson.allume(m(), e)
                val vit = e.p("fnsp")
                val auto = if (m().ancienModele) e.p("fmod") == "AUTO" else e.p("auto") == "ON"
                val osc = e.p("oson") in setOf("ON", "OION")
                val nuit = e.p("nmod") == "ON"
                val lignes = ArrayList<String>()
                lignes.add(if (allume) "Allumé" else "Éteint")
                if (allume) lignes.add(if (auto || vit == "AUTO") "Vitesse : automatique" else "Vitesse : " + (vit.toIntOrNull()?.toString() ?: vit) + " sur 10")
                nombre(e.e("tact"))?.let { lignes.add(String.format(Locale.FRANCE, "Température : %.1f °C", it / 10 - 273.15)) }
                nombre(e.e("hact"))?.let { lignes.add("Humidité : ${it.toInt()} %") }
                (nombre(e.e("pm25")) ?: nombre(e.e("p25r")))?.let { lignes.add("Particules fines : ${it.toInt()} µg/m³") }
                if (nombre(e.e("pm25")) == null && nombre(e.e("p25r")) == null) nombre(e.e("pact"))?.let { lignes.add("Particules : ${it.toInt()} (indice)") }
                texte(c, lignes.joinToString("\n"), 18f, cTexte)
                bouton(c, if (allume) "Éteindre" else "Allumer") { agir(if (allume) "Éteint." else "Allumé.") { dyson.allumer(m(), !allume) } }
                val v = vit.toIntOrNull() ?: 5
                bouton(c, "Vitesse moins forte") { agir("Vitesse ${(v - 1).coerceAtLeast(1)}.") { dyson.vitesse(m(), v - 1) } }
                bouton(c, "Vitesse plus forte") { agir("Vitesse ${(v + 1).coerceAtMost(10)}.") { dyson.vitesse(m(), v + 1) } }
                bouton(c, if (auto) "Mode automatique : oui (toucher pour arrêter)" else "Mode automatique : non (toucher pour l'activer)") { agir("C'est fait.") { dyson.auto(m(), !auto) } }
                bouton(c, if (osc) "Oscillation : oui (toucher pour arrêter)" else "Oscillation : non (toucher pour l'activer)") { agir("C'est fait.") { dyson.oscillation(m(), e, !osc) } }
                bouton(c, if (nuit) "Mode nuit : oui (toucher pour arrêter)" else "Mode nuit : non (toucher pour l'activer)") { agir("C'est fait.") { dyson.nuit(m(), !nuit) } }
            }
            bouton(c, "Actualiser") { rafraichir() }
            bouton(c, "Ouvrir l'appli MyDyson") { ouvrirMyDyson() }
        }
    }

    // ---------- réglages ----------

    private fun reglages() {
        val c = page("Réglages")
        texte(c, "Apparence", 18f, cAccent)
        texte(c, "Thème actuel : " + th.nom, 15f, cTexte)
        bouton(c, "Changer le visuel de Ma Maison") { ouvrir("Apparence", { apparence() }) }
        texte(c, "Manettes et appareils Bluetooth", 18f, cAccent)
        bouton(c, "Chercher et connecter une manette ou un appareil") { ouvrir("Bluetooth", { ecranBluetooth() }) }
        texte(c, "Affichage", 18f, cAccent)
        bouton(c, if (modeTablette) "Mode tablette : activé (toucher pour le désactiver)" else "Mode tablette : désactivé (toucher pour l'activer)") {
            prefs.edit().putBoolean("tablette", !modeTablette).apply()
            reglages()
        }
        val allume = prefs.getBoolean("allume", false)
        bouton(c, if (allume) "Écran toujours allumé en mode tablette : oui" else "Écran toujours allumé en mode tablette : non") {
            prefs.edit().putBoolean("allume", !allume).apply()
            reglages()
        }
        texte(c, "PC de la maison", 18f, cAccent)
        val etat = texte(c, "Recherche…", 15f, cTexte)
        enFond({ passerelle.trouver() }) { p -> etat.text = if (p != null) "Connecté : " + p.removePrefix("http://") else "Introuvable sur ce réseau." }
        bouton(c, "Indiquer l'adresse du PC à la main") {
            saisir("Adresse du PC (ex. 192.168.1.41)", prefs.getString("passerelle_manuelle", "") ?: "") {
                prefs.edit().putString("passerelle_manuelle", it.trim()).remove("passerelle").apply()
                Passerelle.adresse = null
                reglages()
            }
        }
        texte(c, "Partager ce téléphone", 18f, cAccent)
        texte(c, if (Relais.partageActif(this)) "Activé : code " + Relais.codePartage(this) else "Arrêté.", 15f, cTexte)
        bouton(c, "Partager les photos et vidéos de ce téléphone") { ouvrir("Partager", { partagerCeTelephone() }) }
        texte(c, "Smart Life", 18f, cAccent)
        texte(c, if (smartLife.pret) "Relié." else "Pas encore relié.", 15f, cTexte)
        bouton(c, if (smartLife.pret) "Revoir la liaison Smart Life" else "Relier Smart Life") { ouvrir("Relier Smart Life", { relierSmartLife() }) }
        texte(c, "Dyson", 18f, cAccent)
        texte(c, if (dyson.relie) "Relié : ${dyson.machines.size} appareil(s)." else "Pas encore relié.", 15f, cTexte)
        bouton(c, if (dyson.relie) "Ouvrir Dyson" else "Relier Dyson") { ouvrir("Dyson", { dysonAccueil() }) }
        texte(c, "Moteur de la maison (plus tard)", 18f, cAccent)
        texte(c, if (moteur.pret) "Réglé : " + moteur.adresse else "Pas encore installé.", 15f, cTexte)
        bouton(c, "Régler le moteur à la main") {
            saisir("Adresse du moteur (ex. http://192.168.1.41:8123)", moteur.adresse) { a ->
                moteur.adresse = a
                saisir("Clé d'accès du moteur", "") { j -> moteur.jeton = j; reglages() }
            }
        }
        texte(c, "Application", 18f, cAccent)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        texte(c, "Version $version", 15f, cTexte)
        bouton(c, "Vérifier les mises à jour") { miseAJour.verifier(true) }
    }

    /** Choix du thème : appliqué tout de suite à toute l'appli, et retenu. */
    private fun apparence() {
        val c = page("Apparence", "Touche un thème : toute l'appli change tout de suite. « Nuit » est le visuel d'origine.")
        val tuiles = Theme.tous.map { t ->
            val actif = t.id == th.id
            val v = LinearLayout(this)
            v.orientation = LinearLayout.VERTICAL
            val fond = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.carteHaute, t.carte))
            fond.cornerRadius = dp(18).toFloat()
            fond.setStroke(dp(if (actif) 3 else 1), if (actif) t.accent else t.fond)
            v.background = fond
            v.setPadding(dp(16), dp(14), dp(16), dp(14))
            v.minimumHeight = dp(if (modeTablette) 130 else 104)
            val trait = View(this)
            trait.background = rond(t.accent, dp(2))
            val lpt = LinearLayout.LayoutParams(dp(30), dp(4))
            lpt.setMargins(0, 0, 0, dp(10))
            v.addView(trait, lpt)
            val a = TextView(this)
            a.text = if (actif) t.nom + "  (choisi)" else t.nom
            a.setTextColor(t.texte)
            a.textSize = taille(18f)
            a.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            v.addView(a)
            val b = TextView(this)
            b.text = t.description
            b.setTextColor(t.gris)
            b.textSize = taille(13f)
            b.setPadding(0, dp(4), 0, 0)
            v.addView(b)
            v.isClickable = true
            v.contentDescription = "Thème " + t.nom
            v.setOnClickListener {
                prefs.edit().putString("theme", t.id).apply()
                appliquerBarres()
                rafraichir()
                message("Thème « ${t.nom} » appliqué.")
            }
            effetAppui(v)
            v
        }
        grille(c, tuiles)
    }

    // ---------- manettes et appareils Bluetooth ----------

    private var recepteurBt: android.content.BroadcastReceiver? = null
    private val trouvesBt = LinkedHashMap<String, android.bluetooth.BluetoothDevice>()

    private fun permissionsBt(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun btAutorise(): Boolean = Build.VERSION.SDK_INT < 23 ||
        permissionsBt().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun arreterRechercheBt() {
        recepteurBt?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        recepteurBt = null
        try { adaptateurBt()?.cancelDiscovery() } catch (e: SecurityException) { }
    }

    private fun adaptateurBt(): android.bluetooth.BluetoothAdapter? = try {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    } catch (e: Throwable) { null }

    /** Manettes reconnues par Android en ce moment (Bluetooth ou câble). */
    private fun manettesBranchees(): List<String> {
        val l = ArrayList<String>()
        for (id in android.view.InputDevice.getDeviceIds()) {
            val d = android.view.InputDevice.getDevice(id) ?: continue
            val s = d.sources
            if ((s and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
                (s and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
                if (!l.contains(d.name)) l.add(d.name)
            }
        }
        return l
    }

    private fun estConnecte(d: android.bluetooth.BluetoothDevice): Boolean = try {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    } catch (e: Throwable) { false }

    private fun genreBt(d: android.bluetooth.BluetoothDevice): String = try {
        when (d.bluetoothClass?.majorDeviceClass) {
            android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> "Manette, clavier ou souris"
            android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "Casque ou enceinte"
            android.bluetooth.BluetoothClass.Device.Major.PHONE -> "Téléphone"
            android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "Ordinateur"
            android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "Montre ou bracelet"
            android.bluetooth.BluetoothClass.Device.Major.TOY -> "Jouet"
            android.bluetooth.BluetoothClass.Device.Major.HEALTH -> "Appareil de santé"
            else -> "Appareil"
        }
    } catch (e: SecurityException) { "Appareil" }

    private fun nomBt(d: android.bluetooth.BluetoothDevice): String = try { d.name ?: d.address } catch (e: SecurityException) { d.address }

    private fun ouvrirReglagesBt() {
        try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (e: Exception) { message("Les réglages Bluetooth d'Android ne s'ouvrent pas sur cet appareil.") }
    }

    private fun ecranBluetooth() {
        val c = page("Bluetooth", "Manettes, casques, enceintes, objets : chercher, reconnaître et connecter. Les objets du Wi-Fi sont dans la tuile Réseau.")
        val bt = adaptateurBt()
        val manettes = manettesBranchees()
        texte(c, "Manettes reconnues maintenant", 18f, cAccent)
        if (manettes.isEmpty()) texte(c, "Aucune manette branchée pour l'instant. Allume la manette en mode association (souvent un bouton à maintenir jusqu'à ce que la lumière clignote vite), puis touche « Chercher ».")
        else {
            texte(c, "Prêtes pour les jeux :", 15f, cTexte)
            grille(c, manettes.map { tuile(it, "Manette reconnue", voyant = 1) { message("$it est connectée et prête.") } })
        }
        if (bt == null) {
            texte(c, "Cet appareil n'a pas de Bluetooth.", 15f, cTexte)
            return
        }
        if (!btAutorise()) {
            texte(c, "Ma Maison a besoin de l'accès « Appareils à proximité » pour chercher les appareils Bluetooth.", 15f, cTexte)
            bouton(c, "Autoriser") { if (Build.VERSION.SDK_INT >= 23) requestPermissions(permissionsBt(), 88) }
            bouton(c, "Ouvrir les réglages Bluetooth d'Android") { ouvrirReglagesBt() }
            return
        }
        val actif = try { bt.isEnabled } catch (e: SecurityException) { false }
        if (!actif) {
            texte(c, "Le Bluetooth est éteint.", 15f, cTexte)
            bouton(c, "Allumer le Bluetooth") {
                try { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                catch (e: Exception) { ouvrirReglagesBt() }
            }
            return
        }
        // Appareils déjà associés
        val associes = try { bt.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
        texte(c, "Déjà associés à cet appareil", 18f, cAccent)
        if (associes.isEmpty()) texte(c, "Aucun pour l'instant.")
        else grille(c, associes.map { d ->
            val co = estConnecte(d)
            tuile(nomBt(d), genreBt(d) + (if (co) " — connecté" else " — non connecté"), voyant = if (co) 1 else 0) {
                if (co) message("${nomBt(d)} est déjà connecté.")
                else {
                    message("Rallume ${nomBt(d)} : il se reconnecte tout seul. Sinon, ouvre les réglages Bluetooth d'Android.")
                }
            }
        })
        // Recherche des appareils autour
        texte(c, "Appareils trouvés autour", 18f, cAccent)
        val zone = LinearLayout(this)
        zone.orientation = LinearLayout.VERTICAL
        c.addView(zone)
        fun montrerTrouves(enCours: Boolean) {
            zone.removeAllViews()
            val nouveaux = trouvesBt.values.filter { d -> associes.none { it.address == d.address } }
            if (nouveaux.isEmpty()) texte(zone, if (enCours) "Recherche en cours… (environ 12 secondes)" else "Rien de nouveau trouvé. Mets l'appareil en mode association puis relance la recherche.")
            else grille(zone, nouveaux.map { d ->
                tuile(nomBt(d), genreBt(d) + " — toucher pour connecter", voyant = -1) {
                    try {
                        bt.cancelDiscovery()
                        if (d.createBond()) message("Connexion à ${nomBt(d)}… valide si Android le demande.")
                        else message("${nomBt(d)} refuse la connexion. Remets-le en mode association.")
                    } catch (e: SecurityException) { message("Accès Bluetooth refusé.") }
                }
            })
        }
        trouvesBt.clear()
        montrerTrouves(false)
        bouton(c, "Chercher les appareils autour") {
            arreterRechercheBt()
            trouvesBt.clear()
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) {
                    when (i.action) {
                        android.bluetooth.BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val d = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
                            trouvesBt[d.address] = d
                            montrerTrouves(true)
                        }
                        android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { montrerTrouves(false); arreterRechercheBt() }
                        android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            @Suppress("DEPRECATION")
                            val d = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
                            if (d.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) { message("${nomBt(d)} est connecté."); rafraichir() }
                        }
                    }
                }
            }
            val f = android.content.IntentFilter()
            f.addAction(android.bluetooth.BluetoothDevice.ACTION_FOUND)
            f.addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            f.addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            recepteurBt = r
            val ok = try { bt.startDiscovery() } catch (e: SecurityException) { false }
            if (ok) montrerTrouves(true) else { message("La recherche n'a pas pu démarrer : ouverture des réglages Bluetooth d'Android."); arreterRechercheBt(); ouvrirReglagesBt() }
        }
        bouton(c, "Ouvrir les réglages Bluetooth d'Android") { ouvrirReglagesBt() }
        texte(c, "Une fois connectée, la manette sert dans les jeux et pour se déplacer dans Ma Maison.", 12f)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, resultats: IntArray) {
        super.onRequestPermissionsResult(code, permissions, resultats)
        if (code == 88) rafraichir()
    }

    override fun onDestroy() {
        arreterRechercheBt()
        super.onDestroy()
    }

    private fun saisir(titre: String, valeur: String, ok: (String) -> Unit) {
        val e = EditText(this)
        e.setText(valeur)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val cadre = FrameLayout(this)
        cadre.setPadding(dp(20), dp(8), dp(20), 0)
        cadre.addView(e)
        AlertDialog.Builder(this)
            .setTitle(titre)
            .setView(cadre)
            .setPositiveButton("Valider") { _, _ -> ok(e.text.toString()) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun signaler() {
        val e = EditText(this)
        e.hint = "Qu'est-ce qui ne va pas ?"
        val cadre = FrameLayout(this)
        cadre.setPadding(dp(20), dp(8), dp(20), 0)
        cadre.addView(e)
        AlertDialog.Builder(this)
            .setTitle("Signaler un problème (page : $ecranActuel)")
            .setView(cadre)
            .setPositiveButton("Envoyer") { _, _ ->
                // Adresse de signalement : à compléter quand elle sera créée
                val adresse = ""
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$adresse"))
                i.putExtra(Intent.EXTRA_SUBJECT, "Ma Maison — signalement — $ecranActuel")
                i.putExtra(Intent.EXTRA_TEXT, e.text.toString())
                try { startActivity(i) } catch (x: Exception) { message("Aucune appli de courrier sur cet appareil.") }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- la voix ----------

    private fun ecouter() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Exemple : « fais voir la caméra du salon », « allume la lumière de la cuisine »")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 42)
        } catch (e: Exception) {
            message("La reconnaissance de la voix n'est pas disponible sur cet appareil.")
        }
    }

    @Deprecated("Résultat de la voix")
    override fun onActivityResult(code: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(code, res, data)
        if (code != 42 || res != RESULT_OK) return
        val phrase = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        executerPhrase(phrase)
    }

    private fun sansAccents(t: String): String = java.text.Normalizer.normalize(t.lowercase(Locale.FRANCE), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Comprend les ordres simples et agit sur l'appareil dont le nom ressemble le plus. */
    private fun executerPhrase(phrase: String) {
        val p = sansAccents(phrase)
        when {
            p.contains("video") && !p.contains("camera") -> { ouvrir("Vidéos", { medias("videos", "Vidéos") }); return }
            p.contains("photo") -> { ouvrir("Photos", { medias("photos", "Photos") }); return }
            p.contains("musique") -> { ouvrir("Musique", { medias("musique", "Musique") }); return }
            p.contains("planning") -> { ouvrir("Plannings", { plannings() }); return }
        }
        enFond({ appareilsMaison() }) { l ->
            if (l == null) { message("« $phrase » : aucun appareil de la maison n'est relié ou ils ne répondent pas."); return@enFond }
            appareilsCache = l
            val domaines = when {
                p.contains("camera") -> setOf("camera")
                p.contains("volet") || p.contains("store") || p.contains("portail") || p.contains("garage") -> setOf("cover")
                p.contains("porte") -> setOf("lock", "cover")
                p.contains("prise") -> setOf("switch")
                p.contains("tele") || p.contains("enceinte") -> setOf("media_player")
                p.contains("lumiere") || p.contains("lampe") || p.contains("plafonnier") -> setOf("light")
                else -> setOf("light", "switch", "cover", "camera", "media_player", "scene", "script", "lock")
            }
            val candidats = l.filter { it.domaine in domaines }
            val mots = p.split(" ").filter { it.length > 2 && it !in setOf("allume", "eteins", "eteint", "ouvre", "ferme", "fais", "voir", "montre", "les", "des", "la", "le", "camera", "lumiere", "lampe", "volet", "volets", "prise", "dans", "du", "de", "moi") }
            val cible = candidats.maxByOrNull { a -> val n = sansAccents(a.nom); mots.count { n.contains(it) } }
            if (cible == null) { message("Je n'ai trouvé aucun appareil pour « $phrase »."); return@enFond }
            when {
                cible.domaine == "camera" && smartLife.estSmartLife(cible) -> ouvrirAppli("com.tuya.smartlife", "Smart Life")
                cible.domaine == "camera" -> ouvrir("Caméra", { camera(cible) })
                smartLife.estSmartLife(cible) -> {
                    val allumer: Boolean? = when {
                        p.contains("eteins") || p.contains("eteint") || p.contains("coupe") || p.contains("arrete") || p.contains("ferm") -> false
                        p.contains("allume") || p.contains("lance") || p.contains("active") || p.contains("ouvr") -> true
                        else -> null
                    }
                    enFond({ smartLife.agir(cible, allumer) }) { ok ->
                        message(if (ok) "C'est fait : ${cible.nom}" else "${cible.nom} n'a pas répondu. " + smartLife.erreur)
                    }
                }
                else -> {
                    val d = JSONObject().put("entity_id", cible.id)
                    val srv: Pair<String, String> = when {
                        cible.domaine == "cover" && p.contains("ouvr") -> "cover" to "open_cover"
                        cible.domaine == "cover" && p.contains("ferm") -> "cover" to "close_cover"
                        cible.domaine == "lock" && p.contains("ouvr") -> "lock" to "unlock"
                        cible.domaine == "lock" && (p.contains("ferm") || p.contains("verrouill")) -> "lock" to "lock"
                        p.contains("eteins") || p.contains("eteint") || p.contains("coupe") || p.contains("arrete") -> "homeassistant" to "turn_off"
                        p.contains("allume") || p.contains("lance") || p.contains("active") -> "homeassistant" to "turn_on"
                        else -> "homeassistant" to "toggle"
                    }
                    enFond({ moteur.service(srv.first, srv.second, d) }) { ok ->
                        message(if (ok) "C'est fait : ${cible.nom}" else "${cible.nom} n'a pas répondu.")
                    }
                }
            }
        }
    }
}

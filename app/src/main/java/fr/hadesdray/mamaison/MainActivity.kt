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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

/**
 * Ma Maison : une seule appli, en français, pour toute la maison.
 * Vidéos, photos et musique du PC ; lumières, volets, caméras, capteurs,
 * consommation, plannings, télé et enceintes par le moteur caché du PC.
 */
class MainActivity : Activity() {

    // Couleurs
    private val cFond = Color.parseColor("#12161F")
    private val cCarte = Color.parseColor("#1E2533")
    private val cCarteActive = Color.parseColor("#3A3220")
    private val cAccent = Color.parseColor("#F2B544")
    private val cTexte = Color.parseColor("#F3F5F9")
    private val cGris = Color.parseColor("#9AA4B5")
    private val cVert = Color.parseColor("#6FCF97")

    private val ui = Handler(Looper.getMainLooper())
    private val pile = ArrayList<() -> Unit>()
    private var ecranActuel = "Accueil"
    private lateinit var passerelle: Passerelle
    private lateinit var moteur: Moteur
    private lateinit var miseAJour: MiseAJour
    private val prefs by lazy { getSharedPreferences("maison", Context.MODE_PRIVATE) }
    private var minuteur: Runnable? = null
    private var appareilsCache: List<Appareil>? = null

    private val modeTablette: Boolean
        get() = prefs.getBoolean("tablette", resources.configuration.smallestScreenWidthDp >= 600)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun taille(t: Float): Float = if (modeTablette) t * 1.3f else t

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = cFond
        window.navigationBarColor = cFond
        passerelle = Passerelle(this)
        moteur = Moteur(this)
        miseAJour = MiseAJour(this)
        demanderAccesUneFois()
        ouvrir("Accueil", ::accueil, racine = true)
        miseAJour.verifier(false)
    }

    // ---------- navigation ----------

    private fun ouvrir(nom: String, ecran: () -> Unit, racine: Boolean = false) {
        arreterMinuteur()
        if (racine) pile.clear()
        pile.add(ecran)
        ecranActuel = nom
        ecran()
    }

    @Deprecated("Flèche retour d'Android")
    override fun onBackPressed() {
        arreterMinuteur()
        if (pile.size > 1) {
            pile.removeAt(pile.size - 1)
            rafraichir()
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

    private fun demanderAccesUneFois() {
        if (prefs.getBoolean("acces_demandes", false)) return
        prefs.edit().putBoolean("acces_demandes", true).apply()
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    // ---------- briques d'écran ----------

    /** Page avec en-tête (titre, retour, petit « ? ») et un contenu défilant. */
    private fun page(titre: String, sousTitre: String? = null): LinearLayout {
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
        val defil = ScrollView(this)
        defil.isFillViewport = true
        val contenu = LinearLayout(this)
        contenu.orientation = LinearLayout.VERTICAL
        contenu.setPadding(dp(12), dp(4), dp(12), dp(24))
        defil.addView(contenu)
        racine.addView(defil, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(racine)
        return contenu
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
        b.setTextColor(Color.parseColor("#1A1A1A"))
        b.background = rond(cAccent, dp(14))
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(dp(4), dp(8), dp(4), dp(4))
        parent.addView(b, lp)
        return b
    }

    /** Grande tuile : titre + information. */
    private fun tuile(titre: String, info: String, active: Boolean = false, action: () -> Unit): LinearLayout {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.background = rond(if (active) cCarteActive else cCarte, dp(18))
        t.setPadding(dp(16), dp(16), dp(16), dp(16))
        t.minimumHeight = dp(if (modeTablette) 130 else 96)
        val a = TextView(this)
        a.text = titre
        a.setTextColor(if (active) cAccent else cTexte)
        a.textSize = taille(18f)
        a.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        t.addView(a)
        val b = TextView(this)
        b.text = info
        b.setTextColor(cGris)
        b.textSize = taille(13f)
        b.setPadding(0, dp(6), 0, 0)
        t.addView(b)
        t.isClickable = true
        t.setOnClickListener { action() }
        return t
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

        val apps = appareilsCache
        fun compte(dom: Set<String>, allumes: Boolean = false): String {
            val l = apps ?: return if (moteur.pret) "…" else "Moteur à installer"
            val n = l.filter { it.domaine in dom }
            if (n.isEmpty()) return "Aucun appareil branché"
            return if (allumes) "${n.count { it.allume }} allumé(s) sur ${n.size}" else "${n.size} appareil(s)"
        }
        val tuiles = listOf(
            tuile("Vidéos", "Films et vidéos du PC") { ouvrir("Vidéos", { medias("videos", "Vidéos") }) },
            tuile("Photos", "Images du PC") { ouvrir("Photos", { medias("photos", "Photos") }) },
            tuile("Musique", "Musiques du PC") { ouvrir("Musique", { medias("musique", "Musique") }) },
            tuile("Lumières", compte(setOf("light"), true)) { ouvrir("Lumières", { appareilsDe("Lumières", setOf("light"), "lumière") }) },
            tuile("Volets et portes", compte(setOf("cover", "lock"))) { ouvrir("Volets", { appareilsDe("Volets et portes", setOf("cover", "lock"), "volet ou porte") }) },
            tuile("Prises", compte(setOf("switch"), true)) { ouvrir("Prises", { appareilsDe("Prises", setOf("switch"), "prise") }) },
            tuile("Caméras", compte(setOf("camera"))) { ouvrir("Caméras", { cameras() }) },
            tuile("Capteurs", compte(setOf("sensor", "binary_sensor", "weather", "climate"))) { ouvrir("Capteurs", { capteurs() }) },
            tuile("Consommation", "Électricité, eau, gaz") { ouvrir("Consommation", { consommation() }) },
            tuile("Télé et enceintes", compte(setOf("media_player"))) { ouvrir("Télé", { appareilsDe("Télé et enceintes", setOf("media_player"), "télé ou enceinte") }) },
            tuile("Ambiances", compte(setOf("scene", "script"))) { ouvrir("Ambiances", { appareilsDe("Ambiances", setOf("scene", "script"), "ambiance") }) },
            tuile("Plannings", "Allumer, éteindre à heure fixe") { ouvrir("Plannings", { plannings() }) },
            tuile("Ajouter un appareil", "Brancher un nouvel appareil") { ouvrir("Ajouter", { ajouter() }) },
            tuile("Réglages", "Mode tablette, PC, mises à jour") { ouvrir("Réglages", { reglages() }) }
        )
        grille(c, tuiles)

        enFond({
            val p = passerelle.trouver()
            if (!moteur.pret) moteur.recupererDepuisPasserelle(p)
            Pair(p, if (moteur.pret) moteur.appareils() else null)
        }) { (p, l) ->
            val parties = ArrayList<String>()
            parties.add(if (p != null) "PC de la maison : connecté" else "PC de la maison : introuvable (allumé ? même Wi-Fi ?)")
            parties.add(
                when {
                    !moteur.pret -> "Commande des appareils : moteur pas encore installé"
                    l == null -> "Commande des appareils : le moteur ne répond pas"
                    else -> "Commande des appareils : prête"
                }
            )
            val temp = l?.firstOrNull { it.genre == "temperature" && it.etat.toDoubleOrNull() != null }
            if (temp != null) parties.add("Température : ${temp.etat} ${temp.unite}")
            resume.text = parties.joinToString("\n")
            resume.setTextColor(if (p != null) cVert else cGris)
            if (l != null && appareilsCache == null) {
                appareilsCache = l
                accueil()
            } else if (l != null) appareilsCache = l
        }
    }

    // ---------- médias du PC ----------

    private fun medias(type: String, titre: String) {
        val c = page(titre, "Sur le PC de la maison")
        val att = attente(c, "Connexion au PC…")
        enFond({
            val p = passerelle.trouver()
            Pair(p, p?.let { Reseau.lire("$it/racines?type=$type", 5000) })
        }) { (p, t) ->
            c.removeView(att)
            if (p == null || t == null) {
                pcIntrouvable(c)
                return@enFond
            }
            val tab = (try { JSONObject(t).optJSONArray("racines") } catch (e: Exception) { null }) ?: JSONArray()
            if (tab.length() == 0) texte(c, "Aucun dossier trouvé sur le PC pour cette rubrique.")
            val tuiles = ArrayList<View>()
            for (i in 0 until tab.length()) {
                val o = tab.getJSONObject(i)
                val nom = o.optString("nom")
                val chemin = o.optString("chemin")
                tuiles.add(tuile(nom, "Dossier") { ouvrir("$titre/$nom", { dossier(type, nom, chemin) }) })
            }
            grille(c, tuiles)
        }
    }

    private fun pcIntrouvable(c: LinearLayout) {
        texte(c, "Le PC de la maison ne répond pas.", 17f, cTexte)
        texte(c, "Vérifie que le PC est allumé et que ce téléphone est sur le Wi-Fi de la maison. L'appli cherche toute seule sur tout le réseau.")
        bouton(c, "Réessayer") { rafraichir() }
    }

    private fun dossier(type: String, nom: String, chemin: String) {
        val c = page(nom)
        val att = attente(c, "Lecture du dossier…")
        enFond({
            val p = passerelle.trouver()
            Pair(p, p?.let { Reseau.lire("$it/liste?type=$type&chemin=" + Reseau.encode(chemin), 15000) })
        }) { (p, t) ->
            c.removeView(att)
            if (p == null || t == null) {
                pcIntrouvable(c)
                return@enFond
            }
            val o = try { JSONObject(t) } catch (e: Exception) { JSONObject() }
            val dossiers = o.optJSONArray("dossiers") ?: JSONArray()
            val fichiers = o.optJSONArray("fichiers") ?: JSONArray()
            if (dossiers.length() == 0 && fichiers.length() == 0) texte(c, "Ce dossier est vide.")
            val noms = Array(fichiers.length()) { fichiers.getJSONObject(it).optString("nom") }
            val liens = Array(fichiers.length()) { passerelle.lienFichier(p, fichiers.getJSONObject(it).optString("chemin")) }
            if (fichiers.length() > 0 && type != "photos") {
                bouton(c, if (type == "musique") "Tout écouter" else "Tout lire à la suite") { lire(liens, noms, 0, type == "musique") }
            }
            for (i in 0 until dossiers.length()) {
                val d = dossiers.getJSONObject(i)
                ligne(c, "▸  " + d.optString("nom"), "Dossier") {
                    ouvrir(ecranActuel + "/" + d.optString("nom"), { dossier(type, d.optString("nom"), d.optString("chemin")) })
                }
            }
            for (i in 0 until fichiers.length()) {
                val f = fichiers.getJSONObject(i)
                val mo = f.optLong("taille") / (1024 * 1024)
                ligne(c, f.optString("nom"), if (mo > 0) "$mo Mo" else "") {
                    if (type == "photos") ouvrir("Photo", { photo(liens, noms, i) })
                    else lire(liens, noms, i, type == "musique")
                }
            }
        }
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
        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        racine.addView(image, FrameLayout.LayoutParams(-1, -1))
        val nom = TextView(this)
        nom.setTextColor(Color.WHITE)
        nom.textSize = 15f
        nom.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        nom.setPadding(dp(16), dp(16), dp(16), dp(16))
        racine.addView(nom, FrameLayout.LayoutParams(-1, -2))
        val bas = LinearLayout(this)
        bas.orientation = LinearLayout.HORIZONTAL
        bas.setPadding(dp(12), dp(12), dp(12), dp(20))
        val prec = Button(this)
        prec.text = "‹ Précédente"
        prec.isAllCaps = false
        val suiv = Button(this)
        suiv.text = "Suivante ›"
        suiv.isAllCaps = false
        bas.addView(prec, LinearLayout.LayoutParams(0, -2, 1f))
        bas.addView(suiv, LinearLayout.LayoutParams(0, -2, 1f))
        val lpb = FrameLayout.LayoutParams(-1, -2)
        lpb.gravity = Gravity.BOTTOM
        racine.addView(bas, lpb)
        val roue = ProgressBar(this)
        val lpr = FrameLayout.LayoutParams(dp(48), dp(48))
        lpr.gravity = Gravity.CENTER
        racine.addView(roue, lpr)
        setContentView(racine)

        val largeur = resources.displayMetrics.widthPixels
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
                        var ech = 1
                        while (opt.outWidth / (ech * 2) >= largeur && opt.outHeight / (ech * 2) >= largeur / 2) ech *= 2
                        val o2 = BitmapFactory.Options()
                        o2.inSampleSize = ech
                        BitmapFactory.decodeByteArray(o, 0, o.size, o2)
                    }
                } catch (e: Throwable) { null }
                ui.post {
                    if (demande != index || isFinishing) return@post
                    roue.visibility = View.GONE
                    if (bmp == null) message("Cette image ne peut pas s'afficher.")
                    image.setImageBitmap(bmp)
                }
            }
        }
        prec.setOnClickListener { if (index > 0) { index--; charger() } }
        suiv.setOnClickListener { if (index < liens.size - 1) { index++; charger() } }
        charger()
    }

    // ---------- appareils de la maison ----------

    private fun moteurAbsent(c: LinearLayout) {
        texte(c, "Le moteur de la maison n'est pas encore installé sur le PC.", 17f, cTexte)
        texte(c, "Ce module est prêt : dès que le moteur sera installé et tes appareils branchés, ils apparaîtront ici tout seuls.")
    }

    private fun chargerAppareils(c: LinearLayout, suite: (List<Appareil>) -> Unit) {
        if (!moteur.pret) {
            val att = attente(c, "Recherche du moteur…")
            enFond({
                moteur.recupererDepuisPasserelle(passerelle.trouver())
                if (moteur.pret) moteur.appareils() else null
            }) { l ->
                c.removeView(att)
                if (!moteur.pret) moteurAbsent(c)
                else if (l == null) texte(c, "Le moteur ne répond pas. Le PC est-il allumé ?", 16f, cTexte)
                else { appareilsCache = l; suite(l) }
            }
            return
        }
        val att = attente(c, "Chargement…")
        enFond({ moteur.appareils() }) { l ->
            c.removeView(att)
            if (l == null) texte(c, "Le moteur ne répond pas. Le PC est-il allumé et sur le même réseau ?", 16f, cTexte)
            else { appareilsCache = l; suite(l) }
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
                    enFond({ moteur.basculer(a) }) { ok ->
                        if (!ok) message("L'appareil n'a pas répondu.")
                        ui.postDelayed({ if (pile.isNotEmpty()) rafraichir() }, 700)
                    }
                }
            }
            grille(c, tuiles)
            if (domaines.contains("light")) bouton(c, "Tout éteindre") {
                enFond({ moteur.service("light", "turn_off", JSONObject().put("entity_id", "all")) }) { ui.postDelayed({ rafraichir() }, 700) }
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

    private fun cameras() {
        val c = page("Caméras")
        chargerAppareils(c) { tous ->
            val l = tous.filter { it.domaine == "camera" }
            if (l.isEmpty()) {
                texte(c, "Aucune caméra branchée pour l'instant.", 16f, cTexte)
                texte(c, "Va dans « Ajouter un appareil » depuis l'accueil.")
                return@chargerAppareils
            }
            grille(c, l.map { a -> tuile(a.nom, libelleEtat(a)) { ouvrir("Caméra", { camera(a) }) } })
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
            val l = tous.filter { it.domaine == "sensor" && it.genre in setOf("energy", "power", "gas", "water", "current", "voltage") }
            if (l.isEmpty()) {
                texte(c, "Aucun compteur ni prise mesurante branché pour l'instant.", 16f, cTexte)
                texte(c, "Dès qu'une prise qui mesure, un compteur ou un module de consommation sera branché, ses chiffres s'afficheront ici.")
                return@chargerAppareils
            }
            val maintenant = l.filter { it.genre == "power" }
            if (maintenant.isNotEmpty()) {
                val total = maintenant.mapNotNull { it.etat.toDoubleOrNull() }.sum()
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
            val pilotables = tous.filter { it.domaine in setOf("light", "switch", "cover", "lock", "media_player", "scene", "script", "fan", "climate") }
            bouton(c, "Nouveau planning") {
                if (pilotables.isEmpty()) message("Aucun appareil à programmer pour l'instant.") else nouveauPlanning(pilotables)
            }
            val l = tous.filter { it.domaine == "automation" }
            if (l.isEmpty()) texte(c, "Aucun planning pour l'instant.")
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
        texte(c, "Comment ça marche", 18f, cAccent)
        texte(c, "1. Branche l'appareil et installe-le d'abord avec l'appli de sa marque, sur le même Wi-Fi que la maison.\n" +
                "2. Le moteur de la maison, sur le PC, le découvre en général tout seul.\n" +
                "3. Il apparaît alors dans la bonne rubrique : Lumières, Caméras, Capteurs…")
        texte(c, "Appareils compatibles", 18f, cAccent)
        texte(c, "Des milliers de marques : ampoules, prises, volets, serrures, caméras, capteurs de température, d'humidité et de qualité de l'air, compteurs, télés, enceintes Google et autres. " +
                "Les appareils Bluetooth ou d'autres types de liaison peuvent demander un petit récepteur branché sur le PC.")
        texte(c, "Certains appareils ne marchent qu'avec le compte de leur marque sur Internet : ils fonctionneront, mais pas sans connexion.")
        val etat = texte(c, "", 15f, cTexte)
        val att = attente(c, "Recherche des appareils découverts…")
        enFond({
            if (!moteur.pret) moteur.recupererDepuisPasserelle(passerelle.trouver())
            if (moteur.pret) Reseau.lire("${moteur.adresse}/api/config/config_entries/flow", 6000, mapOf("Authorization" to "Bearer ${moteur.jeton}")) else null
        }) { t ->
            c.removeView(att)
            if (!moteur.pret) { etat.text = "Le moteur de la maison n'est pas encore installé sur le PC : cette étape arrive bientôt."; return@enFond }
            val tab = try { JSONArray(t ?: "[]") } catch (e: Exception) { JSONArray() }
            if (tab.length() == 0) { etat.text = "Aucun nouvel appareil en attente pour l'instant."; return@enFond }
            etat.text = "Nouveaux appareils découverts, en attente de validation sur le PC :"
            for (i in 0 until tab.length()) {
                val o = tab.getJSONObject(i)
                val nom = o.optJSONObject("context")?.optString("title_placeholders")?.takeIf { it.isNotBlank() && it != "{}" } ?: o.optString("handler")
                ligne(c, nom, "Découvert sur le réseau") { }
            }
        }
    }

    // ---------- réglages ----------

    private fun reglages() {
        val c = page("Réglages")
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
        texte(c, "Moteur de la maison", 18f, cAccent)
        texte(c, if (moteur.pret) "Réglé : " + moteur.adresse else "Pas encore installé (il sera réglé tout seul).", 15f, cTexte)
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
        enFond({
            if (!moteur.pret) moteur.recupererDepuisPasserelle(passerelle.trouver())
            if (moteur.pret) moteur.appareils() else null
        }) { l ->
            if (l == null) { message("« $phrase » : le moteur de la maison n'est pas encore là."); return@enFond }
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
                cible.domaine == "camera" -> ouvrir("Caméra", { camera(cible) })
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

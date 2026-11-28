package fr.hadesdray.mamaison

import android.content.SharedPreferences
import android.graphics.Color

/** Thèmes de Ma Maison. « Nuit » est le visuel d'origine ; les autres se choisissent dans Réglages > Apparence. */
class Theme(
    val id: String, val nom: String, val description: String,
    fond: String, carte: String, carteHaute: String, carteActive: String,
    accent: String, texte: String, gris: String, texteBouton: String
) {
    val fond = Color.parseColor(fond)
    val carte = Color.parseColor(carte)
    val carteHaute = Color.parseColor(carteHaute)
    val carteActive = Color.parseColor(carteActive)
    val accent = Color.parseColor(accent)
    val texte = Color.parseColor(texte)
    val gris = Color.parseColor(gris)
    val texteBouton = Color.parseColor(texteBouton)
    val clair: Boolean get() = Color.luminance(fond) > 0.5f

    companion object {
        val tous = listOf(
            Theme("nuit", "Nuit", "Le visuel d'origine : bleu nuit et or",
                "#12161F", "#1E2533", "#2A3550", "#3A3220", "#F2B544", "#F3F5F9", "#9AA4B5", "#1A1A1A"),
            Theme("ocean", "Océan", "Bleu profond et turquoise",
                "#0B1A24", "#132A38", "#1B3D52", "#123B3A", "#3FD0C9", "#EEF8FA", "#8FB3C2", "#06201F"),
            Theme("foret", "Forêt", "Vert sapin et vert tendre",
                "#101A14", "#1A2A20", "#24402E", "#2E3A1A", "#8BD46A", "#F1F7F2", "#9DB3A4", "#10200A"),
            Theme("prune", "Prune", "Violet sombre et rose",
                "#18121F", "#261C33", "#3A2850", "#3A2030", "#F07AB8", "#F7F2FA", "#ADA0BA", "#2A0A1C"),
            Theme("braise", "Braise", "Noir chaud et orange",
                "#1A1210", "#2A1D19", "#432A20", "#40261A", "#FF8A3D", "#FAF3EF", "#B8A39A", "#2A1206"),
            Theme("clair", "Clair", "Fond blanc, pour les pièces lumineuses",
                "#F2F4F8", "#FFFFFF", "#E3E9F5", "#FFF1D6", "#D98E04", "#1A2030", "#5C6678", "#FFFFFF")
        )

        fun choisi(prefs: SharedPreferences): Theme {
            val id = prefs.getString("theme", "nuit")
            return tous.firstOrNull { it.id == id } ?: tous[0]
        }
    }
}

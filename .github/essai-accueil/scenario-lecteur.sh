# Essai 1.11.0 : langue des vidéos et menu Réglages du lecteur (téléphone, tablette 10 pouces, tablette 7 pouces)
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
SZ=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); CX=$(( ${SZ%x*} / 2 )); CY=$(( ${SZ#*x} / 2 ))
menu() { adb shell input keyevent 82; sleep 2; }
adb logcat -c

# --- Vidéo 1 : japonais + anglais, sous-titres français intégrés (rien de retenu : langue d'origine + sous-titres français) ---
py tapx:Vidéos dormir:3 "tap:Vidéos essai" dormir:3 cap:01-liste "tap:Essai japonais" dormir:12 cap:02-lecture
# le bouton Réglages se touche ; s'il est caché (commandes disparues), un appui sur l'image le remontre
sortie=$(py "tap:Réglages"); echo "$sortie"
case "$sortie" in *"Réglages True"*) ;; *) adb shell input tap $CX $CY; sleep 1; py "tap:Réglages";; esac
sleep 1
py cap:03-menu-auto
py "tap:Langue" cap:04-langues "tap:Anglais" cap:05-menu-anglais
py "tap:Sous-titres" cap:06-sous-titres "tap:Aucun" cap:07-menu-sans-sous-titres
py "tap:Sous-titres" "tap:Français" "tap:Taille" cap:08-taille "tap:Très grande" "tap:Couleur" cap:09-couleur "tap:Jaune"
py "tap:Vitesse" cap:10-vitesse "tap:1,5x" "tap:Format" cap:11-format "tap:Zoom" cap:12-menu-final "tap:Fermer" dormir:2 cap:13-video-sous-titres-jaunes
py retour dormir:2 cap:14-retour-liste

# --- Vidéo 2 : français + anglais ; la langue choisie (anglais) est remise toute seule ---
py "tap:Essai langues" dormir:12
menu; py cap:15-menu-retenu
py "tap:Sous-titres" cap:16-sous-titres-2 "tap:Anglais" "tap:Langue" cap:17-langues-2 "tap:Français" cap:18-menu-francais
adb shell input keyevent 20; adb shell input keyevent 20; sleep 1; py cap:19-focus-telecommande
adb shell input keyevent 23; sleep 2; py cap:20-focus-sous-menu
py retour cap:21-retour-ferme-le-sous-menu retour cap:22-retour-ferme-le-menu
py retour dormir:2 cap:23-retour-liste

# --- Vidéo 3 : sous-titres en fichier à côté de la vidéo ---
py "tap:Essai externe" dormir:12
menu; py cap:24-menu-externe
py "tap:Sous-titres" cap:25-sous-titres-fichier "tap:Français" "tap:Fermer" dormir:2 cap:26-video-fichier
py retour dormir:2

# --- Vidéo 4 : vieux format, lecteur VLC ---
py "tap:Essai vlc" dormir:15 cap:27-vlc
sortie=$(py "tap:Réglages"); echo "$sortie"
sleep 1; py cap:28-vlc-menu "tap:Langue" cap:29-vlc-langues "tap:Piste 2" cap:30-vlc-menu-apres "tap:Vitesse" "tap:0,5x" "tap:Format" "tap:Remplir" "tap:Fermer" dormir:2 cap:31-vlc-video
py retour dormir:2 cap:32-fin

adb logcat -d -s MaisonLecteur:I > lecteur.txt 2>&1

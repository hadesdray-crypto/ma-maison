# Essai 1.11.0 sur télé : le menu Réglages du lecteur, tout à la télécommande
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
neuf() { adb shell am force-stop fr.hadesdray.mamaison; adb shell am start -n fr.hadesdray.mamaison/.MainActivity >/dev/null; sleep 10; }
py retour dormir:2
neuf
adb logcat -c
py "aller:Vidéos" touche:23 dormir:4 cap:01-videos
py "aller:Vidéos essai" touche:23 dormir:4 cap:02-liste
py "aller:Essai externe" touche:23 dormir:14 cap:03-lecture
adb shell input keyevent 82; sleep 2; py cap:04-menu focus
adb shell input keyevent 20; sleep 1; py cap:05-bas focus
adb shell input keyevent 23; sleep 2; py cap:06-sous-titres focus
adb shell input keyevent 20; sleep 1; py cap:07-fichier focus
adb shell input keyevent 23; sleep 2; py cap:08-choisi focus
adb shell input keyevent 4; sleep 2; py cap:09-menu-ferme focus
adb logcat -d -s MaisonLecteur:I > lecteur.txt 2>&1

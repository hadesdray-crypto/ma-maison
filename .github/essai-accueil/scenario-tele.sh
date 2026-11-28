# Essai 1.10.0 sur télé : visionneuse de photos à la télécommande
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
k() { for c in "$@"; do adb shell input keyevent $c; sleep 0.8; done; sleep 1; }
neuf() { adb shell am force-stop fr.hadesdray.mamaison; adb shell am start -n fr.hadesdray.mamaison/.MainActivity >/dev/null; sleep 10; }
py retour dormir:2
neuf; k 22 22 22 23; sleep 3; k 20 20 23; sleep 3; k 20 20 20 23; sleep 4; py cap:01-photo
k 20; py cap:02-ph1 focus; k 20; py cap:03-ph2 focus; k 22; py cap:04-ph3 focus; k 23; sleep 2; py cap:05-action
k 19; py cap:06-haut focus; k 23; sleep 2; py cap:07-action2
k 22; py cap:08-droite focus; k 23; sleep 3; py cap:09-action3

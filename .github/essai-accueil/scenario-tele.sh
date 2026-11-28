# Essai 1.10.0 sur télé : uniquement à la télécommande, capture après chaque étape (cadre blanc = élément choisi)
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
k() { for c in "$@"; do adb shell input keyevent $c; sleep 0.8; done; sleep 1; }
neuf() { adb shell am force-stop fr.hadesdray.mamaison; adb shell am start -n fr.hadesdray.mamaison/.MainActivity >/dev/null; sleep 10; }
py retour dormir:2
neuf; py cap:01-depart
k 20; py cap:02-bas1; k 20; py cap:03-bas2; k 20; py cap:04-bas3; k 22 22 22; py cap:05-droite3
k 23; sleep 2; py cap:06-reglages focus
k 20; py cap:07-reglages-bas1 focus; k 20; py cap:08-reglages-bas2 focus
neuf; py "aller:Réglages" focus; k 23; sleep 2; py "aller:Changer le visuel" focus; k 23; sleep 2; py cap:09-apparence focus
k 20; py cap:10-apparence-bas focus; k 23; sleep 2; py cap:11-theme-applique focus
k 4; sleep 2; k 4; sleep 2; py cap:12-accueil-theme
neuf; k 22 22 22; py cap:13-photos-choisi focus; k 23; sleep 3; py cap:14-photos focus
k 20; py cap:15-photos-bas focus; k 23; sleep 3; py cap:16-dossier focus; k 20; k 23; sleep 4; py cap:17-photo focus
k 20; py cap:18-photo-bas focus; k 20; py cap:19-photo-bas2 focus; k 23; sleep 1; py cap:20-photo-action
neuf; k 23; sleep 3; py cap:21-videos focus; k 20; py cap:22-videos-bas focus; k 22; py cap:23-videos-droite focus
k 23; sleep 3; py cap:24-dossier-videos focus; k 20; k 23; sleep 8; py cap:25-lecteur focus
k 19; py cap:26-lecteur-haut focus; k 19; py cap:27-lecteur-haut2 focus; k 23; sleep 2; py cap:28-lecteur-ok
neuf; py "aller:Réglages"; k 23; sleep 2; py "aller:Chercher et connecter" focus; k 23; sleep 3; py cap:29-bluetooth focus
k 20; py cap:30-bluetooth-bas focus
neuf; py "aller:Réglages"; k 23; sleep 2; py "aller:Changer le visuel"; k 23; sleep 2; py aller:Nuit; k 23; sleep 2

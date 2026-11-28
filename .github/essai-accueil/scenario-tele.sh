# Essai de la 1.10.0 sur télé, à la télécommande : apparence, photos, lecteur, Bluetooth
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
k() { for c in "$@"; do adb shell input keyevent $c; sleep 0.8; done; sleep 1; }
py retour dormir:2
adb shell am start -n fr.hadesdray.mamaison/.MainActivity; sleep 10
py cap:01-depart
py aller:Réglages; k 23; sleep 2; py cap:02-reglages
py "aller:Changer le visuel"; k 23; sleep 2; py cap:03-apparence
py aller:Prune; k 23; sleep 2; py cap:04-prune
k 4; sleep 2; k 4; sleep 2; py cap:05-accueil-prune
py "aller:Photos"; k 23; sleep 3; py "aller:Images essai"; k 23; sleep 3; k 23; sleep 4; py cap:06-photo
py "aller:Tourner à droite"; k 23; sleep 1; py cap:07-photo-tournee
k 4; sleep 2; k 4; sleep 2; k 4; sleep 2
py "aller:Réglages"; k 23; sleep 2; py "aller:Chercher et connecter"; k 23; sleep 3; py cap:08-bluetooth
k 4; sleep 2; py "aller:Changer le visuel"; k 23; sleep 2; py aller:Nuit; k 23; sleep 2; k 4; sleep 2; k 4; sleep 2; py cap:09-accueil-nuit

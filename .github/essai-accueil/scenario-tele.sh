# Essai de la 1.10.0 sur télé : on va aux pages au toucher, puis on vérifie les nouveautés à la télécommande
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
k() { for c in "$@"; do adb shell input keyevent $c; sleep 0.8; done; sleep 1; }
py retour dormir:2
adb shell am start -n fr.hadesdray.mamaison/.MainActivity; sleep 10
py cap:01-depart
py tapx:Réglages dormir:2 cap:02-reglages "tap:Changer le visuel" dormir:2 cap:03-apparence
py aller:Prune focus; k 23; sleep 2; py cap:04-prune
k 4; sleep 2; k 4; sleep 2; py cap:05-accueil-prune
py tapx:Photos dormir:3 "tap:Images essai" dormir:3 "tap:Photo bleue" dormir:4 cap:06-photo
py "aller:Tourner à droite" focus; k 23; sleep 1; py cap:07-photo-tournee
py "aller:Suivante" focus; k 23; sleep 3; py cap:08-photo-suivante
k 4; sleep 2; k 4; sleep 2; k 4; sleep 2
py tapx:Vidéos dormir:3 "tap:Vidéos essai" dormir:3 "tap:Essai.mp4" dormir:8 cap:09-lecteur
py "aller:Cadrage" focus; k 23; sleep 2; py cap:10-cadrage
k 4; sleep 2; k 4; sleep 2; k 4; sleep 2
py tapx:Réglages dormir:2 "tap:Chercher et connecter" dormir:3 cap:11-bluetooth
py aller:Autoriser focus; k 4; sleep 2
py "tap:Changer le visuel" dormir:2 "tap:Nuit" dormir:2 retour retour cap:12-accueil-nuit

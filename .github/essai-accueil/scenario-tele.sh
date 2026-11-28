py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
k() { for c in "$@"; do adb shell input keyevent $c; sleep 0.8; done; sleep 1; }
py retour dormir:2
adb shell am start -n fr.hadesdray.mamaison/.MainActivity; sleep 10
py cap:01-depart
k 22; py cap:02-droite
k 21 20; py cap:03-bas
k 20 20; py cap:04-tout-en-bas
k 19 19 19 19 19 19 19 19; k 20; py cap:05-remonte
k 19 19 19 19 19 19; k 20; k 23; sleep 4; py cap:06-videos
k 20; py cap:07-videos-choix; k 23; sleep 4; py cap:08-liste
k 20; py cap:09-liste-bouton; k 20 20 20; py cap:10-liste-ligne
k 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20; py cap:11-liste-plus-bas
k 4; sleep 3; py cap:12-retour-videos; k 4; sleep 3; py cap:13-retour-accueil
py appuilong cap:14-appui-long
k 4; sleep 2; py cap:15-fin

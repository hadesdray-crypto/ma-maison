py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
k() { for c in "$@"; do adb shell input keyevent $c; sleep 0.8; done; sleep 1; }
py retour dormir:2
adb shell am start -n fr.hadesdray.mamaison/.MainActivity; sleep 10
py cap:01-depart
k 23; sleep 4; py cap:02-videos
k 20; py cap:03-videos-1; k 20; py cap:04-videos-2
k 23; sleep 4; py cap:05-liste
k 20; py cap:06-liste-1; k 20; py cap:07-liste-2; k 20; py cap:08-liste-3; k 20 20 20; py cap:09-liste-6
k 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20; py cap:10-liste-31
k 19 19 19 19 19; py cap:11-liste-remonte
k 4; sleep 2; py cap:12-retour-videos
k 20 20; k 23; sleep 2; py cap:13-liste-rouverte
k 4; sleep 2; k 4; sleep 2; py cap:14-accueil

py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
k() { for c in "$@"; do adb shell input keyevent $c; sleep 0.8; done; sleep 1; }
py retour dormir:2
adb shell am start -n fr.hadesdray.mamaison/.MainActivity; sleep 10
py cap:01-accueil focus
k 20 20; py focus cap:02-bas2
k 22; py focus; k 20; py focus cap:03-rangee-moyenne; k 20; py focus; k 20; py focus cap:04-rangee-petite
k 19 19 19 19 19 19 19 19 20 20; py focus; k 23; sleep 4; py cap:05-videos focus
k 20; py focus; k 23; sleep 4; py cap:06-liste focus
k 20 20 20; py focus cap:07-ligne-choisie
k 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20; py focus cap:08-plus-bas
k 4; sleep 2; k 4; sleep 2; py cap:09-accueil focus
k 19 19 19 19 19 19 19 19 20 20 22 22 22; py focus cap:10-photos-choisie
py appuilong cap:11-menu focus
k 20 20; py focus; k 23; sleep 2; py cap:12-photos-petite focus

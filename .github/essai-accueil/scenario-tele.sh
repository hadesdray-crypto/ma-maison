# Essai 1.10.0 sur télé : uniquement à la télécommande (touches exactes), capture après chaque étape
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
k() { for c in "$@"; do adb shell input keyevent $c; sleep 0.8; done; sleep 1; }
neuf() { adb shell am force-stop fr.hadesdray.mamaison; adb shell am start -n fr.hadesdray.mamaison/.MainActivity >/dev/null; sleep 10; }
reglages() { neuf; k 20 20 20 22 22 22 23; sleep 2; }
py retour dormir:2
reglages; k 20 20 23; sleep 2; py cap:01-apparence
k 20; py cap:02-a1 focus; k 20; py cap:03-a2 focus; k 20; py cap:04-a3 focus; k 23; sleep 2; py cap:05-theme-applique focus
k 4; sleep 2; k 4; sleep 2; py cap:06-accueil-theme
neuf; k 22 22 22 23; sleep 3; k 20 20 23; sleep 3; py cap:07-dossier-photos
k 20; py cap:08-p1 focus; k 20; py cap:09-p2 focus; k 20; py cap:10-p3 focus; k 23; sleep 4; py cap:11-photo focus
k 20; py cap:12-ph1 focus; k 20; py cap:13-ph2 focus; k 22; py cap:14-ph3 focus; k 23; sleep 2; py cap:15-ph-action
k 22; k 23; sleep 2; py cap:16-ph-action2
neuf; k 23; sleep 3; k 20 20 22 23; sleep 3; py cap:17-dossier-videos
k 20; py cap:18-v1 focus; k 20; py cap:19-v2 focus; k 23; sleep 8; py cap:20-lecteur focus
k 19; py cap:21-l1 focus; k 19; py cap:22-l2 focus; k 22; py cap:23-l3 focus; k 23; sleep 2; py cap:24-l-action
reglages; k 20 20 20; py cap:25-r3 focus; k 20; py cap:26-r4 focus; k 23; sleep 3; py cap:27-bluetooth focus
k 20; py cap:28-b1 focus; k 20; py cap:29-b2 focus; k 20; py cap:30-b3 focus
reglages; k 20 20 23; sleep 2; k 20 20 23; sleep 2; py cap:31-nuit

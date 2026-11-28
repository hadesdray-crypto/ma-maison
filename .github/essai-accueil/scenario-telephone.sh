# Essai 1.10.0 (téléphone) : gestes sur la photo (glisser = suivante, double appui = agrandir, appui = cacher les boutons)
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
py tapx:Photos dormir:3 "tap:Images essai" dormir:3 "tap:Photo bleue" dormir:4 cap:01-photo
adb shell input swipe 900 1100 200 1100 200; sleep 3; py cap:02-glisse
adb shell input tap 540 1000; sleep 0.1; adb shell input tap 540 1000; sleep 2; py cap:03-double-appui
adb shell input tap 540 1000; sleep 0.1; adb shell input tap 540 1000; sleep 2
adb shell input tap 540 1000; sleep 2; py cap:04-appui-simple

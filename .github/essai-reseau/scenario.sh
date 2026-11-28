py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
neuf() { adb shell am force-stop fr.hadesdray.mamaison; adb shell am start -n fr.hadesdray.mamaison/.MainActivity >/dev/null; sleep 8; }
neuf; py "tapx:Réseau" dormir:30 cap:01-reseau
py "tap:PC-ESSAI" dormir:2 cap:02-choix "tap:Dossiers partagés" dormir:8 cap:03-partages
py "tap:Films" dormir:6 cap:04-smb-liste "tap:Film 001" dormir:8 cap:05-smb-lecteur
neuf; py "tapx:Réseau" dormir:3 "tap:PC-ESSAI" dormir:2 "tap:Serveur de vidéos" dormir:5 cap:06-dlna "tap:▸  Films du serveur" dormir:5 cap:07-dlna-liste "tap:Essai serveur" dormir:10 cap:08-dlna-lecteur
neuf; py "tapx:Vidéos" dormir:8 bas bas cap:09-videos-reseau
neuf; py "tapx:Réglages" dormir:2 "tap:Partager les photos" dormir:2 "tap:Activer le partage" dormir:4 cap:10-partage

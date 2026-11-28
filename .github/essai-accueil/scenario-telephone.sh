# Essai de la 1.10.0 : seulement ce qui a changé (âge des films, cadrage, photos, jeux des PC, apparence, Bluetooth)
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
py cap:01-accueil
py tapx:Vidéos dormir:3 "tap:Films essai" dormir:3 cap:02-liste-films "long:Film 001.mp4" cap:03-choix-age "tap:Moins de 12 ans" dormir:1 cap:04-age-12
py "long:▸  Saison 1" "tap:Tous publics" dormir:1 cap:05-dossier-tous-publics retour
py "tap:Vidéos essai" dormir:3 "tap:Essai.mp4" dormir:8 cap:06-lecteur
py "tap:Cadrage" dormir:2 cap:07-cadrage-2 "tap:Cadrage" dormir:2 cap:08-cadrage-3 "tap:Cadrage" dormir:2 cap:09-cadrage-1 retour retour retour
py tapx:Photos dormir:3 "tap:Images essai" dormir:3 "tap:Photo bleue" dormir:4 cap:10-photo
py "tap:Tourner à droite" dormir:1 cap:11-photo-tournee "tap:Agrandir" dormir:1 cap:12-photo-agrandie "tap:Agrandir" "tap:Suivante" dormir:3 cap:13-photo-suivante "tap:Précédente" dormir:3 cap:14-photo-rotation-gardee retour retour retour
py tapx:Jeux dormir:2 cap:15-jeux retour
py tapx:Réglages dormir:2 cap:16-reglages "tap:Changer le visuel" dormir:2 cap:17-apparence "tap:Océan" dormir:2 cap:18-apparence-ocean retour retour cap:19-accueil-ocean
py tapx:Réglages dormir:2 "tap:Changer le visuel" dormir:2 "tap:Clair" dormir:2 retour retour cap:20-accueil-clair
py tapx:Vidéos dormir:3 cap:21-videos-clair retour
py tapx:Réglages dormir:2 "tap:Chercher et connecter" dormir:3 cap:22-bluetooth "tap:Autoriser" dormir:3 cap:23-bluetooth-autorisation
adb shell pm grant fr.hadesdray.mamaison android.permission.BLUETOOTH_SCAN; adb shell pm grant fr.hadesdray.mamaison android.permission.BLUETOOTH_CONNECT
py retour dormir:2 retour "tap:Chercher et connecter" dormir:3 cap:24-bluetooth-autorise "tap:Chercher les appareils" dormir:4 cap:25-bluetooth-recherche retour retour
py tapx:Réglages dormir:2 "tap:Changer le visuel" dormir:2 "tap:Nuit" dormir:2 retour retour cap:26-accueil-nuit

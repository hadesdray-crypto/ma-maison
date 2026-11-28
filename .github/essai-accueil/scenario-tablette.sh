# Essai de la 1.10.0 sur tablette : seulement ce qui a changé
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
py cap:01-accueil
py tapx:Photos dormir:3 "tap:Images essai" dormir:3 "tap:Photo verte" dormir:4 "tap:Tourner à gauche" dormir:1 cap:02-photo-tournee retour retour retour
py tapx:Vidéos dormir:3 "tap:Vidéos essai" dormir:3 "tap:Essai.mp4" dormir:8 cap:03-lecteur "tap:Cadrage" dormir:2 cap:04-cadrage retour retour retour
py tapx:Jeux dormir:2 cap:05-jeux retour
py tapx:Réglages dormir:2 "tap:Changer le visuel" dormir:2 cap:06-apparence "tap:Forêt" dormir:2 retour retour cap:07-accueil-foret
py tapx:Réglages dormir:2 "tap:Chercher et connecter" dormir:3 cap:08-bluetooth retour retour

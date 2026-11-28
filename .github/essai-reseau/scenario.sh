py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
py "tapx:Réseau" dormir:30 cap:01-reseau bas cap:02-reseau-bas haut
py "tap:PC-ESSAI" dormir:2 cap:03-choix
py "tap:Dossiers partagés" dormir:8 cap:04-partages
py "tap:Films" dormir:6 cap:05-smb-liste
py "tap:Film 001" dormir:8 cap:06-smb-lecteur retour dormir:2 retour retour
py "tap:PC-ESSAI" dormir:2 "tap:Serveur de vidéos" dormir:5 cap:07-dlna "tap:Films du serveur" dormir:5 cap:08-dlna-liste
py "tap:Essai serveur" dormir:10 cap:09-dlna-lecteur retour dormir:2 retour retour retour dormir:2
py "tapx:Vidéos" dormir:6 bas bas cap:10-videos-reseau
py retour dormir:2 "tapx:Réglages" dormir:2 "tap:Partager les photos" dormir:2 "tap:Activer le partage" dormir:3 cap:11-partage

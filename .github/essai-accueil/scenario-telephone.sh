# Essai 1.10.0 (téléphone) : page Bluetooth avant et après l'autorisation
py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
py tapx:Réglages dormir:2 "tap:Chercher et connecter" dormir:3 cap:01-bluetooth "tapx:Autoriser" dormir:3 cap:02-demande "tapx:Allow" dormir:4 cap:03-autorise "tap:Chercher les appareils" dormir:6 cap:04-recherche retour retour cap:05-accueil

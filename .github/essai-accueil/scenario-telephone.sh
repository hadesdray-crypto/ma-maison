py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
py cap:01-accueil bas cap:02-accueil-bas haut
py tapx:Vidéos dormir:3 cap:03-videos bas cap:03b-videos-bas "tap:Films du disque" dormir:2 cap:03c-non-connecte dormir:3 "tap:Films essai" dormir:3 cap:04-liste
py "tap:▸  Saison 1" dormir:2 cap:05-sous-dossier retour cap:06-retour-liste
py bas bas bas cap:07-liste-plus-bas "tap:Film 0" dormir:6 cap:08-lecteur retour cap:09-liste-meme-endroit retour retour
py tapx:Maison cap:10-maison long:Prises cap:11-menu-maison "tap:Mettre sur l'accueil" cap:12-maison-apres retour cap:13-accueil-avec-prises
py long:Photos cap:14-menu "tap:Taille : Petite" cap:15-photos-petite
py "long:Smart Life" "tap:Taille : Grande" cap:16-smartlife-grande
py long:Jeux "tap:Reculer d'une place" cap:17-jeux-recule
py long:Jeux "tap:Tout remettre comme au départ" cap:18-remis
py tapx:Photos dormir:3 "tap:Images essai" dormir:3 cap:19-photos "tap:Photo bleue" dormir:4 cap:20-photo retour retour retour
py tapx:Musique dormir:3 "tap:Musique essai" dormir:3 cap:21-musique retour retour
py tapx:Jeux cap:22-jeux retour tapx:Lumières dormir:3 cap:23-lumieres retour
py tapx:Réglages cap:24-reglages retour cap:25-accueil-fin

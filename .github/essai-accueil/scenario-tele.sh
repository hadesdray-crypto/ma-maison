py() { python3 ../.github/essai-accueil/pilote.py "$@"; }
py retour dormir:3
py cap:01-accueil focus
py aller:Vidéos cap:02-videos-choisie touche:23 dormir:4 cap:03-videos focus
py "aller:Films essai" touche:23 dormir:4 cap:04-liste focus
py touche:20x5 cap:05-liste-ligne-choisie focus touche:20x30 cap:06-liste-plus-bas focus
py touche:19x40 touche:20 focus touche:23 dormir:3 cap:07-sous-dossier focus retour dormir:1 cap:08-retour-liste retour retour dormir:1 cap:09-accueil focus
py aller:Photos appuilong cap:10-menu-appui-long touche:20x2 touche:23 dormir:1 cap:11-photos-petite
py aller:Maison touche:23 dormir:2 cap:12-maison focus aller:Capteurs cap:13-capteurs-choisi retour
py aller:Réglages cap:14-reglages-choisi touche:23 dormir:2 cap:15-reglages retour
py aller:Jeux touche:23 dormir:2 cap:16-jeux retour
py aller:YouPub cap:17-youpub-choisie

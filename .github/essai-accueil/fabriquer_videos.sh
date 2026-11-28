#!/bin/sh
# Vidéos d'essai du lecteur 1.11.0 (dans le dossier donné) :
#  - Essai japonais.mkv : sons japonais + anglais, sous-titres français intégrés
#  - Essai langues.mkv  : sons français + anglais, sous-titres français + anglais intégrés
#  - Essai externe.mp4 + Essai externe.fr.srt : un seul son, sous-titres en fichier à côté
#  - Essai vlc.avi : vieux format (lecteur VLC), deux sons
cd "$1" || exit 0
printf '1\n00:00:00,500 --> 00:01:00,000\nBonjour : sous-titres français intégrés\n\n' > fr.srt
printf '1\n00:00:00,500 --> 00:01:00,000\nHello: embedded English subtitles\n\n' > en.srt
printf '1\n00:00:00,500 --> 00:01:00,000\nBonjour : sous-titres en fichier à côté\n\n' > "Essai externe.fr.srt"
V="-f lavfi -i testsrc=duration=60:size=640x360:rate=25"
A1="-f lavfi -i sine=frequency=440:duration=60"
A2="-f lavfi -i sine=frequency=880:duration=60"
ffmpeg -loglevel error -y $V $A1 $A2 -i fr.srt -map 0:v -map 1:a -map 2:a -map 3:0 \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -c:s srt \
  -metadata:s:a:0 language=jpn -metadata:s:a:1 language=eng -metadata:s:s:0 language=fra "Essai japonais.mkv" || true
ffmpeg -loglevel error -y $V $A1 $A2 -i fr.srt -i en.srt -map 0:v -map 1:a -map 2:a -map 3:0 -map 4:0 \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -c:s srt \
  -metadata:s:a:0 language=fra -metadata:s:a:1 language=eng -metadata:s:s:0 language=fra -metadata:s:s:1 language=eng "Essai langues.mkv" || true
ffmpeg -loglevel error -y $V $A1 -map 0:v -map 1:a -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac "Essai externe.mp4" || true
ffmpeg -loglevel error -y $V $A1 $A2 -map 0:v -map 1:a -map 2:a -c:v mpeg4 -c:a mp3 "Essai vlc.avi" || true
rm -f fr.srt en.srt
ls -la

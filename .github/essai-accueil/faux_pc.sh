#!/bin/sh
# Faux PC de la maison pour l'essai : dossiers de films, photos et musiques factices.
set -e
P=/tmp/pc
mkdir -p "$P/films/Saison 1" "$P/films/Dessins animés" "$P/videos" "$P/photos" "$P/musique"
i=1; while [ $i -le 600 ]; do n=$(printf "%03d" $i); : > "$P/films/Film $n.mp4"; i=$((i+1)); done
truncate -s 700M "$P/films/Film 001.mp4"; truncate -s 1400M "$P/films/Film 002.mp4"
for n in 1 2 3 4 5 6 7 8; do : > "$P/films/Saison 1/Episode $n.mkv"; done
if [ -s "$1/essai.mp4" ]; then cp "$1/essai.mp4" "$P/videos/Essai.mp4"; else cp .github/essai-accueil/essai.mp4 "$P/videos/Essai.mp4" 2>/dev/null || : > "$P/videos/Essai.mp4"; fi
# Vidéos d'essai du lecteur (langues, sous-titres) fabriquées par le flux
for f in "$1"/Essai*; do [ -f "$f" ] && cp "$f" "$P/videos/"; done
python3 - <<'PY'
import struct, zlib
def png(nom, c):
    l, h = 320, 200
    ligne = b"\x00" + bytes(c) * l
    d = zlib.compress(ligne * h)
    def bloc(t, x): return struct.pack(">I", len(x)) + t + x + struct.pack(">I", zlib.crc32(t + x) & 0xffffffff)
    open(nom, "wb").write(b"\x89PNG\r\n\x1a\n" + bloc(b"IHDR", struct.pack(">IIBBBBB", l, h, 8, 2, 0, 0, 0)) + bloc(b"IDAT", d) + bloc(b"IEND", b""))
png("/tmp/pc/photos/Photo rouge.png", (200, 40, 40)); png("/tmp/pc/photos/Photo verte.png", (40, 180, 80)); png("/tmp/pc/photos/Photo bleue.png", (40, 80, 200))
PY
for n in 1 2 3 4 5; do : > "$P/musique/Chanson $n.mp3"; done
cat > "$2/reglages.json" <<J
{"disques_connus":[{"lettre":"Z:","nom":"EMTEC · 1 To"}],
 "videos":[{"nom":"Films essai","chemin":"$P/films"},{"nom":"Vidéos essai","chemin":"$P/videos"},{"nom":"Films du disque externe","chemin":"Z:\\films"}],
 "photos":[{"nom":"Images essai","chemin":"$P/photos"}],
 "musique":[{"nom":"Musique essai","chemin":"$P/musique"}]}
J

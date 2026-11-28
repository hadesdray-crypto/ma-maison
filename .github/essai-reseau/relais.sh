CODE=$(grep -o 'partage_code">[0-9]*' prefs.txt | grep -o '[0-9]*$')
echo "code=$CODE"
curl -s -m 5 http://127.0.0.1:8791/partage/ping; echo
curl -s -m 5 "http://127.0.0.1:8791/partage/liste?type=photos&code=$CODE"; echo
ID=$(curl -s -m 5 "http://127.0.0.1:8791/partage/liste?type=photos&code=$CODE" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
curl -s -m 10 -o photo.bin -w "photo id=$ID code http %{http_code} taille %{size_download}\n" "http://127.0.0.1:8791/partage/fichier?type=photos&id=$ID&code=$CODE"
curl -s -m 5 -H "Range: bytes=0-9" -o /dev/null -w "morceau http %{http_code} taille %{size_download}\n" "http://127.0.0.1:8791/partage/fichier?type=photos&id=$ID&code=$CODE"
curl -s -m 5 "http://127.0.0.1:8791/partage/liste?type=photos&code=000000"; echo

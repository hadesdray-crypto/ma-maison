# Faux serveur de vidéos du réseau (comme une Freebox ou MiniDLNA), pour l'essai.
import http.server, re, html
DESC = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>Serveur essai</friendlyName>
<manufacturer>Essai</manufacturer><modelName>Faux DLNA</modelName>
<serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
<controlURL>/ctl/ContentDir</controlURL></service></serviceList></device></root>"""
def didl(objet):
    if objet == "0":
        corps = '<container id="1" parentID="0"><dc:title>Films du serveur</dc:title></container>'
    else:
        corps = ('<item id="11" parentID="1"><dc:title>Essai serveur.mp4</dc:title>'
                 '<res protocolInfo="http-get:*:video/mp4:*" size="1000">http://10.0.2.2:8790/fichier?chemin=%2Ftmp%2Fpc%2Fvideos%2FEssai.mp4</res></item>')
    return ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">' + corps + '</DIDL-Lite>')
class H(http.server.BaseHTTPRequestHandler):
    def envoyer(self, t, ty="text/xml"):
        b = t.encode(); self.send_response(200); self.send_header("Content-Type", ty); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        self.envoyer(DESC) if self.path.startswith("/rootDesc.xml") else (self.send_response(404), self.end_headers())
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); q = self.rfile.read(n).decode()
        m = re.search(r"<ObjectID>(.*?)</ObjectID>", q); objet = m.group(1) if m else "0"
        r = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
             '<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>' + html.escape(didl(objet)) +
             '</Result><NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>')
        self.envoyer(r)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("0.0.0.0", 8200), H).serve_forever()

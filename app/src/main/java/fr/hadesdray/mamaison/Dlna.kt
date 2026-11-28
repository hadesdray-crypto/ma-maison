package fr.hadesdray.mamaison

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/** Lecture d'un serveur de médias du réseau (Freebox, Jellyfin, MiniDLNA, télés…). */
object Dlna {

    class Element(val id: String, val titre: String, val dossier: Boolean, val lien: String, val type: String, val taille: Long)

    /** Contenu d'un dossier du serveur ("0" = la racine). null si le serveur ne répond pas. */
    fun parcourir(controle: String, objet: String): List<Element>? {
        val corps = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
<ObjectID>${echapper(objet)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>
<StartingIndex>0</StartingIndex><RequestedCount>2000</RequestedCount><SortCriteria></SortCriteria>
</u:Browse></s:Body></s:Envelope>"""
        val reponse = try {
            val c = URL(controle).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 20000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            c.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
            val t = if (c.responseCode in 200..299) c.inputStream.bufferedReader(Charsets.UTF_8).readText() else null
            c.disconnect()
            t
        } catch (e: Exception) { null } ?: return null
        // Le contenu (DIDL) est rangé, échappé, dans la balise Result
        val didl = valeur(reponse, "Result") ?: return emptyList()
        return lireDidl(didl)
    }

    private fun echapper(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun valeur(xml: String, balise: String): String? {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml))
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.substringAfter(':') == balise) return p.nextText()
            p.next()
        }
        return null
    }

    private fun lireDidl(didl: String): List<Element> {
        val l = ArrayList<Element>()
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(didl))
        var id = ""; var titre = ""; var dossier = false; var lien = ""; var type = ""; var taille = 0L
        var dans = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "container", "item" -> {
                        dans = true
                        dossier = p.name.substringAfter(':') == "container"
                        id = p.getAttributeValue(null, "id") ?: ""
                        titre = ""; lien = ""; type = ""; taille = 0
                    }
                    "title" -> if (dans) titre = p.nextText().trim()
                    "res" -> if (dans && lien.isEmpty()) {
                        type = (p.getAttributeValue(null, "protocolInfo") ?: "").split(":").getOrElse(2) { "" }
                        taille = p.getAttributeValue(null, "size")?.toLongOrNull() ?: 0
                        lien = p.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                    "container", "item" -> {
                        if (id.isNotEmpty() && (dossier || lien.isNotEmpty())) l.add(Element(id, titre.ifEmpty { "Sans nom" }, dossier, lien, type, taille))
                        dans = false
                    }
                }
            }
            p.next()
        }
        return l
    }
}

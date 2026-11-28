package fr.hadesdray.mamaison

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Garde ce téléphone visible sur le Wi-Fi de la maison tant que le partage est activé
 * (une notification « Partage actif » le rappelle ; on l'arrête depuis Réglages > Partager ce téléphone).
 */
class PartageService : Service() {

    private var annonce: NsdManager.RegistrationListener? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (!Relais.partageActif(this)) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("partage", "Partage sur le Wi-Fi", NotificationManager.IMPORTANCE_LOW))
        val ouvrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "partage") else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Ma Maison : partage actif")
            .setContentText("Tes photos et vidéos sont visibles sur le Wi-Fi de la maison (avec le code).")
            .setContentIntent(ouvrir)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        Relais.demarrer(this)
        annoncer()
        return START_STICKY
    }

    /** Annonce Bonjour : les autres appareils avec Ma Maison trouvent ce téléphone tout seuls. */
    private fun annoncer() {
        if (annonce != null) return
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo()
        info.serviceName = Relais.nomTelephone()
        info.serviceType = "_mamaison._tcp"
        info.port = Relais.PORT
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); annonce = l } catch (e: Exception) { }
    }

    override fun onDestroy() {
        annonce?.let { l -> try { (getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } catch (e: Exception) { } }
        annonce = null
        super.onDestroy()
    }
}

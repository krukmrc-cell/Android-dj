package com.krukkex.radio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Ontvangt push-meldingen van de backend (via Firebase Cloud Messaging) en toont
 * ze als Android-notificatie. Werkt ook als de app op de achtergrond of dicht is.
 */
class KrukkexMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "krukkex_default"
        const val CHANNEL_NAME = "KrukkeX meldingen"
        const val PREFS = "krukkex_push"
        const val KEY_TOKEN = "fcm_token"

        /** Maakt het notificatiekanaal aan (idempotent; vereist op Android O+). */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Meldingen van KrukkeX Radio (chat-tags en aankondigingen)"
                    }
                    mgr.createNotificationChannel(channel)
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        // Bewaar het token zodat MainActivity het na het laden naar de WebView kan
        // sturen, die het via de socket bij de backend registreert.
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notif = message.notification
        val title = notif?.title ?: message.data["title"] ?: "KrukkeX"
        val body = notif?.body ?: message.data["body"] ?: ""

        ensureChannel(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)

        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        try {
            NotificationManagerCompat.from(this).notify(id, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS niet verleend — stil negeren.
        }
    }
}

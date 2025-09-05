package com.futanium.box.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.futanium.box.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Esse token identifica o app instalado no dispositivo.
        // Você pode salvar em um backend se quiser enviar notificações específicas.
        android.util.Log.d("FCM", "Novo token: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Pega título e corpo da notificação
        val title = message.notification?.title ?: message.data["title"] ?: "Futanium"
        val body = message.notification?.body ?: message.data["body"] ?: "Você tem uma novidade!"

        // Mostra a notificação
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "futanium_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android 8+ precisa registrar o canal de notificação
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Futanium Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(ch)
        }

        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notif = NotificationCompat.Builder(this, channelId)
    .setSmallIcon(R.drawable.ic_stat_notify) // 🔔 aparece na status bar
    .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_futanium_logo)) // 🟦 logo grande colorido
    .setContentTitle(title)
    .setContentText(body)
    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
    .setAutoCancel(true)
    .setSound(sound)
    .build()

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }
}
package com.futanium.box.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.futanium.box.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        android.util.Log.d("FCM", "Novo token: $token")
        // Se tiver backend, envie o token para lá
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "Futanium"
        val body  = message.notification?.body  ?: message.data["body"]  ?: "Você tem uma novidade!"
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "futanium_channel_v2" // novo canal (para lockscreen e prioridade)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Futanium Alerts",
                NotificationManager.IMPORTANCE_HIGH // alto para aparecer na lockscreen
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }

        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val largeIcon = BitmapFactory.decodeResource(resources, R.drawable.ic_futanium_logo)

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notify) // sininho (status bar)
            .setLargeIcon(largeIcon)                 // logo grande colorido
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(sound)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // pré-Oreo
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // lockscreen
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }
}
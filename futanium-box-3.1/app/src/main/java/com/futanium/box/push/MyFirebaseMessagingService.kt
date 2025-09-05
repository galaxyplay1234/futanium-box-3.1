private fun showNotification(title: String, body: String) {
    val channelId = "futanium_channel_v2" // novo ID
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel(
            channelId,
            "Futanium Alerts",
            NotificationManager.IMPORTANCE_HIGH // ↑ prioridade alta
        ).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC // ↑ mostrar na lockscreen
        }
        nm.createNotificationChannel(ch)
    }

    val sound = android.media.RingtoneManager.getDefaultUri(
        android.media.RingtoneManager.TYPE_NOTIFICATION
    )

    val notif = androidx.core.app.NotificationCompat.Builder(this, channelId)
        .setSmallIcon(com.futanium.box.R.drawable.ic_stat_notify)
        .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, com.futanium.box.R.drawable.ic_futanium_logo))
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .setSound(sound)
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH) // ↑ pré-Oreo
        .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC) // ↑ lockscreen
        .setShowWhen(true)
        .setCategory(android.app.Notification.CATEGORY_MESSAGE)
        .build()

    nm.notify(System.currentTimeMillis().toInt(), notif)
}
package fi.oma.numeron

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat

class ShiftAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shiftTitle = intent.getStringExtra("SHIFT_TITLE") ?: "Työvuoro"
        val isEnding = intent.getBooleanExtra("IS_ENDING", false)
        val rawBtnText = if (isEnding) "Leimaa Ulos" else "Leimaa Sisään"

        // Värjätään ilmoituksen nappiteksti vihreäksi
        val greenButtonText = SpannableString(rawBtnText).apply {
            setSpan(ForegroundColorSpan(Color.parseColor("#2E7D32")), 0, length, 0)
        }

        val channelId = "numeron_shift_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Työvuoromuistutukset",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiljaiset leimausmuistutukset"
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val clickIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("AUTO_STAMP", true)
            putExtra("IS_ENDING", isEnding)
        }
        val reqCode = (System.currentTimeMillis() / 1000L).toInt()
        val pendingIntent = PendingIntent.getActivity(
            context,
            reqCode,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(if (isEnding) "Vuoro päättyy 30 min kuluttua" else "Vuoro alkaa 30 min kuluttua")
            .setContentText(shiftTitle)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(60 * 60 * 1000L) // Häviää itsestään tunnin kuluttua
            .setContentIntent(pendingIntent)
            .addAction(0, greenButtonText, pendingIntent)
            .build()

        notificationManager.notify(if (isEnding) 2002 else 1001, notification)
    }
}

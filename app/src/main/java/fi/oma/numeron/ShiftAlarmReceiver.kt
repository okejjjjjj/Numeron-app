package fi.oma.numeron

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

class ShiftAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shiftTitle = intent.getStringExtra("SHIFT_TITLE") ?: "Työvuoro"
        val shiftTime = intent.getStringExtra("SHIFT_TIME") ?: ""
        val isEnding = intent.getBooleanExtra("IS_ENDING", false)

        val titleText = if (isEnding) {
            if (shiftTime.isNotEmpty()) "Vuoro päättyy klo $shiftTime" else "Vuoro päättyy pian"
        } else {
            if (shiftTime.isNotEmpty()) "Vuoro alkaa klo $shiftTime" else "Vuoro alkaa pian"
        }
        val btnText = if (isEnding) "LEIMAA ULOS" else "LEIMAA SISÄÄN"

        val channelId = "numeron_shift_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Työvuoromuistutukset", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Hiljaiset leimausmuistutukset"
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val baseReq = (System.currentTimeMillis() / 1000L).toInt()

        // 1. Pelkkä ilmoituksen napautus avaa sovelluksen ilman välitöntä leimausta
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, baseReq, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Vihreän napin painallus käynnistää automaattileimauksen
        val stampIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("AUTO_STAMP", true)
            putExtra("IS_ENDING", isEnding)
        }
        val stampPi = PendingIntent.getActivity(
            context, baseReq + 1, stampIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val customView = RemoteViews(context.packageName, R.layout.notification_shift).apply {
            setTextViewText(R.id.notif_title, titleText)
            setTextViewText(R.id.notif_text, shiftTitle)
            setTextViewText(R.id.notif_btn, btnText)
            setOnClickPendingIntent(R.id.notif_btn, stampPi)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(60 * 60 * 1000L)
            .setContentIntent(openPi)
            .build()

        nm.notify(if (isEnding) 2002 else 1001, notification)
    }
}

package fi.oma.numeron

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

class ShiftAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val shiftTitle = intent.getStringExtra("SHIFT_TITLE") ?: "Työvuoro"
        val isEnding = intent.getBooleanExtra("IS_ENDING", false)
        val shiftTime = intent.getStringExtra("SHIFT_TIME") ?: ""

        val titleText = if (isEnding) {
            if (shiftTime.isNotEmpty()) "Vuoro päättyy klo $shiftTime" else "Vuoro päättyy"
        } else {
            if (shiftTime.isNotEmpty()) "Vuoro alkaa klo $shiftTime" else "Vuoro alkaa"
        }
        val btnText = if (isEnding) "LEIMAA ULOS" else "LEIMAA SISÄÄN"

        showOrUpdateNotification(
            context = context,
            isEnding = isEnding,
            titleText = titleText,
            subText = shiftTitle,
            btnText = btnText,
            showButton = true,
            autoCancelMs = 60 * 60 * 1000L
        )
    }

    companion object {
        const val CHANNEL_ID = "numeron_shift_channel"

        fun showOrUpdateNotification(
            context: Context,
            isEnding: Boolean,
            titleText: String,
            subText: String,
            btnText: String,
            showButton: Boolean,
            autoCancelMs: Long = 60 * 60 * 1000L
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "Työvuoromuistutukset", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Hiljaiset leimausmuistutukset"
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }

            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("AUTO_STAMP", true)
                putExtra("IS_ENDING", isEnding)
            }
            val reqCode = if (isEnding) 2002 else 1001
            val pi = PendingIntent.getActivity(
                context, reqCode, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val customView = RemoteViews(context.packageName, R.layout.notification_shift).apply {
                setTextViewText(R.id.notif_title, titleText)
                setTextViewText(R.id.notif_text, subText)
                setTextViewText(R.id.notif_btn, btnText)
                setViewVisibility(R.id.notif_btn, if (showButton) View.VISIBLE else View.GONE)
                if (showButton) {
                    setOnClickPendingIntent(R.id.notif_btn, pi)
                }
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setCustomContentView(customView)
                .setCustomBigContentView(customView)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setTimeoutAfter(autoCancelMs)
                .setContentIntent(pi)
                .build()

            nm.notify(reqCode, notification)
        }
    }
}

import os

files = {
    "app/src/main/AndroidManifest.xml": """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_CALENDAR" />
    <uses-permission android:name="android.permission.WRITE_CALENDAR" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />

    <application
        android:allowBackup="false"
        android:icon="@drawable/ic_launcher"
        android:roundIcon="@drawable/ic_launcher"
        android:label="Numeron"
        android:supportsRtl="true"
        android:theme="@style/Theme.Numeron"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <receiver android:name=".ShiftAlarmReceiver" android:exported="false" />

    </application>
</manifest>
""",

    "app/src/main/res/layout/notification_shift.xml": """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="64dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingStart="12dp"
    android:paddingEnd="12dp"
    android:paddingTop="6dp"
    android:paddingBottom="6dp">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical"
        android:paddingEnd="8dp">

        <TextView
            android:id="@+id/notif_title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Vuoro alkaa klo 07:00"
            android:textStyle="bold"
            android:textSize="14sp"
            android:textColor="#FFFFFF"
            android:singleLine="true" />

        <TextView
            android:id="@+id/notif_text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Työvuoro"
            android:textSize="12sp"
            android:textColor="#CCCCCC"
            android:singleLine="true" />
    </LinearLayout>

    <TextView
        android:id="@+id/notif_btn"
        android:layout_width="wrap_content"
        android:layout_height="40dp"
        android:background="#2E7D32"
        android:gravity="center"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:text="LEIMAA SISÄÄN"
        android:textColor="#FFFFFF"
        android:textStyle="bold"
        android:textSize="12sp" />
</LinearLayout>
""",

    "app/src/main/res/drawable/ic_launcher.xml": """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#0277BD"
        android:pathData="M0,0h108v108h-108z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M54,24A30,30 0,1 0,84 54A30,30 0,0 0,54 24ZM54,78A24,24 0,1 1,78 54A24,24 0,0 1,54 78ZM56,38H51V56L66,65L68.5,61L56,53.5V38Z" />
</vector>
""",

    "app/src/main/res/values/themes.xml": """<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.Numeron" parent="Theme.MaterialComponents.DayNight.NoActionBar">
        <item name="android:windowBackground">#121212</item>
        <item name="android:statusBarColor">#121212</item>
        <item name="android:navigationBarColor">#1A1A1A</item>
        <item name="android:windowSplashScreenBackground" tools:targetApi="31">#121212</item>
        <item name="android:windowSplashScreenAnimatedIcon" tools:targetApi="31">@drawable/ic_launcher</item>
    </style>
</resources>
""",

    "app/src/main/java/fi/oma/numeron/ShiftAlarmReceiver.kt": """package fi.oma.numeron

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
        val actionType = intent.getStringExtra("ACTION_TYPE") ?: "STAMP"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (actionType == "CHECK_NEW_LIST") {
            val checkIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("AUTO_SYNC_NEW_LIST", true)
            }
            val pi = PendingIntent.getActivity(
                context, 9991, checkIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("numeron_list_channel", "Uudet työvuorolistat", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val notif = NotificationCompat.Builder(context, "numeron_list_channel")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Uusi työvuorolista saatavilla!")
                .setContentText("Napauta tästä, niin uudet vuorot haetaan suoraan kalenteriin.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(3003, notif)
            return
        }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Työvuoromuistutukset", NotificationManager.IMPORTANCE_LOW).apply {
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
        val reqCode = (System.currentTimeMillis() / 1000L).toInt()
        val pi = PendingIntent.getActivity(
            context, reqCode, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val customView = RemoteViews(context.packageName, R.layout.notification_shift).apply {
            setTextViewText(R.id.notif_title, titleText)
            setTextViewText(R.id.notif_text, shiftTitle)
            setTextViewText(R.id.notif_btn, btnText)
            setOnClickPendingIntent(R.id.notif_btn, pi)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(60 * 60 * 1000L)
            .setContentIntent(pi)
            .build()

        nm.notify(if (isEnding) 2002 else 1001, notification)
    }
}
"""
}

for path, content in files.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
print("Osa 1 tiedostoista luotu!")

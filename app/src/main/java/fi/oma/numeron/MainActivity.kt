package fi.oma.numeron

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // VAIHDA TÄHÄN TYÖPAIKKASI NUMERON-OSOITE:
    private val NUMERON_BASE_URL = "https://siunsote.numeronwfm.com/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(CalendarBridge(), "AndroidBridge")
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }

        val btnStamp = Button(this).apply {
            text = "Leimaus"
            setOnClickListener { webView.loadUrl(NUMERON_BASE_URL) }
        }

        val btnWishes = Button(this).apply {
            text = "Toiveet"
            setOnClickListener {
                // Voit vaihtaa tähän suoran URL-polun tai JS-klikkauksen
                webView.loadUrl(NUMERON_BASE_URL)
            }
        }

        val btnSync = Button(this).apply {
            text = "Kalenteriin"
            setOnClickListener { checkPermissionsAndSync() }
        }

        buttonBar.addView(btnStamp)
        buttonBar.addView(btnWishes)
        buttonBar.addView(btnSync)

        rootLayout.addView(webView)
        rootLayout.addView(buttonBar)
        setContentView(rootLayout)

        webView.loadUrl(NUMERON_BASE_URL)
    }

    private fun checkPermissionsAndSync() {
        val readPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
        val writePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)

        if (readPerm != PackageManager.PERMISSION_GRANTED || writePerm != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                100
            )
        } else {
            extractShiftsFromPage()
        }
    }

    private fun extractShiftsFromPage() {
        // Lukee koko näkyvän sivun tekstin ja lähettää sen Kotlinin jäsenneltäväksi.
        // Poimii automaattisesti muodot kuten "17.10.2026 07:00 - 15:15" tai "17.10.2026 07.00-15.15"
        val jsCode = """
            (function() {
                var text = document.body.innerText;
                AndroidBridge.processPageText(text);
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    inner class CalendarBridge {
        @JavascriptInterface
        fun processPageText(pageText: String) {
            runOnUiThread {
                val count = parseAndInsertShifts(pageText)
                Toast.makeText(
                    this@MainActivity,
                    "Lisätty $count uutta työvuoroa kalenteriin",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun parseAndInsertShifts(text: String): Int {
        // Tunnistaa päivämäärän (esim. 17.10.2026) ja kellonajan (esim. 07.00-15.15 tai 07:00 - 15:15)
        val regex = Regex("""(\d{1,2}\.\d{1,2}\.\d{4})\s+(\d{1,2}[:.]\d{2})\s*-\s*(\d{1,2}[:.]\d{2})(?:\s+([^\n\r]+))?""")
        val matches = regex.findAll(text)
        var addedCount = 0

        val calendarId = getPrimaryCalendarId() ?: return 0
        val formatter = DateTimeFormatter.ofPattern("d.M.yyyy H:mm")

        for (match in matches) {
            val dateStr = match.groupValues[1]
            val startStr = match.groupValues[2].replace('.', ':')
            val endStr = match.groupValues[3].replace('.', ':')
            val rawLocation = match.groupValues[4].trim()

            // Oletusyksikkö, jos riviltä ei suoraan löydy nimeä
            val unitName = if (rawLocation.isNotEmpty()) rawLocation else "Hoitokoti onni"
            val title = "työvuoro, $unitName"

            try {
                val startDateTime = LocalDateTime.parse("$dateStr $startStr", formatter)
                var endDateTime = LocalDateTime.parse("$dateStr $endStr", formatter)

                // Jos yövuoro päättyy seuraavan vuorokauden puolella
                if (endDateTime.isBefore(startDateTime)) {
                    endDateTime = endDateTime.plusDays(1)
                }

                val startMillis = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endMillis = endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                if (!eventExists(calendarId, startMillis, endMillis)) {
                    insertCalendarEvent(calendarId, title, startMillis, endMillis)
                    addedCount++
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return addedCount
    }

    private fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.IS_PRIMARY} = 1"
        contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        // Varavaihtoehto: haetaan ensimmäinen muokattava kalenteri
        contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_level} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun eventExists(calendarId: Long, startMillis: Long, endMillis: Long): Boolean {
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} = ? AND ${CalendarContract.Events.DTEND} = ?"
        val selectionArgs = arrayOf(calendarId.toString(), startMillis.toString(), endMillis.toString())

        contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            return cursor.count > 0
        }
        return false
    }

    private fun insertCalendarEvent(calendarId: Long, title: String, startMillis: Long, endMillis: Long) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        }
        contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}


package fi.oma.numeron

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val URL_STAMPING = "https://siunsote.numeronwfm.com/WorktimeStamping"
    private val URL_WISHES = "https://siunsote.numeronwfm.com/PersonRequests/List"
    private val URL_CALENDAR = "https://siunsote.numeronwfm.com/Calendar/Month"

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
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                }
            }
            addJavascriptInterface(CalendarBridge(), "AndroidBridge")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
            weightSum = 4f
        }

        val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 4
            marginEnd = 4
        }

        val btnStamp = Button(this).apply {
            text = "Leimaus"
            textSize = 11f
            layoutParams = btnParams
            setOnClickListener { webView.loadUrl(URL_STAMPING) }
        }

        val btnWishes = Button(this).apply {
            text = "Toiveet"
            textSize = 11f
            layoutParams = btnParams
            setOnClickListener { webView.loadUrl(URL_WISHES) }
        }

        val btnCalendar = Button(this).apply {
            text = "Vuorot"
            textSize = 11f
            layoutParams = btnParams
            setOnClickListener { webView.loadUrl(URL_CALENDAR) }
        }

        val btnSync = Button(this).apply {
            text = "Vie -> GCal"
            textSize = 11f
            layoutParams = btnParams
            setOnClickListener { checkPermissionsAndSync() }
        }

        buttonBar.addView(btnStamp)
        buttonBar.addView(btnWishes)
        buttonBar.addView(btnCalendar)
        buttonBar.addView(btnSync)

        rootLayout.addView(webView)
        rootLayout.addView(buttonBar)
        setContentView(rootLayout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Avataan suoraan leimaussivu käynnistyksessä
        webView.loadUrl(URL_STAMPING)
    }

    private fun checkPermissionsAndSync() {
        val currentUrl = webView.url ?: ""
        if (!currentUrl.contains("/Calendar/")) {
            Toast.makeText(
                this,
                "Avataan ensin vuorokalenteri. Paina 'Vie -> GCal' uudelleen, kun sivu on latautunut.",
                Toast.LENGTH_LONG
            ).show()
            webView.loadUrl(URL_CALENDAR)
            return
        }

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                extractShiftsFromPage()
            } else {
                Toast.makeText(this, "Kalenterioikeudet vaaditaan vuorojen tallentamiseen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractShiftsFromPage() {
        val jsCode = """
            (function() {
                var text = document.body ? document.body.innerText : "";
                var iframes = document.querySelectorAll('iframe');
                for (var i = 0; i < iframes.length; i++) {
                    try {
                        if (iframes[i].contentDocument && iframes[i].contentDocument.body) {
                            text += "\n" + iframes[i].contentDocument.body.innerText;
                        }
                    } catch (e) {}
                }
                AndroidBridge.processPageText(text, window.location.href);
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    inner class CalendarBridge {
        @JavascriptInterface
        fun processPageText(pageText: String, currentUrl: String) {
            runOnUiThread {
                val calendarId = getPrimaryCalendarId()
                if (calendarId == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Virhe: Puhelimesta ei löytynyt Google Kalenteria.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                val result = parseAndInsertShifts(pageText, currentUrl, calendarId)
                val foundCount = result.first
                val addedCount = result.second

                if (foundCount == 0) {
                    val preview = if (pageText.length > 600) pageText.substring(0, 600) + "..." else pageText
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Vuoroja ei tunnistettu")
                        .setMessage("Sivulta ei löytynyt vuoroja. Tässä näyte sivun tekstistä (voit kopioida tai ottaa kuvakaappauksen, niin säädetään luku täsmälliseksi):\n\n$preview")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Löytyi $foundCount vuoroa, lisätty $addedCount uutta kalenteriin.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun parseAndInsertShifts(text: String, currentUrl: String, calendarId: Long): Pair<Int, Int> {
        var foundCount = 0
        var addedCount = 0

        // Tarkistetaan kuukausi ja vuosi osoitteesta (esim. ?month=11&year=2026)
        val now = LocalDate.now()
        val uri = try { Uri.parse(currentUrl) } catch (e: Exception) { null }
        val targetMonth = uri?.getQueryParameter("month")?.toIntOrNull() ?: now.monthValue
        val targetYear = uri?.getQueryParameter("year")?.toIntOrNull() ?: now.year

        val formatter = DateTimeFormatter.ofPattern("d.M.yyyy H:mm")

        // Tapa 1: Täysi tai puolipitkä päivämäärä (esim. "17.10.2026 07:00 - 15:15" tai "17.10. 07.00-15.15")
        val fullDateRegex = Regex("""(\d{1,2}\.\d{1,2}\.(?:\d{4})?)[\s\t]*\r?\n?[\s\t]*(\d{1,2}[:.]\d{2})[\s\t]*[-–][\s\t]*(\d{1,2}[:.]\d{2})(?:[\t ]+([^\n\r\d]+))?""")
        val fullMatches = fullDateRegex.findAll(text).toList()

        if (fullMatches.isNotEmpty()) {
            for (match in fullMatches) {
                foundCount++
                var dateStr = match.groupValues[1].trim()
                if (dateStr.endsWith(".")) {
                    dateStr += targetYear.toString()
                }

                val startStr = match.groupValues[2].trim().replace('.', ':')
                val endStr = match.groupValues[3].trim().replace('.', ':')
                val rawLocation = match.groupValues[4].trim()

                if (saveShift(calendarId, dateStr, startStr, endStr, rawLocation, formatter)) {
                    addedCount++
                }
            }
            return Pair(foundCount, addedCount)
        }

        // Tapa 2: Kuukausinäkymä, jossa on pelkkä päivän numero (1-31) ja sen perässä/alla kellonaika
        val dayOnlyRegex = Regex("""(?:\b|^)(\d{1,2})(?:\.)?[\s\t]*\r?\n[\s\t]*(\d{1,2}[:.]\d{2})[\s\t]*[-–][\s\t]*(\d{1,2}[:.]\d{2})(?:[\s\t]*\r?\n?[\t ]*([^\n\r\d]+))?""")
        val dayMatches = dayOnlyRegex.findAll(text).toList()

        for (match in dayMatches) {
            val day = match.groupValues[1].toIntOrNull() ?: continue
            if (day < 1 || day > 31) continue

            foundCount++
            val dateStr = "$day.$targetMonth.$targetYear"
            val startStr = match.groupValues[2].trim().replace('.', ':')
            val endStr = match.groupValues[3].trim().replace('.', ':')
            val rawLocation = match.groupValues[4].trim()

            if (saveShift(calendarId, dateStr, startStr, endStr, rawLocation, formatter)) {
                addedCount++
            }
        }

        return Pair(foundCount, addedCount)
    }

    private fun saveShift(
        calendarId: Long,
        dateStr: String,
        startStr: String,
        endStr: String,
        rawLocation: String,
        formatter: DateTimeFormatter
    ): Boolean {
        val unitName = if (rawLocation.length > 2) rawLocation else "Hoitokoti onni"
        val title = "työvuoro, $unitName"

        return try {
            val startDateTime = LocalDateTime.parse("$dateStr $startStr", formatter)
            var endDateTime = LocalDateTime.parse("$dateStr $endStr", formatter)

            if (endDateTime.isBefore(startDateTime)) {
                endDateTime = endDateTime.plusDays(1)
            }

            val startMillis = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMillis = endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            if (!eventExists(calendarId, startMillis, endMillis)) {
                insertCalendarEvent(calendarId, title, startMillis, endMillis)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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

        contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
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
}

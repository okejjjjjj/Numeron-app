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

    // VAIHDA TÄHÄN TYÖPAIKKASI NUMERON-OSOITE:
    private val NUMERON_BASE_URL = "https://siunsote.numeronwfm.com"

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
            setPadding(8, 8, 8, 8)
        }

        val btnStamp = Button(this).apply {
            text = "Leimaus"
            setOnClickListener { clickElementByText(listOf("leima", "työaika", "kellokortti")) }
        }

        val btnWishes = Button(this).apply {
            text = "Toiveet"
            setOnClickListener { clickElementByText(listOf("toive", "suunnittelu", "vuorotoive")) }
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

        webView.loadUrl(NUMERON_BASE_URL)
    }

    private fun clickElementByText(keywords: List<String>) {
        val jsArray = keywords.joinToString(",") { "'$it'" }
        val jsCode = """
            (function() {
                var words = [$jsArray];
                var docs = [document];
                var iframes = document.querySelectorAll('iframe');
                for (var i = 0; i < iframes.length; i++) {
                    try { if (iframes[i].contentDocument) docs.push(iframes[i].contentDocument); } catch(e) {}
                }
                for (var d = 0; d < docs.length; d++) {
                    var elements = docs[d].querySelectorAll('a, button, span, div, li, input[type="button"]');
                    for (var w = 0; w < words.length; w++) {
                        for (var i = 0; i < elements.length; i++) {
                            var el = elements[i];
                            var txt = (el.innerText || el.value || "").trim().toLowerCase();
                            if (txt.length > 0 && txt.length < 30 && txt.indexOf(words[w]) !== -1) {
                                el.click();
                                return "Klikattiin: " + txt;
                            }
                        }
                    }
                }
                return "NOT_FOUND";
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result != null && result.contains("NOT_FOUND")) {
                Toast.makeText(this, "Valikkoa ei löytynyt tältä sivulta, ladataan etusivu...", Toast.LENGTH_SHORT).show()
                webView.loadUrl(NUMERON_BASE_URL)
            }
        }
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
        // Lukee tekstin pääsivulta JA kaikista iframe-kehyksistä
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
                AndroidBridge.processPageText(text);
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    inner class CalendarBridge {
        @JavascriptInterface
        fun processPageText(pageText: String) {
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

                val result = parseAndInsertShifts(pageText, calendarId)
                val foundCount = result.first
                val addedCount = result.second

                if (foundCount == 0) {
                    val preview = if (pageText.length > 500) pageText.substring(0, 500) + "..." else pageText
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Vuoroja ei tunnistettu")
                        .setMessage("Sivulta ei löytynyt päivämääriä ja kellonaikoja. Tässä näyte sivun tekstistä:\n\n$preview")
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

    private fun parseAndInsertShifts(text: String, calendarId: Long): Pair<Int, Int> {
        // Tunnistaa sekä "17.10.2026 07:00 - 15:15" että ilman vuosilukua "17.10. 07:00 - 15:15"
        val regex = Regex("""(\d{1,2}\.\d{1,2}\.(?:\d{4})?)[\s\t]*\r?\n?[\s\t]*(\d{1,2}[:.]\d{2})[\s\t]*[-–][\s\t]*(\d{1,2}[:.]\d{2})(?:[\t ]+([^\n\r\d]+))?""")
        val matches = regex.findAll(text)
        var foundCount = 0
        var addedCount = 0

        val currentYear = LocalDate.now().year
        val formatter = DateTimeFormatter.ofPattern("d.M.yyyy H:mm")

        for (match in matches) {
            foundCount++
            var dateStr = match.groupValues[1].trim()
            if (dateStr.endsWith(".")) {
                dateStr += currentYear.toString()
            }

            val startStr = match.groupValues[2].trim().replace('.', ':')
            val endStr = match.groupValues[3].trim().replace('.', ':')
            val rawLocation = match.groupValues[4].trim()

            val unitName = if (rawLocation.length > 2) rawLocation else "Hoitokoti onni"
            val title = "työvuoro, $unitName"

            try {
                val startDateTime = LocalDateTime.parse("$dateStr $startStr", formatter)
                var endDateTime = LocalDateTime.parse("$dateStr $endStr", formatter)

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
        return Pair(foundCount, addedCount)
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

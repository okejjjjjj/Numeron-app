package fi.oma.numeron

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.time.*
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
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
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
            weightSum = 5f
        }

        fun makeBtn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 10f
            setPadding(2, 8, 2, 8)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 2; marginEnd = 2 }
            setOnClickListener { action() }
        }

        buttonBar.addView(makeBtn("Leimaus") { webView.loadUrl(URL_STAMPING) })
        buttonBar.addView(makeBtn("Toiveet") { webView.loadUrl(URL_WISHES) })
        buttonBar.addView(makeBtn("Vuorot") { webView.loadUrl(URL_CALENDAR) })
        buttonBar.addView(makeBtn("GCal") { checkPermissionsAndSync() })
        buttonBar.addView(makeBtn("Sulje") { finishAndRemoveTask() })

        rootLayout.addView(webView)
        rootLayout.addView(buttonBar)
        setContentView(rootLayout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        webView.loadUrl(URL_STAMPING)
    }

    private fun checkPermissionsAndSync() {
        val currentUrl = webView.url ?: ""
        if (!currentUrl.contains("/Calendar/", ignoreCase = true)) {
            Toast.makeText(this, "Avataan vuorokalenteri. Paina 'GCal' uudelleen sivun latauduttua.", Toast.LENGTH_LONG).show()
            webView.loadUrl(URL_CALENDAR)
            return
        }
        val r = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
        val w = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
        if (r != PackageManager.PERMISSION_GRANTED || w != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 100)
        } else {
            extractShiftsFromPage()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            extractShiftsFromPage()
        }
    }

    private fun extractShiftsFromPage() {
        val js = """
            (function() {
                var bodyText = document.body ? document.body.innerText : "";
                var cells = [];
                var tds = document.querySelectorAll('td');
                if (tds.length > 15) {
                    for (var i = 0; i < tds.length; i++) {
                        var td = tds[i];
                        var cls = (td.className || "").toLowerCase();
                        var isWeekCol = /week|vk|wk/.test(cls) || (td.parentElement && td.parentElement.children.length === 8 && td.cellIndex === 0);
                        if (isWeekCol) continue;
                        var isOther = /other|old|new|disabled|off|outside|gray|grey/.test(cls);
                        var extra = [];
                        var ch = td.querySelectorAll('[title], [data-original-title]');
                        for (var c = 0; c < ch.length; c++) {
                            var t = ch[c].getAttribute('title') || ch[c].getAttribute('data-original-title');
                            if (t) extra.push(t);
                        }
                        cells.push({ text: (td.innerText || "").trim(), isOther: isOther, extra: extra.join(" | ") });
                    }
                }
                AndroidBridge.processCalendarData(JSON.stringify(cells), bodyText, window.location.href);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class CalendarBridge {
        @JavascriptInterface
        fun processCalendarData(cellsJson: String, pageText: String, currentUrl: String) {
            runOnUiThread {
                val calId = getPrimaryCalendarId()
                if (calId == null) {
                    Toast.makeText(this@MainActivity, "Google Kalenteria ei löytynyt.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val (found, added) = parseAndInsertCalendar(cellsJson, pageText, currentUrl, calId)
                if (found == 0) {
                    val prev = if (pageText.length > 600) pageText.substring(0, 600) + "..." else pageText
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Vuoroja ei löytynyt")
                        .setMessage("Näkymästä ei tunnistettu vuoroja. Tekstinäyte:\n\n$prev")
                        .setPositiveButton("OK", null).show()
                } else {
                    Toast.makeText(this@MainActivity, "Löytyi $found merkintää, lisätty/päivitetty $added kalenteriin.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resolveMonthAndYear(pageText: String, currentUrl: String): Pair<Int, Int> {
        val months = mapOf("tammikuu" to 1, "helmikuu" to 2, "maaliskuu" to 3, "huhtikuu" to 4, "toukokuu" to 5, "kesäkuu" to 6, "heinäkuu" to 7, "elokuu" to 8, "syyskuu" to 9, "lokakuu" to 10, "marraskuu" to 11, "joulukuu" to 12)
        val mMatch = Regex("""(tammikuu|helmikuu|maaliskuu|huhtikuu|toukokuu|kesäkuu|heinäkuu|elokuu|syyskuu|lokakuu|marraskuu|joulukuu)\s+(\d{4})""", RegexOption.IGNORE_CASE).find(pageText)
        if (mMatch != null) {
            val m = months[mMatch.groupValues[1].lowercase()]
            val y = mMatch.groupValues[2].toIntOrNull()
            if (m != null && y != null) return Pair(m, y)
        }
        val now = LocalDate.now()
        val uri = try { Uri.parse(currentUrl) } catch (e: Exception) { null }
        return Pair(uri?.getQueryParameter("month")?.toIntOrNull() ?: now.monthValue, uri?.getQueryParameter("year")?.toIntOrNull() ?: now.year)
    }

    private fun parseAndInsertCalendar(cellsJson: String, pageText: String, currentUrl: String, calId: Long): Pair<Int, Int> {
        val (targetMonth, targetYear) = resolveMonthAndYear(pageText, currentUrl)
        val fmt = DateTimeFormatter.ofPattern("d.M.yyyy H:mm")

        val listRes = parseListView(pageText, targetMonth, targetYear, calId, fmt)
        if (listRes.first > 0) return listRes

        var found = 0; var added = 0
        try {
            val arr = JSONArray(cellsJson)
            if (arr.length() > 15) {
                var inMonth = false; var lastDay = 0
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val txt = obj.optString("text", "").trim()
                    val isOther = obj.optBoolean("isOther", false)
                    val extra = obj.optString("extra", "").trim()
                    val day = Regex("""^(\d{1,2})\b""").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (day !in 1..31) continue

                    if (!inMonth) {
                        if (day == 1) { inMonth = true; lastDay = 1 } else continue
                    } else {
                        if (day < lastDay && lastDay >= 20) break
                        if (day == lastDay + 1 || day == lastDay) lastDay = day else continue
                    }
                    if (isOther) continue

                    val res = processDayContent(calId, day, targetMonth, targetYear, txt, extra, fmt)
                    found += res.first; added += res.second
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return Pair(found, added)
    }

    private fun parseListView(pageText: String, targetMonth: Int, targetYear: Int, calId: Long, fmt: DateTimeFormatter): Pair<Int, Int> {
        var found = 0; var added = 0
        val dateRe = Regex("""(?:(?:ma|ti|ke|to|pe|la|su|maanantai|tiistai|keskiviikko|torstai|perjantai|lauantai|sunnuntai)[\s.,]+(\d{1,2})\.(\d{1,2})(?:\.(\d{4})?)?|(?<![:\d.])(\d{1,2})\.(\d{1,2})\.(\d{4})?)(?!\d)""", RegexOption.IGNORE_CASE)
        val matches = dateRe.findAll(pageText).toList()
        if (matches.size >= 2) {
            for (i in matches.indices) {
                val m = matches[i]
                val d = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: continue
                val mo = (m.groupValues[2].ifEmpty { m.groupValues[5] }).toIntOrNull() ?: continue
                val yr = (m.groupValues[3].ifEmpty { m.groupValues[6] }).toIntOrNull() ?: targetYear
                if (d !in 1..31 || mo !in 1..12) continue

                val sIdx = m.range.last + 1
                val eIdx = if (i + 1 < matches.size) matches[i + 1].range.first else minOf(sIdx + 400, pageText.length)
                if (sIdx >= eIdx) continue
                val res = processListBlock(calId, d, mo, yr, pageText.substring(sIdx, eIdx).trim(), fmt)
                found += res.first; added += res.second
            }
            if (found > 0) return Pair(found, added)
        }

        if (!Regex("""Vk\.?\s+Ma\s+Ti\s+Ke""", RegexOption.IGNORE_CASE).containsMatchIn(pageText)) {
            val altRe = Regex("""(?:^|\n)[\t ]*(?:(?:ma|ti|ke|to|pe|la|su)[a-zäöå.]*[\t ]+(\d{1,2})|(\d{1,2})\.?[\t ]+(?:ma|ti|ke|to|pe|la|su)[a-zäöå.]*)\b""", RegexOption.IGNORE_CASE)
            val altM = altRe.findAll(pageText).toList()
            if (altM.size >= 2) {
                for (i in altM.indices) {
                    val m = altM[i]
                    val d = m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull() ?: continue
                    if (d !in 1..31) continue
                    val sIdx = m.range.last + 1
                    val eIdx = if (i + 1 < altM.size) altM[i + 1].range.first else minOf(sIdx + 400, pageText.length)
                    if (sIdx >= eIdx) continue
                    val res = processListBlock(calId, d, targetMonth, targetYear, pageText.substring(sIdx, eIdx).trim(), fmt)
                    found += res.first; added += res.second
                }
            }
        }
        return Pair(found, added)
    }

    private fun processListBlock(calId: Long, d: Int, m: Int, y: Int, block: String, fmt: DateTimeFormatter): Pair<Int, Int> {
        val sMatches = Regex("""(\d{1,2}[:.]\d{2})[\s\t]*[-–][\s\t]*(\d{1,2}[:.]\d{2})""").findAll(block).toList()
        if (sMatches.isNotEmpty()) {
            var added = 0
            for (idx in sMatches.indices) {
                val sMatch = sMatches[idx]
                val s = sMatch.groupValues[1].trim().replace('.', ':')
                val e = sMatch.groupValues[2].trim().replace('.', ':')
                val nextStart = if (idx + 1 < sMatches.size) sMatches[idx + 1].range.first else block.length
                val after = block.substring(sMatch.range.last + 1, nextStart).trim()
                val details = if (after.isNotEmpty()) after else block.substring(0, sMatch.range.first).trim()
                if (saveWorkShift(calId, "$d.$m.$y", s, e, details, fmt)) added++
            }
            return Pair(sMatches.size, added)
        }
        if (block.lowercase().let { it.contains("loma") || Regex("""\bvl\b""").containsMatchIn(it) }) {
            return Pair(1, if (saveVacationDay(calId, d, m, y)) 1 else 0)
        }
        return Pair(0, 0)
    }

    private fun processDayContent(calId: Long, d: Int, m: Int, y: Int, txt: String, extra: String, fmt: DateTimeFormatter): Pair<Int, Int> {
        val shifts = Regex("""(\d{1,2}[:.]\d{2})[\s\t\r\n]*[-–][\s\t\r\n]*(\d{1,2}[:.]\d{2})""").findAll(txt).toList()
        if (shifts.isNotEmpty()) {
            var added = 0
            for (sh in shifts) {
                val s = sh.groupValues[1].trim().replace('.', ':')
                val e = sh.groupValues[2].trim().replace('.', ':')
                val after = txt.substring(sh.range.last + 1).trim()
                val comb = listOf(after, extra).filter { it.isNotEmpty() }.joinToString("\n")
                if (saveWorkShift(calId, "$d.$m.$y", s, e, comb, fmt)) added++
            }
            return Pair(shifts.size, added)
        }
        val rest = "${txt.replaceFirst(Regex("""^\d{1,2}\s*"""), "")} $extra".lowercase()
        if (rest.contains("loma") || Regex("""\bvl\b""").containsMatchIn(rest)) {
            return Pair(1, if (saveVacationDay(calId, d, m, y)) 1 else 0)
        }
        return Pair(0, 0)
    }

    private fun extractLocAndDesc(startStr: String, raw: String): Pair<String, String> {
        val lines = raw.lines().map { it.trim() }.filter {
            it.length > 2 && !it.equals("Uusi", true) && !it.equals("Valinnat", true) && !it.startsWith("Vk", true) && !Regex("""^\d{1,2}$""").matches(it)
        }
        if (lines.size >= 2) {
            val codeLine = lines.firstOrNull { Regex("""^\d{3,5}\b""").containsMatchIn(it) }
            val roleLine = lines.firstOrNull { it != codeLine }
            return Pair(codeLine ?: lines[0], roleLine ?: lines[1])
        } else if (lines.size == 1) {
            return Pair(lines[0], lines[0])
        }
        val h = startStr.substringBefore(":").toIntOrNull() ?: 7
        val type = if (h < 11) "aamu" else if (h < 18) "ilta" else "yö"
        return Pair("", "$type hoitaja")
    }

    private fun saveWorkShift(calId: Long, dateStr: String, startStr: String, endStr: String, raw: String, fmt: DateTimeFormatter): Boolean {
        return try {
            val sDt = LocalDateTime.parse("$dateStr $startStr", fmt)
            var eDt = LocalDateTime.parse("$dateStr $endStr", fmt)
            if (eDt.isBefore(sDt)) eDt = eDt.plusDays(1)
            val sMs = sDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val eMs = eDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val (loc, desc) = extractLocAndDesc(startStr, raw)
            val exId = findEventId(calId, sMs, eMs)
            if (exId == null) {
                insertEvent(calId, "Työvuoro", loc, desc, sMs, eMs, false)
                true
            } else if (loc.isNotEmpty()) {
                updateEvent(exId, loc, desc)
            } else false
        } catch (e: Exception) { false }
    }

    private fun saveVacationDay(calId: Long, d: Int, m: Int, y: Int): Boolean {
        return try {
            val date = LocalDate.of(y, m, d)
            val sMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val eMs = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            if (!allDayExists(calId, "Loma", sMs)) {
                insertEvent(calId, "Loma", "", "Vuosiloma", sMs, eMs, true)
                true
            } else false
        } catch (e: Exception) { false }
    }

    private fun getPrimaryCalendarId(): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID)
        contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, "${CalendarContract.Calendars.IS_PRIMARY} = 1", null, null)?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null, null)?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return null
    }

    private fun findEventId(calId: Long, sMs: Long, eMs: Long): Long? {
        val sel = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} = ? AND ${CalendarContract.Events.DTEND} = ? AND ${CalendarContract.Events.DELETED} = 0"
        contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), sel, arrayOf(calId.toString(), sMs.toString(), eMs.toString()), null)?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return null
    }

    private fun updateEvent(eventId: Long, loc: String, desc: String): Boolean {
        var curLoc = ""
        contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.EVENT_LOCATION), "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()), null)?.use {
            if (it.moveToFirst()) curLoc = it.getString(0) ?: ""
        }
        if (curLoc == loc) return false
        val v = ContentValues().apply {
            put(CalendarContract.Events.EVENT_LOCATION, loc)
            put(CalendarContract.Events.DESCRIPTION, desc)
        }
        return contentResolver.update(CalendarContract.Events.CONTENT_URI, v, "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString())) > 0
    }

    private fun allDayExists(calId: Long, title: String, sMs: Long): Boolean {
        val sel = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} = ? AND ${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DELETED} = 0"
        contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), sel, arrayOf(calId.toString(), sMs.toString(), title), null)?.use {
            return it.count > 0
        }
        return false
    }

    private fun insertEvent(calId: Long, title: String, loc: String, desc: String, sMs: Long, eMs: Long, allDay: Boolean) {
        val v = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, sMs)
            put(CalendarContract.Events.DTEND, eMs)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.EVENT_LOCATION, loc)
            put(CalendarContract.Events.DESCRIPTION, desc)
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else ZoneId.systemDefault().id)
        }
        contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
    }
}
// LOPPU

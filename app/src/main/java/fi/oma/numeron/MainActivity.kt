package fi.oma.numeron

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.text.InputType
import android.view.Gravity
import android.view.View
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
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnStamp: Button
    private lateinit var btnWishes: Button
    private lateinit var btnCalendar: Button
    private lateinit var btnSync: Button
    private lateinit var btnClose: Button

    private var pendingAutoStamp = false
    private var pendingStampIsEnding = false
    private var waitingForListViewSync = false
    private var lastAutoLoginAttempt = 0L

    private val URL_STAMPING = "https://siunsote.numeronwfm.com/WorktimeStamping"
    private val URL_WISHES = "https://siunsote.numeronwfm.com/PersonRequests/List"
    private val URL_CALENDAR = "https://siunsote.numeronwfm.com/Calendar/Month"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.parseColor("#121212"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    val msg = (message ?: "").lowercase()
                    if (msg.contains("istunto") || msg.contains("session") || msg.contains("vanhentu") || msg.contains("kirjaudu")) {
                        result?.confirm()
                        view?.loadUrl(URL_STAMPING)
                        return true
                    }
                    return super.onJsAlert(view, url, message, result)
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    val msg = (message ?: "").lowercase()
                    if (msg.contains("istunto") || msg.contains("session") || msg.contains("vanhentu") || msg.contains("kirjaudu")) {
                        result?.confirm()
                        view?.loadUrl(URL_STAMPING)
                        return true
                    }
                    return super.onJsConfirm(view, url, message, result)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    val currentUrl = url ?: ""
                    updateNavigationState(currentUrl)
                    tryAutoLoginIfNeeded()

                    if (pendingAutoStamp && currentUrl.contains("/WorktimeStamping", ignoreCase = true) && !currentUrl.contains("login", ignoreCase = true)) {
                        pendingAutoStamp = false
                        performAutoStamp(pendingStampIsEnding)
                    } else if (waitingForListViewSync && currentUrl.contains("/Calendar/", ignoreCase = true)) {
                        waitingForListViewSync = false
                        webView.postDelayed({ extractShiftsFromPage(forceList = false) }, 700)
                    }
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
            setPadding(10, 14, 10, 14)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }

        fun createNavButton(label: String, action: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 11.5f
                setTextColor(Color.WHITE)
                isAllCaps = false
                setPadding(4, 6, 4, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 5
                    marginEnd = 5
                }
                setOnClickListener { action() }
            }
        }

        btnStamp = createNavButton("Leimaus") { webView.loadUrl(URL_STAMPING) }.apply {
            setOnLongClickListener {
                showCredentialsDialog()
                true
            }
        }
        btnWishes = createNavButton("Toiveet") { webView.loadUrl(URL_WISHES) }
        btnCalendar = createNavButton("Vuorot") { webView.loadUrl(URL_CALENDAR) }
        btnSync = createNavButton("Vie kalenteriin") { checkPermissionsAndSync() }.apply {
            visibility = View.GONE
        }
        btnClose = createNavButton("Sulje") { finishAndRemoveTask() }

        buttonBar.addView(btnStamp)
        buttonBar.addView(btnWishes)
        buttonBar.addView(btnCalendar)
        buttonBar.addView(btnSync)
        buttonBar.addView(btnClose)

        rootLayout.addView(webView)
        rootLayout.addView(buttonBar)
        setContentView(rootLayout)

        updateNavigationState(URL_STAMPING)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        val prefs = getSharedPreferences("numeron_prefs", MODE_PRIVATE)
        if (!prefs.contains("asked_creds")) {
            prefs.edit().putBoolean("asked_creds", true).apply()
            showCredentialsDialog()
        }

        webView.loadUrl(URL_STAMPING)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            tryAutoLoginIfNeeded()
        }
    }

    private fun styleButton(btn: Button, bgColor: String, isSelected: Boolean) {
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(Color.parseColor(bgColor))
            if (isSelected) {
                setStroke(4, Color.WHITE)
            }
        }
        btn.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        btn.alpha = if (isSelected) 1.0f else 0.85f
    }

    private fun updateNavigationState(url: String) {
        val u = if (url.isNotEmpty()) url else (webView.url ?: "")
        val isCal = u.contains("/Calendar/", ignoreCase = true)
        val isWishes = u.contains("/PersonRequests/", ignoreCase = true)
        val isStamp = u.contains("/WorktimeStamping", ignoreCase = true) || (!isCal && !isWishes)

        btnCalendar.visibility = if (isCal) View.GONE else View.VISIBLE
        btnSync.visibility = if (isCal) View.VISIBLE else View.GONE

        styleButton(btnStamp, if (isStamp) "#0288D1" else "#2C2C2C", isStamp)
        styleButton(btnWishes, if (isWishes) "#0288D1" else "#2C2C2C", isWishes)
        styleButton(btnCalendar, "#2C2C2C", false)
        styleButton(btnSync, "#2E7D32", true)
        styleButton(btnClose, "#6A2320", false)
    }

    private fun showCredentialsDialog() {
        val prefs = getSharedPreferences("numeron_prefs", MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val userField = EditText(this).apply {
            hint = "Käyttäjätunnus"
            setText(prefs.getString("username", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passField = EditText(this).apply {
            hint = "Salasana"
            setText(prefs.getString("password", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(userField)
        layout.addView(passField)

        AlertDialog.Builder(this)
            .setTitle("Automaattinen kirjautuminen")
            .setMessage("Tallenna tunnukset, jos haluat sovelluksen kirjautuvan automaattisesti uloskirjautumisen jälkeen (voit avata tämän myöhemmin pitämällä Leimaus-nappia pohjassa).")
            .setView(layout)
            .setPositiveButton("Tallenna") { _, _ ->
                prefs.edit()
                    .putString("username", userField.text.toString().trim())
                    .putString("password", passField.text.toString())
                    .apply()
                Toast.makeText(this, "Tunnukset tallennettu", Toast.LENGTH_SHORT).show()
                tryAutoLoginIfNeeded()
            }
            .setNeutralButton("Tyhjennä") { _, _ ->
                prefs.edit().remove("username").remove("password").apply()
                Toast.makeText(this, "Tunnukset poistettu", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Peruuta", null)
            .show()
    }
    
    private fun tryAutoLoginIfNeeded() {
        val prefs = getSharedPreferences("numeron_prefs", MODE_PRIVATE)
        val u = prefs.getString("username", "") ?: ""
        val p = prefs.getString("password", "") ?: ""

        val safeU = u.replace("\\", "\\\\").replace("'", "\\'")
        val safeP = p.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                var bodyTxt = (document.body ? document.body.innerText : "").toLowerCase();
                var passInputs = document.querySelectorAll('input[type="password"]');
                var hasVisiblePass = false;
                for (var i = 0; i < passInputs.length; i++) {
                    if (passInputs[i].offsetParent !== null) { hasVisiblePass = true; break; }
                }

                if (!hasVisiblePass && (bodyTxt.indexOf('istunto on vanhentunut') !== -1 || bodyTxt.indexOf('istuntosi on vanhentunut') !== -1 || bodyTxt.indexOf('session expired') !== -1 || bodyTxt.indexOf('kirjaudu sisään uudelleen') !== -1 || bodyTxt.indexOf('uloskirjattu') !== -1)) {
                    var btns = document.querySelectorAll('button, a, input[type="button"], input[type="submit"]');
                    for (var b = 0; b < btns.length; b++) {
                        if (btns[b].offsetParent === null) continue;
                        var t = (btns[b].innerText || btns[b].value || "").trim().toLowerCase();
                        if (t.indexOf('kirjaudu') !== -1 || t === 'ok' || t.indexOf('jatka') !== -1 || t.indexOf('uudelleen') !== -1 || t.indexOf('alkuun') !== -1) {
                            btns[b].click();
                            return "SESSION_EXPIRED_CLICKED";
                        }
                    }
                    return "SESSION_EXPIRED_RELOAD";
                }

                if ('$safeU' === '' || '$safeP' === '') return "NO_CREDS";

                function setNativeValue(el, val) {
                    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    if (setter) setter.call(el, val);
                    else el.value = val;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }

                var userInputs = document.querySelectorAll('input[type="email"], input[name*="user" i], input[name*="login" i], input[id*="user" i], input[name*="fmt" i], input[type="text"]');
                var userEl = null;
                for (var uIdx = 0; uIdx < userInputs.length; uIdx++) {
                    var inp = userInputs[uIdx];
                    if (inp.offsetParent !== null && !inp.readOnly && !inp.disabled) {
                        userEl = inp;
                        break;
                    }
                }

                var passEl = null;
                for (var pIdx = 0; pIdx < passInputs.length; pIdx++) {
                    if (passInputs[pIdx].offsetParent !== null && !passInputs[pIdx].disabled) {
                        passEl = passInputs[pIdx];
                        break;
                    }
                }

                if (userEl && !passEl && (window.location.href.toLowerCase().indexOf('login') !== -1 || window.location.href.toLowerCase().indexOf('auth') !== -1 || window.location.href.toLowerCase().indexOf('microsoft') !== -1 || bodyTxt.indexOf('kirjaudu') !== -1)) {
                    setNativeValue(userEl, '$safeU');
                    var nextBtn = document.querySelector('input[type="submit"], button[type="submit"], button[id*="next" i], input[id*="next" i]');
                    if (nextBtn && nextBtn.offsetParent !== null) {
                        nextBtn.click();
                        return "USER_STEP_SUBMITTED";
                    }
                }

                if (!passEl) return "NO_LOGIN_FORM";

                if (userEl) {
                    setNativeValue(userEl, '$safeU');
                }
                setNativeValue(passEl, '$safeP');

                var submitBtn = document.querySelector('input[type="submit"], button[type="submit"], button[id*="login" i], button[name*="login" i], input[id*="SignIn" i]');
                if (submitBtn && submitBtn.offsetParent !== null) {
                    submitBtn.click();
                    return "SUBMITTED";
                }
                var allBtns = document.querySelectorAll('button, a.btn, input[type="button"]');
                for (var k = 0; k < allBtns.length; k++) {
                    var bt = (allBtns[k].innerText || allBtns[k].value || "").trim().toLowerCase();
                    if (bt.indexOf('kirjaudu') !== -1 || bt.indexOf('sign in') !== -1 || bt.indexOf('jatka') !== -1) {
                        allBtns[k].click();
                        return "SUBMITTED";
                    }
                }
                if (passEl.form) {
                    passEl.form.submit();
                    return "SUBMITTED";
                }
                return "FILLED";
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { res ->
            val r = res ?: ""
            val now = System.currentTimeMillis()
            when {
                r.contains("SESSION_EXPIRED_RELOAD") -> webView.loadUrl(URL_STAMPING)
                r.contains("SESSION_EXPIRED_CLICKED") || r.contains("USER_STEP_SUBMITTED") -> {
                    webView.postDelayed({ tryAutoLoginIfNeeded() }, 1200)
                }
                r.contains("SUBMITTED") -> {
                    if (now - lastAutoLoginAttempt > 4000L) {
                        lastAutoLoginAttempt = now
                        Toast.makeText(this, "Istunto uusittu automaattisesti", Toast.LENGTH_SHORT).show()
                    }
                    if (pendingAutoStamp) {
                        webView.postDelayed({ webView.loadUrl(URL_STAMPING) }, 2500)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        if (pendingAutoStamp && ::webView.isInitialized) {
            webView.loadUrl(URL_STAMPING)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("AUTO_STAMP", false) == true) {
            pendingAutoStamp = true
            pendingStampIsEnding = intent.getBooleanExtra("IS_ENDING", false)
            intent.removeExtra("AUTO_STAMP")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(if (pendingStampIsEnding) 2002 else 1001)
        }
    }

    private fun performAutoStamp(isEnding: Boolean) {
        val targetKeyword = if (isEnding) "ulos" else "sisään"
        val js = """
            (function() {
                var btns = document.querySelectorAll('button, input[type="button"], input[type="submit"], a.btn');
                for (var i = 0; i < btns.length; i++) {
                    var txt = (btns[i].innerText || btns[i].value || "").trim().toLowerCase();
                    if (txt.indexOf('leimaa') !== -1 && txt.indexOf('$targetKeyword') !== -1) {
                        btns[i].click();
                        return "CLICKED:" + txt;
                    }
                }
                for (var j = 0; j < btns.length; j++) {
                    var t = (btns[j].innerText || btns[j].value || "").trim().toLowerCase();
                    if (t.indexOf('leimaa sisään') !== -1 || t.indexOf('leimaa ulos') !== -1) {
                        btns[j].click();
                        return "CLICKED:" + t;
                    }
                }
                return "NOT_FOUND";
            })();
        """.trimIndent()

        webView.postDelayed({
            webView.evaluateJavascript(js) { res ->
                if (res != null && res.contains("CLICKED:")) {
                    Toast.makeText(this, "Leimaus suoritettu automaattisesti!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Tarkista kirjautuminen ja paina leimausnappia.", Toast.LENGTH_LONG).show()
                }
            }
        }, 800)
    }

    private fun checkPermissionsAndSync() {
        val needed = mutableListOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            extractShiftsFromPage(forceList = true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val r = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            val w = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
            if (r && w) extractShiftsFromPage(forceList = true)
        }
    }

    private fun extractShiftsFromPage(forceList: Boolean = true) {
        val js = """
            (function() {
                var bodyText = document.body ? document.body.innerText : "";
                var isGrid = /Vk\.?\s+Ma\s+Ti\s+Ke/i.test(bodyText);

                if ($forceList && isGrid) {
                    var selects = document.querySelectorAll('select');
                    for (var s = 0; s < selects.length; s++) {
                        for (var o = 0; o < selects[s].options.length; o++) {
                            var optTxt = (selects[s].options[o].text || "").toLowerCase();
                            if (optTxt.indexOf('luettelo') !== -1 || optTxt.indexOf('lista') !== -1 || optTxt.indexOf('list') !== -1) {
                                selects[s].selectedIndex = o;
                                selects[s].dispatchEvent(new Event('change', { bubbles: true }));
                                return "SWITCHED_TO_LIST";
                            }
                        }
                    }
                    var viewBtns = document.querySelectorAll('button, a, span, div');
                    for (var b = 0; b < viewBtns.length; b++) {
                        var bt = (viewBtns[b].innerText || "").trim().toLowerCase();
                        if (bt === 'näkymä' || bt.indexOf('näkymä') === 0) {
                            viewBtns[b].click();
                            break;
                        }
                    }
                    var links = document.querySelectorAll('a, li, button, span, div');
                    for (var i = 0; i < links.length; i++) {
                        var t = (links[i].innerText || "").trim().toLowerCase();
                        var href = (links[i].getAttribute && links[i].getAttribute('href')) || "";
                        if (t === 'luettelo' || t === 'lista' || href.toLowerCase().indexOf('/calendar/list') !== -1 || href.toLowerCase().indexOf('view=list') !== -1) {
                            links[i].click();
                            return "SWITCHED_TO_LIST";
                        }
                    }
                }

                var cells = [];
                var tds = document.querySelectorAll('td');
                if (tds.length > 15) {
                    for (var j = 0; j < tds.length; j++) {
                        var td = tds[j];
                        var cls = (td.className || "").toLowerCase();
                        var isWeekCol = /week|vk|wk/.test(cls) || (td.parentElement && td.parentElement.children.length === 8 && td.cellIndex === 0);
                        if (isWeekCol) continue;
                        var isOther = /other|old|new|disabled|off|outside|gray|grey/.test(cls);
                        var extra = [];
                        var ch = td.querySelectorAll('[title], [data-original-title]');
                        for (var c = 0; c < ch.length; c++) {
                            var ti = ch[c].getAttribute('title') || ch[c].getAttribute('data-original-title');
                            if (ti) extra.push(ti);
                        }
                        cells.push({ text: (td.innerText || "").trim(), isOther: isOther, extra: extra.join(" | ") });
                    }
                }
                AndroidBridge.processCalendarData(JSON.stringify(cells), bodyText, window.location.href);
                return "EXTRACTED";
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result != null && result.contains("SWITCHED_TO_LIST")) {
                waitingForListViewSync = true
                Toast.makeText(this, "Vaihdetaan luettelonäkymään...", Toast.LENGTH_SHORT).show()
                webView.postDelayed({
                    if (waitingForListViewSync) {
                        waitingForListViewSync = false
                        extractShiftsFromPage(forceList = false)
                    }
                }, 1200)
            }
        }
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
                    Toast.makeText(this@MainActivity, "Tunnistettu $found merkintää, lisätty/päivitetty $added kalenteriin.", Toast.LENGTH_LONG).show()
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
        val wkYear = Regex("""Vk\.?\s*\d{1,2}\s*/\s*(\d{4})""", RegexOption.IGNORE_CASE).find(pageText)?.groupValues?.get(1)?.toIntOrNull()
        val now = LocalDate.now()
        val uri = try { Uri.parse(currentUrl) } catch (e: Exception) { null }
        val m = uri?.getQueryParameter("month")?.toIntOrNull() ?: now.monthValue
        val y = uri?.getQueryParameter("year")?.toIntOrNull() ?: wkYear ?: now.year
        return Pair(m, y)
    }

    private fun extractDirectVacationRanges(text: String, defaultYear: Int, vacationDays: MutableList<LocalDate>): Int {
        var foundRanges = 0
        val rangeRegex = Regex("""(\d{1,2})\.(?:(\d{1,2})\.)?(?:\d{4})?\s*[-–]\s*(\d{1,2})\.(\d{1,2})\.?(?:(\d{4}))?[^\n\r]*(?:loma|\bvl\b)""", RegexOption.IGNORE_CASE)
        for (m in rangeRegex.findAll(text)) {
            val d1 = m.groupValues[1].toIntOrNull() ?: continue
            val d2 = m.groupValues[3].toIntOrNull() ?: continue
            val m2 = m.groupValues[4].toIntOrNull() ?: continue
            val m1 = m.groupValues[2].toIntOrNull() ?: m2
            val y2 = m.groupValues[5].toIntOrNull() ?: defaultYear
            val y1 = if (m1 > m2) y2 - 1 else y2
            try {
                var cur = LocalDate.of(y1, m1, d1)
                val end = LocalDate.of(y2, m2, d2)
                if (!end.isBefore(cur) && ChronoUnit.DAYS.between(cur, end) <= 45) {
                    foundRanges++
                    while (!cur.isAfter(end)) {
                        vacationDays.add(cur)
                        cur = cur.plusDays(1)
                    }
                }
            } catch (_: Exception) {}
        }
        return foundRanges
    }

    private fun parseAndInsertCalendar(cellsJson: String, pageText: String, currentUrl: String, calId: Long): Pair<Int, Int> {
        val (targetMonth, targetYear) = resolveMonthAndYear(pageText, currentUrl)
        val fmt = DateTimeFormatter.ofPattern("d.M.yyyy H:mm")

        val listRes = parseListView(pageText, targetMonth, targetYear, calId, fmt)
        if (listRes.first > 0) return listRes

        var found = 0; var added = 0
        val vacationDays = mutableListOf<LocalDate>()
        found += extractDirectVacationRanges(pageText, targetYear, vacationDays)

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

                    val res = processDayContent(calId, day, targetMonth, targetYear, txt, extra, fmt, vacationDays)
                    found += res.first; added += res.second
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        added += saveGroupedVacations(calId, vacationDays)
        return Pair(found, added)
    }

    private fun parseListView(pageText: String, targetMonth: Int, targetYear: Int, calId: Long, fmt: DateTimeFormatter): Pair<Int, Int> {
        var found = 0; var added = 0
        val vacationDays = mutableListOf<LocalDate>()
        found += extractDirectVacationRanges(pageText, targetYear, vacationDays)

        val dateRe = Regex("""(?:(?:ma|ti|ke|to|pe|la|su|maanantai|tiistai|keskiviikko|torstai|perjantai|lauantai|sunnuntai)[\s.,]+(\d{1,2})\.(\d{1,2})(?:\.(\d{4})?)?|(?<![:\d.])(\d{1,2})\.(\d{1,2})\.(\d{4})?)(?!\d)""", RegexOption.IGNORE_CASE)
        val matches = dateRe.findAll(pageText).toList()
        if (matches.isNotEmpty()) {
            for (i in matches.indices) {
                val m = matches[i]
                val d = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: continue
                val mo = (m.groupValues[2].ifEmpty { m.groupValues[5] }).toIntOrNull() ?: continue
                val yr = (m.groupValues[3].ifEmpty { m.groupValues[6] }).toIntOrNull() ?: targetYear
                if (d !in 1..31 || mo !in 1..12) continue

                val sIdx = m.range.last + 1
                val eIdx = if (i + 1 < matches.size) matches[i + 1].range.first else minOf(sIdx + 500, pageText.length)
                if (sIdx >= eIdx) continue
                val res = processListBlock(calId, d, mo, yr, pageText.substring(sIdx, eIdx).trim(), fmt, vacationDays)
                found += res.first; added += res.second
            }
            added += saveGroupedVacations(calId, vacationDays)
            if (found > 0) return Pair(found, added)
        }
        return Pair(found, added)
    }

    private fun processListBlock(calId: Long, d: Int, m: Int, y: Int, block: String, fmt: DateTimeFormatter, vacationDays: MutableList<LocalDate>): Pair<Int, Int> {
        if (block.lowercase().let { it.contains("vuosiloma") || it.contains("loma") || Regex("""\bvl\b""").containsMatchIn(it) }) {
            try { vacationDays.add(LocalDate.of(y, m, d)) } catch (_: Exception) {}
            return Pair(1, 0)
        }

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
        return Pair(0, 0)
    }

    private fun processDayContent(calId: Long, d: Int, m: Int, y: Int, txt: String, extra: String, fmt: DateTimeFormatter, vacationDays: MutableList<LocalDate>): Pair<Int, Int> {
        val rest = "${txt.replaceFirst(Regex("""^\d{1,2}\s*"""), "")} $extra".lowercase()
        if (rest.contains("vuosiloma") || rest.contains("loma") || Regex("""\bvl\b""").containsMatchIn(rest)) {
            try { vacationDays.add(LocalDate.of(y, m, d)) } catch (_: Exception) {}
            return Pair(1, 0)
        }

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
        return Pair(0, 0)
    }

    private fun extractLocAndDesc(startStr: String, raw: String): Pair<String, String> {
        val lines = raw.lines().map { it.trim() }.filter {
            it.length > 2 &&
            !it.equals("Uusi", true) &&
            !it.equals("Lisää uusi", true) &&
            !it.equals("Valinnat", true) &&
            !it.startsWith("Vk", true) &&
            !it.startsWith("Viikko", true) &&
            !Regex("""^\d{1,2}$""").matches(it)
        }
        if (lines.size >= 2) {
            val codeLine = lines.firstOrNull { Regex("""^\d{3,5}\b""").containsMatchIn(it) }
            val roleLine = lines.firstOrNull { it != codeLine }
            return Pair(codeLine ?: lines[0], roleLine ?: lines[1])
        } else if (lines.size == 1) {
            return Pair(lines[0], lines[0])
        }
        val h = startStr.substringBefore(":").toIntOrNull() ?: 7
        val type = if (h < 11) "Aamuvuoro" else if (h < 18) "Iltavuoro" else "Yövuoro"
        return Pair("", type)
    }

    private fun scheduleShiftAlarms(startMillis: Long, endMillis: Long, title: String) {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            val now = System.currentTimeMillis()

            val startAlarmTime = startMillis - (30 * 60 * 1000L)
            if (startAlarmTime > now) {
                val startIntent = Intent(this, ShiftAlarmReceiver::class.java).apply {
                    putExtra("SHIFT_TITLE", title)
                    putExtra("IS_ENDING", false)
                }
                val startReqCode = (startAlarmTime / 60000L).toInt()
                val startPi = PendingIntent.getBroadcast(
                    this, startReqCode, startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, startAlarmTime, startPi)
            }

            val endAlarmTime = endMillis - (30 * 60 * 1000L)
            if (endAlarmTime > now) {
                val endIntent = Intent(this, ShiftAlarmReceiver::class.java).apply {
                    putExtra("SHIFT_TITLE", title)
                    putExtra("IS_ENDING", true)
                }
                val endReqCode = (endAlarmTime / 60000L).toInt() + 1
                val endPi = PendingIntent.getBroadcast(
                    this, endReqCode, endIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, endAlarmTime, endPi)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveWorkShift(calId: Long, dateStr: String, startStr: String, endStr: String, raw: String, fmt: DateTimeFormatter): Boolean {
        return try {
            val sDt = LocalDateTime.parse("$dateStr $startStr", fmt)
            var eDt = LocalDateTime.parse("$dateStr $endStr", fmt)
            if (eDt.isBefore(sDt)) eDt = eDt.plusDays(1)
            val sMs = sDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val eMs = eDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            if (eMs < System.currentTimeMillis()) return false

            val (loc, desc) = extractLocAndDesc(startStr, raw)
            scheduleShiftAlarms(sMs, eMs, desc.ifEmpty { "Työvuoro" })

            val dayStartMs = sDt.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayEndMs = sDt.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val exId = findShiftOnSameDay(calId, sMs, eMs, dayStartMs, dayEndMs)

            if (exId == null) {
                insertEvent(calId, "Työvuoro", loc, desc, sMs, eMs, false)
                true
            } else if (loc.isNotEmpty()) {
                updateEvent(exId, loc, desc)
            } else false
        } catch (e: Exception) { false }
    }

    private fun saveGroupedVacations(calId: Long, rawDays: List<LocalDate>): Int {
        val days = rawDays.distinct().sorted()
        if (days.isEmpty()) return 0
        var addedCount = 0

        var startDate = days[0]
        var endDate = days[0]

        for (i in 1 until days.size) {
            val current = days[i]
            val gapDays = ChronoUnit.DAYS.between(endDate, current)
            val isWeekendBridge = gapDays in 2..3 &&
                    (endDate.dayOfWeek == DayOfWeek.FRIDAY || endDate.dayOfWeek == DayOfWeek.SATURDAY) &&
                    current.dayOfWeek == DayOfWeek.MONDAY

            if (gapDays == 1L || isWeekendBridge) {
                endDate = current
            } else {
                if (saveCleanVacationPeriod(calId, startDate, endDate)) addedCount++
                startDate = current
                endDate = current
            }
        }
        if (saveCleanVacationPeriod(calId, startDate, endDate)) addedCount++
        return addedCount
    }

    private fun saveCleanVacationPeriod(calId: Long, startDate: LocalDate, endDate: LocalDate): Boolean {
        return try {
            var minStartMs = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            var maxEndMs = endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

            val oneDayMs = 24 * 60 * 60 * 1000L
            val sel = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DTEND} >= ? AND ${CalendarContract.Events.DELETED} = 0"
            val args = arrayOf(calId.toString(), "Loma", (maxEndMs + oneDayMs).toString(), (minStartMs - oneDayMs).toString())

            val existingIds = mutableListOf<Long>()
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
                sel, args, null
            )?.use { c ->
                while (c.moveToNext()) {
                    existingIds.add(c.getLong(0))
                    minStartMs = minOf(minStartMs, c.getLong(1))
                    maxEndMs = maxOf(maxEndMs, c.getLong(2))
                }
            }

            if (existingIds.size == 1) {
                val v = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, minStartMs)
                    put(CalendarContract.Events.DTEND, maxEndMs)
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                }
                contentResolver.update(CalendarContract.Events.CONTENT_URI, v, "${CalendarContract.Events._ID} = ?", arrayOf(existingIds[0].toString()))
                return true
            } else if (existingIds.size > 1) {
                for (id in existingIds) {
                    contentResolver.delete(CalendarContract.Events.CONTENT_URI, "${CalendarContract.Events._ID} = ?", arrayOf(id.toString()))
                }
            }

            insertEvent(calId, "Loma", "", "Vuosiloma", minStartMs, maxEndMs, true)
            true
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

    private fun findShiftOnSameDay(calId: Long, sMs: Long, eMs: Long, dayStartMs: Long, dayEndMs: Long): Long? {
        val exactSel = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} = ? AND ${CalendarContract.Events.DTEND} = ? AND ${CalendarContract.Events.DELETED} = 0"
        contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), exactSel, arrayOf(calId.toString(), sMs.toString(), eMs.toString()), null)?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        val daySel = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DELETED} = 0"
        contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DTSTART), daySel, arrayOf(calId.toString(), "Työvuoro", dayStartMs.toString(), dayEndMs.toString()), null)?.use {
            while (it.moveToNext()) {
                val existingStart = it.getLong(1)
                if (kotlin.math.abs(existingStart - sMs) <= 2 * 60 * 60 * 1000L) {
                    return it.getLong(0)
                }
            }
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

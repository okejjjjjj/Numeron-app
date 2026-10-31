package fi.oma.numeron

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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

    private lateinit var stampOverlay: LinearLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var overlayProgress: ProgressBar

    private var pendingAutoStamp = false
    private var pendingStampIsEnding = false
    private var autoStampClickDone = false
    private var autoStampRetryCount = 0
    private var waitingForListViewSync = false
    private var lastAutoLoginAttempt = 0L

    private val URL_STAMPING = "https://siunsote.numeronwfm.com/WorktimeStamping"
    private val URL_WISHES = "https://siunsote.numeronwfm.com/PersonRequests/List"
    private val URL_CALENDAR = "https://siunsote.numeronwfm.com/Calendar/Month"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootFrame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
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

                    if (pendingAutoStamp) {
                        scheduleAutoStampCheck(1000)
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

        mainColumn.addView(webView)
        mainColumn.addView(buttonBar)

        // Hillitty ja tyylikäs overlay automaattileimaukselle
        stampOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#D9121212"))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 56, 64, 48)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 48
                marginEnd = 48
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32f
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.parseColor("#3A3A3A"))
            }
        }

        overlayProgress = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(96, 96).apply { bottomMargin = 32 }
        }

        overlayTitle = TextView(this).apply {
            text = "Automaattinen leimaus"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        overlayStatus = TextView(this).apply {
            text = "Yhdistetään palveluun..."
            textSize = 13.5f
            setTextColor(Color.parseColor("#BBBBBB"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 36)
        }

        val btnCancelOverlay = Button(this).apply {
            text = "Peruuta"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#CCCCCC"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f
                setColor(Color.parseColor("#333333"))
            }
            setOnClickListener {
                pendingAutoStamp = false
                autoStampClickDone = false
                hideStampOverlay()
            }
        }

        card.addView(overlayProgress)
        card.addView(overlayTitle)
        card.addView(overlayStatus)
        card.addView(btnCancelOverlay)
        stampOverlay.addView(card)

        rootFrame.addView(mainColumn)
        rootFrame.addView(stampOverlay)
        setContentView(rootFrame)

        handleIncomingIntent(intent)
        updateNavigationState(URL_STAMPING)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stampOverlay.visibility == View.VISIBLE) {
                    pendingAutoStamp = false
                    hideStampOverlay()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val prefs = getSharedPreferences("numeron_prefs", MODE_PRIVATE)
        if (!prefs.contains("asked_creds") && !pendingAutoStamp) {
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

    private fun showStampOverlay(title: String, status: String, showSpinner: Boolean = true) {
        if (!::stampOverlay.isInitialized) return
        overlayTitle.text = title
        overlayStatus.text = status
        overlayProgress.visibility = if (showSpinner) View.VISIBLE else View.GONE
        stampOverlay.visibility = View.VISIBLE
    }

    private fun hideStampOverlay() {
        if (!::stampOverlay.isInitialized) return
        stampOverlay.visibility = View.GONE
    }

    private fun styleButton(btn: Button, bgColor: String, isSelected: Boolean) {
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(Color.parseColor(bgColor))
            if (isSelected) setStroke(4, Color.WHITE)
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

                if (userEl) setNativeValue(userEl, '$safeU');
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
                    if (pendingAutoStamp) showStampOverlay(overlayTitle.text.toString(), "Kirjaudutaan sisään...")
                    webView.postDelayed({ tryAutoLoginIfNeeded() }, 1400)
                }
                r.contains("SUBMITTED") -> {
                    if (now - lastAutoLoginAttempt > 4000L) {
                        lastAutoLoginAttempt = now
                        if (pendingAutoStamp) {
                            showStampOverlay(overlayTitle.text.toString(), "Kirjaudutaan sisään, odota hetki...")
                        } else {
                            Toast.makeText(this, "Istunto uusittu automaattisesti", Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (pendingAutoStamp) {
                        webView.postDelayed({ scheduleAutoStampCheck(1500) }, 2200)
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
            autoStampClickDone = false
            autoStampRetryCount = 0
            intent.removeExtra("AUTO_STAMP")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(if (pendingStampIsEnding) 2002 else 1001)

            val title = if (pendingStampIsEnding) "Ulosleimaus käynnissä" else "Sisäänleimaus käynnissä"
            showStampOverlay(title, "Valmistellaan leimausta...")
        }
    }

    private fun scheduleAutoStampCheck(delayMs: Long) {
        if (!pendingAutoStamp) return
        webView.postDelayed({ executeAutoStampStep() }, delayMs)
    }

    private fun executeAutoStampStep() {
        if (!pendingAutoStamp || autoStampClickDone) return
        autoStampRetryCount++

        if (autoStampRetryCount > 15) {
            pendingAutoStamp = false
            hideStampOverlay()
            Toast.makeText(this, "Automaattileimaus aikakatkaistiin. Tarkista näkymä ja leimaa käsin.", Toast.LENGTH_LONG).show()
            return
        }

        val currentUrl = webView.url ?: ""
        val targetKeyword = if (pendingStampIsEnding) "ulos" else "sisään"
        val oppositeKeyword = if (pendingStampIsEnding) "sisään" else "ulos"

        val js = """
            (function() {
                var passInputs = document.querySelectorAll('input[type="password"]');
                for (var p = 0; p < passInputs.length; p++) {
                    if (passInputs[p].offsetParent !== null) return "LOGIN_PAGE";
                }
                var bodyTxt = (document.body ? document.body.innerText : "").toLowerCase();
                if (bodyTxt.indexOf('istunto on vanhentunut') !== -1 || bodyTxt.indexOf('session expired') !== -1) {
                    return "LOGIN_PAGE";
                }

                var btns = document.querySelectorAll('button, input[type="button"], input[type="submit"], a.btn');
                for (var i = 0; i < btns.length; i++) {
                    if (btns[i].offsetParent === null) continue;
                    var txt = (btns[i].innerText || btns[i].value || "").trim().toLowerCase();
                    if (txt.indexOf('leimaa') !== -1 && txt.indexOf('$targetKeyword') !== -1) {
                        btns[i].click();
                        return "CLICKED:" + txt;
                    }
                }
                for (var j = 0; j < btns.length; j++) {
                    if (btns[j].offsetParent === null) continue;
                    var t = (btns[j].innerText || btns[j].value || "").trim().toLowerCase();
                    if (t.indexOf('leimaa') !== -1 && t.indexOf('$oppositeKeyword') !== -1) {
                        return "ALREADY_IN_TARGET_STATE";
                    }
                }
                return "WAITING";
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { res ->
            val r = res ?: ""
            when {
                r.contains("LOGIN_PAGE") -> {
                    showStampOverlay(overlayTitle.text.toString(), "Kirjaudutaan sisään, odota hetki...")
                    tryAutoLoginIfNeeded()
                    scheduleAutoStampCheck(2000)
                }
                r.contains("CLICKED:") -> {
                    autoStampClickDone = true
                    showStampOverlay(overlayTitle.text.toString(), "Suoritetaan leimausta ja varmistetaan...")
                    webView.postDelayed({ verifyStampSuccess(1) }, 1800)
                }
                r.contains("ALREADY_IN_TARGET_STATE") -> {
                    completeAutoStampSuccess("Leimaus on jo voimassa!")
                }
                else -> {
                    if (!currentUrl.contains("/WorktimeStamping", ignoreCase = true) && !currentUrl.contains("login", ignoreCase = true) && autoStampRetryCount % 3 == 0) {
                        webView.loadUrl(URL_STAMPING)
                    }
                    showStampOverlay(overlayTitle.text.toString(), "Ladataan leimaussivua...")
                    scheduleAutoStampCheck(1400)
                }
            }
        }
    }

    private fun verifyStampSuccess(attempt: Int) {
        if (!pendingAutoStamp) return
        val targetKeyword = if (pendingStampIsEnding) "ulos" else "sisään"
        val oppositeKeyword = if (pendingStampIsEnding) "sisään" else "ulos"

        val js = """
            (function() {
                var btns = document.querySelectorAll('button, input[type="button"], input[type="submit"], a.btn');
                var targetStillVisible = false;
                for (var i = 0; i < btns.length; i++) {
                    if (btns[i].offsetParent === null) continue;
                    var txt = (btns[i].innerText || btns[i].value || "").trim().toLowerCase();
                    if (txt.indexOf('leimaa') !== -1 && txt.indexOf('$oppositeKeyword') !== -1) {
                        return "VERIFIED_OPPOSITE";
                    }
                    if (txt.indexOf('leimaa') !== -1 && txt.indexOf('$targetKeyword') !== -1) {
                        targetStillVisible = true;
                    }
                }
                var bodyTxt = (document.body ? document.body.innerText : "").toLowerCase();
                if (bodyTxt.indexOf('onnistui') !== -1 || bodyTxt.indexOf('tallennettu') !== -1 || bodyTxt.indexOf('vastaanotettu') !== -1) {
                    return "VERIFIED_TEXT";
                }
                if (!targetStillVisible && bodyTxt.indexOf('työaikaleimaus') !== -1) {
                    return "VERIFIED_GONE";
                }
                return "PENDING";
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { res ->
            val r = res ?: ""
            if (r.contains("VERIFIED")) {
                val msg = if (pendingStampIsEnding) "Ulosleimaus varmistettu!" else "Sisäänleimaus varmistettu!"
                completeAutoStampSuccess(msg)
            } else if (attempt < 5) {
                webView.postDelayed({ verifyStampSuccess(attempt + 1) }, 1200)
            } else {
                pendingAutoStamp = false
                autoStampClickDone = false
                hideStampOverlay()
                Toast.makeText(this, "Varmista leimauksen tila ruudulta.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun completeAutoStampSuccess(message: String) {
        pendingAutoStamp = false
        autoStampClickDone = false
        showStampOverlay(" ✓  $message", "Suljetaan sovellus...", showSpinner = false)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        webView.postDelayed({ finishAndRemoveTask() }, 1400)
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
        """.trimInden

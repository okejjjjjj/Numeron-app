import os

main_part_a = """package fi.oma.numeron

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {

    private lateinit var mainColumn: LinearLayout
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

    private var pagesRemainingToSync = 0
    private var totalFoundInBatch = 0
    private var totalAddedInBatch = 0
    private var isBackgroundListSync = false

    private val URL_STAMPING = "https://siunsote.numeronwfm.com/WorktimeStamping"
    private val URL_WISHES = "https://siunsote.numeronwfm.com/PersonRequests/List"
    private val URL_CALENDAR = "https://siunsote.numeronwfm.com/Calendar/Month"

    private fun getSecurePrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                "numeron_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            getSharedPreferences("numeron_prefs", MODE_PRIVATE)
        }
    }

    private fun requireBiometricAuth(title: String, onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@MainActivity, "Tunnistus peruutettu: $errString", Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Vahvista sormenjäljellä tai lukituskoodilla")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootFrame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        mainColumn = LinearLayout(this).apply {
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

                    if (currentUrl.contains("/PersonRequests/", ignoreCase = true)) {
                        injectWishProtection()
                    }

                    if (pendingAutoStamp) {
                        scheduleAutoStampCheck(1000)
                    } else if (isBackgroundListSync && currentUrl.contains("/Calendar/", ignoreCase = true) && !waitingForListViewSync) {
                        webView.postDelayed({ extractShiftsFromPage(forceList = true) }, 700)
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

        stampOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#EB0A0A0A"))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 48)
            layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 64
                marginEnd = 64
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 36f
                setColor(Color.parseColor("#1E1E1E"))
                setStroke(2, Color.parseColor("#383838"))
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
                isBackgroundListSync = false
                pagesRemainingToSync = 0
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
        scheduleNextThreeWeekListCheck()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stampOverlay.visibility == View.VISIBLE) {
                    pendingAutoStamp = false
                    isBackgroundListSync = false
                    hideStampOverlay()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val prefs = getSecurePrefs()
        if (!prefs.contains("asked_creds") && !pendingAutoStamp && !isBackgroundListSync) {
            prefs.edit().putBoolean("asked_creds", true).apply()
            showCredentialsDialog()
        }

        if (!isBackgroundListSync) {
            webView.loadUrl(URL_STAMPING)
        }
    }
"""

path = "app/src/main/java/fi/oma/numeron/MainActivity.kt"
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, "w", encoding="utf-8") as f:
    f.write(main_part_a)
print("MainActivity Osa A kirjoitettu!")

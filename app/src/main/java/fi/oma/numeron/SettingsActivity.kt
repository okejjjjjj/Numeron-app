package fi.oma.numeron

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.CalendarContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private data class CalendarItem(val id: Long, val title: String)
    private val calendarList = mutableListOf<CalendarItem>()
    private lateinit var calendarSpinner: Spinner

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#0B1120"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(36))
        }

        val titleView = TextView(this).apply {
            text = "Asetukset"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        val subHeaderView = TextView(this).apply {
            text = "Numeron Vuorovahti – automaation ja kalenterin hallinta"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(4), 0, dp(4), dp(20))
        }
        container.addView(titleView)
        container.addView(subHeaderView)

        fun createCard(sectionTitle: String, subtitle: String): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1), Color.parseColor("#334155"))
                }
            }
            card.addView(TextView(this).apply {
                text = sectionTitle
                textSize = 15.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
            })
            if (subtitle.isNotEmpty()) {
                card.addView(TextView(this).apply {
                    text = subtitle
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(0, dp(4), 0, dp(12))
                })
            }
            container.addView(card)
            return card
        }

        fun styleEdit(edit: EditText) {
            edit.setTextColor(Color.parseColor("#F8FAFC"))
            edit.setHintTextColor(Color.parseColor("#64748B"))
            edit.textSize = 14.5f
            edit.setPadding(dp(16), dp(13), dp(16), dp(13))
            edit.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            edit.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }

        fun createStyledSwitch(label: String, checked: Boolean): Switch {
            return Switch(this).apply {
                text = label
                textSize = 14.5f
                setTextColor(Color.parseColor("#F1F5F9"))
                isChecked = checked
                setPadding(0, dp(4), 0, dp(4))
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
                thumbTintList = ColorStateList(states, intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#94A3B8")))
                trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#065F46"), Color.parseColor("#334155")))
            }
        }

        // 1. Organisaatio & Tunnukset
        val cardOrg = createCard(
            "1. Organisaatio ja kirjautuminen",
            "Syötä organisaation tunnus (esim. omaorganisaatio) tai palvelimen osoite, sekä kirjautumistiedot. (Esittelytila: syötä tunnukseksi ja salasanaksi 'demo')."
        )
        val orgEdit = EditText(this).apply {
            hint = "Organisaatio tai osoite (esim. omaorganisaatio.numeronwfm.com)"
            setText(SettingsManager.getOrganizationRaw(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            styleEdit(this)
        }
        val userEdit = EditText(this).apply {
            hint = "Käyttäjätunnus"
            setText(SettingsManager.getUsername(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            styleEdit(this)
        }
        val passEdit = EditText(this).apply {
            hint = "Salasana"
            setText(SettingsManager.getPassword(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            styleEdit(this)
        }
        cardOrg.addView(orgEdit)
        cardOrg.addView(userEdit)
        cardOrg.addView(passEdit)

        // 2. Biometrinen tunnistus
        val cardBio = createCard(
            "2. Sormenjälkitunnistus",
            "Vahvista työvuorotoiveiden tallennus ja poisto sormenjäljellä tai lukituskoodilla."
        )
        val bioSwitch = createStyledSwitch(
            "Käytä biometristä tunnistusta",
            SettingsManager.isBiometricEnabled(this)
        )
        cardBio.addView(bioSwitch)

        // 3. Automaattinen vuorojen synkronointi + Viimeisin synkronointiaika
        val cardSync = createCard(
            "3. Työvuorojen automaattinen haku",
            "Tarkistaa uudet 3 viikon listat julkaisuperjantaisin klo 11 jälkeen ja vie ne kalenteriin."
        )
        val syncSwitch = createStyledSwitch(
            "Synkronoi työvuorot automaattisesti",
            SettingsManager.isAutoSyncEnabled(this)
        )
        val syncStatusText = TextView(this).apply {
            text = VuorovahtiHelper.getLastSyncStatus(this@SettingsActivity)
            textSize = 12f
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, dp(8), 0, 0)
        }
        cardSync.addView(syncSwitch)
        cardSync.addView(syncStatusText)

        // 4. Kohdekalenteri
        val cardCal = createCard(
            "4. Kohdekalenteri",
            "Valitse mihin puhelimen kalenteriin työvuorot ja vuosilomat tallennetaan."
        )
        calendarSpinner = Spinner(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }
        cardCal.addView(calendarSpinner)

        // 5. Leimausnotifikaatiot
        val cardNotif = createCard(
            "5. Leimausmuistutukset",
            "Näytä hiljainen ilmoitus ja pikaleimausnappi 30 min ennen vuoron alkua ja loppua."
        )
        val notifSwitch = createStyledSwitch(
            "Käytä leimausnotifikaatioita",
            SettingsManager.isStampNotificationEnabled(this)
        )
        cardNotif.addView(notifSwitch)

        // Painikkeet
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }

        val btnCancel = Button(this).apply {
            text = "Peruuta"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#CBD5E1"))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
            setOnClickListener { finish() }
        }

        val btnSave = Button(this).apply {
            text = "Tallenna asetukset"
            isAllCaps = false
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                colors = intArrayOf(Color.parseColor("#0284C7"), Color.parseColor("#0369A1"))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1.5f).apply { marginStart = dp(8) }
            setOnClickListener {
                val orgText = orgEdit.text.toString().trim()
                val userText = userEdit.text.toString().trim()
                val passText = passEdit.text.toString()
                val isDemoUser = userText.equals("demo", ignoreCase = true)

                if (orgText.isEmpty() && !isDemoUser) {
                    Toast.makeText(this@SettingsActivity, "Syötä ensin organisaation tunnus tai osoite.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val selectedIdx = calendarSpinner.selectedItemPosition
                val calId = if (selectedIdx in calendarList.indices) calendarList[selectedIdx].id else -1L
                SettingsManager.saveAll(
                    this@SettingsActivity,
                    if (isDemoUser && orgText.isEmpty()) "demo" else orgText,
                    userText,
                    passText,
                    bioSwitch.isChecked,
                    syncSwitch.isChecked,
                    notifSwitch.isChecked,
                    calId
                )
                VuorovahtiHelper.triggerConfirmHaptics(window.decorView)
                Toast.makeText(this@SettingsActivity, "Asetukset tallennettu!", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        container.addView(btnRow)

        // Vastuuvapauslauseke alareunaan
        val disclaimerView = TextView(this).apply {
            text = "Numeron Vuorovahti on itsenäinen ja epävirallinen aputyökalu. Kehittäjä ei vastaa mahdollisista virheistä työvuorojen synkronoinnissa tai leimauksissa. Tarkista viralliset työaikatiedot aina suoraan työnantajan järjestelmästä."
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(24), dp(12), dp(8))
        }
        container.addView(disclaimerView)

        rootScroll.addView(container)
        setContentView(rootScroll)

        loadAvailableCalendars()
    }

    private fun loadAvailableCalendars() {
        calendarList.clear()
        calendarList.add(CalendarItem(-1L, "Oletuskalenteri (Automaattinen)"))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 201)
        } else {
            val proj = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME
            )
            val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
            try {
                contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: "Kalenteri"
                        val acc = c.getString(2) ?: ""
                        calendarList.add(CalendarItem(id, if (acc.isNotEmpty()) "$name ($acc)" else name))
                    }
                }
            } catch (_: Exception) {}
        }

        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            calendarList.map { it.title }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 14f
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setBackgroundColor(Color.parseColor("#1E293B"))
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
            }
        }
        calendarSpinner.adapter = adapter

        val savedId = SettingsManager.getTargetCalendarId(this)
        val idx = calendarList.indexOfFirst { it.id == savedId }
        if (idx >= 0) calendarSpinner.setSelection(idx)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201) loadAvailableCalendars()
    }
}

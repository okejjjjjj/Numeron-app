package fi.oma.numeron

import android.Manifest
import android.content.pm.PackageManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 64)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 32)
        }

        val titleView = TextView(this).apply {
            text = "Asetukset"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        headerRow.addView(titleView)
        container.addView(headerRow)

        fun createCard(sectionTitle: String, subtitle: String): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 36, 40, 36)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 28 }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 28f
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(2, Color.parseColor("#334155"))
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
                    setPadding(0, 6, 0, 20)
                })
            }
            container.addView(card)
            return card
        }

        fun styleEdit(edit: EditText) {
            edit.setTextColor(Color.WHITE)
            edit.setHintTextColor(Color.parseColor("#64748B"))
            edit.textSize = 14.5f
            edit.setPadding(32, 26, 32, 26)
            edit.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f
                setColor(Color.parseColor("#0F172A"))
                setStroke(2, Color.parseColor("#334155"))
            }
            edit.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 }
        }

        // 1. Organisaatio & Tunnukset
        val cardOrg = createCard(
            "1. Organisaatio ja kirjautuminen",
            "Syötä organisaation tunnus (esim. siunsote) tai koko osoite, sekä kirjautumistiedot."
        )
        val orgEdit = EditText(this).apply {
            hint = "Organisaatio (esim. siunsote)"
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
        val bioSwitch = Switch(this).apply {
            text = "Käytä biometristä tunnistusta"
            textSize = 14.5f
            setTextColor(Color.WHITE)
            isChecked = SettingsManager.isBiometricEnabled(this@SettingsActivity)
        }
        cardBio.addView(bioSwitch)

        // 3. Automaattinen vuorojen synkronointi
        val cardSync = createCard(
            "3. Työvuorojen automaattinen haku",
            "Tarkistaa uudet 3 viikon listat julkaisuperjantaisin klo 11 jälkeen ja vie ne kalenteriin."
        )
        val syncSwitch = Switch(this).apply {
            text = "Synkronoi työvuorot automaattisesti"
            textSize = 14.5f
            setTextColor(Color.WHITE)
            isChecked = SettingsManager.isAutoSyncEnabled(this@SettingsActivity)
        }
        cardSync.addView(syncSwitch)

        // 4. Kohdekalenteri
        val cardCal = createCard(
            "4. Kohdekalenteri",
            "Valitse mihin puhelimen kalenteriin työvuorot ja vuosilomat tallennetaan."
        )
        calendarSpinner = Spinner(this).apply {
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f
                setColor(Color.parseColor("#0F172A"))
                setStroke(2, Color.parseColor("#334155"))
            }
        }
        cardCal.addView(calendarSpinner)

        // 5. Leimausnotifikaatiot
        val cardNotif = createCard(
            "5. Leimausmuistutukset",
            "Näytä hiljainen ilmoitus ja pikaleimausnappi 30 min ennen vuoron alkua ja loppua."
        )
        val notifSwitch = Switch(this).apply {
            text = "Käytä leimausnotifikaatioita"
            textSize = 14.5f
            setTextColor(Color.WHITE)
            isChecked = SettingsManager.isStampNotificationEnabled(this@SettingsActivity)
        }
        cardNotif.addView(notifSwitch)

        // Tallenna & Peruuta -napit
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 }
        }

        val btnCancel = Button(this).apply {
            text = "Peruuta"
            isAllCaps = false
            setTextColor(Color.parseColor("#CBD5E1"))
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 12 }
            setOnClickListener { finish() }
        }

        val btnSave = Button(this).apply {
            text = "Tallenna asetukset"
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#0284C7"))
            }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.5f).apply { marginStart = 12 }
            setOnClickListener {
                val selectedIdx = calendarSpinner.selectedItemPosition
                val calId = if (selectedIdx in calendarList.indices) calendarList[selectedIdx].id else -1L
                SettingsManager.saveAll(
                    this@SettingsActivity,
                    orgEdit.text.toString(),
                    userEdit.text.toString(),
                    passEdit.text.toString(),
                    bioSwitch.isChecked,
                    syncSwitch.isChecked,
                    notifSwitch.isChecked,
                    calId
                )
                Toast.makeText(this@SettingsActivity, "Asetukset tallennettu!", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        container.addView(btnRow)

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
                    setTextColor(Color.WHITE)
                    textSize = 14f
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1E293B"))
                    setPadding(32, 28, 32, 28)
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

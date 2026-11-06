# Tietosuojakäytäntö / Privacy Policy – NumeronSync

**Viimeksi päivitetty / Last updated:** 6.11.2026

---

## 🇫🇮 Suomenkielinen tietosuojaseloste

### 1. Yleistä
Numeron vuorovahti ("Sovellus") on avoimen lähdekoodin paikallinen Android-asiakassovellus, joka on suunniteltu helpottamaan Numeron WFM -työaikajärjestelmän käyttöä, leimauksia ja työvuorojen synkronointia käyttäjän omaan kalenteriin. 

Sovelluksen kehittäjä ei kerää, tallenna, analysoi tai jaa mitään käyttäjätietoja ulkopuolisille palvelimille tai kolmansille osapuolille.

### 2. Mitä tietoja sovellus käsittelee ja miten niitä säilytetään?
Sovellus toimii täysin paikallisesti käyttäjän omassa puhelimessa:
* **Kirjautumistiedot ja organisaatio-osoite:** Käyttäjän syöttämä organisaation palvelinosoite, käyttäjätunnus ja salasana tallennetaan **ainoastaan paikallisesti käyttäjän omaan laitteeseen** vahvasti salattuina (`AndroidX EncryptedSharedPreferences` / AES-256). Tunnuksia käytetään vain suoraan HTTPS-kirjautumiseen käyttäjän määrittämälle Numeron WFM -palvelimelle.
* **Kalenteritiedot:** Sovellus lukee käyttäjän työvuorot ja lomat Numeron-näkymästä ja tallentaa ne paikallisesti puhelimen omaan kalenterisovellukseen (`CalendarContract` API) käyttäjän valitsemaan kalenteriin. Kalenteritietoja ei koskaan lähetetä laitteen ulkopuolelle sovelluksen toimesta.
* **Biometrinen tunnistus:** Sovellus hyödyntää Androidin sisäänrakennettua `BiometricPrompt`-rajapintaa työvuorotoiveiden vahvistamiseen. Sovellus ei koskaan lue, käsittele tai tallenna sormenjälki- tai kasvotietoja, vaan ainoastaan vastaanottaa laitteen käyttöjärjestelmältä tiedon tunnistuksen onnistumisesta.

### 3. Käyttöoikeudet (Permissions)
Sovellus pyytää seuraavia Android-käyttöoikeuksia vain ydintoimintojensa suorittamiseen:
* `INTERNET`: Yhteyden muodostamiseen suoraan käyttäjän organisaation Numeron WFM -palvelimeen.
* `READ_CALENDAR` & `WRITE_CALENDAR`: Työvuorojen ja vuosilomien viemiseen puhelimen kalenteriin sekä päällekkäisten merkintöjen estämiseen.
* `POST_NOTIFICATIONS` & `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`: Paikallisten työvuoro- ja leimausmuistutusten näyttämiseen oikea-aikaisesti.
* `USE_BIOMETRIC`: Toiveiden tallennuksen ja poiston suojaamiseen laitteen lukituksella.

### 4. Tietojen jakaminen ja kolmannet osapuolet
Sovellus **ei sisällä** mainoksia, analytiikkakirjastoja tai kolmannen osapuolen seurantaohjelmistoja. Mitään tietoja ei luovuteta kehittäjälle tai ulkopuolisille tahoille.

### 5. Tietojen poistaminen
Koska kaikki tiedot tallennetaan ainoastaan käyttäjän omalle laitteelle, käyttäjä voi poistaa kaikki tallennetut tietonsa milloin tahansa:
1. Tyhjentämällä sovelluksen tallennustilan puhelimen asetuksista (*Asetukset -> Sovellukset -> NumeronSync -> Tallennustila -> Tyhjennä tiedot*), tai
2. Poistamalla sovelluksen asennuksen laitteelta.

### 6. Yhteystiedot
Jos sinulla on kysyttävää tästä tietosuojakäytännöstä, voit ottaa yhteyttä avaamalla tukipyynnön (Issue) tämän GitHub-projektin sivulla.

---

## 🌐 Privacy Policy (In English)

### 1. Overview
Numeron vuorovahti ("the App") is a local, client-side Android application designed to interact with the user's organization's Numeron WFM system and synchronize work shifts with the device's local calendar.

**The developer does not collect, store, transmit, or share any personal user data, credentials, or calendar events on any external servers.**

### 2. Data Processing and Local Storage
All data processed by the App remains strictly on the user's device:
* **Credentials & Server URL:** The organization URL, username, and password entered by the user are stored **exclusively on the user's device** using hardware-backed encryption (`EncryptedSharedPreferences`, AES-256). They are only transmitted directly via encrypted HTTPS to the Numeron WFM server configured by the user.
* **Calendar Data:** Work shifts and vacation periods parsed from the web interface are written directly to the user's local device calendar via Android's standard `CalendarContract` provider.
* **Biometric Authentication:** The App uses the standard Android `BiometricPrompt` API to confirm user actions. The App never accesses or stores raw biometric data.

### 3. Permissions Used
* `INTERNET`: Required to load the user-configured Numeron WFM web portal.
* `READ_CALENDAR` & `WRITE_CALENDAR`: Required to check existing shifts and insert new work shifts into the selected local calendar.
* `POST_NOTIFICATIONS` & `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`: Required to trigger local shift reminders 30 minutes before shift start/end.
* `USE_BIOMETRIC`: Required for optional local authentication before submitting or deleting shift requests.

### 4. Data Sharing and Third Parties
The App contains **no third-party analytics, trackers, or advertising SDKs**. Zero user data is shared with the developer or third parties.

### 5. Data Deletion
Since all data is stored locally on the device, users have full control over their data and can delete it immediately at any time by clearing the App's storage in Android Settings or uninstalling the App.

### 6. Contact
For any questions regarding privacy, please open an issue on this GitHub repository.

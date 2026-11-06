# Numeron-vuorovahti

Moderni ja kevyt Android-sovellus Numeron WFM -työaikaseurantaan. Numeron-vuorovahti automatisoi työaikaleimaukset, synkronoi työvuorot ja lomat saumattomasti Google Kalenteriin sekä tekee vuorojen hallinnasta vaivatonta.

## Tärkeimmät ominaisuudet

* **Dynaaminen organisaatiotuki:** Toimii millä tahansa Numeron WFM -alustalla. Voit syöttää oman organisaatiosi osoitteen tai tunnuksen helposti asetuksista.
* **Automaattinen kalenterisynkronointi:** Hakee tulevat työvuorot ja vuosilomat suoraan puhelimen Google Kalenteriin.
* **Älykkäät muistutukset ja pikaleimaus:** Ilmoittaa 30 minuuttia ennen vuoron alkua ja loppua. Ilmoituksessa on suora Leimaa sisään / ulos -painike, joka hoitaa leimauksen yhdellä napautuksella ilmoitusta suurentamatta.
* **Sormenjälkitunnistus:** Suojaa työvuorotoiveiden tallennuksen ja poiston biometrisellä tunnistuksella tai laitteen lukituskoodilla.
* **Taustaseuranta:** Tarkistaa säännöllisesti uudet julkaistut työvuorolistat ja päivittää kalenterin automaattisesti taustalla.
* **Salattu tallennus:** Käyttäjätunnukset ja salasanat tallennetaan laitteistotasolla salattuun muistiin (`EncryptedSharedPreferences`).

## Teknologiat

* **Kieli:** Kotlin
* **Käyttöliittymä:** Material Design 3 (Slate Dark -teema, mukautettu sumennusefekti)
* **Tietoturva & Biometria:** AndroidX Security Crypto & Biometric Prompt

## Asetukset

Ensimmäisellä käynnistyskerralla sovellus pyytää määrittämään:
1. **Organisaation osoitteen** (esim. `omaorganisaatio.numeronwfm.com`)
2. **Käyttäjätunnuksen ja salasanan**
3. **Biometrisen tunnistuksen** valinnan
4. **Automaattisen synkronoinnin** asetukset
5. **Kohdekalenterin** (voit valita haluamasi puhelimen kalenterin pudotusvalikosta)
6. **Leimausmuistutukset**

## Asennus kehittäjille

1. Kloonaa repositorio.
2. Avaa projekti **Android Studiossa**.
3. Varmista, että `app/build.gradle`-tiedostosta löytyvät vaadittavat riippuvuudet:
   ```groovy
   implementation "androidx.biometric:biometric:1.1.0"
   implementation "androidx.security:security-crypto:1.1.0-alpha06"

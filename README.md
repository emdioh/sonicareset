# Sonicare NFC

App Android per **leggere** il tag NFC delle testine Philips Sonicare e per
**tenere traccia manualmente** dell'usura delle testine.

> ⚠️ **Sola lettura.** L'app legge i dati accessibili del tag (UID, tecnologia,
> pagine leggibili senza autenticazione, eventuali record NDEF). **Non scrive,
> non azzera e non modifica** in alcun modo il contenuto del tag.

## Funzioni

- **Scansione NFC** — avvicina il retro del telefono alla testina: l'app mostra
  UID, tipo di tag, lista tecnologie, ATQA/SAK e il dump (sola lettura) delle
  pagine leggibili. Le pagine protette non vengono forzate.
- **Scrivi (sperimentale)** — scrive 4 byte su una pagina scelta del tag che
  presenti. Per le pagine protette la password può essere **ricavata dall'app**
  da UID + codice MFG (algoritmo ATC1441, vedi sotto) oppure inserita a mano;
  viene inviata come `PWD_AUTH` standard. L'app non fa brute force e non riprova
  mai automaticamente dopo un errore.
- **Testine** — registra a mano quando monti una testina e la sua durata
  obiettivo (default 90 giorni). L'app calcola giorni trascorsi, percentuale di
  usura e quando è il momento di sostituirla. Pulsante *Reset* quando monti una
  testina nuova.

### ⚠️ Avvertenze sulla scrittura

- Le **pagine 0-3** (UID / lock bytes / OTP) sono **bloccate** dall'app: scriverle
  è inutile o rende il tag **inutilizzabile in modo permanente**.
- Le pagine protette (come il **contatore d'uso** delle testine Sonicare)
  richiedono la **password del tag**. L'app può ricavarla da UID + MFG, oppure
  usare quella che inserisci tu.
- ⚠️ **Le testine NTAG213 hanno CFG1=0x43: dopo 3 password sbagliate la scrittura
  viene disabilitata PER SEMPRE.** L'app non riprova mai da sola. Verifica la
  password derivata prima di armare la scrittura.
- Scrivere valori errati sulle pagine di configurazione (PWD/AUTH0/ACCESS) può
  bloccarti fuori dal tag per sempre. Procedi solo su hardware tuo e di cui puoi
  accettare la perdita.

## Password NFC delle testine

La password di 4 byte che protegge le pagine del NTAG213 è **deterministica**:
si ricava dall'**UID del tag** e dal **codice MFG** stampato sotto la testina
(es. `221214 12K`, salvato nel tag a partire dalla pagina `0x21` offset 2).

Algoritmo reverse-engineered da [@ATC1441](https://github.com/atc1441) e
pubblicato in [questo gist](https://gist.github.com/atc1441/41af75048e4c22af1f5f0d4c1d94bb56):

```
crc  = CRC16(seed = 0x49A3, data = UID[7])          // CRC16-CCITT, poly 0x1021, MSB-first
crc |= CRC16(seed = crc,  data = MFG[10]) << 16     // il CRC dell'MFG è "seminato" con quello dell'UID
crc  = swap16(crc)                                  // scambia le due metà a 16 bit
password = crc big-endian                           // 4 byte inviati in PWD_AUTH (0x1B)
```

Implementazione in [`SonicarePassword.kt`](app/src/main/java/com/sonicareset/app/nfc/SonicarePassword.kt),
verificata con i vettori di test pubblicati:

| UID | MFG | Password |
|---|---|---|
| `04:EC:FC:A2:94:10:90` | `221214 12K` | `61F0A50F` |
| `04:D2:E9:B2:11:68:80` | `210607 43M` | `1D7E4BDF` |

Reimplementazioni indipendenti che concordano (tutte verificate):
[nicjes/SonicareGenerator](https://github.com/nicjes/SonicareGenerator) (JS),
[filipsworks/SoniKraker](https://github.com/filipsworks/SoniKraker) (Python),
[flohzirkus666/sonicare-rs](https://github.com/flohzirkus666/sonicare-rs) (Rust),
[Vilda007/sonicare](https://github.com/Vilda007/sonicare) (Flipper Zero, C).

Il contatore d'uso sta nella pagina `0x24` (byte 0-1). L'app si limita a fornire
la **password derivata** e a scrivere la pagina che scegli: nessun reset
automatico.

## Scaricare l'APK (senza compilare nulla)

Ogni push fa partire la GitHub Action **Build APK**, che produce un APK
installabile (firmato con la chiave di debug).

1. Vai nella scheda **Actions** del repository.
2. Apri l'ultima esecuzione di *Build APK* andata a buon fine.
3. In fondo, nella sezione **Artifacts**, scarica `SonicareNFC-debug-apk`.
4. Estrai lo zip e copia `SonicareNFC-debug.apk` sul telefono.
5. Installa l'APK (abilita *Installa app sconosciute* per il browser/file
   manager se richiesto).

### Via Release

Pubblica un tag che inizia per `v` e l'APK viene allegato automaticamente a una
GitHub Release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Troverai l'APK nella pagina **Releases** del repository.

## Compilare in locale

Serve l'Android SDK. Poi:

```bash
./gradlew assembleDebug
# APK in app/build/outputs/apk/debug/app-debug.apk

./gradlew testDebugUnitTest   # vettori di test della password
```

## Requisiti

- Android 8.0 (API 26) o superiore
- Dispositivo con NFC (la funzione Testine resta utilizzabile anche senza NFC)

## Licenza

GPL-3.0 — vedi [LICENSE](LICENSE).

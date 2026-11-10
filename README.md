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
- **Testine** — registra a mano quando monti una testina e la sua durata
  obiettivo (default 90 giorni). L'app calcola giorni trascorsi, percentuale di
  usura e quando è il momento di sostituirla. Pulsante *Reset* quando monti una
  testina nuova.

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
```

## Requisiti

- Android 8.0 (API 26) o superiore
- Dispositivo con NFC (la funzione Testine resta utilizzabile anche senza NFC)

## Licenza

GPL-3.0 — vedi [LICENSE](LICENSE).

# Vývoj

Pro backend použijte Node.js 22. Aktuální balík nemá běhové závislosti z npm, které by bylo potřeba instalovat.

```sh
npm run check
npm test
node --test ha-app/test/*.test.mjs
```

První příkaz kontroluje syntaxi JavaScriptu. Další spouštějí testy backendu, datových dohod webového rozhraní a balení/spouštění aplikace Home Assistantu. CI používá stejnou verzi Node.js. Počet testů se s vývojem mění, proto zde historické číslo nenahrazuje výsledek aktuálního běhu.

## Generovaný balík Home Assistantu

`ha-app/` obsahuje udržované zdroje balení. Jeho `app-config.yaml` se při balení mění na `config.yaml`. Pro nalezení aplikace v App Store je určený pouze úplný verzovaný `addon/config.yaml`.

Po úpravě běhových či balicích souborů vygenerujte nový adresář:

```sh
node ha-app/prepare-package.mjs ./dist/ha-tv-launcher-app
```

Výsledkem aktualizujte `addon/` a spusťte:

```sh
npm run check:addon
```

Kontrola porovnává dodávané soubory a metadata nezávisle na čase vytvoření. Do `addon/` nekopírujte `.env`, osobní `config.json`, fotografie, záznamy, relace ani vývojářský běhový adresář. Packager vybírá udržované zdroje a vytváří soupis souborů s kontrolními součty. Veřejné demonstrační snímky a video patří do `docs/ukazky/`, mimo běhový balík.

## Android klient

Android projekt obsahuje vlastní Gradle wrapper. Vývojové APK sestavte s výslovně zvolenou adresou backendu:

```sh
cd android
./gradlew :app:assembleDebug -PdashboardUrl='http://home-tv.local:4317/'
```

Požadavky na SDK/JDK najdete v [instalaci](setup.md) a [android/README.md](../android/README.md). Testy nativních protokolů jsou oddělené od zkoušky na TV. U vykreslování srovnávejte přímé snímky ve skutečném rozlišení zařízení a před/po měřte stejnou sekvenci tlačítek. U kamer rozlišujte vstup/dekódování, předávání z fronty, nativní vykreslení a viditelný pohyb.

## Hlášení problému

Uveďte model a OS TV/Androidu, verzi aplikace a backendu, dotčenou obrazovku a postup opakování. Přiložte malou část konfigurace se smyšlenými názvy entit místo úplného exportu domácnosti. Ze sdílených logů odstraňte tokeny, párovací kódy, síťové adresy, polohy osob, soukromé záznamy kalendáře a obrázky.

# Instalace a párování

Potřebujete televizi umožňující instalaci aplikací Android TV, backend dostupný z této televize a vlastní konfiguraci entit Home Assistantu. Rozhraní si můžete nejprve prohlédnout ve webové ukázce bez živých dat.

## 1. Zvolte umístění backendu

### Home Assistant App Store

Na instalaci Home Assistantu s podporou aplikací:

1. Otevřete **Nastavení → Aplikace → App Store** a nabídku pro správu repozitářů.
2. Přidejte `https://github.com/michalschoniger-ops/ha-tv-launcher`.
3. Z tohoto repozitáře nainstalujte **HA TV Launcher**.
4. Nastavte `public_origin` na adresu, kterou bude skutečně používat televize, včetně zveřejněného portu. Například `http://home-tv.local:4317` použijte jen tehdy, pokud tento název ve vaší síti vede na backend.
5. Nastavte vlastní párovací kód s alespoň osmi znaky. Použijte jinou hodnotu než přístupový token Home Assistantu a tajný klíč webhooku.
6. Nahraďte nebo odstraňte zástupné entity `example_*`, přiřaďte vlastní zdroje a spusťte aplikaci. Místnosti se nevyhledávají automaticky.

Připojení `supervisor` používá přístupový údaj předaný Home Assistantem za běhu. Alternativa `direct` vyžaduje vlastní `ha_url` a `ha_access_token`. Úplný popis voleb a trvalé konfigurace je v [ha-app/README.md](../ha-app/README.md).

Přidávání repozitářů popisuje [oficiální návod Home Assistantu](https://www.home-assistant.io/common-tasks/os/#installing-a-third-party-app-repository). Adresář [`addon/`](../addon/) obsahuje nasaditelný sestavovací balík; [`ha-app/`](../ha-app/) obsahuje jeho udržované zdroje.

### Samostatný server Node.js

Použijte Node.js 22. V kořeni repozitáře spusťte:

```sh
cp .env.example .env
cp config.example.json config.json
```

Do `.env` doplňte vlastní nastavení:

```dotenv
HOST=0.0.0.0
PORT=4317
HA_URL=https://homeassistant.example.net
HA_TOKEN=REPLACE_WITH_YOUR_HOME_ASSISTANT_TOKEN
TV_CLIENT_TOKEN=REPLACE_WITH_YOUR_OWN_PAIRING_CODE
TV_WEBHOOK_TOKEN=REPLACE_WITH_A_DIFFERENT_RANDOM_SECRET
TV_PUBLIC_ORIGIN=http://home-tv.local:4317
CONFIG_PATH=./config.json
TV_SESSION_STORE=./private/state/paired-sessions.json
```

Názvy serverů a řetězce přístupových údajů jsou pouze zástupné hodnoty. `TV_PUBLIC_ORIGIN` musí odpovídat protokolu, hostiteli a portu používanému televizí a prohlížečem. V `config.json` nastavte vlastní entity a spusťte `npm start`. Reverzní proxy musí průběžně předávat SSE odpovědi; stejnou vnější adresu pak použijte při sestavení Android aplikace.

Režim `demo` znamená, že není zadané připojení Home Assistantu. Režim `setup` označuje neúplné připojení, konfiguraci nebo párování. Režim `live` znamená, že backend má potřebné nastavení; jednotlivé entity mohou být stále nedostupné. Dostupnost proto posuzujte pro každý zobrazený zdroj zvlášť.

## 2. Sestavte vlastní APK pro TV

Nainstalujte Android Studio nebo Android SDK, platformu Android SDK 36 a JDK kompatibilní s přibaleným Android Gradle Pluginem. Projekt cílí na Java 17. Otevřete `android/` v Android Studiu nebo použijte přibalený Gradle wrapper:

```sh
cd android
./gradlew :app:assembleDebug \
  -PdashboardUrl='http://home-tv.local:4317/'
```

Adresu nahraďte vlastní adresou backendu. Televize se připojuje přímo na ni. HTTP sestavení povoluje nešifrované spojení pouze k výslovně zvolenému lokálnímu hostiteli; HTTPS používá důvěryhodné systémové certifikáty. Název dostupný z počítače nemusí být dostupný i z TV, proto případně upravte vlastní DNS nebo síť.

Pro menší APK určené konkrétnímu zařízení lze přidat `-PtvAbi=arm64-v8a` nebo `-PtvAbi=armeabi-v7a` podle skutečné ABI zařízení. Výstup je `android/app/build/outputs/apk/debug/app-debug.apk` vzhledem ke kořeni repozitáře. Jde o vývojově podepsané APK; pro vlastní průběžná vydání si udržujte vlastní podpisový klíč.

Identifikátor aplikace je `org.hometv.launcher`. Sestavovací nástroje a nativní závislosti se stahují z repozitářů uvedených v Android projektu. Soukromá sestavovací cache ani APK původní instalace nejsou potřeba.

## 3. Nainstalujte aplikaci a spárujte ji

Přeneste APK do vlastní TV, případně jej nainstalujte přes již autorizované připojení Android debugging:

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Spusťte **HA TV Launcher**, otevřete nastavení/párování a zadejte kód svého backendu. Úspěšné párování vytvoří relaci backendu; přístupový token Home Assistantu se do APK nekopíruje. Trvalé úložiště relací umožňuje zachovat párování přes restart backendu.

Pokud to TV podporuje, vyberte launcher v nastavení domovské aplikace. Tato volba, oprávnění k překryvným oznámením a chování ovladače se liší podle výrobce. Oznámení nad jinými aplikacemi vyžadují odpovídající oprávnění Androidu.

Ověřte Home, směrové šipky, Zpět, spuštění aplikace a návrat, obnovení dat backendu při vybrané jiné dlaždici i každý zamýšlený profil kamery. Přehrávání potřebuje zkoušku s pohybující se scénou na skutečné TV. Viz [omezení](limitations.md).

## Vlastní lokální balík pro Home Assistant

Z vlastního checkoutu lze vytvořit nový balík:

```sh
node ha-app/prepare-package.mjs ./dist/ha-tv-launcher-app
```

Cílový adresář nesmí předem existovat. Výsledek zkopírujte do vlastního lokálního adresáře aplikací/addonů Home Assistantu postupem podporovaným danou instalací a znovu načtěte App Store. Konfigurace a přístupové údaje patří do trvalých soukromých dat instalace. [Vývojový návod](development.md) popisuje obnovu verzovaného balíku `addon/`.

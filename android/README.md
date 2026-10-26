# HA TV Launcher — nativní launcher pro Android TV

HA TV Launcher nabízí nativní nabídku vykreslovanou přes Android Canvas: stránky
ovládané dálkovým ovladačem, zkratky aplikací, rodinný kalendář, údaje z Home
Assistantu, měření místností, oznámení, přehrávání médií a mapové přehledy.
Výchozí připojení míří na obecný, dosud nespárovaný server
`http://home-tv.local:4317/`. Vlastní server a zdroje dat nastavíš při sestavení
aplikace a v konfiguraci serveru.

## Sestavení

Potřebuješ JDK 21, Android SDK platformu 36 a Build Tools 35.0.0. Přiložený wrapper
používá Gradle 8.13; projekt dále AGP 8.13.2, Kotlin 2.2.20, JVM 17 a minSdk 24.
Nainstaluj SDK a místně nastav `ANDROID_HOME` a `JAVA_HOME`, potom spusť:

```sh
./gradlew -PdashboardUrl=http://home-tv.local:4317/ :app:assembleDebug
```

`dashboardUrl` musí obsahovat jediný úplný origin serveru: schéma, hostitele
a případně port, bez přihlašovacích údajů, další cesty, dotazu nebo fragmentu.
Podporované je HTTPS. HTTP je povolené pouze pro soukromou IPv4, `localhost`,
`.local` nebo `.home.arpa`; vygenerovaná síťová politika Androidu otevře
nešifrované spojení jen k přesně zvolenému hostiteli.

Volitelně můžeš omezit nativní knihovny parametrem `-PtvAbi=armeabi-v7a` nebo
`-PtvAbi=arm64-v8a`. WebRTC se běžně načítá z Mavenu jako
`io.github.webrtc-sdk:android:150.7871.04`.

Pro sestavení bez přístupu k síti použij předem připravený AAR:

```sh
./gradlew --offline -PdashboardUrl=http://home-tv.local:4317/ \
  -PwebrtcAarPath=/cesta/k/android-150.7871.04.aar :app:assembleDebug
```

Ostatní závislosti Gradlu a Androidu už musejí být v místní mezipaměti. Cesty
k vlastnímu AAR a SDK, sestavovací výstupy ani podpisové klíče neukládej do Gitu.
Testovací APK vznikne v `app/build/outputs/apk/debug/`.

## Párování a údaje domácnosti

Po instalaci spáruj televizi s vlastním serverem. Launcher používá jeho předem
vymezené API. Přihlašovací údaje Home Assistantu zůstávají na serveru; Android
ukládá jen cookie své spárované relace. Projekt neobsahuje osobní adresy serverů,
rodinné údaje, skutečná ID domácích čidel, fotografie ani přístupové údaje.

Měření vybírá výslovné mapování v konfiguraci serveru. Nedostupné hlavní čidlo
se nenahrazuje teplotou regulátoru. Režim regulátoru a skutečné vytápění podle
měřeného příkonu se zobrazují odděleně; ukázková hodnota se nevydává za živé měření.

Oznámení nad ostatními aplikacemi vyžadují oprávnění Androidu pro překryvná okna
a oznámení. Veřejný server má akce dveří a nočního režimu vypnuté. Jejich případné
zapojení vyžaduje vlastní ověřenou integraci s přípravou a potvrzením akce.

Google Mapy zobrazují pouze aktuálně dostupné souřadnice dodané tvým serverem;
žádná osoba ani domácí poloha není vložená napevno. Zobrazením mapy se tyto
souřadnice předávají Googlu. Samostatný kandidát Leaflet/OSM také pracuje jen
s polohami z běžícího serveru a zachovává označení poskytovatele mapy. Ve výchozím
zapojení není aktivním mapovým řadičem.

## Experimentální kamery

Aktuální zdroj kamer obsahuje **experimentální** plánování snímků podle absolutních
hodin a diagnostiku časování s omezenou pamětí. Offline testy a úspěšné sestavení
APK nedokazují plynulé přehrávání, dokončení nativního vykreslování, kompatibilitu
konkrétní televize ani kvalitu fyzického obrazu. Tato veřejná verze nemá úplnou
přejímku plynulosti kamer na skutečné TV. Ověř vlastní zdroj, kodek, zařízení,
poměr obrazu a časování. Diagnostické záznamy jsou číselné, bez obrazových bufferů
a přístupových údajů; jejich exporty přesto uchovávej jako soukromá provozní data.

## Licence a kompatibilita

Podklady třetích stran zůstávají v `THIRD_PARTY_NOTICES.md` a
`app/src/main/assets/licenses/`. Samotné sestavení a offline testy aplikaci
neinstalují ani nepřipojují televizi či Home Assistant.

Viditelný název je HA TV Launcher. Kvůli kompatibilitě zůstává ID balíčku
`org.hometv.launcher`, interní názvy `HomeTV` a schéma `hometv://` zachované.

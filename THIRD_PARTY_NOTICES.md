# Informace o komponentách třetích stran

Licence [MIT](LICENSE) se vztahuje na vlastní kód projektu. Následující komponenty si zachovávají původní licence a upozornění. Při dalším šíření zdrojů nebo APK zachovejte příslušné licenční texty.

| Komponenta | Použití | Licence / přibalené informace |
|---|---|---|
| Gradle wrapper | Spuštění Android sestavení | Apache-2.0; [android/LICENSE-Gradle.txt](android/LICENSE-Gradle.txt). Adresa distribuce a kontrolní součet jsou v `android/gradle/wrapper/gradle-wrapper.properties`. |
| AndroidX Media3 | Nativní přehrávání médií a HLS | Apache-2.0; stahuje se jako deklarovaná Maven závislost. [Zdroj a licence AndroidX Media](https://github.com/androidx/media). |
| WebRTC SDK for Android, `150.7871.04` | Nativní přenos a vykreslování kamer | Distribuce SDK pod MIT, původní WebRTC pod licencí typu BSD a patentovým ujednáním, plus informace o dalších komponentách. Úplné soubory jsou v [android/app/src/main/assets/licenses/](android/app/src/main/assets/licenses/). |
| hls.js light, `1.7.3` | HLS ve webovém rozhraní | Apache-2.0; upozornění Dailymotion a Brightcove jsou v [public/vendor/hls.LICENSE.txt](public/vendor/hls.LICENSE.txt). |
| Leaflet, `1.9.4` | Přibalené experimentální mapové podklady | BSD-2-Clause; [LEAFLET-LICENSE.txt](android/app/src/main/assets/family-map/LEAFLET-LICENSE.txt). Copyright 2010–2023 Volodymyr Agafonkin a 2010–2011 CloudMade. |
| Natural Earth 1:110m Admin 0 Countries | Zjednodušené obrysy map | Volné dílo (public domain). Metadata zdroje a podmínek zůstávají v GeoJSON podkladech. [Podmínky Natural Earth](https://www.naturalearthdata.com/about/terms-of-use/). |

## WebRTC soubory přibalené do APK

- `WEBRTC-SDK-MIT.txt`
- `WEBRTC-BSD.txt`
- `WEBRTC-PATENTS.txt`
- `WEBRTC-THIRD-PARTY.md`

Sousední `README.txt` obsahuje původní adresy a kontrolní součty. Úplný přibalený soupis třetích stran zůstává zachovaný; tento přehled jej nenahrazuje. Zdroj vydavatele: [webrtc-sdk/android](https://github.com/webrtc-sdk/android/releases/tag/v150.7871.04).

## Mapy a vnější služby

Data a dlaždicová služba OpenStreetMap mají vlastní [licenční informace a uvádění zdroje](https://www.openstreetmap.org/copyright) a [podmínky používání dlaždic](https://operations.osmfoundation.org/policies/tiles/). Google Maps je vnější služba s vlastními podmínkami a povinným uvedením zdroje. Licence MIT tohoto projektu podmínky těchto služeb nemění.

Názvy Home Assistant, Android a mediálních služeb popisují integrace a neznamenají jejich podporu či účast na projektu. Grafika nainstalovaných aplikací se získává ze zařízení uživatele. [Veřejné ukázky](docs/ukazky/README.md) zachycují původní instalaci; běhové nasazení používá vlastní konfiguraci.

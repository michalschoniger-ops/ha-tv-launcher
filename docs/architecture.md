# Architektura

## Klient Android TV

Hlavní rozhraní tvoří nativní Kotlin `View`. Pomocí Android Canvas kreslí karty, text, výběr a přechody. Používá návrhový souřadnicový prostor 1440 × 810 přizpůsobený skutečnému rozlišení. Uchované RenderNode se nahrávají v nativním pixelovém rozlišení a obnovují při změně geometrie nebo příslušného obsahu.

Akce ovladače se přiřazují nativním cílům výběru. Android intenty spouštějí nainstalované aplikace. Nastavení, párování a podrobnosti obsluhují nativní dialogy. Video kamer používá Android renderer WebRTC a běžná média Media3. Volitelná mapa má oddělený WebView; hlavní launcher se vykresluje nativně.

Klient komunikuje s jedním backendem vybraným při sestavení přes Gradle vlastnost `dashboardUrl`. Párování a autorizované API požadavky používají relační cookie backendu. Přístupový token Home Assistantu není parametrem Android sestavení.

## Backend Node.js

`server.mjs` poskytuje webové rozhraní a API. Udržované zdroje jsou v `server.mjs`, `lib/` a `public/`; aplikace Home Assistantu dodává jejich vygenerovanou kopii.

Backend:

- Čte stavy Home Assistantu, kalendáře a náhledy kamer přes REST API.
- Udržuje WebSocket připojení pro oznámení, změny stavů/události a signalizaci kamer.
- Převádí výslovně nakonfigurované entity do datového modelu přehledu.
- Posílá klientům oznámení, vybrané aktualizace a události signalizace kamer přes server-sent events (SSE).
- Před předáním povolených akcí kontroluje relaci, origin požadavku a povolené funkce.

Webové rozhraní slouží k demonstračnímu režimu a správě. Android launcher používá vlastní nativní vykreslování.

## Základní API

| Cesta | Účel |
|---|---|
| `GET /api/bootstrap` | Režim, stav párování a dostupné funkce klienta. |
| `POST /api/pair` | Výměna nakonfigurovaného párovacího kódu za relační cookie. |
| `POST /api/logout` | Zrušení této relace. |
| `GET /api/dashboard` | Data přehledu; simulovaná v režimu demo, autorizovaná v režimu live. |
| `GET /api/events` | Autorizované průběžné aktualizace přes SSE. |
| `POST /api/cameras/:id/webrtc` | Zahájení signalizace výslovně nakonfigurované kamery. |
| `/api/webrtc/:sessionId/...` | ICE kandidáti, události a ukončení přenosu vázané na vlastníka relace. |

Jde o implementační rozhraní, ne závazek neměnné veřejné verze API. Před vytvořením dalšího klienta si projděte odpovídající validaci serveru a testy.

## Mediální cesta kamer

Android klient vytvoří nabídku WebRTC. Backend vymění signalizaci s Home Assistantem a kandidáti procházejí autorizovanými HTTP/SSE cestami. Vlastní média přenáší protistrana WebRTC příslušné integrace kamery/Home Assistantu. Node backend každý snímek WebRTC nedekóduje a znovu nekóduje.

Náhledy a HLS používají samostatné backendové cesty a validaci zdrojů. Jejich úspěšné načtení samo neověřuje časování WebRTC ani plynulost. Také počet předání rendereru nepotvrzuje fyzické zobrazení snímků na panelu.

## Konfigurace a trvalá data

Repozitář obsahuje obecnou ukázkovou konfiguraci se zástupnými entitami. Živá instalace dodává vlastní entity, přístupové údaje, párovací klíče a případné fotografie. Volby aplikace Home Assistantu určují připojení a origin; trvalá běhová data a relace se uchovávají mimo dodávaný kód.

Samostatný server ve výchozím nastavení naslouchá jen lokálně. Aplikace Home Assistantu službu spouští uvnitř kontejneru a zveřejňuje nastavený port. Po nasazení potřebuje televize tento backend a Home Assistant; vývojový počítač není součástí provozu.

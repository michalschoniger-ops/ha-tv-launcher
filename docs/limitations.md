# Známá omezení

Projekt byl vyvíjený a fyzicky zkoušený na instalaci TCL s Android/Google TV. Ověření pro Xiaomi TV/boxy ani ostatní výrobce zatím není hotové. Deklarované minimální SDK Androidu popisuje sestavitelnost, nikoli záruku stejného chování launcheru, překryvných oznámení, ovladače nebo médií na každém firmwaru.

## Kamerové přenosy jsou experimentální

Integrace kamer, kodeky a hardwarové dekodéry se liší. Časování a práce s frontou u HD přehrávání stále potřebují zkoušky na konkrétních zařízeních. Automatické testy, počty dekódovaných snímků, síťové statistiky a předání rendereru měří odlišné části přenosu. Žádný z těchto ukazatelů sám nepotvrzuje plynulý viditelný pohyb na televizi.

Přehrávání srovnávejte za stejných podmínek, včetně stavu zrcadlení či záznamu obrazovky. Sledujte pohybující se scénu na fyzické obrazovce a odděleně zaznamenejte chyby dekodéru, zastavení obrazu, ztráty fronty a chování rendereru. Samotné vypnutí zrcadlení není obecnou opravou přehrávání.

## Funkce závislé na konkrétní instalaci

Model místností umožňuje výslovné přiřazení zdrojů. Použitelný přehled přesto vyžaduje vlastní entity a skutečné jednotky čidel. Čtení stavů i nastavitelné ovládání světel a teploty jsou součástí projektu. Přehled zabezpečení, hlasové povely nebo integrace vysavače mohou vedle konfigurace vyžadovat také přizpůsobení kódu.

Fyzické otevření zámku a původní domácí noční makro ve veřejné verzi nejsou. Zástupné implementace vracejí nedostupnost a neposílají požadavky Home Assistantu. Doplnění těchto funkcí vyžaduje samostatně prověřenou implementaci pro zamýšlenou instalaci. Nativní rozhraní se řídí možnostmi backendu a nedostupné akce nechává zakázané.

Rozhraní je nyní česky a část formátování kalendáře/domácnosti používá `Europe/Prague`. Další jazyky a širší nastavení časového pásma potřebují dopracovat.

Volitelné mapy vyžadují internet a dostupnou službu poskytovatele. Google Maps a OpenStreetMap mají vlastní podmínky a povinné uvedení zdroje. Zdrojový projekt obsahuje také kandidátní mapové podklady Leaflet; jejich přítomnost sama neznamená, že daný mapový řadič používá každé sestavení.

## Sestavení a testy

Repozitář obsahuje zdrojové kódy, veřejné podklady třetích stran a výslovně zveřejněné [ukázky původní instalace](ukazky/README.md). Každý uživatel si sestaví APK pro svůj backend a vlastní nasazení. Hotové APK přednastavené pro libovolný Home Assistant se zde nedodává.

CI kontroluje Node kód a balík Home Assistantu. Android kompilace je samostatný krok, po kterém následuje ověření ovladače a přehrávání na cílovém zařízení. Prošlá sada testů není fyzickou instalací ani přejímkou hardwaru.

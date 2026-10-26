# HA TV Launcher pro Home Assistant

Aplikace provozuje server v Node.js vedle HA Core. Televize se připojuje přímo k adrese zařízení s Home Assistantem na portu 4317; pro běžný provoz není potřeba vývojový počítač. Každá instalace má vlastní konfiguraci, párování, profil i fotografie. Repozitář obsahuje pouze obecné příklady.

## Instalace

V HA OS přidejte [repozitář HA TV Launcher](https://github.com/michalschoniger-ops/ha-tv-launcher) do obchodu aplikací a nainstalujte **HA TV Launcher**. Hotový instalační adresář je `addon/`. Zdrojový adresář `ha-app/` používá šablonu `app-config.yaml`, aby jej Supervisor nepovažoval za druhou aplikaci.

Při vývoji nebo ruční místní instalaci vytvořte nový instalační adresář z kořene repozitáře:

```sh
node ha-app/prepare-package.mjs /absolutni/nova/slozka/ha-tv-launcher
```

Výstup má vlastní `config.yaml`, Dockerfile, soubory serveru a manifest SHA-256. Přípravný skript kopíruje jen vybrané zdroje; osobní `.env`, `config.json`, `private/` ani fotografie se do obrazu nepřenášejí. Cílový adresář musí být nový. Instalujte jej běžným postupem pro místní aplikaci HA OS. Ruční instalace i instalace z GitHubu používají stejný interní identifikátor `home_tv_launcher`.

## První spuštění

1. Nastavte `public_origin` na skutečnou adresu hostitele dostupnou z TV. Výchozí název hostitele případně změňte podle své sítě. Android aplikace musí používat stejnou základní adresu služby a povolovat jejího hostitele.
2. Výchozí `ha_connection: supervisor` použije interní REST API, WebSocket a token předaný Supervisorem. Pro přímé spojení `direct` vyplňte vlastní `ha_url` a `ha_access_token` v soukromých volbách aplikace.
3. Zadejte vlastní dlouhý náhodný `pairing_code`, který pak zadáte v TV, a jiný náhodný `webhook_token`, pokud chcete posílat oznámení. Při vynechání se hodnoty vytvoří náhodně v soukromém `/data` a nevypisují se do protokolu. Pro běžné párování pak nastavte známý vlastní kód ve volbách a restartujte aplikaci.
4. První start vytvoří `/data/config.json` z generického `config.example.json`. Entity `example_*` jsou pouze zástupné názvy: nahradíte je vlastními skutečnými entitami. Neznámé zdroje se nemění na falešné živé hodnoty.

## Vlastní mapování a aktualizace

Zkopírujte obecný `config.example.json`, upravte jej pro svou instalaci a vložte jako `config.json` do vlastní konfigurační složky aplikace (uvnitř kontejneru `/config/config.json`). V HA OS je to vlastní složka aplikace v `addon_configs`, nikoli veřejný adresář webového serveru. JSON slouží k mapování zdrojů; HA tokeny a hesla patří do soukromých voleb aplikace.

Nastavte `config_import_revision: 1` a restartujte aplikaci. Při další záměrné změně souboru číslo zvyšte na 2, 3 atd. Pouze vyšší revize požádá o nový import. Před importem se nový JSON zkontroluje; původní konfigurace se uchová v soukromé záloze `/data/config-before-import-N.json`, nový soubor se uloží atomicky a vznikne soukromý záznam importu. Přerušený import lze se stejným souborem a revizí bezpečně zopakovat; neočekávanou mezilehlou změnu konfigurace aplikace odmítne přepsat.

Běžné restarty a aktualizace nemění osobní `/data/config.json`, relace, vytvořené tajné hodnoty, profil ani fotografie. Přepisuje se pouze dodaný kód a veřejné soubory aplikace. Osobní fotografie a vlastní soubory proto neukládejte do spravovaného `runtime/public`. Aktualizace zachovává párování. Osobní konfigurační soubor ani soukromé zálohy neposílejte do veřejného repozitáře.

`night_control_enabled` je ve výchozím stavu vypnuté. Aplikace si nežádá přístup k Docker socketu, síti hostitele ani správě Supervisoru. TCP watchdog kontroluje pouze běžící server; dostupnost HA, kamery a skutečné TV ověřte samostatně. Přímý HTTP port používejte v důvěryhodné místní síti. WebRTC/HLS a ovládání TV ověřte pro vlastní zařízení a zdroje.

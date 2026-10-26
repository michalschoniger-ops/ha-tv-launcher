# HA TV Launcher — oznámení a zvonek přes jiné TV aplikace

`TvOverlayService` zajišťuje oznámení a zvonek nad ostatními aplikacemi. Jde o nativní Android okno, nikoli překryv uvnitř webové stránky. Služba nemá mikrofon, záznam obrazovky, přístupový token HA, libovolné spouštění aplikací ani příkazů. Skutečné překrytí Netflixu a chování při uspání se musí ověřit na konkrétní televizi; samotné sestavení je nedokazuje.

## Události z domácího mostu

Služba čte SSE z pevného `BuildConfig.DASHBOARD_URL` a cesty `/api/events`. Z nativního `CookieManager` převezme pouze platnou cookie `tv_session`; jiné cookies zahodí. Nepovoluje přesměrování a adresu odvozuje výhradně z originu zvoleného při sestavení.

```text
event: notification
data: {"id":"notice-example","title":"Pračka","message":"Prádlo je připravené."}

event: doorbell
data: {"id":"doorbell-example","title":"Někdo zvoní","message":"Pohled u dveří","cameraId":"entrance"}

```

`cameraId` musí být skutečné ID kamery nakonfigurované v mostu, ve tvaru `[a-z0-9_-]{1,80}`. Překryv zvonku navíc používá povolené identifikátory z `NativeDoorbellPolicy`; v tomto zdroji jsou to obecné role `entrance`, `cars`, `garden` a `outside`. Nativní část nikdy nepoužije URL z události. Snímek bere pouze z `/api/cameras/{cameraId}/snapshot`, opět s párovací cookie a bez přesměrování.

- Běžné oznámení se zobrazí na 8 sekund nahoře vpravo. Text a ID jsou omezené; stejný nezměněný obsah se 30 sekund neopakuje.
- Zvonek zobrazí kartu se snímkem na 12 sekund. Snímky se načítají sériově, s pauzou 1 sekundu po dokončení předchozího požadavku. Pokud kamera potřebuje 2,8 sekundy, nevytváří další souběžné požadavky.
- Každý snímek má limit 2 MiB a omezené rozměry pro dekódování; zobrazení používá vzorek nejvýše přibližně 960 × 720 px. Dočasný výpadek ponechá poslední obraz.
- SSE řádek i událost mají limit 16 KiB znaků. Předání do UI má nejvýše osm čekajících událostí, čtyři zpracované za jeden běh; záznam opakování má nejvýše 128 položek. Během zvonku se uchovají nejvýše tři běžná oznámení a po jeho ukončení se vezme nejnovější.
- `FLAG_NOT_FOCUSABLE` ponechává tlačítka ovladače původní TV aplikaci; překryv nezastavuje ani nespouští přehrávání. Po návratu do hlavní aktivity se nativní překryv odstraní a události nechá webové verzi, aby nevznikaly duplicitní toasty.

## Spuštění a oprávnění

Služba se spouští z aktuálně viditelné aktivity po nalezení párovací cookie, jen pokud je povolené zobrazování přes jiné aplikace. Aktivita tento stav krátce kontroluje i po párování webovým formulářem. Služba nevyužívá boot receiver ani předstíranou výjimku pro spuštění na pozadí. Pokud server odmítne cookie HTTP 401/403, stejnou cookie znovu nezkouší; po novém párování je možné spojení obnovit.

Manifest deklaruje `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` a `POST_NOTIFICATIONS`. Pro Android 14+ je uveden typ `specialUse` a jeho konkrétní podtyp. Na starších systémech služba používá dostupné rozhraní služby na popředí. Obsahuje tiché průběžné systémové oznámení se zastavením. Nativní nabídka přes MENU umožňuje překryv vypnout/zapnout; vypnutí se ukládá, aby ho periodická kontrola sama znovu nespouštěla.

Android 13+ má zvláštní oprávnění pro systémová oznámení. To je potřeba odlišit od oprávnění kreslit překryv; samotné `POST_NOTIFICATIONS` nezpřístupní okna přes Netflix. Příprava zdrojů ani sestavení tato oprávnění na TV nemění.

Služba je `START_NOT_STICKY`: násilné zastavení nebo zabití procesu ji ukončí; znovu ji nastartuje otevření launcheru, pokud zůstala povolená. Nepopisuje se jako garantované doručení bezpečnostních alarmů. Android, konkrétní aplikace nebo OEM mohou okna omezit a jejich chování se musí ověřit živě.

## Ověření zdrojů

```sh
python3 scripts/test-android-overlay.py --toolchain /cesta/k/toolchain.json
```

Příkaz spusť z kořene repozitáře; cestu můžeš předat také proměnnou `HOME_TV_TOOLCHAIN`. Testy používají připravený místní JDK, dočasnou složku a žádnou TV ani síť. Ověřují 61 kontrol cookie, injekcí, pevného originu, ID kamer, SSE s CRLF/více řádky, nedokončených rámců, přetečení a neznámých událostí. Nejsou náhradou testu služby Androidu na popředí nebo skutečného okna.

## Podklady Androidu

- [Typy foreground služeb](https://developer.android.com/develop/background-work/services/fgs/service-types): `specialUse` vyžaduje odpovídající oprávnění a popis podtypu v manifestu.
- [Změny foreground služeb](https://developer.android.com/develop/background-work/services/fgs/changes): Android 15 zpřísňuje spuštění z pozadí u `SYSTEM_ALERT_WINDOW`. Náš start pochází z viditelné aktivity.
- [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams): `TYPE_APPLICATION_OVERLAY` vyžaduje zvláštní oprávnění; `FLAG_NOT_FOCUSABLE` nepřebírá klávesový fokus.
- [Oprávnění systémových oznámení](https://developer.android.com/develop/ui/compose/notifications/notification-permission): Android 13+ rozlišuje povolení oznámení od samotného spuštění foreground služby, která stále musí vytvořit průběžné oznámení.

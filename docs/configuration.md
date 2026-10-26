# Vlastní mapování Home Assistantu

Začněte souborem `config.example.json`. U samostatného serveru jej zkopírujte do neverzovaného `config.json`; u aplikace Home Assistantu použijte postup pro trvalou konfiguraci z [ha-app/README.md](../ha-app/README.md).

Ukázková konfigurace obsahuje zřetelně označené zástupné entity `example_*`. Nahraďte je nebo odstraňte a nastavte zdroje, které chcete zobrazovat. Samotný název oblasti či zařízení Home Assistantu neurčuje, které čidlo měří konkrétní místnost.

## Místnosti

`domesticPanel.rooms` výslovně určuje místnosti a jejich pořadí. Každá místnost může obsahovat environmentální čidla, jeden hlavní regulátor, senzory přítomnosti a vybavení. Následující část ukazuje názvy polí; ID entit jsou zástupná:

```json
{
  "domesticPanel": {
    "rooms": [
      {
        "id": "example-room",
        "name": "Ukázková místnost",
        "temperatureEntityId": "sensor.example_room_temperature",
        "humidityEntityId": "sensor.example_room_humidity",
        "co2EntityId": "sensor.example_room_co2",
        "entityId": "climate.example_room",
        "heatingPowerEntityId": "sensor.example_room_heater_power"
      }
    ]
  }
}
```

Vlastní přiřazení vložte do úplné konfigurace a zachovejte ostatní nesouvisející části. Místnost může mít pouze měřicí čidla; samostatná klimatizace nemusí tvořit druhou místnost.

Pro datový model místností v2 platí:

- Velká teplota pochází z výslovně přiřazeného čidla. Nedostupné čidlo se nenahrazuje teplotou regulátoru.
- Vlhkost, CO₂ a PM2.5 mají vlastní ověřený zdroj a jednotku. Nedostupná hodnota zůstává nedostupná; nula je platné měření.
- Cíl a hlášený akční stav regulátoru se rozlišují od měření prostředí.
- Potvrzený údaj „Topí“ vyžaduje platný příkon výslovně přiřazeného topidla. Požadovaný režim topení sám nepotvrzuje fyzický chod.
- Přítomnost a kontakty mají stavy ano/ne/neznámý. Neznámý stav neznamená nepřítomnost ani zavřený kontakt.

Pokud obsah přesáhne kapacitu obrazovky, rozhraní používá stránky místo posouvání dlouhého seznamu.

## Další přehledy

V ukázkovém souboru použijte odpovídající části pro kalendář/počasí, kamery, energii/vodu, pračku/vysavač, oznámení a volitelné funkce domácnosti. Vyplňte pouze potřebná pole. Některé integrace vyžadují určité domény entit, atributy nebo jednotky; přijatelný formát určují normalizační funkce v `lib/`.

Ovládání světel a teploty se řídí výslovně povolenými funkcemi backendu. Ostatní integrace mohou kromě konfigurace vyžadovat i úpravu kódu. Fyzické otevření zámku a původní domácí noční makro jsou ve veřejné verzi nedostupné; jejich doplnění potřebuje samostatně prověřenou implementaci. Samotné čtení senzorů nezajišťuje funkční ovládání alarmu či vysavače. Viz [známá omezení](limitations.md).

## Adresy, párování a tajné údaje

`TV_PUBLIC_ORIGIN` u samostatného serveru nebo `public_origin` ve volbách aplikace určují adresu backendu používanou klienty. Při sestavení nastavte stejný origin do `dashboardUrl`. Požadavek s nepovoleným hostitelem či originem může být odmítnutý i při dostupném serveru.

Token Home Assistantu, párovací kód TV a tajný klíč webhooku mají odlišné účely. Nastavte vlastní hodnoty a uchovávejte je v soukromých volbách nasazení. Běhovou konfiguraci, relace ani fotografie nevkládejte do Git commitů a veřejných diagnostických balíků. Pro spojení mimo důvěryhodnou lokální síť použijte HTTPS.

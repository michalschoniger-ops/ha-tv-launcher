# Volitelná integrace oznámení z Home Assistant

`home_tv.yaml` obsahuje obecné REST příkazy pro oznámení a média v aplikaci **HA TV Launcher**. Definice si začleníte do své instalace; samy nevytvářejí automatizace ani neaktivují žádné akce.

Definice začleňte do svého spravovaného HA YAML podle způsobu instalace. Podle `secrets.example.yaml` vyplňte vlastní hodnoty do soukromého `secrets.yaml`, zkontrolujte konfiguraci a načtěte ji postupem své verze HA. Pro oznámení použijte samostatný `webhook_token` nastavený v aplikaci.

```yaml
action: rest_command.home_tv_notify
data:
  title: "HA TV Launcher"
  message: "Ukázkové oznámení"
  notification_type: info
  duration_seconds: 12
```

```yaml
action: rest_command.home_tv_media
data:
  title: "Moje video"
  media_url: "/media/my-video.mp4"
  media_type: "video/mp4"
```

Odpověď HTTP 202 potvrzuje přijetí serverem, nikoli zobrazení na TV nebo přehrání média. Vlastní soubor musí být v povolené lokální složce nebo na výslovně povoleném hostiteli. Automatizace vytvářejte nad vlastními skutečnými entitami a ověřeným průběhem událostí.

# NOTICE

Hubitat Device Health
Copyright (c) 2026 Ilia Gilderman

This product is original work except where noted below.

## Hubitat endpoint documentation (not copied code)

The app reads undocumented Hubitat admin JSON endpoints. Paths and
field names come from public community documentation. No source code
was copied from those projects.

- Hubitat endpoints wiki — UltronOfSpace
  https://github.com/UltronOfSpace/Hubitat
  Used for:
  - `/hub/zwaveDetails/json`
  - `/hub/zigbeeDetails/json`
  - `/device/list/data`

## Loki push pattern

The Grafana Cloud Loki push (Basic auth `instanceId:apiKey`,
`asynchttpPost`, JSON `streams` body) follows the same approach as
this author's Hubitat Heartbeat + Metrics Monitor:

https://github.com/gilderman/HubitatGrafana

That file was not copied. Device health reimplements the push for
`job=hubitat_device_health` and `job=hubitat_device_health_summary`.

Hubitat® is a trademark of Hubitat, Inc. This project is not
affiliated with or endorsed by Hubitat.

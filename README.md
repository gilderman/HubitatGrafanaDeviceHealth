# Hubitat Device Health

Hubitat app that polls **Z-Wave** and **Zigbee** mesh devices, decides if each one is alive or dead, and publishes results to **Grafana Cloud Loki**. It can also send a Hubitat notification when a device newly becomes dead.

LAN and virtual devices are ignored unless they appear in the hub's Z-Wave or Zigbee mesh JSON.

## Install

1. On the hub: **Apps Code** → **New App** → paste [`apps/HubitatDeviceHealth.groovy`](apps/HubitatDeviceHealth.groovy) → **Save**.
2. **Apps** → **Add User App** → **Hubitat Device Health**.
3. Or install via [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/) using this repo's `packageManifest.json`.

### App settings

| Setting | Default | Purpose |
|---|---|---|
| Loki push URL | — | Grafana Cloud `/loki/api/v1/push` |
| Grafana instance ID + API key | — | Basic auth for Loki |
| Poll interval | 5 minutes | How often mesh health is checked |
| Listening Z-Wave timeout | 4 hours | Always-on / mains Z-Wave |
| Sleepy / battery Z-Wave timeout | 36 hours | Non-listening Z-Wave |
| Zigbee timeout | 24 hours | All Zigbee devices |
| Exclude devices | — | `capability.*` multi-select |
| Notification devices | — | `capability.notification` when newly dead |

A device is **dead** if Z-Wave `nodeState` is `FAILED` (or `DEAD`), or if last-heard age is older than the timeout. Last-heard is the most recent of `lastTime`, `lastMessage`, `lastActivity`, and `lastActivityTime`.

The hub Z-Wave controller (`nodeId` 1) is skipped.

## Exclude

Use **Exclude these devices** to omit known-noisy or unused mesh devices. Excluded devices are not checked, not sent to Loki, and not alerted.

## Snooze

On the **Dead / snoozed devices** page, each listed device has **1h / 24h / 7d / Clear snooze**.

Snooze is stored in app state as `zwave:<nodeId>` or `zigbee:<deviceId>` until an epoch timestamp. Snoozed devices are not notified and appear as `status=snoozed` in Loki (`alive=0`).

## Loki labels

Two jobs are pushed each poll (Basic auth `instanceId:apiKey`, JSON streams):

### `{job="hubitat_device_health"}`

One logfmt line per checked device:

```
status=ok|dead|snoozed alive=0|1 protocol=zwave|zigbee name= deviceId= nodeId= lastHeard= lastActivity= ageSec= timeoutSec= listening= nodeState=
```

Stream labels also include `host` (hub location name) and `source=hubitat_app`.

### `{job="hubitat_device_health_summary"}`

One line:

```
alive=12 dead=1 snoozed=0 checked=13
```

## Grafana import

1. Grafana → **Dashboards** → **Import**.
2. Upload [`dashboards/device-health.json`](dashboards/device-health.json).
3. Select your Loki datasource (the export uses `${DS_GRAFANACLOUD--LOGS}`, same pattern as Hubitat Heartbeat Health).

The dashboard shows alive/dead stats, a timeseries of those counts from the summary job, and current dead-device logs.

**Alert:** create a Grafana alert on the Dead count (or `unwrap dead` from `{job="hubitat_device_health_summary"}`) that fires when **dead > 0**.

## License

MIT © 2026 Ilia Gilderman. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

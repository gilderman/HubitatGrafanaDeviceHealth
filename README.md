# Hubitat Grafana Device Health

Hubitat app that polls **Z-Wave** and **Zigbee** mesh devices, decides if each one is alive or dead, and publishes results to **Grafana Cloud Loki**. It can also send a Hubitat notification when a device newly becomes dead.

LAN and virtual devices are ignored unless they appear in the hub's Z-Wave or Zigbee mesh JSON.

The **app version** is `appVersion()` in the Groovy (`1.0.0`) and the same string in `packageManifest.json`. That is what you compare to GitHub. Hubitat’s internal save token (what `hubitat push` prints as `version=1`) is not a release number.

## Developer lifecycle

**Daily (no version bump)**

1. Edit the Groovy in git.
2. `hubitat push` from [hubitat-deploy](https://github.com/gilderman/hubitat-deploy) so the hub compiles this file.
3. Confirm the app page still shows **App version 1.0.0** (or whatever `appVersion()` is).
4. Commit and push to GitHub when you want the source saved — still the same `1.0.0`.

GitHub `main` and the hub match when that `appVersion()` string is the same **and** you pushed that exact file. If you committed extra changes but did not `hubitat push`, GitHub is ahead.

**Release (bump once)**

1. Change `appVersion()` and `packageManifest.json` `"version"` to the same new string (e.g. `1.0.1`).
2. Commit, tag `v1.0.1`, push GitHub.
3. `hubitat push` so the hub app page shows `1.0.1`.
4. HPM users then see `1.0.1` as the package version.

Do not bump on every save. Bump when you mean “this is a shipped version.”

Check hub vs git with hubitat-deploy (config lives in that repo’s `.hubitat.json`):

```bash
node path/to/hubitat-deploy/src/cli.js status --cwd path/to/hubitat-deploy apps/HubitatGrafanaDeviceHealth.groovy
node path/to/hubitat-deploy/src/cli.js diff  --cwd path/to/hubitat-deploy apps/HubitatGrafanaDeviceHealth.groovy
```

`status` compares `appVersion()`, `packageManifest.json`, and source. Exit code 1 if they differ.

## Install

1. Prefer [hubitat-deploy](https://github.com/gilderman/hubitat-deploy): `hubitat push apps/HubitatGrafanaDeviceHealth.groovy`.
2. Or on the hub: **Apps Code** → **New App** → paste [`apps/HubitatGrafanaDeviceHealth.groovy`](apps/HubitatGrafanaDeviceHealth.groovy) → **Save**.
3. **Apps** → **Add User App** → **Hubitat Grafana Device Health**.
4. Or install via [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/) using this repo's `packageManifest.json`.

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

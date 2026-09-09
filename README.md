# Hubitat Grafana Device Health

Hubitat app that polls **Z-Wave** and **Zigbee** mesh devices, decides if each one is alive or dead, and publishes results to **Grafana Cloud Loki**. It can also send a Hubitat notification when a device newly becomes dead.

LAN and virtual devices are ignored unless they appear in the hub's Z-Wave or Zigbee mesh JSON.

## Tests

Specs for **this app** live here: [`tests/src/test/groovy`](tests/src/test/groovy). The Gradle runner and hubitat_ci library stay in [hubitat-deploy](https://github.com/gilderman/hubitat-deploy) (clone hubitat_ci once under `<workspace>/tools/hubitat_ci`). JDK 11 and Gradle 7.6.x must be on `PATH`.

```bash
node path/to/hubitat-deploy/src/cli.js test --cwd path/to/HubitatGrafanaDeviceHealth
```

### How to add a test

1. Open [`tests/src/test/groovy/gilderman/devicehealth/HubitatGrafanaDeviceHealthSpec.groovy`](tests/src/test/groovy/gilderman/devicehealth/HubitatGrafanaDeviceHealthSpec.groovy).
2. Add another `def "description"() { ... }` method next to the existing ones. Use `loadApp()` (or `loadApp([setting: value])`) and call the Groovy method you care about. `deviceRow(...)` builds a fake mesh row for `evaluateDevices` / `deadReason`.
3. Do **not** add a Gradle project in this repo. Do **not** put specs in hubitat-deploy except the sample template.
4. Run `hubitat test --cwd` this repo and confirm the new method is in the pass list.
5. Preference pages (`dynamicPage` / `state` during install) are not executed in the sandbox. Test helpers and evaluation logic; compile-on-hub is still `hubitat push`.

Starter copy for a **new** Groovy repo: hubitat-deploy [`templates/hubitat_ci/SampleAppSpec.groovy`](https://github.com/gilderman/hubitat-deploy/blob/main/templates/hubitat_ci/SampleAppSpec.groovy).

## Install

1. Prefer [hubitat-deploy](https://github.com/gilderman/hubitat-deploy): `hubitat push --install apps/HubitatGrafanaDeviceHealth.groovy`.
2. Or on the hub: **Apps Code** → **New App** → paste [`apps/HubitatGrafanaDeviceHealth.groovy`](apps/HubitatGrafanaDeviceHealth.groovy) → **Save**.
3. Then **Apps → Add User App**, or `hubitat install` if the code is already on the hub. Open the app once and click **Done** after filling Loki settings.
4. Or install via [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/) using this repo's `packageManifest.json`.

### App settings

| Setting | Default | Purpose |
|---|---|---|
| Loki push URL | — | Grafana Cloud `/loki/api/v1/push` (see [Finding Grafana Loki settings](#finding-grafana-loki-settings)) |
| Grafana instance ID + API key | — | Loki Basic auth user + password |
| Poll interval | 5 minutes | How often hub last-heard tables are re-read and sent to Loki. Does not ping devices. |
| Listening Z-Wave timeout | 4 hours | Always-on / mains Z-Wave |
| Sleepy / battery Z-Wave timeout | 36 hours | Non-listening Z-Wave |
| Zigbee timeout | 24 hours | All Zigbee devices |
| Exclude devices | — | `capability.*` multi-select |
| Notification devices | — | `capability.notification` when newly dead |

A device is **dead** when any of these is true:

1. **Age > timeout** — last-heard is older than the listening / sleepy / Zigbee timeout. `nodeState=OK` and `listening=true` do **not** mean alive; they only mean the controller has not marked the node failed. A mains dimmer with last heard 19 hours ago and a 4 hour timeout is dead.
2. **Z-Wave `nodeState` is `FAILED` or `DEAD`** — the controller already failed the node, even if last-heard looks recent.
3. **Never heard** — no `lastTime` / `lastMessage` / `lastActivity` / `lastActivityTime`.

Last-heard is the most recent of those four fields. The hub Z-Wave controller (`nodeId` 1) is skipped. This app does not ping radios; it only reads hub JSON.

## Exclude

Use **Exclude these devices** to omit known-noisy or unused mesh devices. The status page **Ignore** list does the same by mesh key (`zwave:37` / `zigbee:12`) without picking a Hubitat device. Either way, those devices are not checked, not sent to Loki, and not alerted.

## Status page

**Devices** lists dead, snoozed, and ignored devices together. The name is a link to `/device/edit/<id>` (it stays after snooze or ignore). Status text is colored: dead red, snoozed orange, ignored purple. **Snooze** is a narrow dropdown (Off, 4 hours, 1 day, 7 days, Forever). Forever means no poll, Loki, or alert.

**Age > limit** is last-heard age vs the timeout for that device type. Listening (mains) Z-Wave default is **4 hours** (`Listening Z-Wave timeout` on the settings page). Sleepy/battery Z-Wave default is 36h; Zigbee is 24h.

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

## Finding Grafana Loki settings

The app needs three values. They come from **Grafana Cloud**, not from this repo.

1. Open [Grafana Cloud](https://grafana.com/auth/sign-in) → your stack.
2. **Loki push URL**
   - Stack → **Loki** → **Details** (or **Send Logs**).
   - Copy the Loki **URL** (`https://logs-prod-XX.grafana.net` — region code varies).
   - In the app, paste that host plus the push path: `https://logs-prod-XX.grafana.net/loki/api/v1/push`.
   - Do not use the Grafana UI URL (`https://yourstack.grafana.net`) and do not omit `/loki/api/v1/push`.
3. **Grafana Cloud Instance ID**
   - Same Loki details page: the numeric **User** / **Instance ID** used for Loki Basic auth.
   - Also listed under Grafana Cloud → your org/stack details. It is a number, not your email.
4. **Grafana Cloud API Key/Token**
   - Grafana Cloud → **Access Policies** (or **API Keys** on older stacks) → create a token with **`logs:write`** (Loki write).
   - Paste the token as the API key. The app sends Basic auth `instanceId:apiKey`.
   - Do not use a Grafana session cookie or a read-only dashboard token.

After **Done**, click **Poll Now** and confirm Grafana Explore `{job="hubitat_device_health"}` shows new lines.

## Grafana dashboard

1. In Grafana Cloud, confirm a **Loki** datasource exists (**Connections** → **Data sources**). Grafana Cloud stacks usually ship `grafanacloud-<stack>-logs`.
2. **Dashboards** → **New** → **Import**.
3. Upload [`dashboards/device-health.json`](dashboards/device-health.json) (or paste the JSON).
4. When asked for **grafanacloud--logs** / `${DS_GRAFANACLOUD--LOGS}`, pick that Loki datasource. That placeholder is the same pattern as Hubitat Heartbeat Health.
5. **Import** / **Save**. Open the dashboard and set the time range to cover a poll (default every 5 minutes).

The dashboard shows alive/dead/snoozed stats, a timeseries from `{job="hubitat_device_health_summary"}`, and current dead-device log lines from `{job="hubitat_device_health"}`.

**Alert:** Grafana → **Alerting** → **Alert rules** → new rule on the Dead stat (or LogQL `unwrap dead` from `{job="hubitat_device_health_summary"}`) that fires when **dead > 0**. The JSON description mentions this; it does not ship a pre-wired alert rule.

## License

MIT © 2026 Ilia Gilderman. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

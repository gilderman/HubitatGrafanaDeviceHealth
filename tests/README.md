# Tests for Hubitat Grafana Device Health

This folder is **this app’s** test source. It is not a Gradle project.

Run from hubitat-deploy (JDK 11 + Gradle 7.6 on PATH, hubitat_ci cloned once):

```bash
node src/cli.js test --cwd C:\Users\iliag\hubitat\HubitatGrafanaDeviceHealth
```

`hubitat test` uses the Gradle runner in hubitat-deploy and the hubitat_ci library under `<workspace>/tools/hubitat_ci`.

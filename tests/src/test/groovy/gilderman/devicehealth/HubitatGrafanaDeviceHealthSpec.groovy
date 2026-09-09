package gilderman.devicehealth

import groovy.json.JsonSlurper
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.Log
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Unit tests for HubitatGrafanaDeviceHealth.groovy.
 * Run: hubitat test --cwd this-repo
 */
class HubitatGrafanaDeviceHealthSpec extends Specification {
    static final File PROJECT_DIR = new File(System.getProperty('hubitat.projectDir', '.')).canonicalFile
    static final File APP = new File(PROJECT_DIR, 'apps/HubitatGrafanaDeviceHealth.groovy')
    static final File MANIFEST = new File(PROJECT_DIR, 'packageManifest.json')
    static final long NOW_MS = 1_700_000_000_000L

    def "app source exists"() {
        expect:
            APP.isFile()
    }

    def "app compiles in HubitatAppSandbox"() {
        when:
            new HubitatAppSandbox(APP).compile()
        then:
            noExceptionThrown()
    }

    def "appVersion matches packageManifest"() {
        given:
            def groovy = APP.getText('UTF-8')
            def matcher = groovy =~ /def appVersion\(\)\s*\{\s*"([^"]+)"\s*\}/
            def manifest = new JsonSlurper().parse(MANIFEST)
        expect:
            matcher.find()
            matcher.group(1) == manifest.version
    }

    def "pollMinutes defaults to 5, treats 0 as unset, and clamps high values to 60"() {
        expect:
            loadApp([:]).pollMinutes() == 5
            loadApp([pollIntervalMinutes: 1]).pollMinutes() == 1
            loadApp([pollIntervalMinutes: 60]).pollMinutes() == 60
            loadApp([pollIntervalMinutes: 0]).pollMinutes() == 5
            loadApp([pollIntervalMinutes: 99]).pollMinutes() == 60
    }

    def "isFailedNode treats FAILED and DEAD as failed"() {
        given:
            def app = loadApp()
        expect:
            app.isFailedNode("FAILED")
            app.isFailedNode("dead")
            !app.isFailedNode("OK")
            !app.isFailedNode(null)
    }

    def "deviceKey is protocol:id"() {
        expect:
            loadApp().deviceKey("zwave", "17") == "zwave:17"
            loadApp().deviceKey("zwave", "17") instanceof String
    }

    def "formatAge renders days hours minutes"() {
        given:
            def app = loadApp()
        expect:
            app.formatAge(null) == "unknown"
            app.formatAge(45) == "0m"
            app.formatAge(4 * 3600) == "4h"
            app.formatAge(90 * 60) == "1h 30m"
            app.formatAge(2 * 86400 + 3 * 3600) == "2d 3h"
    }

    def "statusTimeoutLegend uses the Timeouts settings"() {
        expect:
            loadApp().statusTimeoutLegend().contains("listening Z-Wave 4h")
            loadApp().statusTimeoutLegend().contains("sleepy Z-Wave 36h")
            loadApp().statusTimeoutLegend().contains("Zigbee 24h")
            loadApp([zwaveListeningHours: 6]).statusTimeoutLegend().contains("listening Z-Wave 6h")
    }

    def "formatHeard shortens ISO timestamps"() {
        given:
            def app = loadApp()
        expect:
            app.formatHeard("2026-09-08T01:00:04-0700") == "09-08 01:00"
            app.formatHeard(null) == "unknown"
    }

    def "evaluateDevices marks recent heard as ok"() {
        given:
            def app = loadApp()
            def row = deviceRow(heardMs: NOW_MS - 60_000, timeoutSec: 4 * 3600, nodeState: "OK")
        when:
            app.evaluateDevices([row])
        then:
            row.status == "ok"
            row.alive == 1
    }

    def "evaluateDevices marks timed-out and FAILED nodes dead"() {
        given:
            def app = loadApp()
            def timedOut = deviceRow(keyId: "5", heardMs: NOW_MS - 10 * 3600_000L, timeoutSec: 4 * 3600, nodeState: "OK")
            def failed = deviceRow(keyId: "17", heardMs: NOW_MS - 60_000, timeoutSec: 4 * 3600, nodeState: "FAILED")
            def neverHeard = deviceRow(keyId: "9", heardMs: null, timeoutSec: 4 * 3600, nodeState: "OK")
        when:
            app.evaluateDevices([timedOut, failed, neverHeard])
        then:
            timedOut.status == "dead"
            failed.status == "dead"
            neverHeard.status == "dead"
            [timedOut, failed, neverHeard].every { it.alive == 0 }
    }

    def "evaluateDevices marks snoozed dead devices as snoozed"() {
        given:
            def app = loadApp(state: [snooze: ["zwave:17": NOW_MS + 3600_000L]])
            def row = deviceRow(keyId: "17", heardMs: NOW_MS - 10 * 3600_000L, timeoutSec: 4 * 3600, nodeState: "FAILED")
        when:
            app.evaluateDevices([row])
        then:
            row.status == "snoozed"
            row.alive == 0
    }

    def "collectZwaveDevices skips controller node 1 and excluded devices"() {
        given:
            def app = loadApp([zwaveListeningHours: 4, zwaveSleepyHours: 36])
            def zwaveJson = [
                nodes     : [
                    [nodeId: 1, lastTime: "2023-11-14T22:13:20", listening: true, nodeState: "OK", deviceName: "Controller"],
                    [nodeId: 5, lastTime: "2023-11-14T22:12:00", listening: true, nodeState: "OK", deviceName: "Switch"],
                    [nodeId: 17, lastTime: "2023-11-14T12:00:00", listening: false, nodeState: "FAILED", deviceName: "Lock"]
                ],
                zwDevices : [
                    "5" : [id: 100, displayName: "Switch"],
                    "17": [id: 200, displayName: "Lock"]
                ]
            ]
        when:
            def rows = app.collectZwaveDevices(zwaveJson, [:], ["200"] as Set)
        then:
            rows*.keyId == ["5"]
            rows[0].protocol == "zwave"
            rows[0].timeoutSec == 4 * 3600L
            rows[0].listening == true
    }

    def "collectZwaveDevices skips ignored mesh keys forever"() {
        given:
            def app = loadApp(state: [ignore: ["zwave:5": [protocol: "zwave", keyId: "5", name: "Switch"]]])
            def zwaveJson = [
                nodes    : [[nodeId: 5, lastTime: "2023-11-14T22:12:00", listening: true, nodeState: "OK", deviceName: "Switch"]],
                zwDevices: ["5": [id: 100, displayName: "Switch"]]
            ]
        expect:
            app.isIgnored("zwave:5")
            app.collectZwaveDevices(zwaveJson, [:], [] as Set) == []
    }

    def "deadReason explains timeout, FAILED, never heard, and snooze"() {
        given:
            def app = loadApp()
            def timedOut = deviceRow(heardMs: NOW_MS - 10 * 3600_000L, timeoutSec: 4 * 3600, nodeState: "OK", listening: true)
            def failed = deviceRow(keyId: "17", heardMs: NOW_MS - 60_000, timeoutSec: 4 * 3600, nodeState: "FAILED")
            def neverHeard = deviceRow(keyId: "9", heardMs: null, timeoutSec: 4 * 3600, nodeState: "OK")
            def snoozed = deviceRow(keyId: "8", heardMs: NOW_MS - 10 * 3600_000L, timeoutSec: 4 * 3600, nodeState: "OK")
        when:
            app.evaluateDevices([timedOut, failed, neverHeard, snoozed])
            snoozed.status = "snoozed"
        then:
            app.deadReason(timedOut).contains("Age")
            app.deadReason(timedOut).contains("listening Z-Wave timeout")
            app.deadReason(timedOut).contains("nodeState=OK is not a ping")
            app.deadReason(failed) == "Z-Wave nodeState=FAILED"
            app.deadReason(neverHeard) == "Never heard"
            app.deadReason(snoozed).startsWith("Snoozed")
    }

    def "statusProblemRows is one sorted row per dead or snoozed device"() {
        given:
            def app = loadApp()
            def alive = deviceRow(keyId: "1", name: "Alive Switch", status: "ok")
            def dining = deviceRow(keyId: "17", name: "Dining Room Fixture", status: "dead")
            def lock = deviceRow(keyId: "8", name: "Back Lock", status: "snoozed")
        expect:
            app.statusProblemRows([alive, dining, lock])*.name == ["Back Lock", "Dining Room Fixture"]
            loadApp(state: [ignore: ["zwave:17": [protocol: "zwave", keyId: "17"]]]).statusProblemRows([dining])*.name == ["Dining Room Fixture"]
            app.statusProblemRows([]) == []
            app.statusProblemRows(null) == []
    }

    def "statusTableLine shows Hubitat device id and why age exceeded listening timeout"() {
        given:
            def app = loadApp()
            def row = deviceRow(
                keyId: "17",
                deviceId: 292,
                nodeId: 17,
                name: "Dining Room Fixture",
                protocol: "zwave",
                heardMs: NOW_MS - ((19 * 3600) + (25 * 60)) * 1000L,
                lastHeard: "2026-09-08T01:00:04-0700",
                timeoutSec: 4 * 3600,
                nodeState: "OK",
                listening: true
            )
        when:
            app.evaluateDevices([row])
            def line = app.statusTableLine(row)
        then:
            row.status == "dead"
            line.startsWith("Device id 292")
            line.contains("Dining Room Fixture (zwave 17)")
            line.contains("dead")
            line.contains("09-08 01:00")
            line.contains("19h 25m / 4h")
            line.contains("Past listening Z-Wave timeout (setting)")
            !line.contains("Device id 17  ·")
    }

    def "statusTableHtml is a real table with one body row per device"() {
        given:
            def app = loadApp()
            def dining = deviceRow(
                keyId: "37",
                deviceId: 50,
                nodeId: 37,
                name: "Dining Room Fixture",
                status: "dead",
                ageSec: (19 * 3600) + (25 * 60),
                lastHeard: "2026-09-08T01:00:04-0700",
                timeoutSec: 4 * 3600,
                nodeState: "OK"
            )
            def lock = deviceRow(keyId: "12", deviceId: 143, name: "Lock", status: "dead", nodeState: "FAILED")
            def html = app.statusTableHtml([dining, lock])
        expect:
            html.contains("<table")
            html.contains("</table>")
            html.contains("width:auto")
            !html.contains("style='width:100%")
            app.statusTableColumns() == ["Id", "Name", "Status", "Heard", "Age / timeout", "Why", "Ignore"]
            html.contains(">Id<")
            html.contains("Age / timeout")
            html.contains(">Why<")
            html.contains(">Ignore<")
            html.contains("min-width:22em")
            html.contains("max-width:28em")
            html.contains("max-width:36em")
            html.contains(">50<")
            html.contains("Dining Room Fixture")
            html.contains("/device/edit/50")
            html.contains("/device/edit/143")
            html.contains("<a href='/device/edit/50'")
            !html.contains("type='submit'")
            html.contains("listening Z-Wave")
            (html =~ /<tr>/).size() == 3
    }

    def "statusDeviceHref is the hub device page"() {
        expect:
            loadApp().statusDeviceHref(deviceRow(deviceId: 50)) == "/device/edit/50"
            loadApp().statusDeviceHref(deviceRow(keyId: "9")) == ""
    }

    def "statusTableLine falls back to keyId when Hubitat device id is missing"() {
        given:
            def app = loadApp()
            def row = deviceRow(keyId: "9", name: "Unknown", status: "dead", ageSec: null, lastHeard: null)
        expect:
            app.statusDeviceId(row) == "9"
            app.statusTableLine(row).startsWith("Device id 9")
            app.statusTableLine(row).contains("unknown")
    }

    def "statusSnoozeButtons bind 1h 24h 7d Clear to that device id"() {
        given:
            def app = loadApp()
            def row = deviceRow(protocol: "zwave", keyId: "17")
            def buttons = app.statusSnoozeButtons(row)
        expect:
            buttons*.title == ["1h", "24h", "7d", "Clear"]
            buttons*.name == ["snooze_1h_zwave_17", "snooze_24h_zwave_17", "snooze_7d_zwave_17", "snooze_clear_zwave_17"]
            buttons.every { it.name ==~ /^snooze_(1h|24h|7d|clear)_(zwave|zigbee)_\d+$/ }
    }

    def "persistStatus keeps device id for the status table"() {
        given:
            def stateMap = [:]
            def app = loadApp(state: stateMap)
            def row = deviceRow(
                keyId: "17",
                deviceId: 292,
                nodeId: 17,
                name: "Dining Room Fixture",
                heardMs: NOW_MS - 10 * 3600_000L,
                lastHeard: "2026-09-08T01:00:04-0700",
                timeoutSec: 4 * 3600,
                nodeState: "OK"
            )
        when:
            app.evaluateDevices([row])
            app.persistStatus([row])
        then:
            stateMap.lastSummary.dead == 1
            stateMap.lastDevices.size() == 1
            stateMap.lastDevices[0].deviceId == 292
            stateMap.lastDevices[0].status == "dead"
            app.statusTableLine(stateMap.lastDevices[0]).startsWith("Device id 292")
    }

    def "statusHrefDescription summarizes last poll"() {
        expect:
            loadApp(state: [:]).statusHrefDescription() == "No poll yet"
            loadApp(state: [lastSummary: [at: "now", dead: 2, snoozed: 1, checked: 10]]).statusHrefDescription() == "Dead 2 · Snoozed 1 · Checked 10"
    }

    def "status page keeps every device link and uses a narrow snooze dropdown"() {
        given:
            def src = APP.getText("UTF-8")
            def start = src.indexOf("def statusPage")
            def end = src.indexOf("def statusHrefDescription")
            def page = src.substring(start, end)
            def controls = src.substring(src.indexOf("def statusDeviceControls"), src.indexOf("def statusHrefDescription"))
            def ignored = deviceRow(protocol: "zwave", keyId: "17", deviceId: 50, name: "Dining", status: "dead")
            def app = loadApp(state: [ignore: ["zwave:17": [protocol: "zwave", keyId: "17", deviceId: 50, name: "Dining"]]])
        expect:
            page.contains("statusHeading(\"Devices\")")
            page.contains("statusHeading(\"Last poll\")")
            loadApp().statusHeading("Last poll").contains("<div")
            loadApp().statusHeading("Last poll").contains("Last poll")
            !page.contains("Ignored forever")
            !page.contains("statusTableHtml")
            controls.contains("statusOneLine")
            controls.contains("title: \"Snooze\"")
            controls.contains("width: 6")
            controls.contains("width: 1")
            controls.contains("width: 4")
            !controls.contains("width: 2")
            !controls.contains("width: 3")
            !controls.contains("statusRowHtml")
            app.statusOneLine(ignored).contains("Dining")
            app.statusDeviceHref(ignored) == "/device/edit/50"
            app.statusDisplayRow(ignored).status == "ignored"
            app.statusListRows()*.name == ["Dining"]
            loadApp().actionSettingName(deviceRow(protocol: "zwave", keyId: "37")) == "act_zwave_37"
            loadApp().actionOptions().keySet() as List == ["off", "4h", "1d", "7d", "forever"]
    }

    def "snoozeHours reads the duration enum"() {
        expect:
            loadApp().snoozeHours() == 24
            loadApp([statusSnoozeHours: "1"]).snoozeHours() == 1
            loadApp([statusSnoozeHours: "168"]).snoozeHours() == 168
    }

    def "first status visit does not clear an existing snooze when the enum is unset"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", status: "dead")
            def stateMap = [snooze: ["zwave:17": NOW_MS + 3600_000L]]
            def app = loadApp(state: stateMap)
        when:
            app.syncSnoozeSelection([row])
        then:
            app.isSnoozed("zwave:17")
            stateMap.snoozeEnumInited == true
    }

    def "checking a snooze enum value snoozes that device and shows snoozed"() {
        given:
            def row = deviceRow(
                protocol: "zwave",
                keyId: "17",
                deviceId: 50,
                status: "dead",
                heardMs: NOW_MS - 10 * 3600_000L,
                lastHeard: "2026-09-08T01:00:04-0700",
                timeoutSec: 4 * 3600,
                nodeState: "OK"
            )
            def stateMap = [
                snooze          : [:],
                snoozeEnumInited: true,
                lastDevices     : [row]
            ]
            def app = loadApp(state: stateMap, statusSnoozedKeys: ["zwave:17"], statusSnoozeHours: "1")
        when:
            app.syncSnoozeSelection([row])
        then:
            app.isSnoozed("zwave:17")
            stateMap.lastDevices[0].status == "snoozed"
            stateMap.lastSummary.snoozed == 1
            app.statusTableValues(stateMap.lastDevices[0])[2] == "snoozed"
            app.statusRowTitle(stateMap.lastDevices[0]).contains("snoozed")
    }

    def "first submitted check snoozes even when enum was never inited"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", status: "dead")
            def stateMap = [snooze: [:], lastDevices: [row]]
            def app = loadApp(state: stateMap, statusSnoozedKeys: ["zwave:17"], statusSnoozeHours: "24")
        when:
            app.syncSnoozeSelection([row])
        then:
            app.isSnoozed("zwave:17")
            stateMap.lastDevices[0].status == "snoozed"
    }

    def "unchecking a snooze enum value clears that device"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", status: "dead")
            def stateMap = [
                snooze          : ["zwave:17": NOW_MS + 3600_000L],
                snoozeEnumInited: true
            ]
            def app = loadApp(state: stateMap, statusSnoozedKeys: [])
        when:
            app.syncSnoozeSelection([row])
        then:
            !app.isSnoozed("zwave:17")
    }

    def "empty enum on first init does not wipe snooze"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", status: "dead")
            def stateMap = [snooze: ["zwave:17": NOW_MS + 3600_000L]]
            def app = loadApp(state: stateMap, statusSnoozedKeys: [])
        when:
            app.syncSnoozeSelection([row])
        then:
            app.isSnoozed("zwave:17")
    }

    def "turning a Snooze bool on snoozes that device"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", status: "dead")
            def stateMap = [snooze: [:], actionEnums: ["act_zwave_17": "off"], lastDevices: [row]]
            def app = loadApp(state: stateMap, act_zwave_17: "4h")
        when:
            app.syncActionEnums([row])
        then:
            app.isSnoozed("zwave:17")
            !app.isIgnored("zwave:17")
            app.currentAction(row) == "4h"
            stateMap.lastDevices[0].status == "snoozed"
            app.statusControlDescription(row).contains("snoozed")
    }

    def "snooze dropdown Forever ignores that device"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", deviceId: 50, name: "Dining", status: "dead")
            def stateMap = [ignore: [:], actionEnums: ["act_zwave_17": "off"], lastDevices: [row]]
            def app = loadApp(state: stateMap, act_zwave_17: "forever")
        when:
            app.syncActionEnums([row])
        then:
            app.isIgnored("zwave:17")
            app.currentAction(row) == "forever"
            app.statusProblemRows(stateMap.lastDevices)*.name == ["Dining"]
            app.statusRowHtml(row).contains("/device/edit/50")
    }

    def "snooze dropdown Off clears snooze and ignore"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", name: "Dining", status: "dead")
            def stateMap = [
                snooze      : ["zwave:17": NOW_MS + 3600_000L],
                ignore      : ["zwave:17": [protocol: "zwave", keyId: "17", name: "Dining"]],
                actionEnums : ["act_zwave_17": "forever"]
            ]
            def app = loadApp(state: stateMap, act_zwave_17: "off")
        when:
            app.syncActionEnums([row])
        then:
            !app.isSnoozed("zwave:17")
            !app.isIgnored("zwave:17")
            app.currentAction(row) == "off"
    }

    def "turning an Ignore bool on drops that device forever"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", deviceId: 50, name: "Dining", status: "dead")
            def stateMap = [ignore: [:], ignoreToggles: ["ig_zwave_17": false], lastDevices: [row]]
            def app = loadApp(state: stateMap, ig_zwave_17: true)
        when:
            app.syncIgnoreToggles([row])
        then:
            app.isIgnored("zwave:17")
            app.statusProblemRows(stateMap.lastDevices)*.name == ["Dining"]
    }

    def "checking ignore drops that device forever and keeps it off the dead table"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", deviceId: 50, name: "Dining", status: "dead")
            def stateMap = [ignore: [:], ignoreEnumInited: true, lastDevices: [row]]
            def app = loadApp(state: stateMap, statusIgnoredKeys: ["zwave:17"])
        when:
            app.syncIgnoreSelection([row])
        then:
            app.isIgnored("zwave:17")
            app.statusProblemRows(stateMap.lastDevices)*.name == ["Dining"]
            app.currentIgnoredKeys() == ["zwave:17"]
            app.statusTableValues(row)[6] == "on"
            app.statusIgnoreOptions(app.statusIgnoreOptionRows([]))["zwave:17"].contains("Dining")
    }

    def "unchecking ignore watches that device again"() {
        given:
            def row = deviceRow(protocol: "zwave", keyId: "17", name: "Dining", status: "dead")
            def stateMap = [
                ignore          : ["zwave:17": [protocol: "zwave", keyId: "17", name: "Dining"]],
                ignoreEnumInited: true
            ]
            def app = loadApp(state: stateMap, statusIgnoredKeys: [])
        when:
            app.syncIgnoreSelection([row])
        then:
            !app.isIgnored("zwave:17")
    }

    def "statusDisplayRow and snooze options show snoozed in the table"() {
        given:
            def app = loadApp(state: [snooze: ["zwave:17": NOW_MS + 3600_000L]])
            def row = deviceRow(protocol: "zwave", keyId: "17", deviceId: 50, name: "Dining", status: "dead")
            def opts = app.statusSnoozeOptions([row])
        expect:
            app.statusDisplayRow(row).status == "snoozed"
            app.statusTableValues(row)[2] == "snoozed"
            opts["zwave:17"].contains("snoozed")
            opts["zwave:17"].contains("Dining")
            app.currentSnoozedKeys([row]) == ["zwave:17"]
    }

    def "age is computed from lastHeard when heardMs was not persisted"() {
        given:
            def app = loadApp()
            def row = deviceRow(
                protocol: "zwave",
                keyId: "37",
                name: "Dining Room Fixture",
                status: "dead",
                lastHeard: "2023-11-14T12:13:20+0000",
                timeoutSec: 4 * 3600,
                nodeState: "OK"
            )
            row.heardMs = null
            row.ageSec = null
        when:
            app.evaluateDevices([row])
            def vals = app.statusTableValues(row)
        then:
            row.heardMs != null
            row.ageSec != null
            !vals[4].startsWith("unknown")
            vals[4].contains("/ 4h")
    }

    def "persistStatus keeps heardMs so a later evaluate does not wipe age"() {
        given:
            def stateMap = [:]
            def app = loadApp(state: stateMap)
            def row = deviceRow(
                keyId: "17",
                deviceId: 292,
                heardMs: NOW_MS - 10 * 3600_000L,
                lastHeard: "2026-09-08T01:00:04-0700",
                timeoutSec: 4 * 3600,
                nodeState: "OK"
            )
        when:
            app.evaluateDevices([row])
            app.persistStatus([row])
            def stored = stateMap.lastDevices[0]
            stored.ageSec = null
            stored.heardMs = stored.heardMs
            app.evaluateDevices([stored])
        then:
            stored.heardMs == NOW_MS - 10 * 3600_000L
            stored.ageSec == 10 * 3600L
            stateMap.lastDevices[0].heardMs == NOW_MS - 10 * 3600_000L
    }

    def "consumeStatusParams snoozes from a short sz query"() {
        given:
            def stateMap = [snooze: [:]]
            def app = loadApp(state: stateMap)
        when:
            app.consumeStatusParams([sz: "1h", p: "zwave", k: "17"])
        then:
            app.isSnoozed("zwave:17")
    }

    def "snoozeQuery is a short query string"() {
        expect:
            loadApp().snoozeQuery("1h", "zwave", "37") == "?sz=1h&p=zwave&k=37"
    }

    def "parseSnoozeButton reads the table action names"() {
        given:
            def app = loadApp()
        expect:
            app.parseSnoozeButton("snooze_1h_zwave_17") == [action: "1h", protocol: "zwave", keyId: "17"]
            app.parseSnoozeButton("snooze_24h_zigbee_55") == [action: "24h", protocol: "zigbee", keyId: "55"]
            app.parseSnoozeButton("snooze_7d_zwave_17") == [action: "7d", protocol: "zwave", keyId: "17"]
            app.parseSnoozeButton("snooze_clear_zwave_17") == [action: "clear", protocol: "zwave", keyId: "17"]
            app.parseSnoozeButton("pollNowStatusButton") == null
            app.parseSnoozeButton(null) == null
    }

    def "statusSnoozeButtons names parse as snooze actions for that device"() {
        given:
            def app = loadApp()
            def buttons = app.statusSnoozeButtons(deviceRow(protocol: "zwave", keyId: "17"))
        expect:
            buttons.collect { app.parseSnoozeButton(it.name) } == [
                [action: "1h", protocol: "zwave", keyId: "17"],
                [action: "24h", protocol: "zwave", keyId: "17"],
                [action: "7d", protocol: "zwave", keyId: "17"],
                [action: "clear", protocol: "zwave", keyId: "17"]
            ]
    }

    def "appButtonHandler snoozes from the table 1h button name"() {
        given:
            def stateMap = [snooze: [:]]
            def app = loadApp(state: stateMap)
        when:
            app.appButtonHandler("snooze_1h_zwave_17")
        then:
            app.isSnoozed("zwave:17")
            stateMap.snooze["zwave:17"] == NOW_MS + 3600_000L
    }

    def "appButtonHandler 24h and 7d buttons use the same device key as the table"() {
        given:
            def app24 = loadApp(state: [snooze: [:]])
            def app7d = loadApp(state: [snooze: [:]])
        when:
            app24.appButtonHandler("snooze_24h_zigbee_55")
            app7d.appButtonHandler("snooze_7d_zwave_17")
        then:
            app24.isSnoozed("zigbee:55")
            app7d.isSnoozed("zwave:17")
    }

    def "appButtonHandler Clear button unsnoozes that table row"() {
        given:
            def stateMap = [snooze: ["zwave:17": NOW_MS + 3600_000L]]
            def app = loadApp(state: stateMap)
        when:
            app.appButtonHandler("snooze_clear_zwave_17")
        then:
            !app.isSnoozed("zwave:17")
    }

    def "sleepy Z-Wave nodes use the battery timeout"() {
        given:
            def app = loadApp([zwaveListeningHours: 4, zwaveSleepyHours: 36])
            def zwaveJson = [
                nodes    : [[nodeId: 8, lastTime: "2023-11-14T22:12:00", listening: false, nodeState: "OK", deviceName: "Sensor"]],
                zwDevices: ["8": [id: 50]]
            ]
        expect:
            app.collectZwaveDevices(zwaveJson, [:], [] as Set)[0].timeoutSec == 36 * 3600L
    }

    private Object loadApp(Map args = [:]) {
        def stateMap = (args.state instanceof Map) ? args.state : [:]
        def settings = args.findAll { k, v -> k != 'state' }
        def log = Mock(Log)
        AppExecutor api = Mock(AppExecutor) {
            now() >> NOW_MS
            getLog() >> log
            getState() >> stateMap
            updateSetting(*_) >> { Object[] callArgs ->
                def name = callArgs[0]?.toString()
                def val = callArgs.size() > 1 ? callArgs[1] : null
                settings[name] = (val instanceof Map) ? val.value : val
            }
        }
        return new HubitatAppSandbox(APP).compile(
            api: api,
            userSettingValues: settings,
            validationFlags: [Flags.DontValidateDefinition, Flags.AllowReadingNonInputSettings]
        )
    }

    private static Map deviceRow(Map args) {
        def row = [
            protocol   : args.protocol ?: "zwave",
            keyId      : args.keyId ?: "5",
            name       : args.name ?: "Test",
            heardMs    : args.heardMs,
            timeoutSec : args.timeoutSec,
            nodeState  : args.nodeState,
            listening  : args.containsKey("listening") ? args.listening : true
        ]
        ["deviceId", "nodeId", "lastHeard", "status", "ageSec"].each { key ->
            if (args.containsKey(key)) row[key] = args[key]
        }
        return row
    }
}

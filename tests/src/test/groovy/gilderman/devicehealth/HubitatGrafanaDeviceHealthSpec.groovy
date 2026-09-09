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
            app.formatAge(90 * 60) == "1h 30m"
            app.formatAge(2 * 86400 + 3 * 3600) == "2d 3h"
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
            app.deadReason(timedOut).contains("listening timeout")
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
            line.contains("Dining Room Fixture")
            line.contains("zwave")
            line.contains("node 17")
            line.contains("dead")
            line.contains("2026-09-08T01:00:04-0700")
            line.contains("19h 25m / 4h 0m")
            line.contains("Age 19h 25m > listening timeout 4h 0m")
            line.contains("nodeState=OK is not a ping")
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
            app.statusTableColumns() == ["Device id", "Name", "Protocol", "Node", "Status", "Last heard", "Age / timeout", "Why", "Actions"]
            html.contains("Device id")
            html.contains("Actions")
            html.contains(">50<")
            html.contains("Dining Room Fixture")
            html.contains("name='snooze_1h_zwave_37'")
            html.contains("name='snooze_clear_zwave_12'")
            (html =~ /<tr>/).size() == 3
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

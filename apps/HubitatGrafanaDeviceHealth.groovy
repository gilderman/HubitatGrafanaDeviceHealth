/**
 *  Hubitat Grafana Device Health
 *
 *  Polls Z-Wave and Zigbee mesh health, pushes Loki log lines, and notifies
 *  when a device newly becomes dead.
 *
 *  Author: Ilia Gilderman
 *  Date: 2026-09-08
 */

definition(
    name: "Hubitat Grafana Device Health",
    namespace: "gilderman",
    author: "Ilia Gilderman",
    description: "Monitors Z-Wave and Zigbee device last-heard times and publishes health to Grafana Cloud Loki",
    category: "Monitoring",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/gilderman/HubitatGrafanaDeviceHealth/refs/heads/main/apps/HubitatGrafanaDeviceHealth.groovy",
    singleInstance: true
)

// Release version. Keep in sync with packageManifest.json. Do not confuse
// with Hubitat's internal save token (the number hubitat-deploy prints).
def appVersion() { "1.0.0" }

preferences {
    page(name: "configPage")
    page(name: "statusPage")
}

def configPage() {
    dynamicPage(name: "configPage", title: "Device Health", install: true, uninstall: true) {
        section("Status") {
            paragraph "App version ${appVersion()}"
            href(
                name: "toStatus",
                title: "Dead / snoozed devices",
                page: "statusPage",
                description: statusHrefDescription()
            )
            input "pollNowButton", "button", title: "Poll Now"
        }

        section("Grafana Cloud Loki") {
            input "lokiUrl", "text",
                title: "Grafana Cloud Loki Push URL",
                description: "e.g. https://logs-prod-XX.grafana.net/loki/api/v1/push",
                required: false
            input "grafanaInstanceId", "text",
                title: "Grafana Cloud Instance ID",
                required: false
            input "grafanaApiKey", "password",
                title: "Grafana Cloud API Key/Token",
                required: false
            paragraph "Poll interval is how often this app re-reads the hub’s Z-Wave and Zigbee last-heard tables and pushes Loki. It does not ping or refresh devices."
            input "pollIntervalMinutes", "number",
                title: "Poll interval (minutes)",
                description: "Default 5. Range 1–60. Not a device ping.",
                defaultValue: 5,
                range: "1..60",
                required: true
        }

        section("Timeouts (hours)") {
            input "zwaveListeningHours", "number",
                title: "Listening Z-Wave timeout (hours)",
                defaultValue: 4,
                range: "1..168"
            input "zwaveSleepyHours", "number",
                title: "Sleepy / battery Z-Wave timeout (hours)",
                defaultValue: 36,
                range: "1..336"
            input "zigbeeHours", "number",
                title: "Zigbee timeout (hours)",
                defaultValue: 24,
                range: "1..336"
        }

        section("Exclude devices") {
            paragraph "Excluded devices are not checked, logged, or alerted."
            input "excludeDevices", "capability.*",
                title: "Exclude these devices",
                multiple: true,
                required: false
        }

        section("Notifications") {
            paragraph "Notified when a device newly becomes dead and is not snoozed."
            input "notifyDevices", "capability.notification",
                title: "Notification devices",
                multiple: true,
                required: false
        }

        section("Debug") {
            input "debugEnable", "bool",
                title: "Enable debug logging",
                defaultValue: false
        }
    }
}

def statusPage() {
    dynamicPage(name: "statusPage", title: "Device Health Status", install: false, uninstall: false) {
        section("") {
            href(
                name: "toConfig",
                title: "Back to settings",
                page: "configPage",
                description: "Loki, timeouts, exclude, notifications"
            )
            input "pollNowStatusButton", "button", title: "Poll Now"
        }

        def summary = state.lastSummary ?: [:]
        section("Last poll") {
            paragraph "Checked: ${summary.checked ?: 0}  ·  Alive: ${summary.alive ?: 0}  ·  Dead: ${summary.dead ?: 0}  ·  Snoozed: ${summary.snoozed ?: 0}"
            paragraph "Last run: ${summary.at ?: "never"}"
        }

        def rows = (state.lastDevices ?: []).findAll { row ->
            row.status == "dead" || row.status == "snoozed"
        }
        if (!rows) {
            section("Dead / snoozed") {
                paragraph "No dead or snoozed devices."
            }
        } else {
            rows.sort { a, b -> (a.name ?: "").toLowerCase() <=> (b.name ?: "").toLowerCase() }
            rows.each { row ->
                def key = deviceKey(row.protocol, row.keyId)
                def btn = buttonKey(row.protocol, row.keyId)
                def heard = row.lastHeard ?: "unknown"
                def age = formatAge(row.ageSec)
                def snoozeNote = snoozeUntilText(key)
                section("${row.name} (${row.protocol})") {
                    paragraph "Status: ${row.status}  ·  Last heard: ${heard}  ·  Age: ${age}  ·  Timeout: ${formatAge(row.timeoutSec)}"
                    if (row.nodeState) paragraph "nodeState=${row.nodeState}  listening=${row.listening}"
                    if (snoozeNote) paragraph snoozeNote
                    input "snooze_1h_${btn}", "button", title: "Snooze 1h", width: 3
                    input "snooze_24h_${btn}", "button", title: "Snooze 24h", width: 3
                    input "snooze_7d_${btn}", "button", title: "Snooze 7d", width: 3
                    input "snooze_clear_${btn}", "button", title: "Clear snooze", width: 3
                }
            }
        }
    }
}

def statusHrefDescription() {
    def summary = state.lastSummary ?: [:]
    if (!summary.at) return "No poll yet"
    return "Dead ${summary.dead ?: 0} · Snoozed ${summary.snoozed ?: 0} · Checked ${summary.checked ?: 0}"
}

def installed() {
    log.info "Hubitat Device Health installed"
    initialize()
}

def updated() {
    log.info "Hubitat Device Health updated"
    unschedule()
    initialize()
}

def uninstalled() {
    log.info "Hubitat Device Health uninstalled"
    unschedule()
}

def initialize() {
    if (!state.snooze) state.snooze = [:]
    if (state.prevDead == null) state.prevDead = []
    def minutes = pollMinutes()
    log.info "Device health poll every ${minutes} minute(s)"
    runIn(5, "pollDeviceHealth", [overwrite: true])
    if (debugLoggingOn()) {
        log.debug "Debug logging enabled — will disable automatically in 30 minutes"
        runIn(1800, "disableDebugLogging", [overwrite: true])
    }
}

def appButtonHandler(btn) {
    if (btn == "pollNowButton" || btn == "pollNowStatusButton") {
        log.info "Manual device health poll"
        pollDeviceHealth()
        return
    }
    def matcher = (btn =~ /^snooze_(1h|24h|7d|clear)_(zwave|zigbee)_(\d+)$/)
    if (!matcher.find()) {
        logDebug "Unhandled button: ${btn}"
        return
    }
    def action = matcher.group(1)
    def protocol = matcher.group(2)
    def keyId = matcher.group(3)
    def key = deviceKey(protocol, keyId)
    if (!state.snooze) state.snooze = [:]
    if (action == "clear") {
        state.snooze.remove(key)
        log.info "Cleared snooze for ${key}"
        return
    }
    def hours = (action == "1h") ? 1 : ((action == "24h") ? 24 : 168)
    def untilMs = now() + (hours * 3600000L)
    state.snooze[key] = untilMs
    log.info "Snoozed ${key} for ${hours}h (until ${new Date(untilMs)})"
}

def pollDeviceHealth() {
    try {
        pruneSnooze()
        def zwaveJson = getHubJson("/hub/zwaveDetails/json")
        def zigbeeJson = getHubJson("/hub/zigbeeDetails/json")
        def deviceList = getHubJson("/device/list/data")
        def activityById = activityIndex(deviceList)
        def excludeIds = excludeIdSet()
        def devices = []
        devices.addAll(collectZwaveDevices(zwaveJson, activityById, excludeIds))
        devices.addAll(collectZigbeeDevices(zigbeeJson, activityById, excludeIds))
        evaluateDevices(devices)
        persistStatus(devices)
        notifyNewDead(devices)
        pushHealthToLoki(devices)
    } catch (Exception e) {
        log.error "Device health poll failed: ${e.message}"
    }
    runIn(pollMinutes() * 60, "pollDeviceHealth", [overwrite: true])
}

def evaluateDevices(devices) {
    def nowMs = now()
    devices.each { row ->
        def key = deviceKey(row.protocol, row.keyId)
        row.key = key
        def heardMs = row.heardMs
        row.ageSec = (heardMs != null) ? Math.max(0, ((nowMs - heardMs) / 1000).toLong()) : null
        def failed = isFailedNode(row.nodeState)
        def timedOut = (row.ageSec != null && row.timeoutSec != null && row.ageSec > row.timeoutSec)
        def noHeard = (heardMs == null)
        def dead = failed || timedOut || noHeard
        if (isSnoozed(key)) {
            row.status = "snoozed"
            row.alive = 0
        } else if (dead) {
            row.status = "dead"
            row.alive = 0
        } else {
            row.status = "ok"
            row.alive = 1
        }
    }
}

def persistStatus(devices) {
    def alive = devices.count { it.status == "ok" }
    def dead = devices.count { it.status == "dead" }
    def snoozed = devices.count { it.status == "snoozed" }
    state.lastSummary = [
        alive  : alive,
        dead   : dead,
        snoozed: snoozed,
        checked: devices.size(),
        at     : new Date(now()).toString()
    ]
    state.lastDevices = devices.collect { row ->
        [
            protocol   : row.protocol,
            keyId      : row.keyId,
            name       : row.name,
            deviceId   : row.deviceId,
            nodeId     : row.nodeId,
            lastHeard  : row.lastHeard,
            lastActivity: row.lastActivity,
            ageSec     : row.ageSec,
            timeoutSec : row.timeoutSec,
            listening  : row.listening,
            nodeState  : row.nodeState,
            status     : row.status,
            alive      : row.alive
        ]
    }
    log.info "Device health: alive=${alive} dead=${dead} snoozed=${snoozed} checked=${devices.size()}"
}

def notifyNewDead(devices) {
    def prev = (state.prevDead ?: []).collect { it.toString() } as Set
    def currentDead = devices.findAll { it.status == "dead" }.collect { it.key } as Set
    def newlyDead = devices.findAll { it.status == "dead" && !prev.contains(it.key) }
    newlyDead.each { row ->
        def msg = "Device health: ${row.name} (${row.protocol}${row.nodeId ? " node ${row.nodeId}" : ""}) is dead. Last heard: ${row.lastHeard ?: "unknown"}"
        log.warn msg
        notifyDevices?.each { dev ->
            try {
                dev.deviceNotification(msg)
            } catch (Exception e) {
                log.warn "Notification failed on ${dev}: ${e.message}"
            }
        }
    }
    state.prevDead = currentDead.toList()
}

def collectZwaveDevices(zwaveJson, activityById, excludeIds) {
    def rows = []
    if (!(zwaveJson instanceof Map)) {
        logDebug "No Z-Wave details JSON"
        return rows
    }
    def zwDevices = (zwaveJson.zwDevices instanceof Map) ? zwaveJson.zwDevices : [:]
    def nodes = zwaveJson.nodes
    if (!(nodes instanceof List)) {
        logDebug "Z-Wave details missing nodes[]"
        return rows
    }
    def listenHours = (settings.zwaveListeningHours ?: 4) as Integer
    def sleepyHours = (settings.zwaveSleepyHours ?: 36) as Integer
    nodes.each { node ->
        if (!(node instanceof Map)) return
        def nodeId = node.nodeId
        if (nodeId == null) return
        if ((nodeId as Integer) == 1) return
        def zw = zwDevices?."${nodeId}"
        def deviceId = extractHubitatId(zw)
        if (deviceId && excludeIds.contains(deviceId.toString())) {
            logDebug "Excluded Z-Wave node ${nodeId} (device ${deviceId})"
            return
        }
        def activity = deviceId ? activityById[deviceId.toString()] : null
        if (activity?.disabled) {
            logDebug "Skipping disabled Z-Wave node ${nodeId}"
            return
        }
        def lastTime = node.lastTime
        def lastActivityTime = activity?.lastActivityTime
        def heardMs = latestHeardMs([lastTime, lastActivityTime])
        def listening = truthy(node.listening)
        def timeoutSec = (listening ? listenHours : sleepyHours) * 3600L
        def name = node.deviceName ?: zw?.displayName ?: zw?.label ?: activity?.displayName ?: activity?.label ?: "Z-Wave node ${nodeId}"
        rows << [
            protocol    : "zwave",
            keyId       : nodeId.toString(),
            name        : name,
            deviceId    : deviceId ?: "",
            nodeId      : nodeId.toString(),
            lastHeard   : formatHeard(heardMs, lastTime),
            lastActivity: lastActivityTime ?: "",
            heardMs     : heardMs,
            timeoutSec  : timeoutSec,
            listening   : listening,
            nodeState   : node.nodeState ?: ""
        ]
    }
    return rows
}

def collectZigbeeDevices(zigbeeJson, activityById, excludeIds) {
    def rows = []
    if (!(zigbeeJson instanceof Map)) {
        logDebug "No Zigbee details JSON"
        return rows
    }
    def devices = zigbeeJson.devices
    if (!(devices instanceof List)) {
        logDebug "Zigbee details missing devices[]"
        return rows
    }
    def hours = (settings.zigbeeHours ?: 24) as Integer
    def timeoutSec = hours * 3600L
    devices.each { zb ->
        if (!(zb instanceof Map)) return
        def deviceId = zb.id
        if (deviceId == null) return
        if (excludeIds.contains(deviceId.toString())) {
            logDebug "Excluded Zigbee device ${deviceId}"
            return
        }
        def activity = activityById[deviceId.toString()]
        if (activity?.disabled) {
            logDebug "Skipping disabled Zigbee device ${deviceId}"
            return
        }
        def lastMessage = zb.lastMessage
        def lastActivity = zb.lastActivity
        def lastActivityTime = activity?.lastActivityTime
        def heardMs = latestHeardMs([lastMessage, lastActivity, lastActivityTime])
        def name = zb.name ?: activity?.displayName ?: activity?.label ?: "Zigbee ${deviceId}"
        def nodeState = (zb.active != null) ? (truthy(zb.active) ? "OK" : "INACTIVE") : ""
        rows << [
            protocol    : "zigbee",
            keyId       : deviceId.toString(),
            name        : name,
            deviceId    : deviceId.toString(),
            nodeId      : zb.shortZigbeeId ?: "",
            lastHeard   : formatHeard(heardMs, lastMessage ?: lastActivity ?: lastActivityTime),
            lastActivity: lastActivity ?: lastActivityTime ?: "",
            heardMs     : heardMs,
            timeoutSec  : timeoutSec,
            listening   : "",
            nodeState   : nodeState
        ]
    }
    return rows
}

def activityIndex(deviceList) {
    def index = [:]
    def rows = []
    if (deviceList instanceof List) {
        rows = deviceList
    } else if (deviceList instanceof Map && deviceList.data instanceof List) {
        rows = deviceList.data
    }
    rows.each { row ->
        if (!(row instanceof Map) || row.id == null) return
        index[row.id.toString()] = [
            lastActivityTime: row.lastActivityTime ?: row.lastActivity,
            displayName     : row.displayName,
            label           : row.label,
            disabled        : truthy(row.disabled)
        ]
    }
    return index
}

def latestHeardMs(values) {
    def times = []
    values.each { val ->
        def ms = parseTimeMs(val)
        if (ms != null) times << ms
    }
    return times ? times.max() : null
}

def parseTimeMs(val) {
    if (val == null || val == "") return null
    if (val instanceof Number) return val.toLong()
    def raw = val.toString().trim()
    if (!raw || raw == "null") return null
    def formats = [
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss"
    ]
    for (fmt in formats) {
        try {
            return Date.parse(fmt, raw).time
        } catch (ignored) {
        }
    }
    return null
}

def formatHeard(heardMs, fallback) {
    if (heardMs != null) return new Date(heardMs).format("yyyy-MM-dd'T'HH:mm:ssZ")
    return fallback ? fallback.toString() : ""
}

def formatAge(ageSec) {
    if (ageSec == null) return "unknown"
    def s = ageSec.toLong()
    if (s < 0) return "unknown"
    def days = (s / 86400).toInteger()
    def hours = ((s % 86400) / 3600).toInteger()
    def mins = ((s % 3600) / 60).toInteger()
    if (days > 0) return "${days}d ${hours}h"
    if (hours > 0) return "${hours}h ${mins}m"
    return "${mins}m"
}

def extractHubitatId(entry) {
    if (entry == null) return null
    if (entry instanceof Map) return entry.id ?: entry.deviceId
    return entry
}

def excludeIdSet() {
    def ids = [] as Set
    excludeDevices?.each { dev ->
        if (dev?.id != null) ids << dev.id.toString()
    }
    return ids
}

def deviceKey(protocol, keyId) {
    return "${protocol}:${keyId}"
}

def buttonKey(protocol, keyId) {
    return "${protocol}_${keyId}"
}

def pruneSnooze() {
    def nowMs = now()
    def current = state.snooze ?: [:]
    def next = [:]
    current.each { key, untilMs ->
        try {
            if ((untilMs as Long) > nowMs) next[key] = untilMs
        } catch (ignored) {
        }
    }
    state.snooze = next
}

def isSnoozed(key) {
    def untilMs = state.snooze?."${key}"
    if (untilMs == null) return false
    try {
        return (untilMs as Long) > now()
    } catch (ignored) {
        return false
    }
}

def snoozeUntilText(key) {
    def untilMs = state.snooze?."${key}"
    if (untilMs == null) return ""
    try {
        def ms = untilMs as Long
        if (ms <= now()) return ""
        return "Snoozed until ${new Date(ms)}"
    } catch (ignored) {
        return ""
    }
}

def isFailedNode(nodeState) {
    def raw = nodeState?.toString()?.trim()?.toUpperCase()
    return raw == "FAILED" || raw == "DEAD"
}

def truthy(val) {
    if (val == null) return false
    if (val instanceof Boolean) return val
    def raw = val.toString().trim().toLowerCase()
    return raw == "true" || raw == "1" || raw == "yes"
}

def pollMinutes() {
    return Math.max(1, Math.min(60, (settings.pollIntervalMinutes as Integer) ?: 5))
}

def getHubJson(String path) {
    def parsed = null
    try {
        httpGet([
            uri        : "http://127.0.0.1:8080",
            path       : path,
            contentType: "application/json",
            timeout    : 30
        ]) { resp ->
            if (resp.status in [200, 207]) {
                parsed = resp.data
            } else {
                log.warn "GET ${path} returned HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.warn "GET ${path} failed: ${e.message}"
    }
    if (parsed instanceof String) {
        try {
            parsed = new groovy.json.JsonSlurper().parseText(parsed)
        } catch (Exception e) {
            log.warn "Could not parse JSON from ${path}: ${e.message}"
            return null
        }
    }
    return parsed
}

def pushHealthToLoki(devices) {
    if (!lokiUrl || !grafanaInstanceId || !grafanaApiKey) {
        logDebug "Loki is not configured; skipping push"
        return
    }
    def summary = state.lastSummary ?: [:]
    def hostname = location.name ?: "hubitat"
    def baseNs = (now() * 1000000L)
    def deviceValues = []
    devices.eachWithIndex { row, idx ->
        deviceValues << [
            (baseNs + idx).toString(),
            deviceLogfmt(row)
        ]
    }
    def summaryLine = "alive=${summary.alive ?: 0} dead=${summary.dead ?: 0} snoozed=${summary.snoozed ?: 0} checked=${summary.checked ?: 0}"
    def payload = [
        streams: [
            [
                stream: [
                    job   : "hubitat_device_health",
                    host  : hostname,
                    source: "hubitat_app"
                ],
                values: deviceValues
            ],
            [
                stream: [
                    job   : "hubitat_device_health_summary",
                    host  : hostname,
                    source: "hubitat_app"
                ],
                values: [
                    [baseNs.toString(), summaryLine]
                ]
            ]
        ]
    ]
    if (!deviceValues) {
        payload.streams = [payload.streams[1]]
    }
    def credentials = "${grafanaInstanceId}:${grafanaApiKey}"
    def encoded = credentials.bytes.encodeBase64().toString()
    def params = [
        uri        : lokiUrl,
        contentType: "application/json",
        headers    : [
            Authorization : "Basic ${encoded}",
            "Content-Type": "application/json; charset=UTF-8",
            "User-Agent"  : "Hubitat-Device-Health/1.0"
        ],
        body       : groovy.json.JsonOutput.toJson(payload),
        timeout    : 30
    ]
    logDebug "Pushing ${devices.size()} device lines plus summary to Loki"
    try {
        asynchttpPost("lokiResponse", params)
    } catch (Exception e) {
        log.error "Failed async POST to Loki: ${e.message}"
    }
}

def deviceLogfmt(row) {
    def parts = [
        "status=${row.status}",
        "alive=${row.alive}",
        "protocol=${row.protocol}",
        "name=${logfmtValue(row.name)}",
        "deviceId=${logfmtValue(row.deviceId)}",
        "nodeId=${logfmtValue(row.nodeId)}",
        "lastHeard=${logfmtValue(row.lastHeard)}",
        "lastActivity=${logfmtValue(row.lastActivity)}",
        "ageSec=${row.ageSec != null ? row.ageSec : -1}",
        "timeoutSec=${row.timeoutSec != null ? row.timeoutSec : -1}",
        "listening=${logfmtValue(row.listening)}",
        "nodeState=${logfmtValue(row.nodeState)}"
    ]
    return parts.join(" ")
}

def logfmtValue(val) {
    if (val == null || val == "") return '""'
    if (val instanceof Boolean) return val.toString()
    def raw = val.toString()
    if (!(raw =~ /[\s="\\]/)) return raw
    return "\"${raw.replace('\\', '\\\\').replace('\"', '\\\"')}\""
}

def lokiResponse(response, data) {
    if (response.status in [200, 204]) {
        logDebug "Loki push succeeded (${response.status})"
    } else {
        log.warn "Loki push failed: ${response.status} — ${response.errorMessage}"
    }
}

def disableDebugLogging() {
    log.info "Automatically disabling debug logging"
    app.updateSetting("debugEnable", [type: "bool", value: false])
}

def debugLoggingOn() {
    return settings?.debugEnable == true || settings?.debugLog == true
}

def logDebug(msg) {
    if (debugLoggingOn()) log.debug msg
}

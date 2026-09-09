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

def statusPage(params) {
    consumeStatusParams(params)
    def rows = statusListRows()
    syncActionEnums(rows)
    rows = statusListRows()
    dynamicPage(name: "statusPage", title: "Device Health", install: false, uninstall: false) {
        section("Settings") {
            href(
                name: "toConfig",
                title: "App settings",
                page: "configPage",
                description: "Loki, timeouts, exclude, notifications"
            )
            href(
                name: "pollNowStatus",
                title: "Poll now",
                page: "statusPage",
                params: [poll: "1"],
                description: "Re-read last-heard times from the hub"
            )
        }

        def summary = state.lastSummary ?: [:]
        section("") {
            paragraph statusHeading("Last poll")
            paragraph "Checked ${summary.checked ?: 0}, alive ${summary.alive ?: 0}, dead ${summary.dead ?: 0}, snoozed ${summary.snoozed ?: 0}."
            paragraph "Ran ${summary.at ?: "never"}."
        }

        section("") {
            paragraph statusHeading("Devices")
            paragraph "A device is dead when last-heard is older than its timeout, Z-Wave reports FAILED/DEAD, or it was never heard. ${statusTimeoutLegend()}"
            paragraph "The name opens the device page. Snooze hides alerts for 4 hours, 1 day, or 7 days. Forever ignores it (no poll, Loki, or alert). Off turns that off."
            if (!rows) {
                paragraph "No dead, snoozed, or ignored devices."
            } else {
                rows.each { row ->
                    statusDeviceControls(row)
                }
            }
        }
    }
}

def statusHeading(text) {
    return "<div style='font-weight:700;font-size:16px;margin:4px 0'>${htmlEscape(text)}</div>"
}

def statusControlTitle(row) {
    def view = statusDisplayRow(row)
    return "${statusDeviceId(view)}  ${view.name ?: ""}".trim()
}

def statusControlDescription(row) {
    def vals = statusTableValues(row)
    return "${vals[2]}  ·  ${vals[3]}  ·  ${vals[4]}  ·  ${vals[5]}"
}

def actionSettingName(row) {
    return "act_${buttonKey(row?.protocol, row?.keyId)}".toString()
}

def actionOptions() {
    return [
        "off"    : "Off",
        "4h"     : "4 hours",
        "1d"     : "1 day",
        "7d"     : "7 days",
        "forever": "Forever"
    ]
}

def currentAction(row) {
    def key = deviceKey(row.protocol, row.keyId)
    if (isIgnored(key)) return "forever"
    if (isSnoozed(key)) {
        def stored = state.snoozeAction?.get(key.toString())
        if (stored) return stored.toString()
        return "1d"
    }
    return "off"
}

def actionHours(value) {
    if (value == "4h") return 4
    if (value == "1d") return 24
    if (value == "7d") return 168
    return null
}

def statusStatusColor(row) {
    def status = statusDisplayRow(row).status
    if (status == "ignored") return "#6a1b9a"
    if (status == "snoozed") return "#ef6c00"
    if (status == "dead") return "#c62828"
    return "#555"
}

def statusRowHtml(row) {
    def title = htmlEscape(statusControlTitle(row))
    def desc = htmlEscape(statusControlDescription(row))
    def color = statusStatusColor(row)
    def devUrl = statusDeviceHref(row)
    def nameHtml = devUrl ? "<a href='${htmlEscape(devUrl)}' style='color:#1565c0;text-decoration:none;font-weight:600'>${title}</a>" : "<span style='font-weight:600'>${title}</span>"
    return "<div style='margin:0 0 4px 0'>${nameHtml}<br><span style='color:${color};font-size:12px'>${desc}</span></div>"
}

def statusOneLine(row) {
    return "${statusControlTitle(row)}  ·  ${statusControlDescription(row)}"
}

def statusDeviceControls(row) {
    def devUrl = statusDeviceHref(row)
    def btn = buttonKey(row.protocol, row.keyId)
    def link = [
        name : "openDev_${btn}",
        title: statusOneLine(row),
        width: 6
    ]
    if (devUrl) link.url = devUrl
    href(link)
    paragraph " ", width: 1
    input actionSettingName(row), "enum",
        title: "Snooze",
        options: actionOptions(),
        width: 1,
        submitOnChange: true,
        defaultValue: currentAction(row)
    paragraph " ", width: 4
}

def statusHrefDescription() {
    def summary = state.lastSummary ?: [:]
    if (!summary.at) return "No poll yet"
    return "Dead ${summary.dead ?: 0} · Snoozed ${summary.snoozed ?: 0} · Checked ${summary.checked ?: 0}"
}

def statusProblemRows(devices) {
    def rows = (devices ?: []).findAll { row ->
        def key = deviceKey(row.protocol, row.keyId)
        row.status == "dead" || row.status == "snoozed" || row.status == "ignored" || isIgnored(key)
    }
    return rows.sort { a, b -> (a.name ?: "").toLowerCase() <=> (b.name ?: "").toLowerCase() }
}

def statusListRows() {
    def out = []
    def seen = [] as Set
    statusProblemRows(state.lastDevices).each { row ->
        def key = deviceKey(row.protocol, row.keyId)
        if (seen.add(key)) out << row
    }
    ignoredDeviceRows().each { row ->
        def key = deviceKey(row.protocol, row.keyId)
        if (seen.add(key)) out << row
    }
    return out.sort { a, b -> (a.name ?: "").toLowerCase() <=> (b.name ?: "").toLowerCase() }
}

def statusDeviceId(row) {
    return (row?.deviceId ?: row?.keyId ?: "—").toString()
}

def statusTableColumns() {
    return ["Id", "Name", "Status", "Heard", "Age / timeout", "Why", "Ignore"]
}

def statusTimeoutLegend() {
    def listen = (settings.zwaveListeningHours ?: 4)
    def sleepy = (settings.zwaveSleepyHours ?: 36)
    def zig = (settings.zigbeeHours ?: 24)
    return "Age / timeout uses Timeouts on the settings page: listening Z-Wave ${listen}h (mains), sleepy Z-Wave ${sleepy}h (battery), Zigbee ${zig}h."
}

def timeoutKind(row) {
    if (row?.protocol == "zigbee") return "zigbee"
    return truthy(row?.listening) ? "listening Z-Wave" : "sleepy Z-Wave"
}

def statusWhyShort(row) {
    if (isFailedNode(row.nodeState)) return "Z-Wave ${row.nodeState}"
    if (row.ageSec == null) return "Never heard"
    if (row.timeoutSec != null && row.ageSec > row.timeoutSec) {
        return "Past ${timeoutKind(row)} timeout (setting)"
    }
    return (row.status ?: "").toString()
}

def formatHeard(ts) {
    if (!ts) return "unknown"
    def matcher = ts.toString() =~ /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/
    if (matcher.find()) return "${matcher.group(2)}-${matcher.group(3)} ${matcher.group(4)}:${matcher.group(5)}"
    return ts.toString()
}

def statusDisplayRow(row) {
    def out = [:] + row
    def key = deviceKey(row.protocol, row.keyId)
    if (isIgnored(key)) {
        out.status = "ignored"
    } else if (isSnoozed(key)) {
        out.status = "snoozed"
    }
    return out
}

def statusAgeSec(row) {
    if (row?.ageSec != null) {
        try {
            return row.ageSec.toLong()
        } catch (ignored) {
        }
    }
    def heardMs = resolveHeardMs(row)
    if (heardMs == null) return null
    return Math.max(0, ((now() - heardMs) / 1000).toLong())
}

def statusTableValues(row) {
    def view = statusDisplayRow(row)
    def node = view.nodeId ? view.nodeId.toString() : "—"
    def mesh = "${view.protocol ?: ""} ${node}".trim()
    def why = statusWhyShort(view)
    def snoozeNote = compactSnoozeUntil(deviceKey(view.protocol, view.keyId))
    if (snoozeNote) why = "${why} · ${snoozeNote}"
    def age = "${formatAge(statusAgeSec(view))} / ${formatAge(view.timeoutSec)}"
    def name = view.name ? "${view.name} (${mesh})" : mesh
    def ignored = isIgnored(deviceKey(view.protocol, view.keyId)) ? "on" : ""
    return [
        statusDeviceId(view),
        name,
        (view.status ?: "").toString(),
        formatHeard(view.lastHeard),
        age,
        why,
        ignored
    ]
}

def statusRowTitle(row) {
    def vals = statusTableValues(row)
    return "${vals[0]}  ${vals[1]}  ${vals[2]}  ${vals[3]}  ${vals[4]}"
}

def statusSnoozeOptions(rows) {
    def opts = [:]
    (rows ?: []).each { row ->
        opts[deviceKey(row.protocol, row.keyId)] = statusRowTitle(row)
    }
    return opts
}

def currentSnoozedKeys(rows) {
    return (rows ?: []).findAll { row ->
        isSnoozed(deviceKey(row.protocol, row.keyId))
    }.collect { row -> deviceKey(row.protocol, row.keyId) }
}

def ignoreSnapshot(row) {
    return [
        protocol: row.protocol,
        keyId   : row.keyId,
        name    : row.name,
        deviceId: row.deviceId,
        nodeId  : row.nodeId
    ]
}

def copyIgnoreMap() {
    def next = [:]
    (state.ignore ?: [:]).each { key, snap ->
        next[key.toString()] = snap
    }
    return next
}

def isIgnored(key) {
    return copyIgnoreMap().containsKey(key?.toString())
}

def ignoredDeviceRows() {
    def rows = []
    copyIgnoreMap().each { key, snap ->
        if (snap instanceof Map) {
            rows << snap
        } else {
            def parts = key.toString().split(":", 2)
            rows << [protocol: parts[0], keyId: parts.size() > 1 ? parts[1] : key, name: key]
        }
    }
    return rows
}

def statusIgnoreOptionRows(problemRows) {
    def out = []
    def seen = [] as Set
    (problemRows ?: []).each { row ->
        def key = deviceKey(row.protocol, row.keyId)
        if (seen.add(key)) out << row
    }
    ignoredDeviceRows().each { row ->
        def key = deviceKey(row.protocol, row.keyId)
        if (seen.add(key)) out << row
    }
    return out.sort { a, b -> (a.name ?: "").toLowerCase() <=> (b.name ?: "").toLowerCase() }
}

def statusIgnoreOptions(rows) {
    def opts = [:]
    (rows ?: []).each { row ->
        opts[deviceKey(row.protocol, row.keyId)] = "${statusDeviceId(row)}  ${row.name ?: ""}".trim()
    }
    return opts
}

def currentIgnoredKeys() {
    return copyIgnoreMap().keySet().collect { it.toString() }
}

def applyIgnore(row) {
    def key = deviceKey(row.protocol, row.keyId)
    def ignore = copyIgnoreMap()
    ignore[key] = ignoreSnapshot(row)
    state.ignore = ignore
    clearSnooze(key)
    log.info "Ignored ${key} forever"
}

def clearIgnore(key) {
    def want = key.toString()
    def ignore = [:]
    copyIgnoreMap().each { k, snap ->
        if (k != want) ignore[k] = snap
    }
    state.ignore = ignore
    log.info "Cleared ignore for ${want}"
}

def selectedIgnoreKeys() {
    def raw = settings?.statusIgnoredKeys
    if (raw == null) return null
    if (raw instanceof Collection) return raw.collect { it.toString() } as Set
    return [raw.toString()] as Set
}

def syncIgnoreSelection(rows) {
    def selected = selectedIgnoreKeys()
    if (selected == null) {
        state.ignoreEnumInited = true
        return
    }
    if (!state.ignoreEnumInited && selected.isEmpty()) {
        state.ignoreEnumInited = true
        return
    }
    state.ignoreEnumInited = true
    (rows ?: []).each { row ->
        def key = deviceKey(row.protocol, row.keyId)
        def want = selected.contains(key)
        def have = isIgnored(key)
        if (want && !have) applyIgnore(row)
        else if (!want && have) clearIgnore(key)
    }
}

def applyAction(row, value) {
    def key = deviceKey(row.protocol, row.keyId)
    def choice = (value ?: "off").toString()
    if (choice == "forever") {
        applyIgnore(row)
        return
    }
    clearIgnore(key)
    def hours = actionHours(choice)
    if (hours == null) {
        clearSnooze(key)
        def acts = [:]
        (state.snoozeAction ?: [:]).each { k, v ->
            if (k.toString() != key) acts[k.toString()] = v
        }
        state.snoozeAction = acts
        return
    }
    applySnooze(key, hours)
    def acts = [:]
    (state.snoozeAction ?: [:]).each { k, v ->
        acts[k.toString()] = v
    }
    acts[key] = choice
    state.snoozeAction = acts
}

def syncActionEnums(rows) {
    def prev = [:]
    (state.actionEnums ?: [:]).each { name, value ->
        prev[name.toString()] = value?.toString()
    }
    def next = [:]
    def changed = false
    (rows ?: []).each { row ->
        def name = actionSettingName(row)
        def raw = settings[name]
        def current = currentAction(row)
        if (raw == null) {
            next[name] = current
            return
        }
        def want = raw.toString()
        if (prev.containsKey(name)) {
            if (want != prev[name]) {
                applyAction(row, want)
                changed = true
            }
        } else if (want != current) {
            applyAction(row, want)
            changed = true
        }
        next[name] = currentAction(row)
    }
    state.actionEnums = next
    if (changed) refreshSnoozeDisplay()
}

def prevToggleMap(stored) {
    def prev = [:]
    (stored ?: [:]).each { name, checked ->
        prev[name.toString()] = truthy(checked)
    }
    return prev
}

def syncSnoozeToggles(rows) {
    def prev = prevToggleMap(state.snoozeToggles)
    def next = [:]
    def changed = false
    (rows ?: []).each { row ->
        def name = snoozeSettingName(row)
        def key = deviceKey(row.protocol, row.keyId)
        def checked = truthy(settings[name])
        if (prev.containsKey(name)) {
            def was = truthy(prev[name])
            if (checked && !was) {
                applySnooze(key, snoozeHours())
                changed = true
            } else if (!checked && was) {
                clearSnooze(key)
                changed = true
            }
        } else if (checked && !isSnoozed(key)) {
            applySnooze(key, snoozeHours())
            changed = true
        }
        next[name] = isSnoozed(key)
    }
    state.snoozeToggles = next
    if (changed) refreshSnoozeDisplay()
}

def syncIgnoreToggles(rows) {
    def prev = prevToggleMap(state.ignoreToggles)
    def next = [:]
    (rows ?: []).each { row ->
        def name = ignoreSettingName(row)
        def key = deviceKey(row.protocol, row.keyId)
        def checked = truthy(settings[name])
        if (prev.containsKey(name)) {
            def was = truthy(prev[name])
            if (checked && !was) applyIgnore(row)
            else if (!checked && was) clearIgnore(key)
        } else if (checked && !isIgnored(key)) {
            applyIgnore(row)
        }
        next[name] = isIgnored(key)
    }
    state.ignoreToggles = next
}

def statusTableLine(row) {
    def vals = statusTableValues(row)
    return "Device id ${vals[0]}  ·  ${vals[1]}  ·  ${vals[2]}  ·  ${vals[3]}  ·  ${vals[4]}  ·  ${vals[5]}  ·  ${vals[6]}"
}

def statusDeviceHref(row) {
    def id = row?.deviceId
    if (id == null || id.toString().trim() == "") return ""
    return "/device/edit/${id.toString().trim()}"
}

def statusNameHtml(row) {
    def view = statusDisplayRow(row)
    def node = view.nodeId ? view.nodeId.toString() : "—"
    def mesh = "${view.protocol ?: ""} ${node}".trim()
    def label = view.name ? view.name.toString() : mesh
    def href = statusDeviceHref(view)
    def nameHtml = href ? "<a href='${htmlEscape(href)}' style='color:#1565c0;text-decoration:none'>${htmlEscape(label)}</a>" : htmlEscape(label)
    if (view.name) nameHtml = "${nameHtml} <span style='color:#666'>(${htmlEscape(mesh)})</span>"
    return nameHtml
}

def statusTableHtml(rows) {
    def cell = "padding:8px 12px;text-align:left;vertical-align:middle;border-bottom:1px solid #ddd"
    def nowrap = "${cell};white-space:nowrap"
    def wrap = "${cell};white-space:normal;word-break:break-word"
    def sb = new StringBuilder()
    sb << "<div style='display:inline-block;max-width:100%'>"
    sb << "<table style='width:auto!important;border-collapse:collapse;font-size:13px;line-height:1.35'>"
    sb << "<thead><tr>"
    statusTableColumns().each { name ->
        sb << "<th style='${nowrap};border-bottom:2px solid #bbb;font-weight:600'>${htmlEscape(name)}</th>"
    }
    sb << "</tr></thead><tbody>"
    (rows ?: []).each { row ->
        def vals = statusTableValues(row)
        sb << "<tr>"
        sb << "<td style='${nowrap}'>${htmlEscape(vals[0])}</td>"
        sb << "<td style='${wrap};min-width:22em;max-width:28em'>${statusNameHtml(row)}</td>"
        sb << "<td style='${nowrap}'>${htmlEscape(vals[2])}</td>"
        sb << "<td style='${nowrap}'>${htmlEscape(vals[3])}</td>"
        sb << "<td style='${nowrap}'>${htmlEscape(vals[4])}</td>"
        sb << "<td style='${wrap};min-width:22em;max-width:36em'>${htmlEscape(vals[5])}</td>"
        sb << "<td style='${nowrap}'>${htmlEscape(vals[6])}</td>"
        sb << "</tr>"
    }
    sb << "</tbody></table></div>"
    return sb.toString()
}

def htmlEscape(val) {
    if (val == null) return ""
    return val.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

def snoozeQuery(action, protocol, keyId) {
    return "?sz=${action}&p=${protocol}&k=${keyId}"
}

def consumeStatusParams(p) {
    if (!p) return
    if (p.poll == "1" || p.poll == 1) {
        log.info "Manual device health poll"
        pollDeviceHealth()
        return
    }
    if (!p.sz || !p.p || !p.k) return
    appButtonHandler("snooze_${p.sz}_${p.p}_${p.k}")
}

def snoozeSettingName(row) {
    return "sz_${buttonKey(row?.protocol, row?.keyId)}".toString()
}

def ignoreSettingName(row) {
    return "ig_${buttonKey(row?.protocol, row?.keyId)}".toString()
}

def snoozeHours() {
    def raw = settings?.statusSnoozeHours?.toString()
    if (raw == "1") return 1
    if (raw == "168") return 168
    return 24
}

def copySnoozeMap() {
    def next = [:]
    (state.snooze ?: [:]).each { key, untilMs ->
        next[key.toString()] = untilMs
    }
    return next
}

def applySnooze(key, hours) {
    def snooze = copySnoozeMap()
    def untilMs = now() + (hours * 3600000L)
    snooze[key.toString()] = untilMs
    state.snooze = snooze
    log.info "Snoozed ${key} for ${hours}h (until ${new Date(untilMs)})"
}

def clearSnooze(key) {
    def want = key.toString()
    def snooze = [:]
    copySnoozeMap().each { k, untilMs ->
        if (k != want) snooze[k] = untilMs
    }
    state.snooze = snooze
    log.info "Cleared snooze for ${want}"
}

def writeEnumSetting(name, values) {
    try {
        app.updateSetting(name.toString(), [type: "enum", value: values ?: []])
    } catch (Exception ignored) {
    }
}

def selectedSnoozeKeys() {
    def raw = settings?.statusSnoozedKeys
    if (raw == null) return null
    if (raw instanceof Collection) return raw.collect { it.toString() } as Set
    return [raw.toString()] as Set
}

def refreshSnoozeDisplay() {
    def devices = state.lastDevices
    if (!devices) return
    evaluateDevices(devices)
    persistStatus(devices)
}

def syncSnoozeSelection(rows) {
    pruneSnooze()
    def selected = selectedSnoozeKeys()
    if (selected == null) {
        state.snoozeEnumInited = true
        return
    }
    if (!state.snoozeEnumInited && selected.isEmpty()) {
        state.snoozeEnumInited = true
        return
    }
    state.snoozeEnumInited = true
    def changed = false
    (rows ?: []).each { row ->
        def key = deviceKey(row.protocol, row.keyId)
        def want = selected.contains(key)
        def have = isSnoozed(key)
        if (want && !have) {
            applySnooze(key, snoozeHours())
            changed = true
        } else if (!want && have) {
            clearSnooze(key)
            changed = true
        }
    }
    if (changed) refreshSnoozeDisplay()
}

def syncSnoozeCheckboxes(rows) {
    syncSnoozeSelection(rows)
}

def statusSnoozeButtons(row) {
    def btn = buttonKey(row.protocol, row.keyId)
    return [
        [name: "snooze_1h_${btn}", title: "1h"],
        [name: "snooze_24h_${btn}", title: "24h"],
        [name: "snooze_7d_${btn}", title: "7d"],
        [name: "snooze_clear_${btn}", title: "Clear"]
    ]
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
    if (!state.ignore) state.ignore = [:]
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
    def parsed = parseSnoozeButton(btn)
    if (!parsed) {
        logDebug "Unhandled button: ${btn}"
        return
    }
    def key = deviceKey(parsed.protocol, parsed.keyId)
    if (parsed.action == "clear") {
        clearSnooze(key)
        refreshSnoozeDisplay()
        return
    }
    def hours = (parsed.action == "1h") ? 1 : ((parsed.action == "24h") ? 24 : 168)
    applySnooze(key, hours)
    refreshSnoozeDisplay()
}

def parseSnoozeButton(btn) {
    if (btn == null) return null
    def matcher = btn.toString() =~ /^snooze_(1h|24h|7d|clear)_(zwave|zigbee)_(\d+)$/
    if (!matcher.matches()) return null
    return [
        action  : matcher.group(1),
        protocol: matcher.group(2),
        keyId   : matcher.group(3)
    ]
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

def parseHeardMs(ts) {
    if (ts == null) return null
    if (ts instanceof Number) return ts.toLong()
    def raw = ts.toString().trim()
    if (!raw) return null
    def formats = ["yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"]
    def parsed = null
    formats.each { fmt ->
        if (parsed != null) return
        try {
            parsed = Date.parse(fmt, raw).time
        } catch (ignored) {
        }
    }
    return parsed
}

def resolveHeardMs(row) {
    if (row == null) return null
    if (row.heardMs != null) {
        try {
            return row.heardMs.toLong()
        } catch (ignored) {
        }
    }
    return parseHeardMs(row.lastHeard)
}

def evaluateDevices(devices) {
    def nowMs = now()
    devices.each { row ->
        def key = deviceKey(row.protocol, row.keyId)
        row.key = key
        def heardMs = resolveHeardMs(row)
        if (heardMs != null) {
            row.heardMs = heardMs
            row.ageSec = Math.max(0, ((nowMs - heardMs) / 1000).toLong())
        } else if (row.ageSec == null) {
            row.ageSec = null
        }
        def failed = isFailedNode(row.nodeState)
        def timedOut = (row.ageSec != null && row.timeoutSec != null && row.ageSec > row.timeoutSec)
        def noHeard = (heardMs == null && row.ageSec == null)
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

def deadReason(row) {
    def cause = deadCause(row)
    if (row.status == "snoozed") {
        return cause ? "Snoozed · ${cause}" : "Snoozed"
    }
    return cause
}

def deadCause(row) {
    if (isFailedNode(row.nodeState)) {
        return "Z-Wave nodeState=${row.nodeState}"
    }
    if (row.ageSec == null) {
        return "Never heard"
    }
    if (row.timeoutSec != null && row.ageSec > row.timeoutSec) {
        def kind = timeoutKind(row)
        def extra = row.nodeState ? " (nodeState=${row.nodeState} is not a ping)" : ""
        return "Age ${formatAge(row.ageSec)} > ${kind} timeout ${formatAge(row.timeoutSec)}${extra}"
    }
    return row.status ?: ""
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
            heardMs    : row.heardMs,
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
        if (isIgnored(deviceKey("zwave", nodeId))) {
            logDebug "Ignored Z-Wave node ${nodeId}"
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
        if (isIgnored(deviceKey("zigbee", deviceId))) {
            logDebug "Ignored Zigbee device ${deviceId}"
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
    if (days > 0) return hours > 0 ? "${days}d ${hours}h" : "${days}d"
    if (hours > 0) return mins > 0 ? "${hours}h ${mins}m" : "${hours}h"
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
    return "${protocol}:${keyId}".toString()
}

def buttonKey(protocol, keyId) {
    return "${protocol}_${keyId}".toString()
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
    def untilMs = state.snooze?.get(key?.toString())
    if (untilMs == null) return false
    try {
        return (untilMs as Long) > now()
    } catch (ignored) {
        return false
    }
}

def snoozeUntilText(key) {
    def untilMs = state.snooze?.get(key?.toString())
    if (untilMs == null) return ""
    try {
        def ms = untilMs as Long
        if (ms <= now()) return ""
        return "Snoozed until ${new Date(ms)}"
    } catch (ignored) {
        return ""
    }
}

def compactSnoozeUntil(key) {
    def untilMs = state.snooze?.get(key?.toString())
    if (untilMs == null) return ""
    try {
        def ms = untilMs as Long
        if (ms <= now()) return ""
        return "until ${formatHeard(new Date(ms).format("yyyy-MM-dd'T'HH:mm:ssZ"))}"
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

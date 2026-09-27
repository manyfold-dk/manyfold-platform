.pragma library

// Pure evaluation of the platform's public status API into what the bar icon
// and its panel show. No QML in here, so the rules can be tested with plain
// Node (see Model.test.mjs).
//
// The panel follows the website's /status view: a headline, the platform
// services, the four stack layers, then the checks with their uptime and
// response-time history. Four endpoints feed it, fetched by one curl run:
//
//   health/summary   one status per row, and the overall verdict (required)
//   health           the live detail behind each row, and the firing alerts
//   status           the individual checks with their latency
//   status/history   uptime buckets and a latency series per synthetic check
//
// Only the summary is required. Without the others the panel leaves out the
// detail, the alerts, or the charts, rather than reporting the platform down.

var ENDPOINTS = ["health/summary", "health", "status", "status/history"]

// curl runs with -w "\n@@mf %{http_code}\n": every transfer, failed or not,
// ends with that marker, in the order the URLs were given.
var MARKER = "@@mf"

var SERVICES = [
  { key: "edge", name: "Edge", fallback: "Probe from outside the platform" },
  { key: "identity", name: "Identity and secrets", fallback: "Keycloak · OpenBao" },
  { key: "delivery", name: "Delivery", fallback: "Argo CD · GitHub Actions" },
  { key: "observability", name: "Observability", fallback: "Prometheus · Loki · Tempo · Grafana" }
]

// Top to bottom as in the logo: the tenants sit on the network, the network
// on the cluster, the cluster on the infrastructure.
var STACK = [
  { key: "applications", name: "Tenants", fallback: "Workloads running on the platform" },
  { key: "network", name: "Network", fallback: "Cilium · Hubble" },
  { key: "cluster", name: "Cluster", fallback: "Kubernetes on Talos Linux" },
  { key: "infrastructure", name: "Infrastructure", fallback: "Cloud servers" }
]

var HEADLINE = {
  ok: "All systems operational",
  warn: "Degraded performance",
  down: "Service disruption",
  unknown: "Not measured"
}

// The bar shows the Manyfold mark while nothing needs attention, and a
// warning triangle when something does. An unreachable API keeps the mark,
// tinted urgent: that is a fault in the reporting, not a platform alert.
var GLYPH = { ok: "", warn: "\uf071", down: "\uf071", stale: "" }

function showsLogo(level) {
  return level !== "warn" && level !== "down"
}

// The brand blues, light to deep. The deepest two nearly vanish on a dark
// bar, so dark surfaces get the same ramp one step lighter.
var LOGO_ON_LIGHT = ["#93C5FD", "#60A5FA", "#2563EB", "#1E40AF"]
var LOGO_ON_DARK = ["#BFDBFE", "#93C5FD", "#60A5FA", "#3B82F6"]

function channelLuminance(c) {
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
}

// r, g, b in 0..1, as QML colours carry them.
function logoColors(r, g, b) {
  var luminance = 0.2126 * channelLuminance(r) + 0.7152 * channelLuminance(g) + 0.0722 * channelLuminance(b)
  return luminance >= 0.5 ? LOGO_ON_LIGHT : LOGO_ON_DARK
}

// The summary's timestamp is refreshed on every request, so an old one means
// a cache or proxy is serving yesterday's picture.
var STALE_AFTER_MS = 120000

// Same mapping as the website's statusTone. "unknown" means a check produced
// no reading: a gap in the monitoring, not a fault, so it never colours the
// icon.
function tone(value) {
  if (!value) return "unknown"
  var v = String(value).toLowerCase()
  if (v === "operational" || v === "healthy") return "ok"
  if (v === "degraded") return "warn"
  if (v === "unknown") return "unknown"
  return "down"
}

function label(value) {
  var v = value ? String(value).toLowerCase() : ""
  return !v || v === "unknown" ? "not measured" : v
}

var RANK = { unknown: 0, ok: 0, warn: 1, down: 2 }

function worse(level, t) {
  return RANK[t] > RANK[level] ? t : level
}

function clock(date) {
  function pad(n) { return (n < 10 ? "0" : "") + n }
  return pad(date.getHours()) + ":" + pad(date.getMinutes())
}

function parseBatch(raw) {
  var parts = String(raw || "").split(/\n@@mf (\d{3})\n/)
  var out = []
  for (var i = 0; i + 1 < parts.length; i += 2) out.push({ code: parts[i + 1], body: parts[i] })
  return out
}

function json(part) {
  if (!part || part.code !== "200") return null
  try {
    var data = JSON.parse(part.body)
    return data && typeof data === "object" ? data : null
  } catch (e) {
    return null
  }
}

// The live detail behind a row, from /api/v1/health. Empty when that
// endpoint did not answer or has nothing to say; the caller falls back to the
// row's description.
function liveDetail(key, health) {
  if (!health) return ""
  var p = health.platform || {}
  if (key === "edge" || key === "observability")
    return p[key] && p[key].details ? p[key].details : ""
  // "Keycloak healthy, OpenBao healthy": the chip already says healthy, so
  // only a component that is not keeps its state in the detail.
  if (key === "identity")
    return p.identity && p.identity.details ? String(p.identity.details).replace(/ healthy\b/g, "") : ""
  if (key === "delivery")
    return p.argocd && p.argocd.details ? "Argo CD: " + p.argocd.details : ""
  if (key === "applications") {
    var apps = health.applications && health.applications.apps ? health.applications.apps : []
    if (!apps.length) return ""
    var sick = []
    for (var i = 0; i < apps.length; i++)
      if (tone(apps[i].status) !== "ok") sick.push(apps[i].name + " " + label(apps[i].status))
    return sick.length ? sick.join(", ") : apps.length + (apps.length === 1 ? " workload" : " workloads")
  }
  if (key === "network") {
    var c = health.network && health.network.cilium
    return c && c.details ? "Cilium: " + c.details : ""
  }
  if (key === "cluster") {
    var cl = health.cluster
    if (!cl || !cl.totalPods) return ""
    var text = cl.runningPods + "/" + cl.totalPods + " pods running"
    if (cl.failedPods) text += ", " + cl.failedPods + " failed"
    if (cl.pendingPods) text += ", " + cl.pendingPods + " pending"
    return text
  }
  if (key === "infrastructure") {
    var inf = health.infrastructure
    return inf && inf.totalNodes ? inf.healthyNodes + "/" + inf.totalNodes + " nodes ready" : ""
  }
  return ""
}

function rows(defs, summary, health) {
  var out = []
  for (var i = 0; i < defs.length; i++) {
    var value = summary[defs[i].key]
    out.push({
      name: defs[i].name,
      detail: liveDetail(defs[i].key, health) || defs[i].fallback,
      tone: tone(value),
      label: label(value)
    })
  }
  return out
}

function alertsOf(summary, health) {
  var list = health && Array.isArray(health.activeAlerts) ? health.activeAlerts : []
  var alerts = []
  for (var i = 0; i < list.length; i++) {
    var severity = String(list[i].severity || "warning").toLowerCase()
    alerts.push({
      name: list[i].name || "Alert",
      severity: severity,
      message: list[i].message || "",
      tone: severity === "critical" ? "down" : "warn"
    })
  }
  var count = typeof summary.activeAlerts === "number" ? summary.activeAlerts : alerts.length
  return { list: alerts, count: Math.max(count, alerts.length) }
}

function uptimeOf(u) {
  if (!u || !Array.isArray(u.buckets) || !u.buckets.length) return null
  var summary = typeof u.ratio === "number"
    // One decimal, never rounded up to a clean 100% from something less.
    ? Math.floor(u.ratio * 1000) / 10 + "% up"
    : "no readings yet"
  return { buckets: u.buckets, days: u.days || 0, summary: summary }
}

function sparkOf(l) {
  if (!l || !Array.isArray(l.points)) return null
  var peak = null
  for (var i = 0; i < l.points.length; i++)
    if (typeof l.points[i] === "number" && (peak === null || l.points[i] > peak)) peak = l.points[i]
  if (peak === null) return null
  return { points: l.points, hours: l.hours || 0, peak: Math.round(peak) }
}

function formatLatency(ms) {
  return typeof ms === "number" && ms >= 0 ? Math.round(ms) + " ms" : ""
}

function checksOf(status, history) {
  var services = status && Array.isArray(status.services) ? status.services : []
  var series = history && Array.isArray(history.checks) ? history.checks : []
  var byName = {}
  for (var i = 0; i < series.length; i++) byName[series[i].name] = series[i]
  var out = []
  for (var j = 0; j < services.length; j++) {
    var s = services[j]
    var h = byName[s.name]
    out.push({
      name: s.name,
      tone: tone(s.status),
      label: label(s.status),
      latency: formatLatency(s.latencyMs),
      detail: s.detail || "",
      uptime: h ? uptimeOf(h.uptime) : null,
      spark: h ? sparkOf(h.latency) : null
    })
  }
  return out
}

function build(data, now) {
  var summary = data.summary
  var services = rows(SERVICES, summary, data.health)
  var stack = rows(STACK, summary, data.health)
  var alerts = alertsOf(summary, data.health)
  var checks = checksOf(data.status, data.history)

  var headlineTone = tone(summary.overall)
  var level = headlineTone === "unknown" ? "ok" : headlineTone
  var all = services.concat(stack)
  for (var i = 0; i < all.length; i++) level = worse(level, all[i].tone)
  for (var j = 0; j < alerts.list.length; j++) level = worse(level, alerts.list[j].tone)
  if (alerts.count > 0) level = worse(level, "warn")

  var alertLabel = alerts.count === 0 ? ""
    : alerts.count === 1 ? "1 alert needs attention" : alerts.count + " alerts need attention"
  var stamp = summary.timestamp ? new Date(summary.timestamp) : null
  var stale = !!stamp && !isNaN(stamp.getTime()) && now.getTime() - stamp.getTime() > STALE_AFTER_MS

  return {
    level: level,
    glyph: GLYPH[level],
    headline: HEADLINE[headlineTone],
    headlineTone: headlineTone,
    meta: "Updated " + clock(now) + (alertLabel ? " · " + alertLabel : ""),
    alertLabel: alertLabel,
    stale: stale,
    error: "",
    services: services,
    stack: stack,
    alerts: alerts.list,
    checks: checks,
    // The panel shows checks with history as full-width cards with charts,
    // and the rest two to a row.
    plainChecks: checks.filter(function(c) { return !c.uptime && !c.spark }),
    chartChecks: checks.filter(function(c) { return !!(c.uptime || c.spark) }),
    tooltip: "Manyfold: " + HEADLINE[headlineTone].toLowerCase() + (alertLabel ? " · " + alertLabel : "")
  }
}

function unreachable(reason, now, lastGood) {
  return {
    level: "stale",
    glyph: GLYPH.stale,
    headline: "Status unavailable right now",
    headlineTone: "down",
    meta: "Checked " + clock(now) + (lastGood ? " · last good " + clock(lastGood) : ""),
    alertLabel: "",
    stale: false,
    // Worded as the website words it: a silent health API is a fault in the
    // status reporting, which may or may not mean the platform is unwell.
    error: reason + ". Nothing here is current; the platform itself may be fine.",
    services: [],
    stack: [],
    alerts: [],
    checks: [],
    plainChecks: [],
    chartChecks: [],
    tooltip: "Manyfold: status unavailable -- " + reason
  }
}

function fromBatch(raw, now, lastGood) {
  var parts = parseBatch(raw)
  if (!parts.length) return unreachable("curl produced no output", now, lastGood)
  var first = parts[0]
  if (first.code === "000") return unreachable("No response from the health API", now, lastGood)
  if (first.code !== "200") return unreachable("Health API answered HTTP " + first.code, now, lastGood)
  var summary = json(first)
  if (!summary || !summary.overall) return unreachable("Health API answered without a status", now, lastGood)
  return build({ summary: summary, health: json(parts[1]), status: json(parts[2]), history: json(parts[3]) }, now)
}

// A theme colour from colors.toml by name, else its ANSI slot. The shell's
// Color singleton exposes only foreground, accent, urgent and muted.
function themeColor(toml, name, slot, fallback) {
  var text = String(toml || "")
  var m = text.match(new RegExp("^\\s*" + name + "\\s*=\\s*[\"'](#[0-9A-Fa-f]{6,8})[\"']", "m"))
    || text.match(new RegExp("^\\s*" + slot + "\\s*=\\s*[\"'](#[0-9A-Fa-f]{6,8})[\"']", "m"))
  return m ? m[1] : fallback
}

// The Grafana page listing the firing alert rules: an explicit alertsUrl
// wins, else it is derived from the dashboard URL. Grafana lists the
// Prometheus rules as data-source-managed rules, so the filter covers the
// alerts the health API counts.
function alertsUrl(override, dashboardUrl) {
  if (override) return String(override)
  if (!dashboardUrl) return ""
  return String(dashboardUrl).replace(/\/+$/, "") + "/alerting/list?search=state:firing"
}

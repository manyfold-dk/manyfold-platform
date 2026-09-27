// Run from this directory: node --test Model.test.mjs
// Model.js is a QML JavaScript library (".pragma library"), so it is loaded
// into a plain VM context here instead of imported as a module.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("./Model.js", import.meta.url), "utf8").replace(/^\.pragma library\s*$/m, "");
const Model = vm.createContext({});
vm.runInContext(source, Model);
// Arrays built inside the VM belong to another realm; compare them as plain data.
const plain = (value) => JSON.parse(JSON.stringify(value));

const NOW = new Date("2026-09-27T12:30:30Z");
const LOCAL_NOW = `${String(NOW.getHours()).padStart(2, "0")}:${String(NOW.getMinutes()).padStart(2, "0")}`;

function summary(overrides = {}) {
  return {
    overall: "healthy", infrastructure: "healthy", network: "healthy", cluster: "healthy",
    applications: "healthy", platform: "healthy", edge: "healthy", identity: "healthy",
    delivery: "healthy", observability: "healthy", pipelines: "healthy", activeAlerts: 0,
    timestamp: "2026-09-27T12:30:00Z", ...overrides,
  };
}

function health(overrides = {}) {
  return {
    overallStatus: "healthy",
    infrastructure: { status: "healthy", totalNodes: 6, healthyNodes: 6 },
    network: { status: "healthy", cilium: { name: "Cilium", status: "healthy", details: "6/6 pods running" } },
    cluster: { status: "healthy", totalPods: 173, runningPods: 137, pendingPods: 0, failedPods: 0 },
    platform: {
      status: "healthy",
      edge: { name: "Edge", status: "healthy", details: "answered in 243 ms from outside" },
      identity: { name: "Identity and secrets", status: "healthy", details: "Keycloak healthy, OpenBao healthy" },
      argocd: { name: "ArgoCD", status: "healthy", details: "65 apps synced" },
      observability: { name: "Observability", status: "healthy", details: "22/22 pods running" },
    },
    applications: { status: "healthy", apps: [{ name: "A", status: "healthy" }, { name: "B", status: "healthy" }] },
    activeAlerts: [],
    firingAlerts: 0,
    ...overrides,
  };
}

const STATUS = {
  status: "operational",
  services: [
    { name: "Backend API", status: "operational", latencyMs: 9, detail: "live check" },
    { name: "From the internet", status: "operational", latencyMs: 243, detail: "Cloudflare network, 2 minutes ago" },
    { name: "TLS certificate", status: "operational", latencyMs: null, detail: "valid for another 52 days" },
  ],
};

const HISTORY = {
  checks: [{
    name: "From the internet",
    uptime: { days: 14, bucketHours: 12, buckets: [null, 1.0, 0.9996], ratio: 0.9998 },
    latency: { hours: 24, stepMinutes: 30, points: [239, null, 425.4, 245], latestMs: 245 },
  }],
};

function batch(...parts) {
  return parts.map(([code, body]) => `${typeof body === "string" ? body : JSON.stringify(body)}\n@@mf ${code}\n`).join("");
}

function full(s = summary(), h = health()) {
  return batch(["200", s], ["200", h], ["200", STATUS], ["200", HISTORY]);
}

test("all healthy: ok, the website's headline, every row measured", () => {
  const r = Model.fromBatch(full(), NOW, null);
  assert.equal(r.level, "ok");
  assert.equal(r.headline, "All systems operational");
  assert.equal(r.meta, `Updated ${LOCAL_NOW}`);
  assert.deepEqual(plain(r.services.map((s) => s.name)), ["Edge", "Identity and secrets", "Delivery", "Observability"]);
  assert.deepEqual(plain(r.stack.map((s) => s.name)), ["Tenants", "Network", "Cluster", "Infrastructure"]);
  assert.ok([...r.services, ...r.stack].every((row) => row.tone === "ok" && row.label === "healthy"));
  assert.equal(r.stale, false);
});

test("rows carry the live detail from /health", () => {
  const r = Model.fromBatch(full(), NOW, null);
  const detail = Object.fromEntries([...r.services, ...r.stack].map((row) => [row.name, row.detail]));
  assert.equal(detail.Edge, "answered in 243 ms from outside");
  assert.equal(detail["Identity and secrets"], "Keycloak, OpenBao");
  assert.equal(detail.Delivery, "Argo CD: 65 apps synced");
  assert.equal(detail.Tenants, "2 workloads");
  assert.equal(detail.Network, "Cilium: 6/6 pods running");
  assert.equal(detail.Cluster, "137/173 pods running");
  assert.equal(detail.Infrastructure, "6/6 nodes ready");
});

test("without /health the rows fall back to their descriptions and alerts come from the count", () => {
  const r = Model.fromBatch(batch(["200", summary({ activeAlerts: 2 })], ["000", ""], ["200", STATUS], ["200", HISTORY]), NOW, null);
  assert.equal(r.stack[1].detail, "Cilium · Hubble");
  assert.equal(r.level, "warn");
  assert.equal(r.alertLabel, "2 alerts need attention");
  assert.equal(r.alerts.length, 0);
});

test("a firing warning alert is warn while the headline stays operational", () => {
  const h = health({ activeAlerts: [{ name: "Multiple Alerts", severity: "warning", message: "1 alerts firing" }], firingAlerts: 1 });
  const r = Model.fromBatch(full(summary({ activeAlerts: 1 }), h), NOW, null);
  assert.equal(r.level, "warn");
  assert.equal(r.headline, "All systems operational");
  assert.equal(r.meta, `Updated ${LOCAL_NOW} · 1 alert needs attention`);
  assert.deepEqual(plain(r.alerts.map((a) => [a.name, a.tone])), [["Multiple Alerts", "warn"]]);
  assert.equal(r.tooltip, "Manyfold: all systems operational · 1 alert needs attention");
});

test("a critical alert is down", () => {
  const h = health({ activeAlerts: [{ name: "NodeDown", severity: "critical" }] });
  assert.equal(Model.fromBatch(full(summary({ activeAlerts: 1 }), h), NOW, null).level, "down");
});

test("a degraded row is warn, an unhealthy row is down", () => {
  assert.equal(Model.fromBatch(full(summary({ delivery: "degraded" })), NOW, null).level, "warn");
  const r = Model.fromBatch(full(summary({ overall: "unhealthy", cluster: "unhealthy" })), NOW, null);
  assert.equal(r.level, "down");
  assert.equal(r.headline, "Service disruption");
});

test("unknown is 'not measured' and never colours the icon, as on the website", () => {
  const r = Model.fromBatch(full(summary({ edge: "unknown" })), NOW, null);
  assert.equal(r.level, "ok");
  assert.equal(r.services[0].tone, "unknown");
  assert.equal(r.services[0].label, "not measured");
});

test("pipelines do not change the level", () => {
  assert.equal(Model.fromBatch(full(summary({ pipelines: "unhealthy" })), NOW, null).level, "ok");
});

test("checks join the live status with the history by name", () => {
  const [api, internet, tls] = Model.fromBatch(full(), NOW, null).checks;
  assert.deepEqual([api.name, api.latency, api.uptime, api.spark], ["Backend API", "9 ms", null, null]);
  assert.equal(internet.latency, "243 ms");
  assert.equal(internet.uptime.summary, "99.9% up");
  assert.equal(internet.uptime.days, 14);
  assert.deepEqual([internet.spark.hours, internet.spark.peak], [24, 425]);
  assert.equal(tls.latency, "");
  assert.equal(tls.detail, "valid for another 52 days");
  const r = Model.fromBatch(full(), NOW, null);
  assert.deepEqual(plain(r.plainChecks.map((c) => c.name)), ["Backend API", "TLS certificate"]);
  assert.deepEqual(plain(r.chartChecks.map((c) => c.name)), ["From the internet"]);
});

test("a sick identity component keeps its state in the detail", () => {
  const h = health();
  h.platform.identity.details = "Keycloak healthy, OpenBao degraded";
  assert.equal(Model.fromBatch(full(summary(), h), NOW, null).services[1].detail, "Keycloak, OpenBao degraded");
});

test("an old summary timestamp marks the data stale", () => {
  assert.equal(Model.fromBatch(full(summary({ timestamp: "2026-09-27T12:20:00Z" })), NOW, null).stale, true);
});

test("the summary is required; everything else is optional", () => {
  const lastGood = new Date(NOW.getTime() - 600000);
  const offline = Model.fromBatch(batch(["000", ""], ["000", ""], ["000", ""], ["000", ""]), NOW, lastGood);
  assert.equal(offline.level, "stale");
  assert.match(offline.error, /No response from the health API/);
  assert.match(offline.meta, /last good/);
  assert.match(Model.fromBatch(batch(["502", "<html>"]), NOW, null).error, /HTTP 502/);
  assert.match(Model.fromBatch(batch(["200", "<html>login</html>"]), NOW, null).error, /without a status/);
  assert.match(Model.fromBatch("", NOW, null).error, /no output/);
  const partial = Model.fromBatch(batch(["200", summary()], ["500", ""], ["000", ""], ["200", "not json"]), NOW, null);
  assert.equal(partial.level, "ok");
  assert.equal(partial.checks.length, 0);
});

test("theme colours come from colors.toml by name, then the ANSI slot, then the fallback", () => {
  assert.equal(Model.themeColor('red = "#f7768e"\nyellow = "#e0af68"\n', "yellow", "color3", "#000000"), "#e0af68");
  assert.equal(Model.themeColor('color2 = "#a9b665"\n', "green", "color2", "#000000"), "#a9b665");
  assert.equal(Model.themeColor("", "green", "color2", "#000000"), "#000000");
});

test("the alert list URL is the override, else derived from the dashboard, else empty", () => {
  assert.equal(Model.alertsUrl("https://alerts.example/x", "https://grafana.example"), "https://alerts.example/x");
  assert.equal(Model.alertsUrl("", "https://grafana.example/"), "https://grafana.example/alerting/list?search=state:firing");
  assert.equal(Model.alertsUrl("", ""), "");
});

test("the bar shows the logo unless something needs attention", () => {
  assert.equal(Model.showsLogo("ok"), true);
  assert.equal(Model.showsLogo("stale"), true);
  assert.equal(Model.showsLogo("pending"), true);
  assert.equal(Model.showsLogo("warn"), false);
  assert.equal(Model.showsLogo("down"), false);
  assert.equal(Model.fromBatch(full(), NOW, null).glyph, "");
  const h = health({ activeAlerts: [{ name: "X", severity: "warning" }] });
  assert.equal(Model.fromBatch(full(summary({ activeAlerts: 1 }), h), NOW, null).glyph, "\uf071");
});

test("the logo keeps the brand ramp on light bars and lightens it on dark ones", () => {
  assert.equal(Model.logoColors(1, 1, 1)[3], "#1E40AF");
  assert.equal(Model.logoColors(0x1a / 255, 0x1b / 255, 0x26 / 255)[3], "#3B82F6");
});

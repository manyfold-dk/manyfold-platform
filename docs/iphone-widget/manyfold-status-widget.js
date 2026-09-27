// Manyfold Platform Status Widget for Scriptable
//
// Installation:
// 1. Install "Scriptable" app from the App Store
// 2. Create a new script and paste this code
// 3. Add a Scriptable widget to your home screen
// 4. Select this script for the widget
//
// Configuration:
const CONFIG = {
    // Layered health API endpoint
    apiUrl: "https://manyfold.dk/api/v1/health",

    // Fallback: Check if site is reachable (used if API fails)
    fallbackUrl: "https://manyfold.dk",

    // Widget refresh interval (iOS manages this, but we cache data)
    cacheMinutes: 5
};

// Color palette
const COLORS = {
    background: new Color("#0a0a0a"),
    backgroundSecondary: new Color("#1a1a1a"),
    textPrimary: new Color("#f0f0f0"),
    textSecondary: new Color("#888888"),
    textMuted: new Color("#555555"),
    green: new Color("#00ff88"),
    yellow: new Color("#ffcc00"),
    red: new Color("#ff3366"),
    border: new Color("#2a2a2a")
};

// Status configuration
const STATUS_CONFIG = {
    operational: {
        color: COLORS.green,
        label: "OPERATIONAL"
    },
    degraded: {
        color: COLORS.yellow,
        label: "DEGRADED"
    },
    outage: {
        color: COLORS.red,
        label: "OUTAGE"
    }
};

// Map health API status to display status
function mapStatus(healthStatus) {
    if (healthStatus === "healthy") return "operational";
    if (healthStatus === "degraded") return "degraded";
    return "outage";
}

// Compute overall status excluding pipelines
function computeOverallStatus(data) {
    const layers = [
        data.infrastructure?.status,
        data.cluster?.status,
        data.platform?.status,
        data.applications?.status
    ];
    if (layers.some(s => s === "unhealthy")) return "outage";
    if (layers.some(s => s === "degraded")) return "degraded";
    return "operational";
}

// Transform health response to layer array
function buildLayers(data) {
    return [
        { name: "Infrastructure", status: mapStatus(data.infrastructure?.status), infoOnly: false },
        { name: "Cluster", status: mapStatus(data.cluster?.status), infoOnly: false },
        { name: "Platform", status: mapStatus(data.platform?.status), infoOnly: false },
        { name: "Pipelines", status: mapStatus(data.pipelines?.status), infoOnly: true },
        { name: "Applications", status: mapStatus(data.applications?.status), infoOnly: false }
    ];
}

// Main widget creation
async function createWidget() {
    const widget = new ListWidget();
    widget.backgroundColor = COLORS.background;
    widget.setPadding(16, 16, 16, 16);

    // Fetch status
    const statusData = await fetchStatus();

    // Header
    const headerStack = widget.addStack();
    headerStack.layoutHorizontally();
    headerStack.centerAlignContent();

    // Title
    const titleStack = headerStack.addStack();
    titleStack.layoutVertically();

    const brandText = titleStack.addText("MANYFOLD");
    brandText.font = Font.boldSystemFont(10);
    brandText.textColor = COLORS.textMuted;
    brandText.textOpacity = 0.8;

    const statusTitle = titleStack.addText("STATUS");
    statusTitle.font = Font.boldSystemFont(16);
    statusTitle.textColor = COLORS.textPrimary;

    headerStack.addSpacer();

    // Traffic light
    const lightStack = headerStack.addStack();
    lightStack.layoutVertically();
    lightStack.spacing = 4;
    lightStack.setPadding(8, 8, 8, 8);
    lightStack.backgroundColor = COLORS.backgroundSecondary;
    lightStack.cornerRadius = 4;

    const lights = [
        { color: COLORS.red, active: statusData.overall === "outage" },
        { color: COLORS.yellow, active: statusData.overall === "degraded" },
        { color: COLORS.green, active: statusData.overall === "operational" }
    ];

    for (const light of lights) {
        const lightCircle = lightStack.addStack();
        lightCircle.size = new Size(14, 14);
        lightCircle.cornerRadius = 7;
        lightCircle.backgroundColor = light.active ? light.color : COLORS.backgroundSecondary;
        if (!light.active) {
            lightCircle.borderWidth = 1;
            lightCircle.borderColor = COLORS.border;
        }
    }

    widget.addSpacer(12);

    // Divider
    const divider = widget.addStack();
    divider.size = new Size(0, 1);
    divider.backgroundColor = COLORS.border;

    widget.addSpacer(12);

    // Status message
    const statusConfig = STATUS_CONFIG[statusData.overall] || STATUS_CONFIG.outage;

    const statusStack = widget.addStack();
    statusStack.layoutHorizontally();
    statusStack.centerAlignContent();

    // Status indicator dot
    const indicatorDot = statusStack.addStack();
    indicatorDot.size = new Size(8, 8);
    indicatorDot.cornerRadius = 4;
    indicatorDot.backgroundColor = statusConfig.color;

    statusStack.addSpacer(8);

    const statusLabel = statusStack.addText(statusConfig.label);
    statusLabel.font = Font.boldMonospacedSystemFont(12);
    statusLabel.textColor = statusConfig.color;

    widget.addSpacer(8);

    // Small widget: show 4 core layer dots (no pipelines)
    if (config.widgetFamily === "small") {
        const dotsStack = widget.addStack();
        dotsStack.layoutHorizontally();
        dotsStack.spacing = 6;
        dotsStack.centerAlignContent();

        const coreLayers = statusData.layers.filter(l => !l.infoOnly);
        for (const layer of coreLayers) {
            const layerConfig = STATUS_CONFIG[layer.status] || STATUS_CONFIG.outage;
            const dot = dotsStack.addStack();
            dot.size = new Size(8, 8);
            dot.cornerRadius = 4;
            dot.backgroundColor = layerConfig.color;
        }
    }

    // Medium/large widget: show all 5 layers
    if (config.widgetFamily !== "small" && statusData.layers) {
        const layersStack = widget.addStack();
        layersStack.layoutVertically();
        layersStack.spacing = 4;

        for (const layer of statusData.layers) {
            const layerRow = layersStack.addStack();
            layerRow.layoutHorizontally();

            const layerConfig = STATUS_CONFIG[layer.status] || STATUS_CONFIG.outage;

            const layerDot = layerRow.addStack();
            layerDot.size = new Size(6, 6);
            layerDot.cornerRadius = 3;
            layerDot.backgroundColor = layerConfig.color;

            layerRow.addSpacer(6);

            const displayName = layer.infoOnly ? `${layer.name} (info)` : layer.name;
            const layerName = layerRow.addText(displayName);
            layerName.font = Font.regularMonospacedSystemFont(10);
            layerName.textColor = layer.infoOnly ? COLORS.textMuted : COLORS.textSecondary;
            layerName.lineLimit = 1;

            layerRow.addSpacer();
        }
    }

    widget.addSpacer();

    // Footer with timestamp
    const footerStack = widget.addStack();
    footerStack.layoutHorizontally();

    const updateTime = new Date(statusData.timestamp || Date.now());
    const timeStr = updateTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    const timeText = footerStack.addText(`Updated ${timeStr}`);
    timeText.font = Font.regularSystemFont(9);
    timeText.textColor = COLORS.textMuted;

    // Set widget URL to open status page
    widget.url = "https://manyfold.dk/status.html";

    return widget;
}

// Fetch status from API
async function fetchStatus() {
    try {
        const req = new Request(CONFIG.apiUrl);
        req.timeoutInterval = 10;
        const data = await req.loadJSON();

        const result = {
            overall: computeOverallStatus(data),
            layers: buildLayers(data),
            timestamp: data.timestamp
        };

        // Cache the successful response
        Keychain.set("manyfold_status_cache", JSON.stringify(result));
        Keychain.set("manyfold_status_cache_time", Date.now().toString());

        return result;
    } catch (error) {
        console.error("API fetch failed:", error);

        // Try to use cached data
        try {
            const cached = Keychain.get("manyfold_status_cache");
            const cacheTime = parseInt(Keychain.get("manyfold_status_cache_time") || "0");
            const cacheAge = (Date.now() - cacheTime) / 1000 / 60; // minutes

            if (cached && cacheAge < 60) { // Use cache if less than 60 mins old
                console.log("Using cached data");
                return JSON.parse(cached);
            }
        } catch (cacheError) {
            console.error("Cache read failed:", cacheError);
        }

        // Fallback: try simple connectivity check
        return await fallbackCheck();
    }
}

// Fallback connectivity check
async function fallbackCheck() {
    try {
        const req = new Request(CONFIG.fallbackUrl);
        req.timeoutInterval = 10;
        await req.load();

        return {
            overall: "operational",
            layers: [
                { name: "Infrastructure", status: "operational", infoOnly: false },
                { name: "Cluster", status: "operational", infoOnly: false },
                { name: "Platform", status: "operational", infoOnly: false },
                { name: "Pipelines", status: "operational", infoOnly: true },
                { name: "Applications", status: "operational", infoOnly: false }
            ],
            timestamp: new Date().toISOString()
        };
    } catch (error) {
        return {
            overall: "outage",
            layers: [
                { name: "Infrastructure", status: "outage", infoOnly: false },
                { name: "Cluster", status: "outage", infoOnly: false },
                { name: "Platform", status: "outage", infoOnly: false },
                { name: "Pipelines", status: "outage", infoOnly: true },
                { name: "Applications", status: "outage", infoOnly: false }
            ],
            timestamp: new Date().toISOString()
        };
    }
}

// Run widget
const widget = await createWidget();

if (config.runsInWidget) {
    Script.setWidget(widget);
} else {
    // Preview in app
    switch (config.widgetFamily) {
        case "medium":
            widget.presentMedium();
            break;
        case "large":
            widget.presentLarge();
            break;
        default:
            widget.presentSmall();
    }
}

Script.complete();

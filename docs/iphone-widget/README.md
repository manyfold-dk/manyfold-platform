# Manyfold Platform iPhone Status Widget

A traffic light status widget for iOS showing the health of manyfold.dk services.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Widget Sizes](#widget-sizes)
- [How It Works](#how-it-works)
- [Customization](#customization)
- [Troubleshooting](#troubleshooting)

## Prerequisites

- iPhone running iOS 14 or later
- [Scriptable](https://apps.apple.com/app/scriptable/id1405459188) app (free)

## Installation

### Step 1: Install Scriptable

Download **Scriptable** from the App Store. It's a free app that lets you run JavaScript widgets on iOS.

### Step 2: Add the Script

1. Open Scriptable
2. Tap the **+** button to create a new script
3. Copy the contents of `manyfold-status-widget.js` and paste it
4. Tap the script name at the top and rename it to "Manyfold Status"
5. Tap **Done**

### Step 3: Test the Script

1. Tap the **Play** button to run the script
2. You should see a preview of the widget

### Step 4: Add Widget to Home Screen

1. Go to your iPhone home screen
2. Long press to enter jiggle mode
3. Tap the **+** button (top left)
4. Search for "Scriptable"
5. Choose your preferred widget size
6. Tap **Add Widget**
7. Long press the widget and tap **Edit Widget**
8. Under "Script", select "Manyfold Status"
9. Tap outside to save

## Widget Sizes

| Size | Display |
|------|---------|
| **Small** | Traffic light + overall status |
| **Medium** | Traffic light + status + service list |
| **Large** | Full details with latency metrics |

## How It Works

```
┌─────────────────────────────────────┐
│  iPhone Widget (Scriptable)         │
│           │                         │
│           ▼                         │
│  https://manyfold.dk/api/v1/status  │
│           │                         │
│           ▼                         │
│  Backend Status Endpoint            │
│  - Checks /health/live              │
│  - Checks frontend connectivity     │
│  - Returns aggregated status        │
└─────────────────────────────────────┘

Status Mapping:
  🟢 Operational  → All services healthy
  🟡 Degraded     → Some services impaired
  🔴 Outage       → Critical services down
```

## Customization

Edit the `CONFIG` object at the top of the script:

```javascript
const CONFIG = {
    // Your status API URL
    apiUrl: "https://manyfold.dk/api/v1/status",

    // Fallback URL for basic connectivity check
    fallbackUrl: "https://manyfold.dk",

    // Cache duration in minutes
    cacheMinutes: 5
};
```

### Changing Colors

Modify the `COLORS` object to match your preferences:

```javascript
const COLORS = {
    background: new Color("#0a0a0a"),
    green: new Color("#00ff88"),
    yellow: new Color("#ffcc00"),
    red: new Color("#ff3366"),
    // ...
};
```

## Troubleshooting

### Widget Shows "Unable to Load"

1. Open Scriptable and run the script manually to check for errors
2. Verify the API URL is accessible from your phone
3. Check if the backend is deployed and running

### Widget Doesn't Update

iOS limits widget refresh rates. The widget typically updates:
- Every 5-15 minutes in the background
- When you tap on the widget
- When Scriptable is opened

### API Connection Errors

The widget includes fallback logic:
1. First, tries the status API
2. If that fails, uses cached data (up to 60 minutes old)
3. If no cache, performs a basic connectivity check to the website

## API Response Format

The status endpoint returns:

```json
{
  "status": "operational",
  "services": [
    {
      "name": "Backend API",
      "status": "operational",
      "latencyMs": 45
    },
    {
      "name": "Frontend",
      "status": "operational",
      "latencyMs": 120
    }
  ],
  "timestamp": "2024-01-24T21:30:00.000Z"
}
```

## Related

- [Status Page](https://manyfold.dk/status.html) - Web version of the status page
- Grafana dashboards at `https://grafana.<domain>`, where `<domain>` is the installation's domain - Detailed monitoring
- [Platform Health Dashboard](../../platform/observability/grafana/platform-health-dashboard.yaml)

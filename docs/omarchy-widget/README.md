# Manyfold Platform Omarchy Bar Widget

A traffic-light status icon for the [Omarchy](https://omarchy.org/) bar that shows the health of
the Manyfold platform. It reads the same layered health API as the
[iPhone widget](../iphone-widget/README.md).

## Table of Contents

- [What It Shows](#what-it-shows)
- [Installation](#installation)
- [Settings](#settings)
- [Development](#development)
- [Removal](#removal)

## What It Shows

One icon in the bar. Click it for a panel laid out like the website's `/status` view:

| Panel section | Content |
|---------------|---------|
| Headline | The overall verdict, the update time, and the number of alerts that need attention |
| Platform services | Edge, identity and secrets, delivery, observability, each with its live detail |
| The stack | Tenants, network, cluster, infrastructure, shaded like the four layers in the logo |
| Firing alerts | Each warning or critical alert, when any fire |
| Checks | Every check with its latency; the synthetic checks add a 14-day uptime strip and a 24-hour response-time line |

| Bar icon | Meaning |
|----------|---------|
| Manyfold logo, brand blues | Every row healthy, no alert firing |
| Yellow warning triangle | A row is `degraded`, or a warning alert fires |
| Warning triangle in the bar's urgent colour | A row is `unhealthy`, or a critical alert fires |
| Manyfold logo in the bar's urgent colour | The health API could not be read two checks in a row |
| Dimmed logo | First check not finished yet |

On a dark bar the logo uses the brand ramp one step lighter, because the deepest brand blue is
nearly invisible there.

A row that reads `not measured` (the API says `unknown`) does not change the colour: nothing
measured it, which is a gap in the monitoring rather than a fault. Pipelines do not change the
colour either, as on the website and the iPhone widget.

| Mouse | Action |
|-------|--------|
| Left click | Open or close the panel |
| Middle click | Check now |
| Right click | Open the status page in the browser |

The panel has buttons to check now, open the status page, open the firing alerts in Grafana, and
open Grafana. The alert count under the headline and each row under "Firing alerts" also open the
firing alerts. The Grafana links appear once `dashboardUrl` is set.

The widget reads four endpoints under `apiBaseUrl` with one `curl` run: `health/summary` (required),
`health`, `status` and `status/history`. It checks every 60 seconds, and every 30 seconds while the
panel is open. `omarchy-shell manyfold.platform-status refresh` checks now, and
`omarchy-shell manyfold.platform-status toggle` opens the panel, for a keybinding.

## Installation

The plugin runs from this checkout through a symlink, so a `git pull` updates it. `omarchy plugin
add` cannot install it, because that command needs `manifest.json` at the root of a repository.

```bash
ln -sfn "$PWD/docs/omarchy-widget" ~/.config/omarchy/plugins/manyfold.platform-status
omarchy-shell shell rescanPlugins
omarchy plugin enable manyfold.platform-status --after omarchy.agents
omarchy bar set manyfold.platform-status dashboardUrl https://grafana.<domain>
```

## Settings

The defaults are in `manifest.json`. Override a value on the bar entry:

```bash
omarchy bar set manyfold.platform-status refreshIntervalSec 30
```

| Key | Default |
|-----|---------|
| `apiBaseUrl` | `https://manyfold.dk/api/v1` |
| `statusPageUrl` | `https://manyfold.dk/status` |
| `dashboardUrl` | empty: no Grafana links |
| `alertsUrl` | empty: `<dashboardUrl>/alerting/list?search=state:firing`, Grafana's rule list filtered to firing rules |
| `refreshIntervalSec` | `60` (minimum 15) |

## Development

| File | Purpose |
|------|---------|
| `manifest.json` | Omarchy plugin manifest |
| `StatusWidget.qml` | Bar widget and panel: polling, colour, clicks, layout |
| `LogoMark.qml` | The Manyfold mark, drawn from the website's geometry in any colours |
| `StatusRow.qml`, `StatusChip.qml` | A service or stack row, and its status chip |
| `UptimeStrip.qml`, `Sparkline.qml` | The uptime buckets and the response-time line of a check |
| `Model.js` | Pure evaluation of the API responses into the icon level and the panel content |
| `Model.test.mjs` | Node tests for `Model.js` |

```bash
(cd docs/omarchy-widget && node --test Model.test.mjs)
omarchy plugin validate docs/omarchy-widget
```

The shell does not watch files behind the symlink, and `omarchy-shell shell rescanPlugins` does not
reload a changed widget. After an edit, run `omarchy restart shell`. Shell errors appear in `journalctl --user --since -5min | grep omarchy-shell`.

## Removal

```bash
omarchy plugin disable manyfold.platform-status
rm ~/.config/omarchy/plugins/manyfold.platform-status
```

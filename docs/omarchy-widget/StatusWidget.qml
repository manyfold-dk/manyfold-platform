import QtQuick
import Quickshell
import Quickshell.Io
import qs.Commons
import qs.Ui
import "Model.js" as Model

// Manyfold platform health as one bar icon with a popup laid out like the
// website's /status view. The icon keeps the bar's text colour while all is
// well, turns the theme's yellow for a degraded row or a warning alert, and
// the bar's urgent colour for an outage, a critical alert, or a health API
// that cannot be read twice in a row. Left click opens the panel, middle
// click checks now, right click opens the status page in the browser.
BarWidget {
  id: root
  moduleName: "manyfold.platform-status"

  readonly property string apiBaseUrl: String(setting("apiBaseUrl", "https://manyfold.dk/api/v1")).replace(/\/+$/, "")
  readonly property string statusPageUrl: String(setting("statusPageUrl", "https://manyfold.dk/status"))
  readonly property string dashboardUrl: String(setting("dashboardUrl", ""))
  readonly property string alertsUrl: Model.alertsUrl(String(setting("alertsUrl", "")), dashboardUrl)
  readonly property int refreshIntervalSec: Math.max(15, Number(setting("refreshIntervalSec", 60)) || 60)

  property var report: ({
    level: "pending", glyph: "", headline: "Checking...", headlineTone: "unknown",
    meta: "", alertLabel: "", stale: false, error: "",
    services: [], stack: [], alerts: [], checks: [], plainChecks: [], chartChecks: [],
    tooltip: "Manyfold: checking..."
  })
  property int failures: 0
  property var lastGood: null
  property bool popupOpen: false

  // Theme colours. The shell's Color singleton has no green or yellow, so
  // they come from the theme's colors.toml.
  property color okColor: "#9ece6a"
  property color warnColor: "#e0af68"
  readonly property color downColor: root.bar ? root.bar.urgent : Color.urgent
  readonly property color panelText: Color.popups.text
  readonly property color dimText: Qt.rgba(panelText.r, panelText.g, panelText.b, 0.6)
  readonly property color emptyColor: Qt.rgba(panelText.r, panelText.g, panelText.b, 0.14)
  readonly property string fontFamily: root.bar ? root.bar.fontFamily : Style.font.family

  // The four stack layers, lightest at the top like the logo.
  readonly property var stackStrength: [0.08, 0.16, 0.26, 0.38]

  function toneColor(t) {
    if (t === "ok") return okColor
    if (t === "warn") return warnColor
    if (t === "down") return downColor
    return dimText
  }

  // The brand ramp, lightened on a dark bar; urgent when the API cannot be
  // read. A transparent bar shows the wallpaper, so it counts as dark.
  function logoColorsOn(surface) {
    var bg = surface && surface.a > 0.3 ? surface : Color.background
    return Model.logoColors(bg.r, bg.g, bg.b)
  }
  readonly property var barLogoColors: report.level === "stale"
    ? [downColor]
    : logoColorsOn(root.bar ? root.bar.background : Color.bar.background)

  readonly property color iconColor: {
    if (report.level === "warn") return warnColor
    if (report.level === "down" || report.level === "stale") return downColor
    return root.bar ? root.bar.barForeground : Color.foreground
  }

  // Popout contract the bar uses to route summon/close and to hand over when
  // another bar popup opens.
  readonly property bool opened: popupOpen
  function open() { popupOpen = true; refresh() }
  function close() { popupOpen = false }
  function toggle() { popupOpen ? close() : open() }
  function closeForPopoutSwitch() { close() }

  function refresh() {
    if (!fetchProc.running) fetchProc.running = true
  }

  function accept(output) {
    var now = new Date()
    var next = Model.fromBatch(output, now, root.lastGood)
    if (next.level !== "stale") {
      failures = 0
      lastGood = now
      report = next
      return
    }
    failures++
    // One dropped request (a wake from suspend, a Wi-Fi hop) is not an outage.
    if (failures >= 2 || report.level === "pending") report = next
  }

  // An argv vector, not a shell string: the restricted bar API third-party
  // plugins receive has no shellQuote, and a URL never needs a shell anyway.
  function openUrl(url) {
    if (!url) return
    Util.execArgv(["omarchy-launch-browser", url])
    close()
  }

  implicitWidth: button.implicitWidth
  implicitHeight: button.implicitHeight

  // omarchy-shell manyfold.platform-status refresh | toggle
  IpcHandler {
    target: "manyfold.platform-status"

    function refresh(): void { root.broadcast("refresh") }
    function toggle(): void { root.toggle() }
  }

  Process {
    id: fetchProc
    command: ["curl", "-sS", "--max-time", "10", "-w", "\n@@mf %{http_code}\n"]
      .concat(Model.ENDPOINTS.map(function(path) { return root.apiBaseUrl + "/" + path }))
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: root.accept(text)
    }
  }

  // Checked every 30 seconds while the panel is open, as the website does.
  Timer {
    interval: (root.popupOpen ? 30 : root.refreshIntervalSec) * 1000
    running: true
    repeat: true
    triggeredOnStart: true
    onTriggered: root.refresh()
  }

  FileView {
    id: themeColors
    path: Color.currentThemePath + "/colors.toml"
    watchChanges: true
    printErrors: false
    onFileChanged: reload()
    onLoaded: {
      root.okColor = Model.themeColor(text(), "green", "color2", root.okColor)
      root.warnColor = Model.themeColor(text(), "yellow", "color3", root.warnColor)
    }
  }

  // A theme switch replaces the directory behind currentThemePath, which the
  // file watch may miss; the shell's own colours changing is the reliable cue.
  Connections {
    target: Color
    function onUrgentChanged() { themeColors.reload() }
  }

  BarIconButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: root.report.glyph
    iconComponent: Model.showsLogo(root.report.level) ? barLogo : null
    foreground: root.iconColor
    dimmed: root.report.level === "pending"
    slotSize: Style.bar.statusSlot
    tooltipText: root.popupOpen ? "" : root.report.tooltip

    onPressed: function(b) {
      if (b === Qt.RightButton) root.openUrl(root.statusPageUrl)
      else if (b === Qt.MiddleButton) root.refresh()
      else root.toggle()
    }
  }

  Component {
    id: barLogo

    LogoMark {
      fillRatio: 0.8
      colors: root.barLogoColors
    }
  }

  PopupCard {
    id: popup
    anchorItem: button
    bar: root.bar
    owner: root
    open: root.popupOpen
    contentWidth: popup.fittedContentWidth(Style.space(440))
    contentHeight: popup.fittedContentHeight(content.implicitHeight)

    Flickable {
      anchors.fill: parent
      contentWidth: width
      contentHeight: content.implicitHeight
      clip: true
      boundsBehavior: Flickable.StopAtBounds

      Column {
        id: content
        width: parent.width
        spacing: Style.space(6)

        // ---------- Headline ----------
        Item {
          width: parent.width
          implicitHeight: Math.max(headline.implicitHeight, actions.implicitHeight)

          Column {
            id: headline
            anchors.left: parent.left
            anchors.right: actions.left
            anchors.rightMargin: Style.space(8)
            spacing: Style.space(2)

            Row {
              spacing: Style.space(6)

              LogoMark {
                anchors.verticalCenter: parent.verticalCenter
                size: Style.font.caption
                colors: root.logoColorsOn(Color.popups.background)
              }

              Text {
                textFormat: Text.PlainText
                text: "MANYFOLD STATUS"
                color: root.dimText
                font.family: root.fontFamily
                font.pixelSize: Style.font.caption
                font.bold: true
                font.letterSpacing: 1.2
              }
            }

            Text {
              width: parent.width
              textFormat: Text.PlainText
              text: root.report.headline
              color: root.toneColor(root.report.headlineTone)
              font.family: root.fontFamily
              font.pixelSize: Style.font.heading
              font.bold: true
              elide: Text.ElideRight
            }

            Row {
              spacing: Style.space(6)

              Text {
                textFormat: Text.PlainText
                text: root.report.meta.replace(/ · .*$/, "")
                color: root.dimText
                font.family: root.fontFamily
                font.pixelSize: Style.font.caption
              }

              Text {
                visible: root.report.alertLabel !== ""
                textFormat: Text.PlainText
                text: "· " + root.report.alertLabel
                color: root.warnColor
                font.family: root.fontFamily
                font.pixelSize: Style.font.caption
                font.bold: true
                font.underline: root.alertsUrl !== "" && alertLabelMouse.containsMouse

                MouseArea {
                  id: alertLabelMouse
                  anchors.fill: parent
                  enabled: root.alertsUrl !== ""
                  hoverEnabled: true
                  cursorShape: Qt.PointingHandCursor
                  onClicked: root.openUrl(root.alertsUrl)
                }
              }

              Text {
                visible: root.report.stale
                textFormat: Text.PlainText
                text: "· stale data"
                color: root.warnColor
                font.family: root.fontFamily
                font.pixelSize: Style.font.caption
                font.bold: true
              }
            }
          }

          Row {
            id: actions
            anchors.right: parent.right
            anchors.top: parent.top
            spacing: Style.space(2)

            PanelActionButton {
              iconText: ""
              tooltipText: fetchProc.running ? "Checking..." : "Check now"
              foreground: root.panelText
              fontFamily: root.fontFamily
              enabled: !fetchProc.running
              onClicked: root.refresh()
            }

            PanelActionButton {
              iconText: ""
              tooltipText: "Open the status page"
              foreground: root.panelText
              fontFamily: root.fontFamily
              onClicked: root.openUrl(root.statusPageUrl)
            }

            PanelActionButton {
              visible: root.alertsUrl !== ""
              iconText: "\uf0f3"
              tooltipText: "Firing alerts in Grafana"
              foreground: root.panelText
              fontFamily: root.fontFamily
              onClicked: root.openUrl(root.alertsUrl)
            }

            PanelActionButton {
              visible: root.dashboardUrl !== ""
              iconText: ""
              tooltipText: "Open Grafana"
              foreground: root.panelText
              fontFamily: root.fontFamily
              onClicked: root.openUrl(root.dashboardUrl)
            }
          }
        }

        Rectangle {
          visible: root.report.error !== ""
          width: parent.width
          implicitHeight: errorText.implicitHeight + Style.space(16)
          radius: Style.cornerRadius
          color: "transparent"
          border.width: 1
          border.color: Qt.rgba(root.downColor.r, root.downColor.g, root.downColor.b, 0.5)

          Text {
            id: errorText
            anchors.fill: parent
            anchors.margins: Style.space(8)
            textFormat: Text.PlainText
            text: root.report.error
            wrapMode: Text.Wrap
            color: root.panelText
            font.family: root.fontFamily
            font.pixelSize: Style.font.bodySmall
          }
        }

        // ---------- Platform services ----------
        Item { width: 1; height: Style.space(2); visible: root.report.services.length > 0 }

        PanelSectionHeader {
          visible: root.report.services.length > 0
          text: "PLATFORM SERVICES · RUN ON THE STACK"
          foreground: root.panelText
          fontFamily: root.fontFamily
        }

        Repeater {
          model: root.report.services

          StatusRow {
            required property var modelData
            width: content.width
            name: modelData.name
            detail: modelData.detail
            statusText: modelData.label
            toneColor: root.toneColor(modelData.tone)
            foreground: root.panelText
            fontFamily: root.fontFamily
          }
        }

        // ---------- The stack ----------
        Item { width: 1; height: Style.space(2); visible: root.report.stack.length > 0 }

        PanelSectionHeader {
          visible: root.report.stack.length > 0
          text: "THE STACK · THE FOUR LAYERS IN THE LOGO"
          foreground: root.panelText
          fontFamily: root.fontFamily
        }

        Repeater {
          model: root.report.stack

          StatusRow {
            required property var modelData
            required property int index
            width: content.width
            name: modelData.name
            detail: modelData.detail
            statusText: modelData.label
            toneColor: root.toneColor(modelData.tone)
            foreground: root.panelText
            fontFamily: root.fontFamily
            fill: Qt.rgba(Color.accent.r, Color.accent.g, Color.accent.b, root.stackStrength[Math.min(index, 3)])
          }
        }

        // ---------- Alerts ----------
        Item { width: 1; height: Style.space(2); visible: root.report.alerts.length > 0 }

        PanelSectionHeader {
          visible: root.report.alerts.length > 0
          text: "FIRING ALERTS"
          foreground: root.panelText
          fontFamily: root.fontFamily
        }

        Repeater {
          model: root.report.alerts

          StatusRow {
            required property var modelData
            width: content.width
            name: modelData.name
            detail: modelData.message
            statusText: modelData.severity
            toneColor: root.toneColor(modelData.tone)
            foreground: root.panelText
            fontFamily: root.fontFamily
            clickable: root.alertsUrl !== ""
            onClicked: root.openUrl(root.alertsUrl)
          }
        }

        // ---------- Checks ----------
        Item { width: 1; height: Style.space(2); visible: root.report.checks.length > 0 }

        PanelSectionHeader {
          visible: root.report.checks.length > 0
          text: "CHECKS · WHAT ANSWERED, AND HOW FAST"
          foreground: root.panelText
          fontFamily: root.fontFamily
        }

        Grid {
          visible: root.report.plainChecks.length > 0
          width: content.width
          columns: 2
          columnSpacing: Style.space(6)
          rowSpacing: Style.space(6)

          Repeater {
            model: root.report.plainChecks

            Rectangle {
              id: plainCheck
              required property var modelData

              width: (content.width - Style.space(6)) / 2
              implicitHeight: plainColumn.implicitHeight + Style.space(10)
              radius: Style.cornerRadius
              color: "transparent"
              border.width: 1
              border.color: Qt.rgba(root.panelText.r, root.panelText.g, root.panelText.b, 0.1)

              Column {
                id: plainColumn
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                anchors.leftMargin: Style.space(10)
                anchors.rightMargin: Style.space(8)
                spacing: Style.space(3)

                // Half a panel is too narrow for a name and a chip side by
                // side, so the chip leads the second line.
                Text {
                  width: parent.width
                  textFormat: Text.PlainText
                  text: plainCheck.modelData.name
                  color: root.panelText
                  font.family: root.fontFamily
                  font.pixelSize: Style.font.body
                  font.bold: true
                  elide: Text.ElideRight
                }

                Item {
                  width: parent.width
                  implicitHeight: plainChip.implicitHeight

                  StatusChip {
                    id: plainChip
                    anchors.left: parent.left
                    anchors.verticalCenter: parent.verticalCenter
                    text: plainCheck.modelData.label
                    toneColor: root.toneColor(plainCheck.modelData.tone)
                    foreground: root.panelText
                    fontFamily: root.fontFamily
                  }

                  Text {
                    anchors.left: plainChip.right
                    anchors.leftMargin: Style.space(6)
                    anchors.verticalCenter: parent.verticalCenter
                    visible: plainCheck.modelData.latency !== ""
                    textFormat: Text.PlainText
                    text: plainCheck.modelData.latency
                    color: root.panelText
                    font.family: root.fontFamily
                    font.pixelSize: Style.font.caption
                    font.bold: true
                  }
                }

                Text {
                  visible: text !== ""
                  width: parent.width
                  textFormat: Text.PlainText
                  text: plainCheck.modelData.detail
                  color: root.dimText
                  font.family: root.fontFamily
                  font.pixelSize: Style.font.caption
                  elide: Text.ElideRight
                }
              }
            }
          }
        }

        Repeater {
          model: root.report.chartChecks

          Rectangle {
            id: check
            required property var modelData
            readonly property bool hasHistory: !!modelData.uptime || !!modelData.spark

            width: content.width
            implicitHeight: checkColumn.implicitHeight + Style.space(10)
            radius: Style.cornerRadius
            color: "transparent"
            border.width: 1
            border.color: Qt.rgba(root.panelText.r, root.panelText.g, root.panelText.b, 0.1)

            Column {
              id: checkColumn
              anchors.left: parent.left
              anchors.right: parent.right
              anchors.verticalCenter: parent.verticalCenter
              anchors.leftMargin: Style.space(10)
              anchors.rightMargin: Style.space(8)
              spacing: Style.space(4)

              Item {
                width: parent.width
                implicitHeight: Math.max(checkName.implicitHeight, checkChip.implicitHeight)

                Text {
                  id: checkName
                  anchors.left: parent.left
                  anchors.verticalCenter: parent.verticalCenter
                  textFormat: Text.PlainText
                  text: check.modelData.name
                  color: root.panelText
                  font.family: root.fontFamily
                  font.pixelSize: Style.font.body
                  font.bold: true
                }

                Text {
                  anchors.right: checkChip.left
                  anchors.rightMargin: Style.space(8)
                  anchors.verticalCenter: parent.verticalCenter
                  visible: check.modelData.latency !== ""
                  textFormat: Text.PlainText
                  text: check.modelData.latency
                  color: root.panelText
                  font.family: root.fontFamily
                  font.pixelSize: Style.font.caption
                  font.bold: true
                }

                StatusChip {
                  id: checkChip
                  anchors.right: parent.right
                  anchors.verticalCenter: parent.verticalCenter
                  text: check.modelData.label
                  toneColor: root.toneColor(check.modelData.tone)
                  foreground: root.panelText
                  fontFamily: root.fontFamily
                }
              }

              Text {
                visible: text !== ""
                width: parent.width
                textFormat: Text.PlainText
                text: check.modelData.detail
                color: root.dimText
                font.family: root.fontFamily
                font.pixelSize: Style.font.caption
                elide: Text.ElideRight
              }

              Row {
                visible: check.hasHistory
                width: parent.width
                spacing: Style.space(12)

                Column {
                  width: (parent.width - parent.spacing) / 2
                  spacing: Style.space(3)
                  visible: !!check.modelData.uptime

                  UptimeStrip {
                    width: parent.width
                    buckets: check.modelData.uptime ? check.modelData.uptime.buckets : []
                    okColor: root.okColor
                    warnColor: root.warnColor
                    downColor: root.downColor
                    emptyColor: root.emptyColor
                  }

                  Text {
                    textFormat: Text.PlainText
                    text: check.modelData.uptime
                      ? check.modelData.uptime.days + " days · " + check.modelData.uptime.summary : ""
                    color: root.dimText
                    font.family: root.fontFamily
                    font.pixelSize: Style.font.caption
                  }
                }

                Column {
                  width: (parent.width - parent.spacing) / 2
                  spacing: Style.space(3)
                  visible: !!check.modelData.spark

                  Sparkline {
                    width: parent.width
                    height: Style.space(12)
                    points: check.modelData.spark ? check.modelData.spark.points : []
                    lineColor: Color.accent
                  }

                  Text {
                    textFormat: Text.PlainText
                    text: check.modelData.spark
                      ? check.modelData.spark.hours + " hours · peak " + check.modelData.spark.peak + " ms" : ""
                    color: root.dimText
                    font.family: root.fontFamily
                    font.pixelSize: Style.font.caption
                  }
                }
              }
            }
          }
        }

        Text {
          visible: root.report.services.length > 0
          width: parent.width
          topPadding: Style.space(4)
          textFormat: Text.PlainText
          wrapMode: Text.Wrap
          text: "Edge is probed from outside; the rest from inside the cluster. \"Not measured\" is a gap in the monitoring, not a fault."
          color: root.dimText
          font.family: root.fontFamily
          font.pixelSize: Style.font.caption
        }
      }
    }
  }
}

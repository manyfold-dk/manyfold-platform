import QtQuick
import qs.Commons

// One segment per bucket, with the website's thresholds. A bucket with no
// reading is grey, never red: nothing was measured then, which is a gap in
// the monitoring rather than an outage.
Item {
  id: root

  property var buckets: []
  property color okColor: "green"
  property color warnColor: "yellow"
  property color downColor: "red"
  property color emptyColor: "grey"
  property real gap: Style.space(2)

  readonly property int count: buckets ? buckets.length : 0
  readonly property real segmentWidth: count > 0 ? Math.max(1, (width - gap * (count - 1)) / count) : 0

  function colorOf(value) {
    if (value === null || value === undefined) return emptyColor
    if (value >= 0.999) return okColor
    if (value >= 0.95) return warnColor
    return downColor
  }

  implicitHeight: Style.space(12)

  Row {
    anchors.fill: parent
    spacing: root.gap

    Repeater {
      model: root.buckets

      Rectangle {
        required property var modelData
        width: root.segmentWidth
        height: root.height
        radius: Math.min(2, width / 2)
        color: root.colorOf(modelData)
      }
    }
  }
}

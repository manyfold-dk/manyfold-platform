import QtQuick

// The Manyfold mark: the four layers of the platform stack (tenants,
// network, cluster, infrastructure), light to deep. Drawn from the website's
// 40x40 geometry rather than loaded as an image, so it takes any colours.
Item {
  id: root

  property real size: 16
  // Share of the box the mark fills. Glyph icons leave optical margins; a
  // mark that fills its box edge to edge reads larger than its neighbours.
  property real fillRatio: 1
  // Top to bottom. Four colours, or one repeated for a single-tone mark.
  property var colors: ["#93C5FD", "#60A5FA", "#2563EB", "#1E40AF"]

  // Drawn square and centred in whatever box a parent gives it.
  readonly property real drawn: Math.min(width, height) * fillRatio
  readonly property real unit: drawn / 40
  readonly property real offsetX: (width - drawn) / 2
  readonly property real offsetY: (height - drawn) / 2

  implicitWidth: size
  implicitHeight: size

  Repeater {
    model: 4

    Rectangle {
      required property int index
      x: root.offsetX
      y: root.offsetY + index * 11 * root.unit
      width: root.drawn
      height: 7 * root.unit
      radius: 2 * root.unit
      color: root.colors[Math.min(index, root.colors.length - 1)]
      antialiasing: true
    }
  }
}

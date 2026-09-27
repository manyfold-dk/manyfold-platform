import QtQuick
import qs.Commons

// A response-time line. Gaps break the line rather than being interpolated
// across: a straight segment over missing data would invent readings that
// were never taken.
Canvas {
  id: root

  property var points: []
  property color lineColor: Color.accent

  implicitHeight: Style.space(22)

  onPointsChanged: requestPaint()
  onLineColorChanged: requestPaint()
  onWidthChanged: requestPaint()
  onHeightChanged: requestPaint()

  onPaint: {
    var ctx = getContext("2d")
    ctx.reset()
    var pts = points || []
    var min = Infinity, max = -Infinity
    for (var i = 0; i < pts.length; i++) {
      if (typeof pts[i] !== "number") continue
      min = Math.min(min, pts[i])
      max = Math.max(max, pts[i])
    }
    if (min === Infinity) return
    // A flat line sits in the middle rather than on an edge.
    if (max === min) { min -= 1; max += 1 }

    var pad = 2
    function x(index) { return index / Math.max(pts.length - 1, 1) * width }
    function y(value) { return height - pad - (value - min) / (max - min) * (height - pad * 2) }

    // Each run of consecutive readings becomes its own filled shape and line.
    var runs = []
    var run = []
    for (var j = 0; j < pts.length; j++) {
      if (typeof pts[j] !== "number") {
        if (run.length > 1) runs.push(run)
        run = []
        continue
      }
      run.push([x(j), y(pts[j])])
    }
    if (run.length > 1) runs.push(run)

    for (var r = 0; r < runs.length; r++) {
      var seg = runs[r]
      ctx.beginPath()
      ctx.moveTo(seg[0][0], height)
      for (var k = 0; k < seg.length; k++) ctx.lineTo(seg[k][0], seg[k][1])
      ctx.lineTo(seg[seg.length - 1][0], height)
      ctx.closePath()
      ctx.fillStyle = Qt.rgba(lineColor.r, lineColor.g, lineColor.b, 0.16)
      ctx.fill()

      ctx.beginPath()
      ctx.moveTo(seg[0][0], seg[0][1])
      for (var m = 1; m < seg.length; m++) ctx.lineTo(seg[m][0], seg[m][1])
      ctx.strokeStyle = lineColor
      ctx.lineWidth = 1.5
      ctx.lineJoin = "round"
      ctx.stroke()
    }
  }
}

import QtQuick
import qs.Commons

// A status is never carried by colour alone: the chip always spells the state
// out, as on the website's status page.
Rectangle {
  id: root

  property string text: ""
  property color toneColor: Color.muted
  property color foreground: Color.foreground
  property string fontFamily: Style.font.family

  implicitWidth: row.implicitWidth + Style.space(12)
  implicitHeight: row.implicitHeight + Style.space(4)
  radius: height / 2
  color: "transparent"
  border.width: 1
  border.color: Qt.rgba(foreground.r, foreground.g, foreground.b, 0.18)

  Row {
    id: row
    anchors.centerIn: parent
    spacing: Style.space(5)

    Rectangle {
      anchors.verticalCenter: parent.verticalCenter
      width: Style.space(6)
      height: width
      radius: width / 2
      color: root.toneColor
    }

    Text {
      textFormat: Text.PlainText
      text: root.text
      color: root.toneColor
      font.family: root.fontFamily
      font.pixelSize: Style.font.caption
      font.bold: true
    }
  }
}

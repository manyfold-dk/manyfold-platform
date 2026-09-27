import QtQuick
import qs.Commons

// One row of the platform picture: a name, the live detail behind it, and a
// status chip. A service row has only a hairline outline; a stack row is
// filled with the accent at the strength its layer carries, deepest at the
// bottom, like the four layers in the logo.
Rectangle {
  id: root

  property string name: ""
  property string detail: ""
  property string statusText: ""
  property color toneColor: Color.muted
  property color foreground: Color.foreground
  property color fill: "transparent"
  property string fontFamily: Style.font.family
  property bool clickable: false

  signal clicked()

  implicitHeight: Math.max(nameText.implicitHeight, chip.implicitHeight) + Style.space(6)
  radius: Style.cornerRadius
  color: fill.a > 0 ? fill
    : (clickable && mouse.containsMouse ? Qt.rgba(foreground.r, foreground.g, foreground.b, 0.06) : "transparent")
  border.width: fill.a > 0 ? 0 : 1
  border.color: Qt.rgba(foreground.r, foreground.g, foreground.b, 0.1)

  Text {
    id: nameText
    anchors.left: parent.left
    anchors.leftMargin: Style.space(10)
    anchors.verticalCenter: parent.verticalCenter
    width: Math.min(implicitWidth, parent.width * 0.42)
    textFormat: Text.PlainText
    text: root.name
    color: root.foreground
    font.family: root.fontFamily
    font.pixelSize: Style.font.body
    font.bold: true
    elide: Text.ElideRight
  }

  Text {
    anchors.left: nameText.right
    anchors.leftMargin: Style.space(10)
    anchors.right: chip.left
    anchors.rightMargin: Style.space(8)
    anchors.verticalCenter: parent.verticalCenter
    textFormat: Text.PlainText
    text: root.detail
    horizontalAlignment: Text.AlignRight
    color: Qt.rgba(root.foreground.r, root.foreground.g, root.foreground.b, 0.62)
    font.family: root.fontFamily
    font.pixelSize: Style.font.caption
    elide: Text.ElideRight
  }

  StatusChip {
    id: chip
    anchors.right: parent.right
    anchors.rightMargin: Style.space(8)
    anchors.verticalCenter: parent.verticalCenter
    text: root.statusText
    toneColor: root.toneColor
    foreground: root.foreground
    fontFamily: root.fontFamily
  }

  MouseArea {
    id: mouse
    anchors.fill: parent
    enabled: root.clickable
    hoverEnabled: true
    cursorShape: Qt.PointingHandCursor
    onClicked: root.clicked()
  }
}

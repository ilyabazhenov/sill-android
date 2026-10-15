// Prints the Mac's system cursors as the host sends them (the representation nearest 2×) for
// protocol/src/test/resources/cursors: a header line "name ptW ptH hotX hotY pxW pxH", then one
// row of 0/1 (alpha > 128) per pixel row. Run: swiftc -O dump.swift -o dump && ./dump

import AppKit
_ = NSApplication.shared
var cursors: [(String, NSCursor)] = [("arrow", .arrow), ("iBeam", .iBeam), ("iBeamV", .iBeamCursorForVerticalLayout), ("pointingHand", .pointingHand), ("openHand", .openHand), ("closedHand", .closedHand), ("resizeUpDown", .resizeUpDown), ("resizeLeftRight", .resizeLeftRight), ("resizeUp", .resizeUp), ("resizeDown", .resizeDown), ("crosshair", .crosshair), ("notAllowed", .operationNotAllowed), ("contextMenu", .contextualMenu), ("dragCopy", .dragCopy), ("dragLink", .dragLink), ("disappearing", .disappearingItem)]
if #available(macOS 15.0, *) {
  cursors += [("columnResize", .columnResize), ("rowResize", .rowResize), ("zoomIn", .zoomIn), ("frameResizeTop", .frameResize(position: .top, directions: .all)), ("frameResizeLeft", .frameResize(position: .left, directions: .all)), ("frameResizeTopLeft", .frameResize(position: .topLeft, directions: .all))]
}
for (name, c) in cursors {
  let image = c.image
  let wanted = image.size.width * 2
  guard let rep = image.representations.min(by: { abs(CGFloat($0.pixelsWide) - wanted) < abs(CGFloat($1.pixelsWide) - wanted) }),
        let cg = rep.cgImage(forProposedRect: nil, context: nil, hints: nil) else { print(name, "no rep"); continue }
  let w = cg.width, h = cg.height
  var px = [UInt8](repeating: 0, count: w*h*4)
  let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w*4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
  ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
  var rows = ""
  for y in 0..<h { var r = ""; for x in 0..<w { r += px[(y*w+x)*4+3] > 128 ? "1" : "0" }; rows += r + "\n" }
  let s = "\(name) \(image.size.width) \(image.size.height) \(c.hotSpot.x) \(c.hotSpot.y) \(w) \(h)\n" + rows
  try! s.write(toFile: "\(name).txt", atomically: true, encoding: .utf8)
}

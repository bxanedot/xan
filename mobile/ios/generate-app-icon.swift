import Foundation
import CoreGraphics
import ImageIO

// Compile Android's current Gold AppLogo mark into Apple's opaque 1024px app icon.
func fail(_ message: String) -> Never {
    fputs("XAN app icon: \(message)\n", stderr)
    exit(1)
}
guard CommandLine.arguments.count == 2 else { fail("expected the iOS project directory") }
let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let sourceURL = root.appendingPathComponent("../android/app/src/main/res/drawable-nodpi/xan_gold_mark.png")
let outputURL = root.appendingPathComponent("iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
let size = 1024

guard let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
      image.width == image.height else { fail("could not read the square XAN logo") }
guard let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                              bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
    fail("could not create the app icon bitmap")
}
let rect = CGRect(x: 0, y: 0, width: CGFloat(size), height: CGFloat(size))
context.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
context.fill(rect)
context.interpolationQuality = .high
context.draw(image, in: rect)
guard let icon = context.makeImage() else { fail("could not render the app icon") }
let data = NSMutableData()
guard let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil) else {
    fail("could not create the PNG encoder")
}
CGImageDestinationAddImage(destination, icon, nil)
guard CGImageDestinationFinalize(destination) else { fail("PNG encoding failed") }
try (data as Data).write(to: outputURL, options: .atomic)
guard let result = CGImageSourceCreateWithURL(outputURL as CFURL, nil),
      let decoded = CGImageSourceCreateImageAtIndex(result, 0, nil),
      decoded.width == size, decoded.height == size,
      [.none, .noneSkipFirst, .noneSkipLast].contains(decoded.alphaInfo) else {
    fail("generated asset must be 1024x1024 without transparency")
}
print("Generated AppIcon.png from Android's current Gold AppLogo: 1024x1024, opaque")

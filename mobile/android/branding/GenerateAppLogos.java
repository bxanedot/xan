import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import javax.imageio.ImageIO;

/** Regenerates the equally sized, softly glowing launcher and in-app icon marks. */
public final class GenerateAppLogos {
    private static final int CANVAS = 1024;
    private static final int MARK_SIZE = 616;

    private record Logo(String id, String assetName, String sourceName, Color glow, Palette palette) {}
    private enum Palette { ORIGINAL, AURORA, PRISM }

    private static final List<Logo> LOGOS = List.of(
        new Logo("white", "xan_launcher_white_mark.png", "xan_launcher_white_mark.png", new Color(198, 224, 255), Palette.ORIGINAL),
        new Logo("gold", "xan_gold_mark.png", "xan_gold_mark.png", new Color(255, 196, 64), Palette.ORIGINAL),
        new Logo("horizon", "xan_horizon_mark.png", "xan_horizon_mark.png", new Color(241, 102, 208), Palette.ORIGINAL),
        new Logo("aurora", "xan_logo_aurora_mark.png", "xan_logo_aurora_mark.png", new Color(76, 244, 197), Palette.AURORA),
        new Logo("sunset", "xan_logo_sunset_mark.png", "xan_logo_sunset_mark.png", new Color(255, 135, 107), Palette.ORIGINAL),
        new Logo("green", "xan_logo_green_mark.png", "xan_logo_green_mark.png", new Color(89, 226, 147), Palette.ORIGINAL),
        new Logo("prism", "xan_logo_prism_mark.png", "xan_logo_prism_mark.png", new Color(215, 113, 255), Palette.PRISM),
        new Logo("lilac", "xan_logo_lilac_mark.png", "xan_logo_lilac_mark.png", new Color(195, 143, 255), Palette.ORIGINAL),
        new Logo("blue", "xan_logo_blue_mark.png", "xan_logo_blue_mark.png", new Color(94, 164, 255), Palette.ORIGINAL)
    );

    private GenerateAppLogos() {}

    public static void main(String[] args) throws Exception {
        Path androidRoot = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        Path branding = androidRoot.resolve("branding");
        Path marks = branding.resolve("app-marks");
        Path sourceRoot = marks.resolve("source");
        Path drawableRoot = androidRoot.resolve("app/src/main/res/drawable-nodpi");
        Path packaged = branding.resolve("hotswap/android/res-packaged/drawable-nodpi");
        Path hotswap = branding.resolve("hotswap/android/res/drawable-nodpi");
        Files.createDirectories(sourceRoot);

        for (Logo logo : LOGOS) {
            Path source = sourceRoot.resolve(logo.sourceName());
            if (!Files.exists(source)) {
                Path currentAsset = marks.resolve(logo.assetName());
                if (logo.id().equals("horizon")) {
                    currentAsset = packaged.resolve("xan_mark.png");
                }
                Files.copy(currentAsset, source, StandardCopyOption.COPY_ATTRIBUTES);
            }
            BufferedImage generated = render(source, logo);
            Path assetOutput = marks.resolve(logo.assetName());
            write(generated, assetOutput);
            if (!logo.id().equals("horizon")) {
                write(generated, drawableRoot.resolve(logo.assetName()));
            }
            if (logo.id().equals("horizon")) {
                write(generated, marks.resolve("xan_horizon_mark.png"));
                write(generated, packaged.resolve("xan_mark.png"));
                write(generated, hotswap.resolve("xan_mark.png"));
                write(generated, hotswap.resolve("xan_round_mark.png"));
            }
        }
        System.out.println("Generated nine normalized XAN logo marks with a soft halo.");
        System.out.println("Every core mark is centered on 1024px and occupies 616px at its longest edge.");
    }

    private static BufferedImage render(Path sourcePath, Logo logo) throws IOException {
        BufferedImage source = ImageIO.read(sourcePath.toFile());
        if (source == null) throw new IOException("Cannot read PNG: " + sourcePath);
        int minX = source.getWidth(), minY = source.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                if (((source.getRGB(x, y) >>> 24) & 0xff) > 8) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) throw new IOException("Logo has no visible pixels: " + sourcePath);

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        double scale = (double) MARK_SIZE / Math.max(width, height);
        int outputWidth = Math.max(1, (int) Math.round(width * scale));
        int outputHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage core = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = core.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(
            source,
            (CANVAS - outputWidth) / 2, (CANVAS - outputHeight) / 2,
            (CANVAS + outputWidth) / 2, (CANVAS + outputHeight) / 2,
            minX, minY, maxX + 1, maxY + 1,
            null
        );
        graphics.dispose();

        if (logo.palette() != Palette.ORIGINAL) applyPalette(core, logo.palette());
        return addSoftGlow(core, logo.glow());
    }

    private static void applyPalette(BufferedImage image, Palette palette) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                if (alpha == 0) continue;
                int r = (argb >>> 16) & 0xff;
                int g = (argb >>> 8) & 0xff;
                int b = argb & 0xff;
                double luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
                double brightness = 0.38 + 0.62 * luminance;
                Color target = palette == Palette.AURORA
                    ? auroraColor((x + 0.32 * y) / (CANVAS * 1.32))
                    : prismColor(x, y);
                int nr = clamp((int) Math.round(target.getRed() * brightness));
                int ng = clamp((int) Math.round(target.getGreen() * brightness));
                int nb = clamp((int) Math.round(target.getBlue() * brightness));
                image.setRGB(x, y, (alpha << 24) | (nr << 16) | (ng << 8) | nb);
            }
        }
    }

    private static Color auroraColor(double position) {
        Color[] stops = {
            new Color(38, 222, 153), new Color(82, 249, 198), new Color(75, 220, 237),
            new Color(104, 151, 255), new Color(178, 112, 255)
        };
        double p = Math.max(0, Math.min(1, position)) * (stops.length - 1);
        int index = Math.min(stops.length - 2, (int) p);
        return mix(stops[index], stops[index + 1], p - index);
    }

    private static Color prismColor(int x, int y) {
        double angle = Math.atan2(y - CANVAS / 2.0, x - CANVAS / 2.0);
        float hue = (float) ((angle + Math.PI) / (2.0 * Math.PI) * 0.84);
        return Color.getHSBColor(hue, 0.78f, 1f);
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color(
            clamp((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t)),
            clamp((int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t)),
            clamp((int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t))
        );
    }

    private static BufferedImage addSoftGlow(BufferedImage core, Color glowColor) {
        int[] alpha = new int[CANVAS * CANVAS];
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) alpha[y * CANVAS + x] = (core.getRGB(x, y) >>> 24) & 0xff;
        }
        float[] near = boxBlur(alpha, 8);
        float[] halo = boxBlur(alpha, 20);
        BufferedImage result = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                int i = y * CANVAS + x;
                int argb = core.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                double outside = 1.0 - a / 255.0;
                int glowAlpha = clamp((int) Math.round((near[i] * 0.075 + halo[i] * 0.022) * outside));
                if (glowAlpha == 0) {
                    result.setRGB(x, y, argb);
                    continue;
                }
                double baseWeight = a / 255.0;
                double glowWeight = glowAlpha / 255.0;
                double outAlpha = baseWeight + glowWeight * (1.0 - baseWeight);
                int r = (int) Math.round((((argb >>> 16) & 0xff) * baseWeight + glowColor.getRed() * glowWeight * (1.0 - baseWeight)) / outAlpha);
                int g = (int) Math.round((((argb >>> 8) & 0xff) * baseWeight + glowColor.getGreen() * glowWeight * (1.0 - baseWeight)) / outAlpha);
                int b = (int) Math.round(((argb & 0xff) * baseWeight + glowColor.getBlue() * glowWeight * (1.0 - baseWeight)) / outAlpha);
                result.setRGB(x, y, (clamp((int) Math.round(outAlpha * 255)) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
        }
        return result;
    }

    private static float[] boxBlur(int[] source, int radius) {
        float[] horizontal = new float[source.length];
        float[] output = new float[source.length];
        int window = radius * 2 + 1;
        for (int y = 0; y < CANVAS; y++) {
            long sum = 0;
            for (int x = -radius; x <= radius; x++) if (x >= 0) sum += source[y * CANVAS + x];
            for (int x = 0; x < CANVAS; x++) {
                horizontal[y * CANVAS + x] = (float) sum / window;
                int remove = x - radius;
                int add = x + radius + 1;
                if (remove >= 0) sum -= source[y * CANVAS + remove];
                if (add < CANVAS) sum += source[y * CANVAS + add];
            }
        }
        for (int x = 0; x < CANVAS; x++) {
            double sum = 0;
            for (int y = -radius; y <= radius; y++) if (y >= 0) sum += horizontal[y * CANVAS + x];
            for (int y = 0; y < CANVAS; y++) {
                output[y * CANVAS + x] = (float) (sum / window);
                int remove = y - radius;
                int add = y + radius + 1;
                if (remove >= 0) sum -= horizontal[remove * CANVAS + x];
                if (add < CANVAS) sum += horizontal[add * CANVAS + x];
            }
        }
        return output;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private static void write(BufferedImage image, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        if (!ImageIO.write(image, "png", destination.toFile())) throw new IOException("PNG writer unavailable");
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders the application's authoritative brand icon (the vector AppIcon.Symbol.BRAND shown on the splash
 * screen and window title bars) into the raster files that packaging needs. The artwork is not redrawn:
 * every size is painted by AppIcon itself.
 *
 *   java -Djava.awt.headless=true -Dflatlaf.uiScale=1 -cp "<build>/classes;<build>/lib/*" tools/RenderIcons.java res
 *
 * Writes res/app.ico (Windows EXE, MSI), res/app.png (Linux) and res/icons/app-<size>.png (macOS, MSIX).
 */
public class RenderIcons {
    static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 96, 128, 256};
    static final int[] PNG_SIZES = {16, 32, 44, 50, 64, 128, 150, 256, 512, 1024};

    public static void main(String[] args) throws Exception {
        Path res = Path.of(args.length > 0 ? args[0] : "res");
        Path icons = Files.createDirectories(res.resolve("icons"));
        for (int size : PNG_SIZES) ImageIO.write(render(size), "png", icons.resolve("app-" + size + ".png").toFile());
        ImageIO.write(render(512), "png", res.resolve("app.png").toFile());
        List<byte[]> entries = new ArrayList<>();
        for (int size : ICO_SIZES) entries.add(png(render(size)));
        Files.write(res.resolve("app.ico"), ico(ICO_SIZES, entries));
        System.out.println("Rendered AppIcon BRAND to " + res.resolve("app.ico") + ", " + res.resolve("app.png") + " and " + icons);
    }

    static BufferedImage render(int size) {
        AppIcon icon = new AppIcon(AppIcon.Symbol.BRAND, size);
        if (icon.getIconWidth() != size) throw new IllegalStateException("Run with -Dflatlaf.uiScale=1 (icon reports " + icon.getIconWidth() + " px for " + size + ").");
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return image;
    }

    static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** ICO container with PNG-compressed 32-bit images (supported by Windows Vista and later). */
    static byte[] ico(int[] sizes, List<byte[]> images) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeShort(out, 0); writeShort(out, 1); writeShort(out, sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            out.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
            out.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
            out.writeByte(0); out.writeByte(0);
            writeShort(out, 1); writeShort(out, 32);
            writeInt(out, images.get(i).length); writeInt(out, offset);
            offset += images.get(i).length;
        }
        for (byte[] image : images) out.write(image);
        return bytes.toByteArray();
    }

    static void writeShort(DataOutputStream out, int value) throws Exception { out.writeByte(value & 0xff); out.writeByte(value >>> 8 & 0xff); }
    static void writeInt(DataOutputStream out, int value) throws Exception { writeShort(out, value & 0xffff); writeShort(out, value >>> 16 & 0xffff); }
}

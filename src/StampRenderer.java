// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lays out a visible stamp once and draws it identically on screen and in PDF. Text is shaped by Java
 * (HarfBuzz), so Devanagari conjuncts and matras are correct, and embedded in the PDF as vector outlines.
 */
public final class StampRenderer {
    /** Values substituted into a design's template. Page numbers are supplied per stamp. */
    public record Values(SigningMode mode, String name, String reason, String location, java.time.OffsetDateTime time, String issuer, String tsa) {
        public Values {
            mode = mode == null ? SigningMode.DSC : mode;
            name = clean(name); reason = clean(reason); location = clean(location);
            issuer = clean(issuer); tsa = clean(tsa);
        }
        private static String clean(String value) { return value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").strip(); }
    }

    /** A laid-out stamp in points, origin at the top-left, y increasing downwards. */
    public record Composition(double width, double height, List<Shape> glyphs, Rectangle2D image, boolean border, Color ink) { }

    private static final Pattern TOKEN = Pattern.compile("\\{([a-z]+)}");
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final float MAX_FONT = 16f;
    private static final float MIN_FONT = 3f;

    private StampRenderer() { }

    public static List<String> lines(StampDesign design, Values values, int page, int pages) {
        String template = design.template();
        if (values.mode() == SigningMode.TIMESTAMP_SEAL && !template.contains("{tsa}")) {
            template = template + "\nSealed by {tsa} trusted timestamp";
        }
        Map<String, String> tokens = Map.of("heading", values.mode().heading(), "name", values.name(),
                "reason", values.reason(), "location", values.location(), "date", design.dateStyle().format(values.time()), "issuer", values.issuer(),
                "tsa", values.tsa(), "page", Integer.toString(page), "pages", Integer.toString(pages));
        List<String> result = new ArrayList<>();
        for (String line : template.split("\n", -1)) {
            Matcher matcher = TOKEN.matcher(line);
            StringBuilder out = new StringBuilder();
            boolean hadToken = false, hadValue = false;
            while (matcher.find()) {
                String value = tokens.get(matcher.group(1));
                if (value == null) { matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group())); continue; }
                hadToken = true;
                hadValue |= !value.isBlank();
                matcher.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(out);
            if (hadToken && !hadValue) continue; // "Reason: " with no reason is omitted.
            result.add(out.toString().strip());
        }
        while (!result.isEmpty() && result.getFirst().isEmpty()) result.removeFirst();
        while (!result.isEmpty() && result.getLast().isEmpty()) result.removeLast();
        return result;
    }

    public static Composition compose(StampDesign design, List<String> lines, double imageAspect, SigningMode mode,
                                      double width, double height) {
        return compose(design, lines, "", imageAspect, mode, width, height);
    }

    /** {@code name} fills the left column of the two-column layout when there is no image. */
    public static Composition compose(StampDesign design, List<String> lines, String name, double imageAspect, SigningMode mode,
                                      double width, double height) {
        StampDesign.Layout layout = design.layout();
        boolean image = design.hasImage() && imageAspect > 0 && layout != StampDesign.Layout.TEXT_ONLY;
        // A seal always shows its declaration and timestamp wording.
        if (image && layout == StampDesign.Layout.IMAGE_ONLY && mode == SigningMode.TIMESTAMP_SEAL) layout = StampDesign.Layout.IMAGE_TOP;
        double padding = Math.max(1.5, Math.min(7, Math.min(width, height) * 0.07));
        Rectangle2D content = new Rectangle2D.Double(padding, padding, Math.max(1, width - 2 * padding), Math.max(1, height - 2 * padding));
        if (layout == StampDesign.Layout.NAME_LEFT) {
            double gap = padding;
            double half = (content.getWidth() - gap) / 2;
            Rectangle2D left = new Rectangle2D.Double(content.getX(), content.getY(), half, content.getHeight());
            Rectangle2D right = new Rectangle2D.Double(content.getX() + half + gap, content.getY(), half, content.getHeight());
            List<Shape> glyphs = new ArrayList<>();
            Rectangle2D picture = null;
            if (image) picture = fit(left, imageAspect);
            else if (name != null && !name.isBlank()) glyphs.addAll(layoutText(List.of(name.strip()), left, 40f));
            if (!lines.isEmpty()) glyphs.addAll(layoutText(lines, right, MAX_FONT));
            return new Composition(width, height, List.copyOf(glyphs), picture, design.border(), design.ink().color());
        }
        Rectangle2D text = content;
        Rectangle2D picture = null;
        if (image) {
            double gap = padding * 0.8;
            switch (layout) {
                case IMAGE_ONLY -> { picture = fit(content, imageAspect); text = null; }
                case IMAGE_LEFT -> {
                    double w = Math.min(content.getWidth() * 0.42, content.getHeight() * imageAspect);
                    picture = fit(new Rectangle2D.Double(content.getX(), content.getY(), w, content.getHeight()), imageAspect);
                    text = new Rectangle2D.Double(content.getX() + w + gap, content.getY(), Math.max(1, content.getWidth() - w - gap), content.getHeight());
                }
                default -> {
                    double h = Math.min(content.getHeight() * 0.45, content.getWidth() / imageAspect);
                    picture = fit(new Rectangle2D.Double(content.getX(), content.getY(), content.getWidth(), h), imageAspect);
                    text = new Rectangle2D.Double(content.getX(), content.getY() + h + gap, content.getWidth(), Math.max(1, content.getHeight() - h - gap));
                }
            }
        }
        List<Shape> glyphs = text == null || lines.isEmpty() ? List.of() : layoutText(lines, text, MAX_FONT);
        return new Composition(width, height, glyphs, picture, design.border(), design.ink().color());
    }

    public static void paint(Graphics2D graphics, Composition composition, BufferedImage image) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.clip(new Rectangle2D.Double(0, 0, composition.width(), composition.height()));
            if (image != null && composition.image() != null) {
                Rectangle2D r = composition.image();
                AffineTransform place = new AffineTransform(r.getWidth() / image.getWidth(), 0, 0, r.getHeight() / image.getHeight(), r.getX(), r.getY());
                g.drawImage(image, place, null);
            }
            g.setColor(composition.ink());
            if (composition.border()) {
                g.setStroke(new BasicStroke(0.8f));
                g.draw(border(composition));
            }
            for (Shape glyph : composition.glyphs()) g.fill(glyph);
        } finally { g.dispose(); }
    }

    /** A reusable form XObject whose bounding box is {@code [0 0 width height]} in upright stamp orientation. */
    public static PdfFormXObject toForm(PdfDocument document, Composition composition, PdfImageXObject image) {
        float width = (float) composition.width(), height = (float) composition.height();
        PdfFormXObject form = new PdfFormXObject(new Rectangle(0, 0, width, height));
        PdfCanvas canvas = new PdfCanvas(form, document);
        if (image != null && composition.image() != null) {
            Rectangle2D r = composition.image();
            canvas.addXObjectWithTransformationMatrix(image, (float) r.getWidth(), 0, 0, (float) r.getHeight(),
                    (float) r.getX(), (float) (height - r.getMaxY()));
        }
        Color ink = composition.ink();
        DeviceRgb color = new DeviceRgb(ink.getRed(), ink.getGreen(), ink.getBlue());
        canvas.saveState();
        canvas.concatMatrix(1, 0, 0, -1, 0, height);
        if (composition.border()) {
            canvas.setStrokeColor(color).setLineWidth(0.8f);
            append(canvas, border(composition));
            canvas.stroke();
        }
        canvas.setFillColor(color);
        for (Shape glyph : composition.glyphs()) {
            int rule = append(canvas, glyph);
            if (rule == PathIterator.WIND_EVEN_ODD) canvas.eoFill(); else canvas.fill();
        }
        canvas.restoreState();
        canvas.release();
        return form;
    }

    public static PdfImageXObject pdfImage(byte[] png) { return png == null ? null : new PdfImageXObject(ImageDataFactory.create(png)); }

    /** Cached screen preview used by the designer and the placement editor. */
    public static final class Preview {
        private final StampDesign design;
        private final BufferedImage image;
        private final Values values;
        private final Map<String, Composition> cache = new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Composition> eldest) { return size() > 64; }
        };

        public Preview(StampDesign design, BufferedImage image, Values values) {
            this.design = design;
            this.image = design.hasImage() ? image : null;
            this.values = values;
        }

        public void paint(Graphics2D g, double width, double height, int page, int pages) {
            if (width < 4 || height < 4) return;
            String key = Math.round(width * 4) + "x" + Math.round(height * 4) + ":" + page + "/" + pages;
            Composition composition = cache.computeIfAbsent(key, k -> compose(design, lines(design, values, page, pages), values.name(),
                    image == null ? 0 : image.getWidth() / (double) image.getHeight(), values.mode(), width, height));
            StampRenderer.paint(g, composition, image);
        }
    }

    private static List<Shape> layoutText(List<String> lines, Rectangle2D area, float maxFont) {
        float low = MIN_FONT, high = maxFont;
        List<TextLayout> best = wrap(lines, low, area.getWidth());
        for (int i = 0; i < 12; i++) {
            float size = (low + high) / 2;
            List<TextLayout> candidate = wrap(lines, size, area.getWidth());
            if (height(candidate, size) <= area.getHeight()) { low = size; best = candidate; } else high = size;
        }
        float size = low;
        double y = area.getY() + Math.max(0, (area.getHeight() - height(best, size)) / 2);
        List<Shape> shapes = new ArrayList<>();
        for (TextLayout layout : best) {
            if (layout == null) { y += size * 1.18; continue; }
            y += layout.getAscent();
            shapes.add(layout.getOutline(AffineTransform.getTranslateInstance(area.getX(), y)));
            y += layout.getDescent() + layout.getLeading() + size * 0.12;
        }
        return List.copyOf(shapes);
    }

    private static List<TextLayout> wrap(List<String> lines, float size, double width) {
        List<TextLayout> layouts = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) { layouts.add(null); continue; }
            LineBreakMeasurer measurer = new LineBreakMeasurer(attributed(line, size).getIterator(), FRC);
            while (measurer.getPosition() < line.length()) layouts.add(measurer.nextLayout((float) Math.max(1, width)));
        }
        return layouts;
    }

    private static double height(List<TextLayout> layouts, float size) {
        double total = 0;
        for (TextLayout layout : layouts) {
            total += layout == null ? size * 1.18 : layout.getAscent() + layout.getDescent() + layout.getLeading() + size * 0.12;
        }
        return total - size * 0.12;
    }

    /** Devanagari runs use Noto Sans Devanagari; everything else uses Noto Sans. */
    private static AttributedString attributed(String text, float size) {
        AttributedString attributed = new AttributedString(text);
        Font latin = Fonts.LATIN.deriveFont(size), devanagari = Fonts.DEVANAGARI.deriveFont(size);
        int start = 0;
        Boolean current = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            boolean deva = isDevanagari(cp) || (cp == 0x200C || cp == 0x200D) && Boolean.TRUE.equals(current)
                    || !Fonts.LATIN.canDisplay(cp) && Fonts.DEVANAGARI.canDisplay(cp);
            if (current != null && deva != current) {
                attributed.addAttribute(TextAttribute.FONT, current ? devanagari : latin, start, i);
                start = i;
            }
            current = deva;
            i += Character.charCount(cp);
        }
        if (current != null) attributed.addAttribute(TextAttribute.FONT, current ? devanagari : latin, start, text.length());
        return attributed;
    }

    private static boolean isDevanagari(int cp) {
        return cp >= 0x0900 && cp <= 0x097F || cp >= 0xA8E0 && cp <= 0xA8FF || cp >= 0x1CD0 && cp <= 0x1CFF;
    }

    private static Rectangle2D fit(Rectangle2D region, double aspect) {
        double width = region.getWidth(), height = width / aspect;
        if (height > region.getHeight()) { height = region.getHeight(); width = height * aspect; }
        return new Rectangle2D.Double(region.getX() + (region.getWidth() - width) / 2, region.getY() + (region.getHeight() - height) / 2, width, height);
    }

    private static Shape border(Composition composition) {
        double radius = Math.min(6, Math.min(composition.width(), composition.height()) * 0.12);
        return new RoundRectangle2D.Double(0.4, 0.4, composition.width() - 0.8, composition.height() - 0.8, radius, radius);
    }

    private static int append(PdfCanvas canvas, Shape shape) {
        PathIterator iterator = shape.getPathIterator(null);
        double[] c = new double[6];
        double x = 0, y = 0;
        while (!iterator.isDone()) {
            switch (iterator.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> { canvas.moveTo(c[0], c[1]); x = c[0]; y = c[1]; }
                case PathIterator.SEG_LINETO -> { canvas.lineTo(c[0], c[1]); x = c[0]; y = c[1]; }
                case PathIterator.SEG_QUADTO -> {
                    canvas.curveTo(x + 2 * (c[0] - x) / 3, y + 2 * (c[1] - y) / 3,
                            c[2] + 2 * (c[0] - c[2]) / 3, c[3] + 2 * (c[1] - c[3]) / 3, c[2], c[3]);
                    x = c[2]; y = c[3];
                }
                case PathIterator.SEG_CUBICTO -> { canvas.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]); x = c[4]; y = c[5]; }
                case PathIterator.SEG_CLOSE -> canvas.closePath();
                default -> { }
            }
            iterator.next();
        }
        return iterator.getWindingRule();
    }

    private static final class Fonts {
        static final Font LATIN = load("NotoSans-Regular.ttf");
        static final Font DEVANAGARI = load("NotoSansDevanagari-Regular.ttf");
        private static Font load(String name) {
            try (InputStream in = StampRenderer.class.getResourceAsStream("/fonts/" + name)) {
                if (in == null) throw new IllegalStateException("Packaged font missing: " + name);
                return Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (IOException | FontFormatException ex) { throw new IllegalStateException("Cannot load font " + name, ex); }
        }
    }

    /** Forces font loading off the EDT during startup. */
    static void preload() { Fonts.LATIN.canDisplay('A'); }
}

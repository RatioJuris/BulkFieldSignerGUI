// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.Color;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** A saved visible-stamp appearance. The image, when present, is stored beside it as {@code <id>.png}. */
public record StampDesign(String id, String name, String template, Layout layout, Ink ink, boolean border, boolean hasImage,
                          DateStyle dateStyle) {
    public enum Layout {
        TEXT_ONLY("Text only"), IMAGE_LEFT("Image beside text"), IMAGE_TOP("Image above text"), IMAGE_ONLY("Image only"),
        NAME_LEFT("Name (or image) beside details");
        private final String label;
        Layout(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum Ink {
        BLUE("Blue", new Color(0x1d3fae)), BLACK("Black", new Color(0x111827)), GREEN("Green", new Color(0x166534));
        private final String label;
        private final Color color;
        Ink(String label, Color color) { this.label = label; this.color = color; }
        public Color color() { return color; }
        @Override public String toString() { return label; }
    }

    public enum DateStyle {
        INDIAN("05-11-2026 15:30:00 +05:30", "dd-MM-yyyy HH:mm:ss xxx"),
        CLASSIC("2026.11.05 15:30:00 +05'30'", "yyyy.MM.dd HH:mm:ss xxx");
        private final String label;
        private final DateTimeFormatter format;
        DateStyle(String label, String pattern) { this.label = label; this.format = DateTimeFormatter.ofPattern(pattern); }
        public String format(java.time.OffsetDateTime time) {
            if (time == null) return "";
            String text = format.format(time);
            // PDF-reader convention: +05'30' rather than +05:30.
            return this == CLASSIC ? text.substring(0, text.length() - 3) + "'" + text.substring(text.length() - 2) + "'" : text;
        }
        @Override public String toString() { return label; }
    }

    public static final String DEFAULT_ID = "standard";
    public static final String CLASSIC_ID = "classic";
    public static final String DEFAULT_TEMPLATE = "{heading} {name}\nReason: {reason}\nLocation: {location}\nDate: {date}";
    public static final String TOKENS = "{heading} {name} {reason} {location} {date} {issuer} {tsa} {page} {pages}";
    public static final int MAX_TEMPLATE = 1000;
    public static final int MAX_LINES = 12;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    public StampDesign {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid design identifier.");
        name = name == null || name.isBlank() ? "Untitled" : name.strip();
        if (name.length() > 60) throw new IllegalArgumentException("Design names are limited to 60 characters.");
        template = template == null ? "" : template.replace("\r\n", "\n").replace('\r', '\n');
        if (template.length() > MAX_TEMPLATE || template.lines().count() > MAX_LINES) {
            throw new IllegalArgumentException("Stamp text is limited to " + MAX_LINES + " lines and " + MAX_TEMPLATE + " characters.");
        }
        if (layout == null) layout = Layout.TEXT_ONLY;
        if (ink == null) ink = Ink.BLUE;
        if (dateStyle == null) dateStyle = DateStyle.INDIAN;
    }

    public StampDesign(String id, String name, String template, Layout layout, Ink ink, boolean border, boolean hasImage) {
        this(id, name, template, layout, ink, border, hasImage, DateStyle.INDIAN);
    }

    public static StampDesign standard() {
        return new StampDesign(DEFAULT_ID, "Standard", DEFAULT_TEMPLATE, Layout.TEXT_ONLY, Ink.BLUE, true, false, DateStyle.INDIAN);
    }

    /** The familiar PDF-reader signature look: large name on the left, details on the right, no border, no logo. */
    public static StampDesign classic() {
        return new StampDesign(CLASSIC_ID, "Classic (name | details)", DEFAULT_TEMPLATE, Layout.NAME_LEFT, Ink.BLACK, false, false,
                DateStyle.CLASSIC);
    }

    public static boolean builtIn(String id) { return DEFAULT_ID.equals(id) || CLASSIC_ID.equals(id); }

    public static StampDesign builtIn(String id, StampDesign fallback) {
        return DEFAULT_ID.equals(id) ? standard() : CLASSIC_ID.equals(id) ? classic() : fallback;
    }

    public boolean usesImage() { return hasImage && layout != Layout.TEXT_ONLY; }
    public boolean usesText() { return layout != Layout.IMAGE_ONLY || !hasImage; }

    @Override public String toString() { return name; }
}

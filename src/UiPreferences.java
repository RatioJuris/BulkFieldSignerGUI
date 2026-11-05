// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Separate from signing metadata so saving a signature cannot erase the appearance preference. */
public record UiPreferences(ThemeMode theme, int width, int height, boolean maximized) {
    public static final Path FILE = AppPaths.CONFIG.resolve("ui.properties");
    public static final UiPreferences DEFAULT = new UiPreferences(ThemeMode.SYSTEM, 1280, 900, false);

    public UiPreferences {
        if (theme == null) theme = ThemeMode.SYSTEM;
        width = Math.max(960, Math.min(2560, width));
        height = Math.max(640, Math.min(1600, height));
    }

    public static UiPreferences load() throws IOException { return read(FILE); }

    static UiPreferences read(Path path) throws IOException {
        if (!Files.exists(AppPaths.requireLocal(path))) return DEFAULT;
        Properties values = new Properties();
        values.load(new ByteArrayInputStream(LocalFiles.readBounded(path, 8192)));
        return new UiPreferences(ThemeMode.parse(values.getProperty("theme")),
                number(values.getProperty("width"), DEFAULT.width), number(values.getProperty("height"), DEFAULT.height),
                Boolean.parseBoolean(values.getProperty("maximized", "false")));
    }

    public void save() throws IOException { write(FILE); }

    void write(Path path) throws IOException {
        Properties values = new Properties();
        values.setProperty("theme", theme.name());
        values.setProperty("width", Integer.toString(width));
        values.setProperty("height", Integer.toString(height));
        values.setProperty("maximized", Boolean.toString(maximized));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.store(bytes, "Ratio Juris appearance and window state");
        LocalFiles.atomicWrite(path, bytes.toByteArray());
    }

    private static int number(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { return fallback; }
    }
}

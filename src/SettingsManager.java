// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class SettingsManager {
    private static final Path FILE = AppPaths.CONFIG.resolve("settings.properties");
    private SettingsManager() { }

    public static void save(SigningRequest request) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("mode", request.mode().name());
        properties.setProperty("reason", request.reason());
        properties.setProperty("location", request.location());
        properties.setProperty("declaredName", request.declaredName());
        properties.setProperty(request.mode() == SigningMode.DSC ? "tsa" : "sealTsa", request.timestampProvider().name());
        properties.setProperty("design", request.design().id());
        Properties previous = load();
        // Keep the other mode's authority choice.
        String other = request.mode() == SigningMode.DSC ? "sealTsa" : "tsa";
        if (previous.getProperty(other) != null) properties.setProperty(other, previous.getProperty(other));
        if (request.mode() == SigningMode.DSC && previous.getProperty("declaredName") != null) {
            properties.setProperty("declaredName", previous.getProperty("declaredName"));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, "Ratio Juris user settings");
        LocalFiles.atomicWrite(FILE, bytes.toByteArray());
    }

    public static Properties load() throws IOException {
        Properties properties = new Properties();
        if (Files.exists(AppPaths.requireLocal(FILE))) {
            properties.load(new ByteArrayInputStream(LocalFiles.readBounded(FILE, 32_768)));
        }
        return properties;
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

/**
 * Update state kept between sessions in {@code config/update.properties}. Contains no credentials,
 * keys or document data. {@code mandatoryVersion} keeps a known Type A requirement in force after a restart.
 */
public record UpdatePreferences(Instant lastCheck, String lastAttemptedVersion, SemanticVersion skippedVersion,
                                SemanticVersion remindVersion, Instant remindUntil, Duration checkInterval, Duration remindInterval,
                                SemanticVersion mandatoryVersion, String mandatoryReason, Instant nextAllowedCheck, String lastStatus) {
    public static final Path FILE = AppPaths.CONFIG.resolve("update.properties");
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofHours(24);
    public static final Duration DEFAULT_REMIND_INTERVAL = Duration.ofHours(24);

    public UpdatePreferences {
        checkInterval = clamp(checkInterval, DEFAULT_CHECK_INTERVAL);
        remindInterval = clamp(remindInterval, DEFAULT_REMIND_INTERVAL);
    }

    public static UpdatePreferences defaults() {
        return new UpdatePreferences(null, null, null, null, null, DEFAULT_CHECK_INTERVAL, DEFAULT_REMIND_INTERVAL, null, null, null, null);
    }

    /** Between one hour and 30 days. */
    private static Duration clamp(Duration value, Duration fallback) {
        if (value == null) return fallback;
        return value.compareTo(Duration.ofHours(1)) < 0 ? Duration.ofHours(1) : value.compareTo(Duration.ofDays(30)) > 0 ? Duration.ofDays(30) : value;
    }

    public static UpdatePreferences load() throws IOException { return read(FILE); }

    static UpdatePreferences read(Path path) throws IOException {
        if (!Files.exists(AppPaths.requireLocal(path))) return defaults();
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(LocalFiles.readBounded(path, 16_384)));
        return new UpdatePreferences(instant(p, "lastCheck"), p.getProperty("lastAttemptedVersion"), version(p, "skippedVersion"),
                version(p, "remindVersion"), instant(p, "remindUntil"), hours(p, "checkIntervalHours"), hours(p, "remindIntervalHours"),
                version(p, "mandatoryVersion"), p.getProperty("mandatoryReason"), instant(p, "nextAllowedCheck"), p.getProperty("lastStatus"));
    }

    public void save() throws IOException { write(FILE); }

    void write(Path path) throws IOException {
        Properties p = new Properties();
        put(p, "lastCheck", lastCheck);
        put(p, "lastAttemptedVersion", lastAttemptedVersion);
        put(p, "skippedVersion", skippedVersion);
        put(p, "remindVersion", remindVersion);
        put(p, "remindUntil", remindUntil);
        p.setProperty("checkIntervalHours", Long.toString(checkInterval.toHours()));
        p.setProperty("remindIntervalHours", Long.toString(remindInterval.toHours()));
        put(p, "mandatoryVersion", mandatoryVersion);
        put(p, "mandatoryReason", mandatoryReason == null ? null : mandatoryReason.substring(0, Math.min(2000, mandatoryReason.length())));
        put(p, "nextAllowedCheck", nextAllowedCheck);
        put(p, "lastStatus", lastStatus == null ? null : lastStatus.substring(0, Math.min(500, lastStatus.length())));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        p.store(bytes, Product.NAME + " update state");
        LocalFiles.atomicWrite(path, bytes.toByteArray());
    }

    public boolean checkDue(Instant now) {
        return (nextAllowedCheck == null || !now.isBefore(nextAllowedCheck)) && (lastCheck == null || !now.isBefore(lastCheck.plus(checkInterval)));
    }

    UpdatePreferences withCheck(Instant time, String status) {
        return new UpdatePreferences(time, lastAttemptedVersion, skippedVersion, remindVersion, remindUntil, checkInterval, remindInterval,
                mandatoryVersion, mandatoryReason, nextAllowedCheck, status);
    }
    UpdatePreferences withAttempt(SemanticVersion version) {
        return new UpdatePreferences(lastCheck, version.toString(), skippedVersion, remindVersion, remindUntil, checkInterval, remindInterval,
                mandatoryVersion, mandatoryReason, nextAllowedCheck, lastStatus);
    }
    UpdatePreferences withSkipped(SemanticVersion version) {
        return new UpdatePreferences(lastCheck, lastAttemptedVersion, version, remindVersion, remindUntil, checkInterval, remindInterval,
                mandatoryVersion, mandatoryReason, nextAllowedCheck, lastStatus);
    }
    UpdatePreferences withReminder(SemanticVersion version, Instant until) {
        return new UpdatePreferences(lastCheck, lastAttemptedVersion, skippedVersion, version, until, checkInterval, remindInterval,
                mandatoryVersion, mandatoryReason, nextAllowedCheck, lastStatus);
    }
    UpdatePreferences withMandatory(SemanticVersion version, String reason) {
        return new UpdatePreferences(lastCheck, lastAttemptedVersion, skippedVersion, remindVersion, remindUntil, checkInterval, remindInterval,
                version, version == null ? null : reason, nextAllowedCheck, lastStatus);
    }
    UpdatePreferences withNextAllowedCheck(Instant time) {
        return new UpdatePreferences(lastCheck, lastAttemptedVersion, skippedVersion, remindVersion, remindUntil, checkInterval, remindInterval,
                mandatoryVersion, mandatoryReason, time, lastStatus);
    }

    private static Instant instant(Properties p, String key) {
        try { return p.getProperty(key) == null ? null : Instant.parse(p.getProperty(key)); } catch (RuntimeException ex) { return null; }
    }
    private static SemanticVersion version(Properties p, String key) {
        try { return p.getProperty(key) == null ? null : SemanticVersion.parse(p.getProperty(key)); } catch (RuntimeException ex) { return null; }
    }
    private static Duration hours(Properties p, String key) {
        try { return p.getProperty(key) == null ? null : Duration.ofHours(Long.parseLong(p.getProperty(key))); } catch (RuntimeException ex) { return null; }
    }
    private static void put(Properties p, String key, Object value) { if (value != null) p.setProperty(key, value.toString()); }
}

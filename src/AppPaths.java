// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import javax.imageio.ImageIO;

/**
 * All application-owned files live in the current user's application-data directory:
 * {@code %LOCALAPPDATA%} on Windows, {@code ~/Library/Application Support} on macOS and
 * {@code $XDG_DATA_HOME} (default {@code ~/.local/share}) on Linux. Never inside the installation directory.
 */
public final class AppPaths {
    private static final Path LOCAL = resolveLocalData();
    public static final Path ROOT = LOCAL.resolve("RatioJuris").resolve("BulkFiledSigner");
    public static final Path CONFIG = ROOT.resolve("config");
    public static final Path STAMPS = CONFIG.resolve("stamps");
    /** Additional trusted root certificates added by the user, for example a national CA root. */
    public static final Path TRUST = CONFIG.resolve("trusted-roots");
    public static final Path PLACEHOLDERS = ROOT.resolve("placeholders");
    public static final Path IMPORTS = ROOT.resolve("imports");
    public static final Path OUTPUTS = ROOT.resolve("output");
    public static final Path TEMP = ROOT.resolve("temp");
    public static final Path LOGS = ROOT.resolve("logs");
    public static final Path CACHE = ROOT.resolve("cache");
    public static final Path UPDATES = ROOT.resolve("updates");

    private AppPaths() { }

    private static Path resolveLocalData() {
        Path local = Platform.dataHome();
        if (!local.isAbsolute()) throw new IllegalStateException("The application-data directory must be an absolute path.");
        return local.normalize();
    }

    /** Called before initializing PDF, image, font, or logging libraries. */
    public static void initialize() throws IOException {
        Files.createDirectories(ROOT);
        // Documents and settings are private to the user on POSIX systems.
        if (Files.getFileStore(ROOT).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(ROOT, PosixFilePermissions.fromString("rwx------"));
        }
        for (Path directory : new Path[] {ROOT, CONFIG, STAMPS, TRUST, PLACEHOLDERS, IMPORTS, OUTPUTS, TEMP, LOGS, CACHE, UPDATES}) {
            requireLocal(directory);
            Files.createDirectories(directory);
            requireLocal(directory);
        }
        System.setProperty("java.io.tmpdir", TEMP.toString());
        System.setProperty("pdfbox.fontcache", CACHE.toString());
        System.setProperty("jna.tmpdir", TEMP.toString());
        System.setProperty("jna.nosys", "true");
        ImageIO.setUseCache(false);
    }

    /** Reject traversal and existing junctions/symlinks that escape the application-data directory. */
    public static Path requireLocal(Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(ROOT)) throw new IOException("Application files must be inside " + ROOT);
        Path expectedRoot = LOCAL.toRealPath().resolve("RatioJuris").resolve("BulkFiledSigner");
        Path existing = normalized;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null) throw new IOException("Application-data directory is unavailable: " + ROOT);
        Path resolved = existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
        if (!resolved.startsWith(expectedRoot)) throw new IOException("Application path redirects outside " + ROOT);
        return normalized;
    }
}

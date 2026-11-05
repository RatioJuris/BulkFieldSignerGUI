// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.nio.file.Path;
import java.util.Locale;

/** Operating system, CPU architecture and installation channel of the running copy. */
public final class Platform {
    public enum Os { WINDOWS, MACOS, LINUX }
    public enum Arch { X64, ARM64 }

    /**
     * How this copy was installed. Set by the package launcher through {@code -Dpdfsigner.channel};
     * a missing value means a source build installed by {@code build.bat}.
     */
    public enum Channel {
        SOURCE("source", "Source build"),
        WINDOWS_PORTABLE("windows-portable", "Portable (Windows)"),
        WINDOWS_MSI("windows-msi", "Windows Installer (MSI)"),
        WINDOWS_STORE("windows-store", "Microsoft Store"),
        LINUX_PORTABLE("linux-portable", "Portable (Linux)"),
        LINUX_DEB("linux-deb", "Debian package"),
        MACOS_DMG("macos-dmg", "macOS disk image");

        private final String id;
        private final String label;
        Channel(String id, String label) { this.id = id; this.label = label; }
        public String id() { return id; }
        @Override public String toString() { return label; }

        static Channel parse(String value) {
            if (value == null || value.isBlank()) return SOURCE;
            for (Channel channel : values()) if (channel.id.equals(value.strip())) return channel;
            throw new IllegalStateException("Unknown installation channel: " + value);
        }
    }

    private Platform() { }

    public static Os os() { return os(System.getProperty("os.name", "")); }

    static Os os(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("windows")) return Os.WINDOWS;
        if (n.startsWith("mac") || n.startsWith("darwin")) return Os.MACOS;
        if (n.startsWith("linux")) return Os.LINUX;
        throw new IllegalStateException("Unsupported operating system: " + name);
    }

    public static Arch arch() { return arch(System.getProperty("os.arch", "")); }

    static Arch arch(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> Arch.X64;
            case "aarch64", "arm64" -> Arch.ARM64;
            default -> throw new IllegalStateException("Unsupported CPU architecture: " + name);
        };
    }

    public static Channel channel() { return Channel.parse(System.getProperty("pdfsigner.channel")); }

    /** For example {@code windows-x64}. */
    public static String id() { return id(os(), arch()); }

    static String id(Os os, Arch arch) { return os.name().toLowerCase(Locale.ROOT) + "-" + arch.name().toLowerCase(Locale.ROOT); }

    /**
     * The user's Documents folder (or home folder), where file dialogs start. Uses the real profile from the
     * environment, because source builds run with {@code user.home} redirected to the application folder.
     */
    public static Path documentsFolder() {
        String home = os() == Os.WINDOWS ? System.getenv("USERPROFILE") : System.getenv("HOME");
        Path base = home == null || home.isBlank() ? Path.of(System.getProperty("user.home")) : Path.of(home);
        Path documents = base.resolve("Documents");
        return java.nio.file.Files.isDirectory(documents) ? documents : base;
    }

    /** The per-user application-data base directory of the current operating system. */
    static Path dataHome() {
        String home = System.getProperty("user.home");
        return switch (os()) {
            case WINDOWS -> {
                String local = System.getenv("LOCALAPPDATA");
                yield local == null || local.isBlank() ? Path.of(home, "AppData", "Local") : Path.of(local);
            }
            case MACOS -> Path.of(home, "Library", "Application Support");
            case LINUX -> {
                String xdg = System.getenv("XDG_DATA_HOME");
                // The XDG specification ignores relative values.
                yield xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute() ? Path.of(xdg) : Path.of(home, ".local", "share");
            }
        };
    }
}

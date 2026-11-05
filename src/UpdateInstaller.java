// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.jar.JarFile;

/**
 * Applies a verified package using the mechanism of the installation channel. The running copy is never
 * overwritten: portable updates are installed side by side, MSI updates run after this process exits, and
 * Linux/macOS packages are handed to the operating system's installer.
 */
final class UpdateInstaller {
    private static final System.Logger LOG = System.getLogger("updates");
    static final String APP_FOLDER = Product.NAME;
    static final String JAR = "BulkFiledSigner.jar";

    enum Outcome {
        /** The new copy is running; this process should now exit. */
        RESTARTING,
        /** Windows Installer will run as soon as this process exits, then start the new version. */
        EXIT_FOR_INSTALLER,
        /** The operating system's installer was opened; the user completes it and restarts the application. */
        HANDED_TO_SYSTEM
    }

    record Result(Outcome outcome, String message) { }

    private final Path markers;

    UpdateInstaller(Path markers) { this.markers = markers; }

    Result install(Platform.Channel channel, Path verifiedPackage, UpdateManifest manifest, Duration launchTimeout) throws UpdateException {
        LOG.log(System.Logger.Level.INFO, "event=update.install.started channel={0} version={1}", channel.id(), manifest.version());
        try {
            Result result = switch (channel) {
                case WINDOWS_PORTABLE, LINUX_PORTABLE -> installPortable(channel, verifiedPackage, manifest.version(), launchTimeout);
                case WINDOWS_MSI -> installMsi(verifiedPackage, manifest.version());
                case LINUX_DEB, MACOS_DMG -> openWithSystem(verifiedPackage, manifest.version());
                case WINDOWS_STORE, SOURCE -> throw new UpdateException(UpdateException.Category.INSTALL, "This copy is not updated by downloading packages.");
            };
            LOG.log(System.Logger.Level.INFO, "event=update.install.completed channel={0} version={1} outcome={2}", channel.id(), manifest.version(), result.outcome());
            return result;
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "event=update.install.failed channel={0} version={1} reason={2}", channel.id(), manifest.version(), ex.getMessage());
            throw new UpdateException(UpdateException.Category.INSTALL, "The update could not be installed: " + ex.getMessage(), ex);
        }
    }

    // Portable: side-by-side folder, verified, started, confirmed.

    private Result installPortable(Platform.Channel channel, Path archive, SemanticVersion version, Duration launchTimeout)
            throws IOException, UpdateException {
        Path current = currentInstallation(channel);
        Path parent = current.getParent();
        if (parent == null || !Files.isWritable(parent)) {
            throw new UpdateException(UpdateException.Category.INSTALL, "The folder containing this copy (" + parent + ") is not writable. "
                    + "Download the portable package from the release page and extract it somewhere writable.");
        }
        String top = APP_FOLDER + " " + version;
        Path staging = Files.createTempDirectory(parent, ".pdf-dsc-tsa-update-");
        Path installed = null;
        try {
            UpdateArchive.extract(archive, top, staging);
            verifyImage(staging, channel, version);
            installed = parent.resolve(top);
            for (int n = 2; Files.exists(installed); n++) installed = parent.resolve(top + " (" + n + ")");
            Files.move(staging, installed, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
            Path launcher = launcher(installed, channel);
            Instant started = Instant.now();
            Process process = new ProcessBuilder(launcher.toString()).directory(installed.toFile()).start();
            if (!awaitLaunch(version, started, launchTimeout)) {
                process.destroy();
                process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                deleteTree(installed);
                throw new UpdateException(UpdateException.Category.INSTALL, "Version " + version + " did not start. This copy remains in use.");
            }
            return new Result(Outcome.RESTARTING, "Version " + version + " is installed in " + installed
                    + ". Your previous copy remains in " + current + " and can be deleted.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UpdateException(UpdateException.Category.CANCELLED, "Interrupted.", ex);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            throw new UpdateException(UpdateException.Category.INSTALL, "Version " + version + " did not stop cleanly after a failed start.", ex);
        } finally {
            if (staging != null) deleteTree(staging);
        }
    }

    /** The folder of the running portable copy, derived from its launcher. */
    static Path currentInstallation(Platform.Channel channel) throws UpdateException {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null) throw new UpdateException(UpdateException.Category.INSTALL, "The application launcher could not be located.");
        Path launcher = Path.of(appPath).toAbsolutePath().normalize();
        // Windows images keep the launcher at the top; Linux images keep it in bin/.
        return channel == Platform.Channel.WINDOWS_PORTABLE ? launcher.getParent() : launcher.getParent().getParent();
    }

    static Path launcher(Path image, Platform.Channel channel) {
        return channel == Platform.Channel.WINDOWS_PORTABLE ? image.resolve(APP_FOLDER + ".exe") : image.resolve("bin").resolve(APP_FOLDER);
    }

    /** The extracted image must be the expected version, built for this channel, and complete. */
    static void verifyImage(Path image, Platform.Channel channel, SemanticVersion version) throws IOException, UpdateException {
        Path app = channel == Platform.Channel.WINDOWS_PORTABLE ? image.resolve("app") : image.resolve("lib").resolve("app");
        Path launcher = launcher(image, channel);
        if (!Files.isRegularFile(launcher)) throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The package has no application launcher.");
        if (!Files.isRegularFile(app.resolve(JAR))) throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The package has no application.");
        try (JarFile jar = new JarFile(app.resolve(JAR).toFile())) {
            String implementation = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes().getValue("Implementation-Version");
            if (implementation == null || !SemanticVersion.parse(implementation).equals(version)) {
                throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The package contains version " + implementation + ", not " + version + ".");
            }
        } catch (IllegalArgumentException ex) {
            throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The package has an invalid version.", ex);
        }
        Path config = app.resolve(APP_FOLDER + ".cfg");
        String expected = "-Dpdfsigner.channel=" + channel.id();
        if (!Files.isRegularFile(config) || Files.readAllLines(config).stream().noneMatch(line -> line.strip().endsWith(expected))) {
            throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The package was not built for " + channel + ".");
        }
        if (channel == Platform.Channel.LINUX_PORTABLE && !Files.isExecutable(launcher)) {
            throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The package's launcher is not executable.");
        }
    }

    // MSI: Windows Installer upgrade after this process exits.

    private Result installMsi(Path msi, SemanticVersion version) throws IOException, UpdateException {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null) throw new UpdateException(UpdateException.Category.INSTALL, "The installed application could not be located.");
        Path log = AppPaths.LOGS.resolve("msi-update-" + version + ".log");
        // Paths are produced by this application, never taken from release metadata. Windows paths cannot contain '"'.
        String arguments = "/i \"" + msi.toAbsolutePath() + "\" /passive /norestart /l*v \"" + log + "\"";
        String script = "$ErrorActionPreference = 'Stop'; Wait-Process -Id " + ProcessHandle.current().pid() + " -ErrorAction SilentlyContinue; "
                + "$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList " + powershellString(arguments) + " -Wait -PassThru; "
                + "if ($p.ExitCode -eq 0 -or $p.ExitCode -eq 3010) { Start-Process -FilePath " + powershellString(Path.of(appPath).toString()) + " }";
        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(AppPaths.LOGS.resolve("application.log").toFile())).start();
        return new Result(Outcome.EXIT_FOR_INSTALLER, "Windows Installer will install version " + version
                + " as soon as this window closes, then start it again. Installer log: " + log);
    }

    static String powershellString(String value) { return "'" + value.replace("'", "''") + "'"; }

    // Linux .deb and macOS .dmg: the operating system's installer completes the update.

    private Result openWithSystem(Path file, SemanticVersion version) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(file.toFile());
        else new ProcessBuilder(Platform.os() == Platform.Os.MACOS ? "open" : "xdg-open", file.toString()).start();
        String how = Platform.os() == Platform.Os.MACOS
                ? "Drag the application from the opened disk image into Applications, replacing the old copy, then start it again."
                : "Install it with your software installer (administrator rights may be required), then start the application again.";
        return new Result(Outcome.HANDED_TO_SYSTEM, "The verified package for version " + version + " has been opened: " + file + ". " + how);
    }

    // Launch confirmation shared by old and new versions through the per-user data directory.

    /** Called by a newly started application once its window is shown. */
    static void recordLaunch(Path markers) {
        try {
            Files.createDirectories(markers);
            LocalFiles.atomicWrite(markers.resolve("launched-" + SemanticVersion.current() + ".ok"), Instant.now().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "event=update.launch.marker.failed reason={0}", ex.getMessage());
        }
    }

    boolean awaitLaunch(SemanticVersion version, Instant since, Duration timeout) throws InterruptedException {
        Path marker = markers.resolve("launched-" + version + ".ok");
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (Files.exists(marker) && !Files.getLastModifiedTime(marker).toInstant().isBefore(since.minusSeconds(2))) return true;
            } catch (IOException ignored) { /* Retry until the deadline. */ }
            Thread.sleep(500);
        }
        return false;
    }

    static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true); // Read-only launchers on Windows.
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "event=update.cleanup.failed path={0} reason={1}", root, ex.getMessage());
        }
    }
}

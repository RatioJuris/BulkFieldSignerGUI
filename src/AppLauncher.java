// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.Properties;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class AppLauncher {
    private AppLauncher() { }

    public static void main(String[] args) {
        try {
            boolean localTemp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().equals(AppPaths.TEMP);
            AppPaths.initialize();
            Platform.channel(); // Rejects an unknown package channel before anything else starts.
            if (!"Microsoft".equals(System.getProperty("java.vendor")) || Runtime.version().feature() != 25) {
                throw new IllegalStateException("This application requires the Microsoft Build of OpenJDK 25. "
                        + "Use a release package, or run.bat for a source build.");
            }
            // Packaged launchers (jpackage) start the bundled runtime directly; AppPaths.initialize has already
            // redirected temporary files. A double-clicked source-build JAR may inherit another working directory
            // and JVM temp cache, so it is relaunched with the installed runtime.
            boolean packaged = System.getProperty("jpackage.app-version") != null;
            if (!packaged && (!localTemp || !Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().equals(AppPaths.ROOT))) {
                Path jar = Path.of(AppLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (!Files.isRegularFile(jar)) throw new IllegalStateException("Launch the installed application JAR using run.bat.");
                ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString(),
                        "--enable-native-access=ALL-UNNAMED",
                        "-Djava.io.tmpdir=" + AppPaths.TEMP, "-Duser.home=" + AppPaths.ROOT,
                        "-XX:ErrorFile=" + AppPaths.LOGS.resolve("hs_err_pid%p.log"), "-jar", jar.toString())
                        .directory(AppPaths.ROOT.toFile()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(AppPaths.LOGS.resolve("application.log").toFile()));
                process.environment().put("TEMP", AppPaths.TEMP.toString());
                process.environment().put("TMP", AppPaths.TEMP.toString());
                for (String variable : java.util.List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
                    process.environment().remove(variable);
                }
                process.start();
                return;
            }
            Path smokeReport = smokeReport(args);
            if (smokeReport != null) { smokeTest(smokeReport); return; }
            startUi().exceptionally(failure -> {
                showStartupFailure(failure);
                return null;
            });
        } catch (Exception ex) {
            Path smokeReport = smokeReport(args);
            if (smokeReport != null) { writeSmoke(smokeReport, "result=FAILED\nerror=" + ex); System.exit(1); }
            showStartupFailure(ex);
        }
    }

    /** {@code --smoke-test=<report file>}: used by release verification to prove a package starts. */
    private static Path smokeReport(String[] args) {
        for (String arg : args) if (arg.startsWith("--smoke-test=")) return Path.of(arg.substring("--smoke-test=".length())).toAbsolutePath();
        return null;
    }

    /**
     * Starts the real application, waits for the main window and the startup update check, writes a report
     * and exits. No document is opened and nothing is signed.
     */
    private static void smokeTest(Path report) {
        startUi().whenComplete((gui, failure) -> Thread.ofPlatform().name("smoke-test").start(() -> {
            if (failure != null) { writeSmoke(report, "result=FAILED\nerror=" + failure); System.exit(1); }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline && (gui.lastUpdateStatus().isBlank() || gui.lastUpdateStatus().startsWith("Checking"))) {
                try { Thread.sleep(250); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
            }
            boolean[] visible = new boolean[1];
            try { SwingUtilities.invokeAndWait(() -> visible[0] = gui.isShowing()); } catch (Exception ignored) { /* Reported as not visible. */ }
            writeSmoke(report, "result=" + (visible[0] ? "OK" : "FAILED") + "\nproduct=" + Product.NAME + "\nversion=" + Product.VERSION
                    + "\nchannel=" + Platform.channel().id() + "\nplatform=" + Platform.id() + "\njava=" + System.getProperty("java.vendor") + " "
                    + Runtime.version() + "\nmainWindowVisible=" + visible[0] + "\ndataDirectory=" + AppPaths.ROOT
                    + "\nupdateStatus=" + gui.lastUpdateStatus().replace('\n', ' '));
            SwingUtilities.invokeLater(gui::exitForUpdate);
        }));
    }

    private static void writeSmoke(Path report, String text) {
        try { java.nio.file.Files.writeString(report, text + "\n"); }
        catch (java.io.IOException ex) { System.err.println("Smoke report could not be written: " + ex); }
    }

    /** Completion is signalled only after the main window is visible and the splash has closed. */
    public static CompletableFuture<BulkFieldSignerGUI> startUi() {
        CompletableFuture<BulkFieldSignerGUI> ready = new CompletableFuture<>();
        Thread.ofPlatform().name("startup-preferences").start(() -> {
            try {
                AppPaths.initialize();
                List<String> warnings = new ArrayList<>();
                UiPreferences preferences;
                try { preferences = UiPreferences.load(); }
                catch (java.io.IOException | IllegalArgumentException ex) {
                    preferences = UiPreferences.DEFAULT;
                    warnings.add("Appearance preferences could not be loaded: " + ex.getMessage());
                }
                var detected = WindowsThemeDetector.detectDarkMode();
                if (detected.isEmpty() && Platform.os() == Platform.Os.WINDOWS) {
                    warnings.add("Windows appearance could not be detected; System default uses Light until detection succeeds.");
                }
                UiPreferences initial = preferences;
                SwingUtilities.invokeLater(() -> initializeBackend(initial, detected.orElse(false), warnings, ready));
            } catch (Exception ex) { ready.completeExceptionally(ex); }
        });
        return ready;
    }

    private static void initializeBackend(UiPreferences preferences, boolean dark, List<String> warnings,
                                          CompletableFuture<BulkFieldSignerGUI> ready) {
        ThemeManager themes;
        try {
            themes = new ThemeManager(preferences, dark);
        } catch (RuntimeException ex) { ready.completeExceptionally(ex); return; }
        SplashScreen splash;
        try {
            splash = new SplashScreen();
            splash.setVisible(true);
        } catch (RuntimeException ex) { themes.close(); ready.completeExceptionally(ex); return; }
        new SwingWorker<StartupData, String>() {
            @Override protected StartupData doInBackground() throws Exception {
                publish("Initializing cryptographic services…");
                Security.addProvider(new BouncyCastleProvider());
                publish("Preparing document rendering and fonts…");
                Class.forName("org.apache.pdfbox.Loader");
                StampRenderer.preload();
                Properties settings;
                try { settings = SettingsManager.load(); }
                catch (java.io.IOException | IllegalArgumentException ex) {
                    settings = new Properties();
                    warnings.add("Signing settings could not be loaded: " + ex.getMessage());
                }
                publish("Checking your signing certificates…");
                RegistryContext registry;
                try { registry = KeystoreMonitorThread.readRegistry(); }
                catch (Exception ex) {
                    registry = new RegistryContext(List.of());
                    warnings.add("Certificate store unavailable: " + ex.getMessage());
                }
                UpdatePreferences updatePreferences;
                try { updatePreferences = UpdatePreferences.load(); }
                catch (java.io.IOException | RuntimeException ex) {
                    updatePreferences = UpdatePreferences.defaults();
                    warnings.add("Update preferences could not be loaded: " + ex.getMessage());
                }
                return new StartupData(settings, registry, updatePreferences);
            }
            @Override protected void process(List<String> messages) { splash.updateStatus(messages.getLast()); }
            @Override protected void done() {
                try {
                    StartupData data = get();
                    BulkFieldSignerGUI gui = new BulkFieldSignerGUI(data.settings(), themes, data.registry());
                    // Same event-thread task as window creation: a pending mandatory update applies before any input.
                    UpdateService updates = new UpdateService(new UpdateService.Host() {
                        @Override public boolean isBusy() { return gui.isBusy(); }
                        @Override public String busyDescription() { return gui.busyDescription(); }
                        @Override public void onUpdateStatus(String text) { gui.onUpdateStatus(text); }
                        @Override public void setUpdateCheckEnabled(boolean enabled) { gui.setUpdateCheckEnabled(enabled); }
                        @Override public java.awt.Window window() { return gui; }
                        @Override public void exitForUpdate() { gui.exitForUpdate(); }
                    }, data.updatePreferences());
                    gui.attachUpdates(updates);
                    updates.start();
                    warnings.forEach(gui::onLog);
                    splash.dispose();
                    ready.complete(gui);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    themes.close();
                    ready.completeExceptionally(ex);
                } catch (ExecutionException | RuntimeException ex) {
                    themes.close();
                    ready.completeExceptionally(ex instanceof ExecutionException ? ex.getCause() : ex);
                } finally { splash.dispose(); }
            }
        }.execute();
    }

    private static void showStartupFailure(Throwable failure) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                "Startup failed: " + failure.getMessage() + "\nApplication data: " + AppPaths.ROOT,
                Product.NAME, JOptionPane.ERROR_MESSAGE));
    }

    private record StartupData(Properties settings, RegistryContext registry, UpdatePreferences updatePreferences) { }
}

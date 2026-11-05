// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** All theme transitions occur on the EDT; registry reads and preference writes never do. */
public final class ThemeManager implements AutoCloseable {
    private static boolean defaultsRegistered;
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "windows-theme-watch");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "ui-preference-writer"));
    private UiPreferences preferences;
    private boolean windowsDark;
    private boolean appliedDark;
    private boolean closed;
    private long revision;
    private Consumer<String> status = message -> { };

    public ThemeManager(UiPreferences preferences, boolean windowsDark) {
        requireEdt();
        this.preferences = preferences;
        this.windowsDark = windowsDark;
        apply();
        watcher.scheduleWithFixedDelay(() -> WindowsThemeDetector.detectDarkMode().ifPresent(dark ->
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    this.windowsDark = dark;
                    if (preferences().theme() == ThemeMode.SYSTEM && appliedDark != dark) apply();
                })), 5, 5, TimeUnit.SECONDS);
    }

    public UiPreferences preferences() { requireEdt(); return preferences; }
    public void onSaveStatus(Consumer<String> listener) { requireEdt(); status = listener; }

    public void select(ThemeMode mode) {
        requireEdt();
        if (closed || preferences.theme() == mode) return;
        preferences = new UiPreferences(mode, preferences.width(), preferences.height(), preferences.maximized());
        apply();
        persist();
    }

    public void rememberWindow(int width, int height, boolean maximized) {
        requireEdt();
        if (closed) return;
        preferences = new UiPreferences(preferences.theme(), width, height, maximized);
        persist();
    }

    public static void install(boolean dark) {
        requireEdt();
        if (!defaultsRegistered) {
            FlatLaf.registerCustomDefaultsSource("themes");
            boolean decorated = Platform.os() != Platform.Os.MACOS; // macOS keeps its native title bar.
            JFrame.setDefaultLookAndFeelDecorated(decorated);
            JDialog.setDefaultLookAndFeelDecorated(decorated);
            defaultsRegistered = true;
        }
        try { UIManager.setLookAndFeel(dark ? new FlatDarkLaf() : new FlatLightLaf()); }
        catch (javax.swing.UnsupportedLookAndFeelException ex) { throw new IllegalStateException("Cannot initialize appearance", ex); }
        FlatLaf.updateUI();
    }

    private void apply() {
        appliedDark = preferences.theme().isDark(windowsDark);
        install(appliedDark);
    }

    private void persist() {
        UiPreferences snapshot = preferences;
        long request = ++revision;
        status.accept("Saving preferences…");
        writer.execute(() -> {
            String result;
            try { snapshot.save(); result = "Preferences saved on this device"; }
            catch (java.io.IOException ex) { result = "Could not save preferences: " + ex.getMessage(); }
            String message = result;
            SwingUtilities.invokeLater(() -> { if (!closed && request == revision) status.accept(message); });
        });
    }

    @Override public void close() {
        requireEdt();
        if (closed) return;
        closed = true;
        watcher.shutdownNow();
        writer.shutdown(); // Finish queued atomic saves without blocking window disposal.
        status = message -> { };
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Theme state belongs to the EDT.");
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Coordinates update checks, the update dialog and installation. Network and disk work runs on a single
 * background worker; all state and UI changes happen on the Swing event thread. Kept separate from signing:
 * it only consults the host's busy state and blocks new operations through {@link UpdateStateManager}.
 */
public final class UpdateService {
    /** What the update service needs from the main window. */
    public interface Host {
        boolean isBusy();
        String busyDescription();
        void onUpdateStatus(String text);
        void setUpdateCheckEnabled(boolean enabled);
        java.awt.Window window();
        /** Closes the application normally so a new version or the installer can take over. */
        void exitForUpdate();
    }

    private static final System.Logger LOG = System.getLogger("updates");
    private static final Duration MANUAL_SPACING = Duration.ofSeconds(30);
    private static final Duration LAUNCH_TIMEOUT = Duration.ofSeconds(90);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final Host host;
    private final UpdateStateManager state = new UpdateStateManager();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "update-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Platform.Channel channel = Platform.channel();
    private final String platformId = Platform.id();
    private final SemanticVersion installed = SemanticVersion.current();
    private final UpdateHttp http = UpdateHttp.production();
    private final UpdateDownloader downloader = new UpdateDownloader(http);
    private final UpdateInstaller installer = new UpdateInstaller(AppPaths.UPDATES);
    private UpdatePreferences preferences = UpdatePreferences.defaults();
    private UpdateDialog dialog;
    private UpdateDecision.Outcome offer;
    private boolean checking;
    private boolean installing;
    private Instant lastRequest;

    /** {@code initial} is loaded before the main window exists, so a remembered requirement applies at once. */
    public UpdateService(Host host, UpdatePreferences initial) {
        this.host = host;
        this.preferences = initial;
    }

    public UpdateStateManager state() { return state; }

    /** Startup, on the event thread: restores a pending mandatory requirement, confirms this launch, then checks if due. */
    public void start() {
        SemanticVersion required = preferences.mandatoryVersion();
        if (required != null && !required.isNewerThan(installed)) {
            preferences = preferences.withMandatory(null, null);
            save();
        } else if (required != null) {
            // A known Type A requirement stays in force across restarts, even offline.
            state.set(UpdateStateManager.State.MANDATORY_UPDATE_REQUIRED, true, "Version " + required + " or newer is required.");
            dialog(UpdateType.A).showPending(required, preferences.mandatoryReason(), installed);
            showDialog();
        }
        worker.execute(() -> {
            UpdateInstaller.recordLaunch(AppPaths.UPDATES);
            cleanupDownloads();
        });
        if (state.mandatory() || preferences.checkDue(Instant.now())) check(false);
        else host.onUpdateStatus(preferences.lastCheck() == null ? "" : "Last checked " + TIME.format(preferences.lastCheck()) + ".");
    }

    /** Settings → About → Check for updates. */
    public void checkNow() { check(true, false); }

    private void check(boolean manual) { check(manual, false); }

    /** {@code retry}: an explicit Retry in the update dialog, which is not subject to the manual-check spacing. */
    private void check(boolean manual, boolean retry) {
        if (checking || installing) return;
        Instant now = Instant.now();
        if (preferences.nextAllowedCheck() != null && now.isBefore(preferences.nextAllowedCheck())) {
            String text = "GitHub's request limit was reached. Updates can be checked again after " + TIME.format(preferences.nextAllowedCheck()) + ".";
            host.onUpdateStatus(text);
            if (dialog != null) dialog.showFailure(new UpdateException(UpdateException.Category.RATE_LIMITED, text), true);
            return;
        }
        if (manual && !retry && lastRequest != null && Duration.between(lastRequest, now).compareTo(MANUAL_SPACING) < 0) {
            host.onUpdateStatus("Checked moments ago. Try again in a few seconds.");
            return;
        }
        checking = true;
        lastRequest = now;
        host.setUpdateCheckEnabled(false);
        host.onUpdateStatus("Checking for updates…");
        if (dialog != null && retry) dialog.showProgress("Checking GitHub for the release…", 0, 0);
        LOG.log(System.Logger.Level.INFO, "event=update.check.started installed={0} platform={1} channel={2} manual={3}", installed, platformId, channel.id(), manual);
        UpdatePreferences snapshot = preferences;
        worker.execute(() -> {
            Object result;
            try {
                UpdateDiscovery discovery = new UpdateDiscovery(new GitHubReleaseClient(http), UpdateTrust.publisherKey(), platformId, channel);
                var found = discovery.discover(installed, snapshot.mandatoryVersion());
                result = UpdateDecision.decide(installed, found.latestPublished(), channel, found.candidates(), found.problems(), snapshot, Instant.now(), manual);
            } catch (UpdateException ex) {
                result = ex;
            } catch (Exception ex) {
                result = new UpdateException(UpdateException.Category.INVALID_RESPONSE, "Unexpected update error: " + ex.getMessage(), ex);
            }
            Object outcome = result;
            SwingUtilities.invokeLater(() -> handle(outcome));
        });
    }

    private void handle(Object result) {
        checking = false;
        host.setUpdateCheckEnabled(true);
        if (result instanceof UpdateException failure) {
            LOG.log(System.Logger.Level.WARNING, "event=update.check.failed category={0} http={1} reason={2}", failure.category().name(), failure.httpStatus(), failure.getMessage());
            if (failure.category() == UpdateException.Category.RATE_LIMITED && failure.retryAfter() != null) {
                preferences = preferences.withNextAllowedCheck(failure.retryAfter());
                save();
            }
            host.onUpdateStatus("Update check failed: " + failure.category() + ". " + failure.getMessage());
            if (state.mandatory()) state.set(UpdateStateManager.State.UPDATE_FAILED, true, failure.getMessage());
            if (dialog != null) { dialog.setState(state.state()); dialog.showFailure(failure, true); }
            return;
        }
        UpdateDecision.Outcome outcome = (UpdateDecision.Outcome) result;
        LOG.log(System.Logger.Level.INFO, "event=update.check.completed installed={0} result={1} available={2} type={3}", installed, outcome.kind(),
                outcome.target() == null ? "-" : outcome.target().version(), outcome.type() == null ? "-" : outcome.type().name());
        preferences = preferences.withCheck(Instant.now(), outcome.kind().name());
        switch (outcome.kind()) {
            case UP_TO_DATE, INSTALLED_NEWER, SUPPRESSED -> {
                // A successful check without a newer Type A release ends a remembered requirement.
                preferences = preferences.withMandatory(null, null);
                boolean available = outcome.kind() == UpdateDecision.Kind.SUPPRESSED;
                state.set(available ? UpdateStateManager.State.UPDATE_AVAILABLE : UpdateStateManager.State.UP_TO_DATE, false, outcome.detail());
                if (dialog != null && dialog.type() == UpdateType.A) closeDialog();
                host.onUpdateStatus(outcome.detail());
            }
            case METADATA_PROBLEM -> {
                host.onUpdateStatus(outcome.detail());
                if (state.mandatory() && dialog != null) {
                    state.set(UpdateStateManager.State.UPDATE_FAILED, true, outcome.detail());
                    dialog.setState(state.state());
                    dialog.showFailure(new UpdateException(UpdateException.Category.METADATA, outcome.detail()), true);
                }
            }
            case OFFER -> {
                offer = outcome;
                if (outcome.mandatory()) {
                    preferences = preferences.withMandatory(outcome.mandatoryVersion(), outcome.mandatoryReason());
                    state.set(UpdateStateManager.State.MANDATORY_UPDATE_REQUIRED, true, "Version " + outcome.mandatoryVersion() + " or newer is required.");
                    host.onUpdateStatus((outcome.securityUpdate() ? "Security" : "Critical") + " update " + outcome.target().version() + " is required.");
                } else {
                    state.set(UpdateStateManager.State.UPDATE_AVAILABLE, false, "Version " + outcome.target().version() + " is available.");
                    host.onUpdateStatus(outcome.type() + " " + outcome.target().version() + " is available.");
                }
                UpdateDialog shown = dialog(outcome.type());
                shown.setState(state.state());
                shown.showOffer(outcome, installed);
                showDialog();
            }
        }
        save();
    }

    private UpdateDialog dialog(UpdateType type) {
        if (dialog != null && dialog.type() != type) closeDialog();
        if (dialog == null) dialog = new UpdateDialog(host.window(), type, this::act);
        return dialog;
    }

    private void showDialog() {
        UpdateDialog shown = dialog;
        // Deferred so that a modal (Type A) dialog never blocks the caller.
        if (!shown.isVisible()) SwingUtilities.invokeLater(() -> { if (dialog == shown) shown.setVisible(true); });
    }

    private void closeDialog() {
        if (dialog != null) { dialog.dispose(); dialog = null; }
    }

    private void act(UpdateDialog.Action action) {
        switch (action) {
            case INSTALL, UPDATE_NOW -> install();
            case RETRY -> { if (offer != null && offer.installable()) install(); else check(true, true); }
            case REMIND_LATER, CLOSE -> postpone();
            case SKIP -> skip();
            case RELEASE_NOTES -> browse(offer != null ? offer.target().manifest().releaseNotesUrl() : URI.create(Product.REPOSITORY + "/releases"));
            case EXIT -> exitApplication();
            case DETAILS -> { }
        }
    }

    private void postpone() {
        if (offer == null || offer.mandatory()) return; // A mandatory update can never be postponed.
        SemanticVersion version = offer.target().version();
        preferences = preferences.withReminder(version, Instant.now().plus(preferences.remindInterval()));
        save();
        LOG.log(System.Logger.Level.INFO, "event=update.postponed version={0} until={1}", version, preferences.remindUntil());
        host.onUpdateStatus("Version " + version + " postponed until " + TIME.format(preferences.remindUntil()) + ".");
        closeDialog();
    }

    private void skip() {
        if (offer == null || !offer.type().skippable()) return; // Only Type C may be skipped.
        SemanticVersion version = offer.target().version();
        preferences = preferences.withSkipped(version);
        save();
        LOG.log(System.Logger.Level.INFO, "event=update.skipped version={0}", version);
        host.onUpdateStatus("Version " + version + " skipped. Newer versions will still be offered.");
        closeDialog();
    }

    private void exitApplication() {
        if (host.isBusy()) { if (dialog != null) dialog.showStatus("Waiting for: " + host.busyDescription() + ". Exit is available when it finishes."); return; }
        LOG.log(System.Logger.Level.INFO, "event=update.exit.requested mandatory={0}", state.mandatory());
        closeDialog();
        host.exitForUpdate();
    }

    private void install() {
        if (offer == null || installing) return;
        if (!offer.installable()) {
            browse(channel == Platform.Channel.WINDOWS_STORE ? URI.create("ms-windows-store://downloadsandupdates") : offer.target().manifest().releaseNotesUrl());
            if (dialog != null) dialog.showStatus(offer.detail());
            return;
        }
        UpdateDecision.Candidate target = offer.target();
        UpdateManifest.Asset expected = target.manifest().assetFor(platformId, channel);
        boolean mandatory = offer.mandatory();
        installing = true;
        dialog.setWorking(true);
        preferences = preferences.withAttempt(target.version());
        save();
        state.set(UpdateStateManager.State.DOWNLOADING, mandatory, "Downloading " + target.version() + "…");
        dialog.setState(state.state());
        dialog.showProgress("Downloading version " + target.version() + "…", 0, expected.size());
        long[] lastPost = {0};
        worker.execute(() -> {
            try {
                Path verified = downloader.download(target.download(), expected, AppPaths.UPDATES, bytes -> {
                    long now = System.nanoTime();
                    if (now - lastPost[0] > 200_000_000L || bytes == expected.size()) {
                        lastPost[0] = now;
                        SwingUtilities.invokeLater(() -> { if (dialog != null) dialog.showProgress("Downloading version " + target.version() + "…", bytes, expected.size()); });
                    }
                }, () -> false);
                SwingUtilities.invokeLater(() -> {
                    state.set(UpdateStateManager.State.VERIFYING, mandatory, "Verified.");
                    dialog.setState(state.state());
                    dialog.showProgress("Verified: publisher signature and SHA-256 match.", 0, 0);
                    whenIdle(() -> applyVerified(verified, target, mandatory));
                });
            } catch (UpdateException ex) {
                SwingUtilities.invokeLater(() -> failed(ex, mandatory));
            }
        });
    }

    /** Waits on the event thread until no signing or document operation is running, then continues. */
    private void whenIdle(Runnable next) {
        if (!host.isBusy()) { next.run(); return; }
        dialog.showStatus("Waiting for the current operation to finish: " + host.busyDescription() + ".");
        Timer timer = new Timer(500, null);
        timer.addActionListener(event -> {
            if (host.isBusy()) return;
            timer.stop();
            next.run();
        });
        timer.start();
    }

    private void applyVerified(Path verified, UpdateDecision.Candidate target, boolean mandatory) {
        // Set on the event thread while idle: no new signing can start from here on.
        state.set(UpdateStateManager.State.INSTALLING, mandatory, "Installing " + target.version() + "…");
        dialog.setState(state.state());
        dialog.showProgress("Installing version " + target.version() + "…", 0, 0);
        worker.execute(() -> {
            try {
                UpdateInstaller.Result result = installer.install(channel, verified, target.manifest(), LAUNCH_TIMEOUT);
                SwingUtilities.invokeLater(() -> finished(result, mandatory));
            } catch (UpdateException ex) {
                SwingUtilities.invokeLater(() -> failed(ex, mandatory));
            }
        });
    }

    private void finished(UpdateInstaller.Result result, boolean mandatory) {
        installing = false;
        switch (result.outcome()) {
            case RESTARTING, EXIT_FOR_INSTALLER -> {
                state.set(UpdateStateManager.State.RESTART_REQUIRED, mandatory, result.message());
                dialog.setState(state.state());
                dialog.showFinished(result.message() + "\nThis window will close now.", false);
                LOG.log(System.Logger.Level.INFO, "event=update.restart outcome={0}", result.outcome());
                Timer timer = new Timer(2500, event -> { closeDialog(); host.exitForUpdate(); });
                timer.setRepeats(false);
                timer.start();
            }
            case HANDED_TO_SYSTEM -> {
                // Until the new version actually runs, a mandatory requirement stays in force.
                state.set(mandatory ? UpdateStateManager.State.MANDATORY_UPDATE_REQUIRED : UpdateStateManager.State.UPDATE_AVAILABLE, mandatory, result.message());
                dialog.setState(state.state());
                dialog.showFinished(result.message(), !mandatory);
            }
        }
        host.onUpdateStatus(result.message());
    }

    private void failed(UpdateException failure, boolean mandatory) {
        installing = false;
        LOG.log(System.Logger.Level.WARNING, "event=update.install.failed category={0} http={1} reason={2}", failure.category().name(), failure.httpStatus(), failure.getMessage());
        state.set(mandatory ? UpdateStateManager.State.UPDATE_FAILED : UpdateStateManager.State.UPDATE_AVAILABLE, mandatory, failure.getMessage());
        if (dialog != null) { dialog.setState(state.state()); dialog.showFailure(failure, true); }
        host.onUpdateStatus("Update failed: " + failure.getMessage());
    }

    private void browse(URI uri) {
        String text = uri.toString();
        // Only the canonical repository and the Store's update page are ever opened.
        if (!(text.startsWith(Product.REPOSITORY + "/") || text.equals("ms-windows-store://downloadsandupdates"))) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(uri);
            else if (dialog != null) dialog.showStatus("Open " + text + " in a browser.");
        } catch (Exception ex) {
            if (dialog != null) dialog.showStatus("Open " + text + " in a browser.");
        }
    }

    private void save() {
        UpdatePreferences snapshot = preferences;
        worker.execute(() -> {
            try { snapshot.save(); }
            catch (Exception ex) { LOG.log(System.Logger.Level.WARNING, "event=update.preferences.save.failed reason={0}", ex.getMessage()); }
        });
    }

    /** Removes interrupted downloads and verified packages older than a day; launch markers are kept. */
    private static void cleanupDownloads() {
        try (var files = Files.list(AppPaths.requireLocal(AppPaths.UPDATES))) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(1));
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".ok") || !Files.isRegularFile(file)) continue;
                if (name.endsWith(".part") || Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) Files.deleteIfExists(file);
            }
        } catch (Exception ex) {
            LOG.log(System.Logger.Level.DEBUG, "event=update.cleanup.skipped reason={0}", ex.getMessage());
        }
    }
}

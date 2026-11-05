// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.*;

/**
 * Presents an available update with only the actions its classification permits. A Type A dialog is
 * application-modal, ignores the close button and Escape, and has no skip, later or dismiss action.
 */
@SuppressWarnings("serial")
final class UpdateDialog extends JDialog {
    enum Action {
        INSTALL("Install Update"), UPDATE_NOW("Update Now"), RETRY("Retry"), REMIND_LATER("Remind Me Later"), SKIP("Skip This Version"),
        RELEASE_NOTES("View Release Notes"), DETAILS("Diagnostics"), EXIT("Exit Application"), CLOSE("Close");
        private final String label;
        Action(String label) { this.label = label; }
        String label() { return label; }
    }

    /** The actions offered for each classification. Type A has no way to postpone, skip or dismiss. */
    static List<Action> actionsFor(UpdateType type) {
        return switch (type) {
            case A -> List.of(Action.INSTALL, Action.RETRY, Action.RELEASE_NOTES, Action.DETAILS, Action.EXIT);
            case B -> List.of(Action.UPDATE_NOW, Action.REMIND_LATER, Action.RELEASE_NOTES, Action.RETRY, Action.DETAILS);
            case C -> List.of(Action.UPDATE_NOW, Action.SKIP, Action.REMIND_LATER, Action.RELEASE_NOTES, Action.RETRY, Action.DETAILS);
        };
    }

    /** Whether the window may be closed: never for Type A, and not while files are being replaced. */
    static boolean userMayClose(UpdateType type, UpdateStateManager.State state) {
        return type != UpdateType.A && state != UpdateStateManager.State.INSTALLING && state != UpdateStateManager.State.RESTART_REQUIRED;
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.systemDefault());
    private final UpdateType type;
    private final Consumer<Action> listener;
    private final Map<Action, JButton> buttons = new EnumMap<>(Action.class);
    private final JLabel title = UIComponentFactory.label("", "font: bold +6");
    private final JTextArea summary = UIComponentFactory.description("");
    private final JTextArea notes = new JTextArea(10, 60);
    private final JLabel status = UIComponentFactory.label(" ", "font: bold");
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea diagnostics = new JTextArea(5, 60);
    private final JScrollPane diagnosticsPane = new JScrollPane(diagnostics);
    private UpdateStateManager.State state = UpdateStateManager.State.UPDATE_AVAILABLE;

    UpdateDialog(Window owner, UpdateType type, Consumer<Action> listener) {
        super(owner, Product.NAME + " · Update", type == UpdateType.A ? ModalityType.APPLICATION_MODAL : ModalityType.MODELESS);
        this.type = type;
        this.listener = listener;
        setName("update-dialog-" + type.name());
        setIconImages(AppIcon.windowImages());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (userMayClose(UpdateDialog.this.type, state)) listener.accept(Action.CLOSE);
                else if (UpdateDialog.this.type == UpdateType.A) status.setText("This update is required. Install it to continue signing, or exit the application.");
            }
        });
        if (type != UpdateType.A) {
            getRootPane().registerKeyboardAction(event -> { if (userMayClose(type, state)) listener.accept(Action.CLOSE); },
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
        JPanel root = new JPanel(new BorderLayout(0, UIScale.scale(14)));
        root.setBorder(UIComponentFactory.padding(22));
        setContentPane(root);
        JLabel badge = UIComponentFactory.label(type == UpdateType.A ? "CRITICAL / SECURITY" : type == UpdateType.B ? "IMPORTANT" : "OPTIONAL",
                "font: bold -1; foreground: " + (type == UpdateType.A ? "$RJ.warning" : "$RJ.accent"));
        root.add(UIComponentFactory.stack(8, badge, title, summary), BorderLayout.NORTH);
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.getAccessibleContext().setAccessibleName("Release notes");
        JScrollPane notesPane = new JScrollPane(notes);
        notesPane.setPreferredSize(new Dimension(UIScale.scale(620), UIScale.scale(220)));
        diagnostics.setEditable(false);
        diagnostics.setLineWrap(true);
        diagnostics.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        diagnosticsPane.setVisible(false);
        progress.setVisible(false);
        progress.setStringPainted(true);
        JPanel middle = UIComponentFactory.stack(10, UIComponentFactory.label("Release notes", "font: bold"), notesPane, status, progress, diagnosticsPane);
        root.add(middle, BorderLayout.CENTER);
        JPanel actions = UIComponentFactory.transparent(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(8), 0));
        for (Action action : actionsFor(type)) {
            JButton button = action == Action.INSTALL || action == Action.UPDATE_NOW
                    ? UIComponentFactory.createModernButton(action.label(), true) : new JButton(action.label());
            button.setName("update-" + action.name().toLowerCase(java.util.Locale.ROOT));
            button.addActionListener(event -> {
                if (action == Action.DETAILS) { diagnosticsPane.setVisible(!diagnosticsPane.isVisible()); pack(); return; }
                listener.accept(action);
            });
            buttons.put(action, button);
            actions.add(button);
        }
        buttons.get(Action.RETRY).setVisible(false);
        root.add(actions, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    UpdateType type() { return type; }

    /** Shows a classified, verified release. */
    void showOffer(UpdateDecision.Outcome outcome, SemanticVersion installed) {
        var target = outcome.target();
        var release = target.release();
        boolean security = outcome.securityUpdate();
        title.setText(type == UpdateType.A ? (security ? "Security Update Required" : "Critical Update Required")
                : type == UpdateType.B ? "Important Update Available" : "Optional Update Available");
        StringBuilder text = new StringBuilder(Product.NAME).append("\nInstalled version: ").append(installed)
                .append("\nAvailable version: ").append(target.version()).append(" (").append(type).append(")");
        if (release.published() != null) text.append("\nReleased: ").append(DATE.format(release.published()));
        if (target.download() != null) text.append("\nDownload size: ").append(megabytes(target.download().size()));
        if (type == UpdateType.A) {
            text.append("\n\nWhy this update is required: ").append(outcome.mandatoryReason() == null ? "See the release notes." : outcome.mandatoryReason());
            text.append("\nSigning is disabled until version ").append(outcome.mandatoryVersion()).append(" or newer is installed.");
        }
        if (!outcome.installable() && outcome.detail() != null) text.append("\n\n").append(outcome.detail());
        summary.setText(text.toString());
        String body = release.body() == null || release.body().isBlank() ? "No release notes were provided." : release.body();
        notes.setText(body.length() > 20_000 ? body.substring(0, 20_000) + "\n…" : body);
        notes.setCaretPosition(0);
        Action primary = type == UpdateType.A ? Action.INSTALL : Action.UPDATE_NOW;
        buttons.get(primary).setText(!outcome.installable() ? (Platform.channel() == Platform.Channel.WINDOWS_STORE ? "Open Microsoft Store" : "Open Release Page")
                : primary.label());
        status.setText(outcome.installable() ? "Ready to install." : " ");
        setWorking(false);
        pack();
    }

    /** A mandatory requirement remembered from an earlier session, while the current release is being retrieved. */
    void showPending(SemanticVersion required, String reason, SemanticVersion installed) {
        title.setText("Critical Update Required");
        summary.setText(Product.NAME + "\nInstalled version: " + installed + "\nRequired version: " + required + " or newer"
                + "\n\nWhy this update is required: " + (reason == null ? "A critical or security update was published." : reason)
                + "\nSigning is disabled until the update is installed.");
        notes.setText("Retrieving the release information from GitHub…");
        status.setText("Checking for the required update…");
        setWorking(true);
    }

    void setState(UpdateStateManager.State next) { state = next; }

    void showProgress(String text, long done, long total) {
        status.setText(text);
        progress.setVisible(true);
        progress.setIndeterminate(total <= 0);
        if (total > 0) {
            progress.setMaximum(1000);
            progress.setValue((int) Math.min(1000, done * 1000 / total));
            progress.setString(megabytes(done) + " of " + megabytes(total));
        } else progress.setString(null);
    }

    void showStatus(String text) { status.setText(text); }

    void setWorking(boolean working) {
        for (var entry : buttons.entrySet()) {
            boolean always = entry.getKey() == Action.DETAILS || entry.getKey() == Action.RELEASE_NOTES;
            entry.getValue().setEnabled(always || !working);
        }
        if (!working) progress.setVisible(false);
    }

    void showFailure(UpdateException failure, boolean retry) {
        status.setText(failure.category() + ": " + failure.getMessage());
        diagnostics.setText("Category: " + failure.category().name() + (failure.httpStatus() > 0 ? "\nHTTP status: " + failure.httpStatus() : "")
                + (failure.retryAfter() != null ? "\nRetry after: " + failure.retryAfter() : "") + "\nMessage: " + failure.getMessage()
                + "\nPlatform: " + Platform.id() + " · " + Platform.channel() + "\nInstalled version: " + SemanticVersion.current()
                + "\nLogs: " + AppPaths.LOGS);
        buttons.get(Action.RETRY).setVisible(retry);
        setWorking(false);
        pack();
    }

    /** After installation: further actions are disabled; Type B/C may close when the OS installer took over. */
    void showFinished(String text, boolean closable) {
        status.setText(text);
        progress.setVisible(false);
        for (var entry : buttons.entrySet()) entry.getValue().setEnabled(entry.getKey() == Action.DETAILS || entry.getKey() == Action.EXIT
                || closable && (entry.getKey() == Action.REMIND_LATER || entry.getKey() == Action.RELEASE_NOTES));
        pack();
    }

    private static String megabytes(long bytes) { return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0); }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.Desktop;
import java.awt.FileDialog;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

/**
 * After a successful signing, asks the user where to save the signed PDF (starting in the folder the
 * original came from), copies the verified file there, and offers to open it or its folder. The verified
 * copy in the application's workspace is kept either way, and the original document is never overwritten.
 */
public final class SignedFileSaver {
    private static volatile Path automaticFolder;

    private final BulkFieldSignerGUI gui;
    private final Path verified;
    private final boolean seal;

    private SignedFileSaver(BulkFieldSignerGUI gui, Path verified, boolean seal) {
        this.gui = gui;
        this.verified = verified;
        this.seal = seal;
    }

    /** Runs on the event thread once signing has succeeded. */
    static void offer(BulkFieldSignerGUI gui, Path verified, boolean seal) { new SignedFileSaver(gui, verified, seal).ask(); }

    /** Automated tools only (product screenshots): save into this folder without showing the file dialog. */
    public static void saveAutomaticallyTo(Path folder) { automaticFolder = folder; }

    /** For example "Agreement.pdf" becomes "Agreement - signed.pdf". */
    static String suggestedName(String original, boolean seal) {
        String base = original == null || original.isBlank() ? "document" : original.strip();
        if (base.toLowerCase(Locale.ROOT).endsWith(".pdf")) base = base.substring(0, base.length() - 4);
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        return base + (seal ? " - sealed.pdf" : " - signed.pdf");
    }

    private String title() { return seal ? "Sealing complete" : "Signing complete"; }

    private void ask() {
        Path target = automaticFolder != null ? automaticFolder.resolve(suggestedName(gui.getDocumentName(), seal)) : chooseLocation();
        if (target == null) { cancelled(); return; }
        save(target);
    }

    /**
     * Signing can take a while (token PIN window, timestamp server), and the user may have switched to another
     * program meanwhile. Windows then opens the Save window behind it, where it is easily missed. The application
     * window is therefore restored and raised, and kept above other programs while the Save window is open.
     */
    private void bringToFront() {
        if ((gui.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) gui.setExtendedState(gui.getExtendedState() & ~java.awt.Frame.ICONIFIED);
        gui.setAlwaysOnTop(true);
        gui.toFront();
        gui.requestFocus();
        try {
            if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.USER_ATTENTION_WINDOW)) {
                java.awt.Taskbar.getTaskbar().requestWindowUserAttention(gui);
            }
        } catch (RuntimeException ignored) { /* Flashing the taskbar button is only a hint. */ }
    }

    private Path chooseLocation() {
        while (true) {
            FileDialog dialog = new FileDialog(gui, seal ? "Save the sealed PDF" : "Save the signed PDF", FileDialog.SAVE);
            dialog.setDirectory(gui.lastFolder().toString());
            dialog.setFile(suggestedName(gui.getDocumentName(), seal));
            Path chosen;
            try {
                bringToFront();
                dialog.setVisible(true);
                if (dialog.getFile() == null) return null;
                chosen = Path.of(dialog.getDirectory(), dialog.getFile()).toAbsolutePath().normalize();
            } finally {
                dialog.dispose();
                gui.setAlwaysOnTop(false);
            }
            if (!chosen.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) chosen = chosen.resolveSibling(chosen.getFileName() + ".pdf");
            Path original = gui.getSourceFile();
            if (original != null && chosen.equals(original.toAbsolutePath().normalize())) {
                JOptionPane.showMessageDialog(gui, "That is your original document. Please choose a different name,\n"
                        + "so the original stays unchanged.", title(), JOptionPane.WARNING_MESSAGE);
                continue;
            }
            // Windows and macOS file dialogs ask before replacing a file; the Linux AWT dialog does not.
            if (Platform.os() == Platform.Os.LINUX && Files.exists(chosen) && JOptionPane.showConfirmDialog(gui,
                    chosen.getFileName() + " already exists. Replace it?", title(), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) continue;
            return chosen;
        }
    }

    private void save(Path target) {
        gui.setBusy(true, "Saving…");
        new SwingWorker<Path, Void>() {
            @Override protected Path doInBackground() throws Exception { return copy(verified, target); }
            @Override protected void done() {
                gui.setBusy(false, "");
                try {
                    Path saved = get();
                    gui.rememberFolder(saved.getParent());
                    gui.onLog((seal ? "Sealed" : "Signed") + " PDF saved to " + saved);
                    saved(saved);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    gui.onLog("Could not save to " + target + ": " + ex.getCause().getMessage());
                    JOptionPane.showMessageDialog(gui, "The signed PDF could not be saved there:\n" + ex.getCause().getMessage()
                            + "\n\nPlease choose another folder, for example Documents.", title(), JOptionPane.ERROR_MESSAGE);
                    ask();
                }
            }
        }.execute();
    }

    /** Copies through a temporary file in the destination folder, so a failed copy never leaves a broken PDF. */
    static Path copy(Path source, Path target) throws IOException {
        Path folder = target.getParent();
        if (folder == null || !Files.isDirectory(folder)) throw new IOException("The folder does not exist.");
        Path partial = Files.createTempFile(folder, ".saving-", ".tmp");
        try {
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(partial) != Files.size(source)) throw new IOException("The copy is incomplete.");
            try { Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING); }
            return target;
        } finally { Files.deleteIfExists(partial); }
    }

    private void saved(Path saved) {
        String[] options = {"Open PDF", "Open folder", "Close"};
        int choice = JOptionPane.showOptionDialog(gui, message("Your " + (seal ? "sealed" : "signed") + " PDF has been saved:\n" + saved
                        + "\n\nOpen it in a PDF reader to check the " + (seal ? "timestamp" : "signature") + " before you submit it."),
                title(), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (choice == 0) open(saved, false);
        else if (choice == 1) open(saved, true);
    }

    private void cancelled() {
        String[] options = {"Save As…", "Close"};
        int choice = JOptionPane.showOptionDialog(gui, message("The " + (seal ? "sealed" : "signed") + " PDF was not saved to a folder of your choice.\n"
                        + "A copy is kept in the application's workspace:\n" + verified),
                title(), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (choice == 0) ask();
    }

    private void open(Path file, boolean folder) {
        Thread.ofPlatform().name("open-saved-pdf").start(() -> {
            try {
                if (folder && Platform.os() == Platform.Os.WINDOWS) new ProcessBuilder("explorer.exe", "/select,", file.toString()).start();
                else if (folder && Platform.os() == Platform.Os.MACOS) new ProcessBuilder("open", "-R", file.toString()).start();
                else Desktop.getDesktop().open(folder ? file.getParent().toFile() : file.toFile());
            } catch (Exception ex) {
                gui.onLog("Could not open " + file + ": " + ex.getMessage());
            }
        });
    }

    private static JTextArea message(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(60);
        return area;
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.FileDialog;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.SwingWorker;

public final class SigningController implements ISigningController {
    private final BulkFieldSignerGUI gui;
    public SigningController(BulkFieldSignerGUI gui) { this.gui = gui; }

    private boolean blockedByUpdate() {
        if (!gui.updatesBlockOperations()) return false;
        gui.showError("A required update must be installed before documents can be processed.");
        return true;
    }

    @Override public void choosePDF() {
        if (gui.isBusy() || blockedByUpdate()) return;
        FileDialog dialog = new FileDialog(gui, "Choose the PDF to sign", FileDialog.LOAD);
        dialog.setDirectory(gui.lastFolder().toString());
        dialog.setFile("*.pdf");
        Path selected;
        try {
            dialog.setVisible(true);
            if (dialog.getFile() == null) return;
            selected = Path.of(dialog.getDirectory(), dialog.getFile());
        } finally { dialog.dispose(); }
        gui.setBusy(true, "Importing...");
        new SwingWorker<Path, Void>() {
            private List<SignaturePlaceholder> areas = List.of();
            @Override protected Path doInBackground() throws Exception {
                Path imported = LocalFiles.importPdf(selected);
                // Parse off the EDT before registering an imported file as the active document.
                try (var document = org.apache.pdfbox.Loader.loadPDF(imported.toFile())) {
                    if (document.getNumberOfPages() < 1 || document.getNumberOfPages() > 10_000) {
                        throw new java.io.IOException("PDF must contain between 1 and 10,000 pages.");
                    }
                }
                try { areas = PlaceholderStore.load(imported); }
                catch (java.io.IOException ex) { gui.onLog("Saved layout ignored: " + ex.getMessage()); }
                return imported;
            }
            @Override protected void done() {
                try {
                    gui.setPdf(get(), areas, selected.getFileName().toString());
                    gui.setSourceFile(selected);
                    gui.onLog("Opened " + selected.getFileName() + ". The original file is only read, never changed.");
                }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (ExecutionException ex) { gui.showError(ex.getCause().getMessage()); }
                finally { gui.setBusy(false, ""); }
            }
        }.execute();
    }

    @Override public void openPicker() {
        if (gui.isBusy() || blockedByUpdate()) return;
        Path pdf = gui.getPdf();
        if (pdf == null) { gui.showError("Import a PDF first."); return; }
        gui.setEditorOpen();
        try {
            VisualSignaturePicker picker = new VisualSignaturePicker(gui, pdf, gui.getPlaceholders(), gui.getDocumentName(), gui.stampPreview());
            // A mandatory-update dialog must not trap an open editor; closing it lets the update proceed.
            picker.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
            picker.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent event) {
                    if (!picker.isAccepted()) { gui.setBusy(false, ""); return; }
                    List<SignaturePlaceholder> areas = picker.getPlaceholders();
                    gui.setPlaceholders(areas);
                    gui.setBusy(true, "Saving layout…");
                    new SwingWorker<Void, Void>() {
                        @Override protected Void doInBackground() throws Exception { PlaceholderStore.save(pdf, areas); return null; }
                        @Override protected void done() {
                            try { get(); }
                            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                            catch (ExecutionException ex) { gui.showError("Layout could not be saved: " + ex.getCause().getMessage()); }
                            finally { gui.setBusy(false, ""); }
                        }
                    }.execute();
                }
            });
            picker.setVisible(true);
        } catch (RuntimeException ex) {
            gui.setBusy(false, "");
            gui.showError("Preview failed: " + ex.getMessage());
        }
    }

    @Override public void startSigningProcessAsync() {
        if (gui.isBusy() || blockedByUpdate()) return;
        final SigningRequest request;
        try { request = gui.buildSigningRequest(); }
        catch (IllegalArgumentException ex) { gui.showError(ex.getMessage()); return; }
        gui.setBusy(true, "Signing...");
        new BulkSigningWorker(gui, request).execute();
    }
}

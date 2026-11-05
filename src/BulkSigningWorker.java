// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.SwingWorker;

public final class BulkSigningWorker extends SwingWorker<Path, Void> {
    private final BulkFieldSignerGUI gui;
    private final SigningRequest request;

    public BulkSigningWorker(BulkFieldSignerGUI gui, SigningRequest request) {
        this.gui = gui;
        this.request = request;
    }

    @Override protected Path doInBackground() throws Exception {
        try { SettingsManager.save(request); }
        catch (java.io.IOException ex) { gui.onLog("Settings could not be saved: " + ex.getMessage()); }
        return PdfSignerService.executeSigningPipeline(request, gui::onLog);
    }

    @Override protected void done() {
        Path output = null;
        boolean seal = request.mode() == SigningMode.TIMESTAMP_SEAL;
        try {
            output = get();
            gui.onLog((seal ? "Seal" : "Signature") + " verified. Verified copy kept at " + output);
        } catch (CancellationException ex) {
            gui.onLog("Signing cancelled.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            gui.onLog("Signing failed: " + ex.getCause());
            gui.showError("Signing failed: " + ex.getCause().getMessage());
        } finally { gui.setBusy(false, ""); }
        // Ask where to save only after the busy state is released, so the dialog sees a ready window.
        if (output != null) SignedFileSaver.offer(gui, output, seal);
    }
}

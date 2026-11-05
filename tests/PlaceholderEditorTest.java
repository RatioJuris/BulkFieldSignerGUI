// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaceholderEditorTest {
    @Test void editsAreUndoableAndCancelLeavesOriginalSnapshotUntouched() {
        var first = new SignaturePlaceholder(1, 10, 20, 100, 50);
        List<SignaturePlaceholder> source = List.of(first);
        PlaceholderEditorModel model = new PlaceholderEditorModel(source);
        model.add(new SignaturePlaceholder(2, 20, 30, 100, 50));
        model.clearPage(1);
        assertEquals(1, model.areas().size());
        model.undo(); assertEquals(2, model.areas().size());
        model.undo(); assertEquals(source, model.areas());
        model.redo(); assertEquals(2, model.areas().size());
        model.select(0); model.deleteSelected();
        assertEquals(1, model.areas().size());
        assertFalse(model.canRedo());
        assertEquals(List.of(first), source);
    }

    @Test void drawingCommitsImmediatelyAndResizingUsesDisplayZoomNotRasterSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.install(false);
            PlaceholderEditorModel model = new PlaceholderEditorModel(List.of());
            SignatureCanvas canvas = new SignatureCanvas(model, () -> { }, message -> { });
            JViewport viewport = new JViewport();
            viewport.setExtentSize(new Dimension(800, 600));
            viewport.setView(canvas);
            PageGeometry geometry = new PageGeometry(0, 0, 600, 800, 0);
            canvas.setGeometry(0, geometry, 1);
            canvas.setImage(new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB)); // Low-resolution draft.
            viewport.setViewSize(new Dimension(800, 848));
            Rectangle2D paper = canvas.pageBounds();
            gesture(canvas, paper.getX() + 100, paper.getY() + 100, paper.getX() + 300, paper.getY() + 200);
            assertEquals(List.of(new SignaturePlaceholder(1, 100, 600, 200, 100)), model.areas());
            canvas.setGeometry(0, geometry, 2);
            viewport.setViewSize(canvas.getPreferredSize());
            paper = canvas.pageBounds();
            Rectangle2D selected = canvas.selectedBounds();
            gesture(canvas, paper.getX() + selected.getMaxX(), paper.getY() + selected.getMaxY(),
                    paper.getX() + selected.getMaxX() + 40, paper.getY() + selected.getMaxY() + 20);
            assertEquals(new SignaturePlaceholder(1, 100, 590, 220, 110), model.areas().getFirst());
            model.undo();
            assertEquals(new SignaturePlaceholder(1, 100, 600, 200, 100), model.areas().getFirst());
            canvas.setMode(SignatureCanvas.Mode.PAN);
            gesture(canvas, 200, 200, 100, 100);
            assertEquals(1, model.areas().size(), "Panning must not add or move signature areas.");
        });
    }

    @Test void repeatingKeepsTheStampAtTheSameEdgeOnEveryPageAndIsOneUndoStep() {
        List<PageGeometry> pages = List.of(new PageGeometry(0, 0, 600, 800, 0), new PageGeometry(0, 0, 400, 500, 0),
                new PageGeometry(10, 20, 600, 800, 90));
        // Bottom-right on page 1: 20 pt from the right edge and 30 pt from the bottom.
        SignaturePlaceholder area = new SignaturePlaceholder(1, 400, 30, 180, 60);
        List<SignaturePlaceholder> copies = PageGeometry.repeatOnAllPages(area, pages);
        assertEquals(List.of(2, 3), copies.stream().map(SignaturePlaceholder::page).toList());
        for (SignaturePlaceholder copy : copies) {
            PageGeometry target = pages.get(copy.page() - 1);
            Rectangle2D shown = target.toDisplay(copy, 1);
            assertEquals(180, shown.getWidth(), 0.01);
            assertEquals(60, shown.getHeight(), 0.01);
            assertEquals(20, target.displayWidth() - shown.getMaxX(), 0.01, "Right margin kept on page " + copy.page());
            assertEquals(30, target.displayHeight() - shown.getMaxY(), 0.01, "Bottom margin kept on page " + copy.page());
        }
        PlaceholderEditorModel model = new PlaceholderEditorModel(List.of(area));
        model.select(0);
        assertEquals(2, model.addAll(copies));
        assertEquals(0, model.addAll(copies), "Existing identical stamps are not duplicated.");
        assertEquals(3, model.areas().size());
        assertEquals(0, model.selected());
        model.undo();
        assertEquals(List.of(area), model.areas());
    }

    private static void gesture(SignatureCanvas canvas, double x1, double y1, double x2, double y2) {
        long time = System.currentTimeMillis();
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, time, MouseEvent.BUTTON1_DOWN_MASK, (int) x1, (int) y1, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, time + 1, MouseEvent.BUTTON1_DOWN_MASK, (int) x2, (int) y2, 0, false, MouseEvent.NOBUTTON));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, time + 2, 0, (int) x2, (int) y2, 1, false, MouseEvent.BUTTON1));
    }
}

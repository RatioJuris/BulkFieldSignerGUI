// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.*;

/** Interactive overlay geometry is independent of the raster's resolution and refinement lifecycle. */
@SuppressWarnings("serial")
final class SignatureCanvas extends JPanel implements Scrollable {
    enum Mode { DRAW, PAN }
    private enum Handle { NONE, CREATE, MOVE, NW, N, NE, E, SE, S, SW, W, PAN }
    private final PlaceholderEditorModel model;
    private final Runnable changed;
    private final Consumer<String> feedback;
    private PageGeometry geometry;
    private BufferedImage image;
    private int page = -1;
    private float scale = 1;
    private Mode mode = Mode.DRAW;
    private boolean spaceHeld;
    private Handle handle = Handle.NONE;
    private Rectangle2D.Double draft;
    private Rectangle2D.Double original;
    private Point2D start;
    private Point panOrigin;
    private Point panPointer;
    private String emptyMessage = "Opening PDF…";
    private StampRenderer.Preview stamp;
    private int pageCount = 1;

    SignatureCanvas(PlaceholderEditorModel model, Runnable changed, Consumer<String> feedback) {
        this.model = model;
        this.changed = changed;
        this.feedback = feedback;
        setFocusable(true);
        setName("signature-canvas");
        getAccessibleContext().setAccessibleName("PDF signature placement canvas");
        getAccessibleContext().setAccessibleDescription("Draw a box to add a signature. Drag existing boxes to move them, or their handles to resize. Hold Space to pan.");
        putClientProperty(FlatClientProperties.STYLE, "background: $RJ.preview");
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { begin(event); }
            @Override public void mouseDragged(MouseEvent event) { drag(event); }
            @Override public void mouseReleased(MouseEvent event) { commit(); }
            @Override public void mouseMoved(MouseEvent event) { updateCursor(local(event.getPoint())); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { spaceHeld = false; cancelGesture(); }
        });
        bind("pressed SPACE", "pan-start", () -> { spaceHeld = true; setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); });
        bind("released SPACE", "pan-stop", () -> { spaceHeld = false; updateCursor(null); });
    }

    private void bind(String key, String name, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { action.run(); } });
    }

    void setMode(Mode mode) { cancelGesture(); this.mode = mode; updateCursor(null); }
    PageGeometry geometry() { return geometry; }
    float scale() { return scale; }
    boolean hasImage() { return image != null; }
    void setEmptyMessage(String message) { emptyMessage = message; repaint(); }
    void setStamp(StampRenderer.Preview stamp) { this.stamp = stamp; repaint(); }
    void setPageCount(int count) { pageCount = Math.max(1, count); repaint(); }

    void loadingPage(int page) {
        cancelGesture();
        if (this.page != page) { image = null; geometry = null; }
        this.page = page;
        emptyMessage = "Loading page " + (page + 1) + "…";
        revalidate();
        repaint();
    }

    void setGeometry(int page, PageGeometry geometry, float scale) {
        if (this.page != page) image = null;
        this.page = page;
        this.geometry = geometry;
        this.scale = scale;
        revalidate();
        repaint();
    }

    void setImage(BufferedImage image) {
        boolean ready = this.image != null;
        this.image = image;
        repaint();
        firePropertyChange("previewReady", ready, true);
    }

    Rectangle2D.Double pageBounds() {
        if (geometry == null) return new Rectangle2D.Double();
        double width = geometry.displayWidth() * scale;
        double height = geometry.displayHeight() * scale;
        int margin = UIScale.scale(24);
        return new Rectangle2D.Double(Math.max(margin, (getWidth() - width) / 2), Math.max(margin, (getHeight() - height) / 2), width, height);
    }

    Rectangle2D selectedBounds() {
        if (geometry == null || model.selected() < 0) return null;
        SignaturePlaceholder area = model.areas().get(model.selected());
        return area.page() == page + 1 ? geometry.toDisplay(area, scale) : null;
    }

    void revealSelection() {
        Rectangle2D selected = selectedBounds();
        if (selected == null) return;
        Rectangle2D paper = pageBounds();
        Rectangle bounds = new Rectangle((int) (paper.getX() + selected.getX()) - 24, (int) (paper.getY() + selected.getY()) - 24,
                (int) selected.getWidth() + 48, (int) selected.getHeight() + 48);
        scrollRectToVisible(bounds);
    }

    void duplicateSelected() {
        Rectangle2D selected = selectedBounds();
        if (selected == null) return;
        Rectangle2D moved = translated(selected, 16 * scale, 16 * scale);
        try { model.add(geometry.toPdf(page + 1, moved, scale)); changed.run(); repaint(); }
        catch (IllegalArgumentException ex) { feedback.accept(ex.getMessage()); }
    }

    void nudge(double x, double y) {
        Rectangle2D selected = selectedBounds();
        if (selected == null) return;
        model.replaceSelected(geometry.toPdf(page + 1, translated(selected, x * scale, y * scale), scale));
        changed.run();
        repaint();
    }

    private Rectangle2D.Double translated(Rectangle2D rectangle, double dx, double dy) {
        double x = Math.max(0, Math.min(rectangle.getX() + dx, geometry.displayWidth() * scale - rectangle.getWidth()));
        double y = Math.max(0, Math.min(rectangle.getY() + dy, geometry.displayHeight() * scale - rectangle.getHeight()));
        return new Rectangle2D.Double(x, y, rectangle.getWidth(), rectangle.getHeight());
    }

    private Point2D local(Point point) {
        Rectangle2D paper = pageBounds();
        return new Point2D.Double(point.x - paper.getX(), point.y - paper.getY());
    }

    private void begin(MouseEvent event) {
        requestFocusInWindow();
        if (mode == Mode.PAN || spaceHeld || SwingUtilities.isMiddleMouseButton(event)) {
            if (getParent() instanceof JViewport viewport) {
                handle = Handle.PAN;
                panOrigin = viewport.getViewPosition();
                panPointer = SwingUtilities.convertPoint(this, event.getPoint(), viewport);
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(event) || !hasImage() || geometry == null || !pageBounds().contains(event.getPoint())) return;
        start = local(event.getPoint());
        handle = hitHandle(start);
        if (handle == Handle.NONE) {
            int hit = hitArea(start);
            model.select(hit);
            handle = hit >= 0 ? Handle.MOVE : Handle.CREATE;
            changed.run();
        }
        Rectangle2D selected = selectedBounds();
        original = selected == null ? null : copy(selected);
        draft = handle == Handle.CREATE ? new Rectangle2D.Double(start.getX(), start.getY(), 0, 0) : copy(selected);
        repaint();
    }

    private void drag(MouseEvent event) {
        if (handle == Handle.PAN) {
            if (getParent() instanceof JViewport viewport) {
                Point pointer = SwingUtilities.convertPoint(this, event.getPoint(), viewport);
                viewport.setViewPosition(new Point(
                        clamp(panOrigin.x - pointer.x + panPointer.x, 0, Math.max(0, getWidth() - viewport.getWidth())),
                        clamp(panOrigin.y - pointer.y + panPointer.y, 0, Math.max(0, getHeight() - viewport.getHeight()))));
            }
            return;
        }
        if (draft == null || geometry == null || handle == Handle.NONE) return;
        Point2D pointer = local(event.getPoint());
        double x = Math.max(0, Math.min(pointer.getX(), geometry.displayWidth() * scale));
        double y = Math.max(0, Math.min(pointer.getY(), geometry.displayHeight() * scale));
        if (handle == Handle.CREATE) {
            draft.setRect(Math.min(start.getX(), x), Math.min(start.getY(), y), Math.abs(x - start.getX()), Math.abs(y - start.getY()));
        } else if (handle == Handle.MOVE) {
            draft = translated(original, x - start.getX(), y - start.getY());
        } else {
            double left = original.getMinX(), top = original.getMinY(), right = original.getMaxX(), bottom = original.getMaxY();
            double minimum = 8 * scale;
            if (handle == Handle.NW || handle == Handle.W || handle == Handle.SW) left = Math.min(x, right - minimum);
            if (handle == Handle.NE || handle == Handle.E || handle == Handle.SE) right = Math.max(x, left + minimum);
            if (handle == Handle.NW || handle == Handle.N || handle == Handle.NE) top = Math.min(y, bottom - minimum);
            if (handle == Handle.SW || handle == Handle.S || handle == Handle.SE) bottom = Math.max(y, top + minimum);
            left = Math.max(0, left); top = Math.max(0, top);
            right = Math.min(geometry.displayWidth() * scale, right); bottom = Math.min(geometry.displayHeight() * scale, bottom);
            draft.setRect(left, top, right - left, bottom - top);
        }
        feedback.accept(String.format(java.util.Locale.ROOT, "Area: %.0f × %.0f pt", draft.width / scale, draft.height / scale));
        repaint();
    }

    private void commit() {
        if (draft != null && geometry != null) {
            if (draft.width / scale >= 8 && draft.height / scale >= 8) {
                try {
                    SignaturePlaceholder area = geometry.toPdf(page + 1, draft, scale);
                    if (handle == Handle.CREATE) model.add(area); else model.replaceSelected(area);
                    changed.run();
                } catch (IllegalArgumentException ex) { feedback.accept(ex.getMessage()); }
            } else feedback.accept("Draw a larger box (at least 8 × 8 pt).");
        }
        cancelGesture();
    }

    void cancelGesture() { draft = null; original = null; start = null; handle = Handle.NONE; updateCursor(null); repaint(); }

    private int hitArea(Point2D point) {
        if (geometry == null) return -1;
        for (int i = model.areas().size() - 1; i >= 0; i--) {
            SignaturePlaceholder area = model.areas().get(i);
            if (area.page() == page + 1 && geometry.toDisplay(area, scale).contains(point)) return i;
        }
        return -1;
    }

    private Handle hitHandle(Point2D point) {
        Rectangle2D selected = selectedBounds();
        if (selected == null) return Handle.NONE;
        Point2D[] points = handles(selected);
        Handle[] names = {Handle.NW, Handle.N, Handle.NE, Handle.E, Handle.SE, Handle.S, Handle.SW, Handle.W};
        for (int i = 0; i < points.length; i++) if (points[i].distance(point) <= UIScale.scale(7)) return names[i];
        return Handle.NONE;
    }

    private void updateCursor(Point2D point) {
        int cursor = mode == Mode.PAN || spaceHeld ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR;
        if (point != null && mode == Mode.DRAW && !spaceHeld) {
            cursor = switch (hitHandle(point)) {
                case NW, SE -> Cursor.NW_RESIZE_CURSOR;
                case NE, SW -> Cursor.NE_RESIZE_CURSOR;
                case N, S -> Cursor.N_RESIZE_CURSOR;
                case W, E -> Cursor.E_RESIZE_CURSOR;
                default -> hitArea(point) >= 0 ? Cursor.MOVE_CURSOR : Cursor.CROSSHAIR_CURSOR;
            };
        }
        setCursor(Cursor.getPredefinedCursor(cursor));
    }

    @Override public Dimension getPreferredSize() {
        if (geometry == null) return new Dimension(400, 300);
        int margin = UIScale.scale(48);
        return new Dimension((int) Math.ceil(geometry.displayWidth() * scale) + margin, (int) Math.ceil(geometry.displayHeight() * scale) + margin);
    }
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return UIScale.scale(18); }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 48); }
    @Override public boolean getScrollableTracksViewportWidth() { return getParent() instanceof JViewport viewport && viewport.getWidth() > getPreferredSize().width; }
    @Override public boolean getScrollableTracksViewportHeight() { return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height; }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (geometry == null) { paintLoading(g); return; }
            Rectangle2D paper = pageBounds();
            g.setColor(new Color(0, 0, 0, 22));
            g.fill(new Rectangle2D.Double(paper.getX() + 4, paper.getY() + 5, paper.getWidth(), paper.getHeight()));
            g.setColor(Color.WHITE);
            g.fill(paper);
            if (image != null) g.drawImage(image, (int) paper.getX(), (int) paper.getY(), (int) Math.ceil(paper.getWidth()), (int) Math.ceil(paper.getHeight()), null);
            else { paintLoading(g); return; }
            g.translate(paper.getX(), paper.getY());
            for (int i = 0; i < model.areas().size(); i++) {
                SignaturePlaceholder area = model.areas().get(i);
                if (area.page() != page + 1 || draft != null && i == model.selected()) continue;
                paintArea(g, geometry.toDisplay(area, scale), i + 1, i == model.selected());
            }
            if (draft != null) paintArea(g, draft, handle == Handle.CREATE ? model.areas().size() + 1 : model.selected() + 1, true);
        } finally { g.dispose(); }
    }

    private void paintLoading(Graphics2D g) {
        Rectangle visible = getVisibleRect();
        g.setColor(UIConstants.color("muted"));
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(emptyMessage, visible.x + Math.max(12, (visible.width - metrics.stringWidth(emptyMessage)) / 2), visible.y + visible.height / 2);
    }

    private void paintArea(Graphics2D graphics, Rectangle2D bounds, int number, boolean selected) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            if (stamp != null) {
                g.setColor(new Color(37, 99, 235, selected ? 22 : 8));
                g.fill(bounds);
                Graphics2D s = (Graphics2D) g.create();
                try {
                    s.translate(bounds.getX(), bounds.getY());
                    s.scale(scale, scale);
                    stamp.paint(s, bounds.getWidth() / scale, bounds.getHeight() / scale, page + 1, pageCount);
                } finally { s.dispose(); }
                g.setColor(new Color(0x2563eb));
                g.setStroke(selected ? new BasicStroke(2f)
                        : new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{4, 3}, 0));
                g.draw(bounds);
            } else {
                g.setColor(new Color(37, 99, 235, selected ? 35 : 20));
                g.fill(bounds);
                g.setColor(new Color(0x2563eb));
                g.setStroke(new BasicStroke(selected ? 2f : 1.2f));
                g.draw(bounds);
                g.clip(bounds);
                int x = (int) bounds.getX() + UIScale.scale(10), y = (int) bounds.getY() + UIScale.scale(21);
                g.setFont(getFont().deriveFont(Font.BOLD));
                if (bounds.getHeight() > UIScale.scale(23)) g.drawString("Stamp " + number, x, y);
                g.setFont(getFont().deriveFont(Font.PLAIN, getFont().getSize2D() - 1));
                if (bounds.getHeight() > UIScale.scale(48)) g.drawString("Signer name · Date · Reason", x, y + UIScale.scale(19));
            }
        } finally { g.dispose(); }
        if (selected) {
            int size = UIScale.scale(7);
            for (Point2D point : handles(bounds)) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect((int) point.getX() - size / 2, (int) point.getY() - size / 2, size, size);
                graphics.setColor(new Color(0x2563eb));
                graphics.drawRect((int) point.getX() - size / 2, (int) point.getY() - size / 2, size, size);
            }
        }
    }

    private static Point2D[] handles(Rectangle2D r) {
        return new Point2D[]{new Point2D.Double(r.getMinX(), r.getMinY()), new Point2D.Double(r.getCenterX(), r.getMinY()),
                new Point2D.Double(r.getMaxX(), r.getMinY()), new Point2D.Double(r.getMaxX(), r.getCenterY()),
                new Point2D.Double(r.getMaxX(), r.getMaxY()), new Point2D.Double(r.getCenterX(), r.getMaxY()),
                new Point2D.Double(r.getMinX(), r.getMaxY()), new Point2D.Double(r.getMinX(), r.getCenterY())};
    }
    private static Rectangle2D.Double copy(Rectangle2D r) { return new Rectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight()); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.Icon;

/** Resolution-independent line icons, with no platform font or external asset dependency. */
public record AppIcon(Symbol symbol, int size) implements Icon {
    public enum Symbol { BRAND, DOCUMENT, SIGN, SHIELD, SETTINGS, UPLOAD, SUN, MOON, SYSTEM, CHECK, ARROW, ACTIVITY, FOLDER,
        PREVIOUS, NEXT, ZOOM_IN, ZOOM_OUT, UNDO, REDO, BOX, HAND, DUPLICATE, TRASH }
    @Override public int getIconWidth() { return UIScale.scale(size); }
    @Override public int getIconHeight() { return UIScale.scale(size); }
    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.scale(getIconWidth() / 24.0, getIconHeight() / 24.0);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(component == null ? new Color(0x2563eb) : component.getForeground());
            if (component != null && !component.isEnabled()) g.setColor(javax.swing.UIManager.getColor("Label.disabledForeground"));
            switch (symbol) {
                case BRAND -> {
                    g.setColor(new Color(0x2563eb));
                    g.fillRoundRect(0, 0, 24, 24, 7, 7);
                    g.setColor(Color.WHITE);
                    path(g, 7, 18, 7, 6, 13, 6, 16, 9, 16, 13);
                    path(g, 10, 17, 13, 14, 15, 16, 19, 11);
                }
                case DOCUMENT -> { path(g, 14, 3, 5, 3, 5, 21, 19, 21, 19, 8, 14, 3, 14, 8, 19, 8); path(g, 8, 12, 15, 12); path(g, 8, 16, 14, 16); }
                case SIGN -> { path(g, 4, 17, 3, 21, 7, 20, 20, 7, 17, 4, 4, 17); path(g, 14, 7, 17, 10); path(g, 11, 21, 21, 21); }
                case SHIELD -> { path(g, 12, 2, 20, 5, 19, 14, 16, 19, 12, 22, 8, 19, 5, 14, 4, 5, 12, 2); path(g, 8, 12, 11, 15, 16, 9); }
                case SETTINGS -> { g.drawOval(4, 4, 16, 16); g.drawOval(9, 9, 6, 6); for (int i = 0; i < 8; i++) { g.rotate(Math.PI / 4, 12, 12); g.drawLine(12, 1, 12, 4); } }
                case UPLOAD -> { path(g, 12, 16, 12, 3, 7, 8); path(g, 12, 3, 17, 8); path(g, 4, 15, 4, 21, 20, 21, 20, 15); }
                case SUN -> { g.drawOval(7, 7, 10, 10); for (int i = 0; i < 8; i++) { g.rotate(Math.PI / 4, 12, 12); g.drawLine(12, 1, 12, 3); } }
                case MOON -> { Path2D p = new Path2D.Double(); p.moveTo(19, 16); p.curveTo(6, 21, 3, 8, 10, 3); p.curveTo(-2, 6, 1, 25, 16, 21); p.curveTo(19, 20, 20, 18, 19, 16); g.draw(p); }
                case SYSTEM -> { g.drawRoundRect(2, 3, 20, 14, 3, 3); path(g, 12, 17, 12, 21); path(g, 7, 21, 17, 21); }
                case CHECK -> path(g, 5, 12, 10, 17, 20, 6);
                case ARROW -> { path(g, 4, 12, 20, 12, 14, 6); path(g, 20, 12, 14, 18); }
                case ACTIVITY -> path(g, 2, 13, 6, 13, 9, 5, 14, 20, 17, 11, 22, 11);
                case FOLDER -> path(g, 2, 6, 10, 6, 12, 9, 22, 9, 21, 20, 3, 20, 2, 6);
                case PREVIOUS -> path(g, 15, 5, 8, 12, 15, 19);
                case NEXT -> path(g, 9, 5, 16, 12, 9, 19);
                case ZOOM_IN, ZOOM_OUT -> { g.drawOval(3, 3, 13, 13); path(g, 15, 15, 21, 21); path(g, 6, 9, 13, 9); if (symbol == Symbol.ZOOM_IN) path(g, 10, 6, 10, 13); }
                case UNDO -> { path(g, 8, 3, 3, 8, 8, 13); path(g, 3, 8, 15, 8, 20, 11, 20, 17, 16, 21); }
                case REDO -> { path(g, 16, 3, 21, 8, 16, 13); path(g, 21, 8, 9, 8, 4, 11, 4, 17, 8, 21); }
                case BOX -> { g.drawRect(5, 5, 14, 14); for (int x1 : new int[]{3, 17}) for (int y1 : new int[]{3, 17}) g.drawRect(x1, y1, 4, 4); }
                case HAND -> { path(g, 7, 13, 7, 6, 10, 6, 10, 12, 10, 3, 13, 3, 13, 12, 13, 5, 16, 5, 16, 12, 16, 8, 19, 8, 19, 16, 16, 21, 9, 21, 3, 14, 4, 11, 7, 13); }
                case DUPLICATE -> { g.drawRoundRect(8, 8, 13, 13, 2, 2); path(g, 16, 5, 16, 3, 3, 3, 3, 16, 5, 16); }
                case TRASH -> { path(g, 3, 6, 21, 6); path(g, 8, 6, 8, 3, 16, 3, 16, 6); path(g, 5, 6, 6, 21, 18, 21, 19, 6); path(g, 10, 10, 10, 17); path(g, 14, 10, 14, 17); }
            }
        } finally { g.dispose(); }
    }
    private static void path(Graphics2D g, int... points) {
        Path2D path = new Path2D.Double();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        g.draw(path);
    }
    public static List<Image> windowImages() {
        return java.util.stream.Stream.of(16, 32, 48, 128).map(size -> {
            AppIcon icon = new AppIcon(Symbol.BRAND, size);
            BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            icon.paintIcon(null, g, 0, 0);
            g.dispose();
            return (Image) image;
        }).toList();
    }
}

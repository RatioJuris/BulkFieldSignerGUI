// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;

/** Conversion between PDF user space and PDFBox's rotated, cropped preview space. */
public record PageGeometry(float x, float y, float width, float height, int rotation) {
    public PageGeometry {
        rotation = Math.floorMod(rotation, 360);
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(height)
                || width <= 0 || height <= 0 || rotation % 90 != 0) {
            throw new IllegalArgumentException("Unsupported PDF page geometry.");
        }
    }

    public float displayWidth() { return rotation % 180 == 0 ? width : height; }
    public float displayHeight() { return rotation % 180 == 0 ? height : width; }

    private AffineTransform toDisplay(double scale) {
        AffineTransform transform = AffineTransform.getScaleInstance(scale, scale);
        transform.concatenate(switch (rotation) {
            case 90 -> new AffineTransform(0, 1, 1, 0, -y, -x);
            case 180 -> new AffineTransform(-1, 0, 0, 1, x + width, -y);
            case 270 -> new AffineTransform(0, -1, -1, 0, y + height, x + width);
            default -> new AffineTransform(1, 0, 0, -1, -x, y + height);
        });
        return transform;
    }

    public Rectangle2D toDisplay(SignaturePlaceholder area, double scale) {
        return toDisplay(scale).createTransformedShape(new Rectangle2D.Float(area.x(), area.y(), area.width(), area.height())).getBounds2D();
    }

    /**
     * Copies an area onto every other page at the same upright position. The area keeps its size and its
     * distance from the nearest horizontal and vertical page edges, so a bottom-right stamp stays bottom-right
     * on pages of other sizes or rotations.
     */
    public static java.util.List<SignaturePlaceholder> repeatOnAllPages(SignaturePlaceholder area, java.util.List<PageGeometry> pages) {
        PageGeometry source = pages.get(area.page() - 1);
        Rectangle2D shown = source.toDisplay(area, 1);
        boolean right = shown.getCenterX() > source.displayWidth() / 2.0;
        boolean bottom = shown.getCenterY() > source.displayHeight() / 2.0;
        double rightGap = source.displayWidth() - shown.getMaxX(), bottomGap = source.displayHeight() - shown.getMaxY();
        java.util.List<SignaturePlaceholder> copies = new java.util.ArrayList<>();
        for (int index = 0; index < pages.size(); index++) {
            if (index == area.page() - 1) continue;
            PageGeometry target = pages.get(index);
            double width = Math.min(shown.getWidth(), target.displayWidth()), height = Math.min(shown.getHeight(), target.displayHeight());
            double x = right ? target.displayWidth() - rightGap - width : shown.getX();
            double y = bottom ? target.displayHeight() - bottomGap - height : shown.getY();
            x = Math.max(0, Math.min(x, target.displayWidth() - width));
            y = Math.max(0, Math.min(y, target.displayHeight() - height));
            copies.add(target.toPdf(index + 1, new Rectangle2D.Double(x, y, width, height), 1));
        }
        return copies;
    }

    public SignaturePlaceholder toPdf(int page, Rectangle2D area, double scale) {
        try {
            Rectangle2D bounds = toDisplay(scale).createInverse().createTransformedShape(area).getBounds2D();
            return new SignaturePlaceholder(page, (float) bounds.getX(), (float) bounds.getY(),
                    (float) bounds.getWidth(), (float) bounds.getHeight());
        } catch (NoninvertibleTransformException ex) { throw new IllegalArgumentException("Invalid preview scale.", ex); }
    }
}

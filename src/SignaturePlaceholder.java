// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.kernel.geom.Rectangle;

public record SignaturePlaceholder(int page, float x, float y, float width, float height) {
    public SignaturePlaceholder {
        if (page < 1 || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid signature rectangle.");
        }
    }

    public Rectangle toRectangle() { return new Rectangle(x, y, width, height); }
}

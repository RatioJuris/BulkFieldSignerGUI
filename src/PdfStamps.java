// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfString;
import com.itextpdf.kernel.pdf.annot.PdfAnnotation;
import com.itextpdf.kernel.pdf.annot.PdfStampAnnotation;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies visible stamps before the document is secured, so the signature or timestamp covers them.
 * Unsigned input: drawn into page content. Signed input: locked stamp annotations in an incremental update,
 * which keeps earlier signatures valid.
 */
final class PdfStamps {
    private PdfStamps() { }

    static int apply(PdfDocument document, List<SignaturePlaceholder> areas, StampDesign design, byte[] png,
                     StampRenderer.Values values, boolean asAnnotations) {
        PdfImageXObject image = design.usesImage() ? StampRenderer.pdfImage(png) : null;
        double aspect = image == null ? 0 : image.getWidth() / (double) image.getHeight();
        int pages = document.getNumberOfPages();
        Map<String, PdfFormXObject> forms = new HashMap<>();
        Map<Integer, PdfCanvas> canvases = new HashMap<>();
        for (SignaturePlaceholder area : areas) {
            PdfPage page = document.getPage(area.page());
            int rotation = Math.floorMod(page.getRotation(), 360);
            boolean sideways = rotation % 180 != 0;
            float width = sideways ? area.height() : area.width();
            float height = sideways ? area.width() : area.height();
            List<String> lines = StampRenderer.lines(design, values, area.page(), pages);
            String key = lines + "|" + width + "x" + height + (asAnnotations ? "|" + rotation : "");
            PdfFormXObject form = forms.computeIfAbsent(key, k -> {
                PdfFormXObject created = StampRenderer.toForm(document,
                        StampRenderer.compose(design, lines, values.name(), aspect, values.mode(), width, height), image);
                if (asAnnotations) {
                    float[] m = orientation(rotation);
                    created.put(PdfName.Matrix, new PdfArray(new float[]{m[0], m[1], m[2], m[3], 0, 0}));
                }
                return created;
            });
            if (asAnnotations) {
                PdfStampAnnotation annotation = new PdfStampAnnotation(area.toRectangle());
                annotation.setStampName(new PdfName("RJ_Signature"));
                annotation.setNormalAppearance(form.getPdfObject());
                annotation.setFlags(PdfAnnotation.PRINT | PdfAnnotation.LOCKED | PdfAnnotation.READ_ONLY | PdfAnnotation.LOCKED_CONTENTS);
                annotation.setContents(new PdfString(String.join(" | ", lines), "UnicodeBig"));
                page.addAnnotation(annotation);
            } else {
                // Wrap existing content once per page so its graphics state cannot distort the stamp.
                PdfCanvas canvas = canvases.computeIfAbsent(area.page(), index -> new PdfCanvas(page, true));
                float[] m = orientation(rotation);
                float x = area.x(), y = area.y(), w = area.width(), h = area.height();
                float e = switch (rotation) { case 90, 180 -> x + w; default -> x; };
                float f = switch (rotation) { case 180, 270 -> y + h; default -> y; };
                canvas.addXObjectWithTransformationMatrix(form, m[0], m[1], m[2], m[3], e, f);
            }
        }
        canvases.values().forEach(PdfCanvas::release);
        return areas.size();
    }

    /** Maps upright stamp space into user space so the stamp reads upright on a /Rotate page. */
    static float[] orientation(int rotation) {
        return switch (rotation) {
            case 90 -> new float[]{0, 1, -1, 0};
            case 180 -> new float[]{-1, 0, 0, -1};
            case 270 -> new float[]{0, -1, 1, 0};
            default -> new float[]{1, 0, 0, 1};
        };
    }
}

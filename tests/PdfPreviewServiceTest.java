// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PdfPreviewServiceTest {
    private static class FakePdf implements PdfPreviewService.Backend {
        final AtomicInteger geometries = new AtomicInteger();
        final AtomicInteger renders = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        volatile boolean closed;
        @Override public int pageCount() { return 1000; }
        @Override public PageGeometry geometry(int page) { geometries.incrementAndGet(); return new PageGeometry(0, 0, 600, 800, 0); }
        @Override public BufferedImage render(int page, float scale) throws IOException {
            assertFalse(javax.swing.SwingUtilities.isEventDispatchThread());
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { renders.incrementAndGet(); return new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB); }
            finally { active.decrementAndGet(); }
        }
        @Override public void close() { assertEquals(0, active.get()); closed = true; }
    }

    private static final class Events implements PdfPreviewService.Listener {
        final CountDownLatch opened = new CountDownLatch(1);
        final LinkedBlockingQueue<PdfPreviewService.Frame> ready = new LinkedBlockingQueue<>();
        final List<PdfPreviewService.Quality> qualities = new CopyOnWriteArrayList<>();
        final List<Exception> errors = new CopyOnWriteArrayList<>();
        @Override public void opened(int count) { opened.countDown(); }
        @Override public void rendered(PdfPreviewService.Frame frame) {
            qualities.add(frame.quality());
            if (frame.quality() == PdfPreviewService.Quality.READY) ready.add(frame);
        }
        @Override public void failed(long id, int page, Exception failure) { errors.add(failure); }
        PdfPreviewService.Frame next() throws InterruptedException {
            PdfPreviewService.Frame frame = ready.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "Preview must complete without blocking the UI.");
            return frame;
        }
    }

    private static PdfPreviewService.Request request(long id, int page) {
        return new PdfPreviewService.Request(id, page, PdfPreviewService.ZoomMode.CUSTOM, 1, 900, 900, 1);
    }

    @Test void metadataIsLazyAndRevisitingPagesOrZoomingOutUsesCachedImages() throws Exception {
        FakePdf pdf = new FakePdf();
        Events events = new Events();
        try (PdfPreviewService service = new PdfPreviewService(() -> pdf, events, Runnable::run)) {
            assertTrue(events.opened.await(5, TimeUnit.SECONDS));
            assertEquals(0, pdf.geometries.get(), "Opening a 1000-page file must not inspect all page geometries.");
            service.request(request(1, 0)); assertEquals(0, events.next().page());
            service.request(request(2, 1)); assertEquals(1, events.next().page());
            assertEquals(2, pdf.renders.get());
            service.request(request(3, 0)); assertEquals(0, events.next().page());
            service.request(new PdfPreviewService.Request(4, 0, PdfPreviewService.ZoomMode.CUSTOM, .5f, 900, 900, 1));
            assertEquals(.5f, events.next().scale());
            assertEquals(2, pdf.renders.get(), "Cached pixels can be rescaled without parsing or rendering again.");
            assertTrue(events.errors.isEmpty());
        }
    }

    @Test void latestPageWinsAndStaleRenderErrorsDoNotReplaceIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakePdf pdf = new FakePdf() {
            @Override public BufferedImage render(int page, float scale) throws IOException {
                if (page == 0) {
                    started.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                    throw new IOException("Old page failed");
                }
                return super.render(page, scale);
            }
        };
        Events events = new Events();
        try (PdfPreviewService service = new PdfPreviewService(() -> pdf, events, Runnable::run)) {
            assertTrue(events.opened.await(5, TimeUnit.SECONDS));
            service.request(request(1, 0));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            service.request(request(2, 1));
            service.request(request(3, 2));
            release.countDown();
            assertEquals(3, events.next().id());
            assertTrue(events.ready.isEmpty());
            assertTrue(events.errors.isEmpty(), "Errors from obsolete page requests must be ignored.");
            assertEquals(1, pdf.peak.get());
        } finally { release.countDown(); }
    }

    @Test void largePagesEmitDraftBeforeFinalAndBoundRasterScale() throws Exception {
        List<Float> scales = new CopyOnWriteArrayList<>();
        FakePdf pdf = new FakePdf() {
            @Override public PageGeometry geometry(int page) { return new PageGeometry(0, 0, 2000, 3000, 0); }
            @Override public BufferedImage render(int page, float scale) throws IOException { scales.add(scale); return super.render(page, scale); }
        };
        Events events = new Events();
        try (PdfPreviewService service = new PdfPreviewService(() -> pdf, events, Runnable::run)) {
            assertTrue(events.opened.await(5, TimeUnit.SECONDS));
            service.request(new PdfPreviewService.Request(1, 0, PdfPreviewService.ZoomMode.CUSTOM, 4, 900, 900, 2));
            assertEquals(4, events.next().scale());
            assertEquals(List.of(PdfPreviewService.Quality.GEOMETRY, PdfPreviewService.Quality.DRAFT, PdfPreviewService.Quality.READY), events.qualities);
            assertEquals(2, scales.size());
            assertTrue(scales.get(0) < scales.get(1));
            assertTrue(2000d * 3000 * scales.get(1) * scales.get(1) <= 8_000_002);
        }
    }

    @Test void closeReturnsImmediatelyAndClosesTheDocumentAfterRendering() throws Exception {
        CountDownLatch rendering = new CountDownLatch(1), release = new CountDownLatch(1);
        FakePdf pdf = new FakePdf() {
            @Override public BufferedImage render(int page, float scale) throws IOException {
                rendering.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                return super.render(page, scale);
            }
        };
        Events events = new Events();
        PdfPreviewService service = new PdfPreviewService(() -> pdf, events, Runnable::run);
        try {
            assertTrue(events.opened.await(5, TimeUnit.SECONDS));
            service.request(request(1, 0));
            assertTrue(rendering.await(5, TimeUnit.SECONDS));
            assertTimeout(Duration.ofMillis(250), service::close);
            assertFalse(pdf.closed);
            release.countDown();
            service.termination().get(5, TimeUnit.SECONDS);
            assertTrue(pdf.closed);
            assertTrue(events.ready.isEmpty(), "Disposed editors must not receive late images.");
        } finally { release.countDown(); service.close(); }
    }
}

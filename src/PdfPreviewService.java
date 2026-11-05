// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/** One PDFBox owner thread, a latest-request queue, progressive rendering, and a byte-bounded LRU. */
final class PdfPreviewService implements AutoCloseable {
    enum ZoomMode { FIT_PAGE, FIT_WIDTH, CUSTOM }
    enum Quality { GEOMETRY, DRAFT, READY }
    record Request(long id, int page, ZoomMode mode, float zoom, int width, int height, double pixelRatio) { }
    record Frame(long id, int page, PageGeometry geometry, float scale, BufferedImage image, Quality quality) { }
    interface Listener {
        void opened(int pageCount);
        void rendered(Frame frame);
        void failed(long id, int page, Exception failure);
    }
    interface Backend extends AutoCloseable {
        int pageCount();
        PageGeometry geometry(int page);
        BufferedImage render(int page, float scale) throws IOException;
        default java.util.List<PageGeometry> geometries() {
            java.util.List<PageGeometry> all = new java.util.ArrayList<>();
            for (int i = 0; i < pageCount(); i++) all.add(geometry(i));
            return all;
        }
        @Override void close() throws IOException;
    }
    private record Key(int page, float scale) { }
    private static final long CACHE_BYTES = 64L * 1024 * 1024;
    private static final long FINAL_PIXELS = 8_000_000;
    private static final long DRAFT_PIXELS = 800_000;
    private final Object lifecycle = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pdf-preview-renderer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicReference<Request> pending = new AtomicReference<>();
    private final AtomicLong newest = new AtomicLong();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final Map<Key, BufferedImage> cache = new LinkedHashMap<>(16, .75f, true);
    private final Listener listener;
    private final Executor callbacks;
    private long cacheBytes;
    private Backend backend;

    PdfPreviewService(Path source, Listener listener) {
        this(() -> new PdfBoxBackend(source), listener, SwingUtilities::invokeLater);
    }

    PdfPreviewService(Callable<Backend> factory, Listener listener, Executor callbacks) {
        this.listener = listener;
        this.callbacks = callbacks;
        worker.execute(() -> {
            try {
                backend = factory.call();
                int count = backend.pageCount();
                if (count < 1 || count > 10_000) throw new IOException("PDF must contain between 1 and 10,000 pages.");
                post(() -> listener.opened(count));
            } catch (Exception failure) { post(() -> listener.failed(0, 0, failure)); }
        });
    }

    /** Invalidate an in-flight frame immediately, even while the UI debounce timer is running. */
    void supersede(long id) {
        synchronized (lifecycle) {
            if (closed.get()) return;
            newest.set(id);
            pending.set(null);
        }
    }

    void request(Request request) {
        synchronized (lifecycle) {
            if (closed.get()) return;
            newest.set(request.id());
            pending.set(request);
            schedule();
        }
    }

    /** Reads every page's crop box and rotation on the PDFBox thread; results arrive on the callback executor. */
    void geometries(java.util.function.Consumer<java.util.List<PageGeometry>> done, java.util.function.Consumer<Exception> failed) {
        synchronized (lifecycle) {
            if (closed.get()) return;
            worker.execute(() -> {
                try {
                    if (backend == null) throw new IOException("The PDF is not open. Try opening it again.");
                    var all = java.util.List.copyOf(backend.geometries());
                    post(() -> done.accept(all));
                } catch (Exception failure) { post(() -> failed.accept(failure)); }
            });
        }
    }

    private void schedule() {
        if (!closed.get() && pending.get() != null && draining.compareAndSet(false, true)) worker.execute(this::drain);
    }

    private void drain() {
        try {
            Request request;
            while (!closed.get() && (request = pending.getAndSet(null)) != null) {
                Request active = request;
                try { render(active); }
                catch (Exception failure) { if (current(active)) post(() -> { if (current(active)) listener.failed(active.id(), active.page(), failure); }); }
            }
        } finally {
            synchronized (lifecycle) { draining.set(false); schedule(); }
        }
    }

    private void render(Request request) throws IOException {
        if (!current(request)) return;
        if (backend == null) throw new IOException("The PDF is not open. Try opening it again.");
        if (request.page() < 0 || request.page() >= backend.pageCount()) throw new IOException("Page is outside the document.");
        PageGeometry geometry = backend.geometry(request.page()); // Only the requested page is inspected.
        float displayScale = displayScale(geometry, request);
        emit(request, geometry, displayScale, null, Quality.GEOMETRY);
        float finalScale = pixelScale(geometry, (float) (displayScale * Math.max(1, request.pixelRatio())), FINAL_PIXELS);
        Key suitable = bestCached(request.page(), finalScale, true);
        if (suitable != null) {
            emit(request, geometry, displayScale, cache.get(suitable), Quality.READY);
            return;
        }
        Key previous = bestCached(request.page(), finalScale, false);
        if (previous != null) emit(request, geometry, displayScale, cache.get(previous), Quality.DRAFT);
        float draftScale = pixelScale(geometry, finalScale, DRAFT_PIXELS);
        if (previous == null && draftScale < finalScale * .85f) {
            if (!current(request)) return;
            BufferedImage draft = backend.render(request.page(), draftScale);
            remember(new Key(request.page(), draftScale), draft);
            emit(request, geometry, displayScale, draft, Quality.DRAFT);
        }
        if (!current(request)) return;
        BufferedImage image = backend.render(request.page(), finalScale);
        remember(new Key(request.page(), finalScale), image);
        emit(request, geometry, displayScale, image, Quality.READY);
    }

    static float displayScale(PageGeometry geometry, Request request) {
        double width = Math.max(1, request.width() - 48) / (double) geometry.displayWidth();
        double height = Math.max(1, request.height() - 48) / (double) geometry.displayHeight();
        double scale = switch (request.mode()) {
            case FIT_PAGE -> Math.min(width, height);
            case FIT_WIDTH -> width;
            case CUSTOM -> Math.max(.25, Math.min(4, request.zoom()));
        };
        // Bound Swing's virtual canvas as well as the independently bounded image allocation.
        return (float) Math.min(Math.min(scale, 4), 32_000d / Math.max(geometry.width(), geometry.height()));
    }

    private static float pixelScale(PageGeometry geometry, float requested, long pixels) throws IOException {
        double scale = Math.min(requested, Math.sqrt(pixels / ((double) geometry.width() * geometry.height())));
        scale = Math.min(scale, 8192d / Math.max(geometry.width(), geometry.height()));
        if (!Double.isFinite(scale) || scale <= 0 || geometry.width() * scale < 1 || geometry.height() * scale < 1) {
            throw new IOException("This page has unsupported dimensions.");
        }
        return (float) scale;
    }

    private Key bestCached(int page, float target, boolean sufficient) {
        Key best = null;
        for (Key key : cache.keySet()) {
            if (key.page() != page || sufficient && key.scale() < target * .98f) continue;
            if (best == null || (sufficient ? key.scale() < best.scale() : key.scale() > best.scale())) best = key;
        }
        return best;
    }

    private void remember(Key key, BufferedImage image) {
        long bytes = bytes(image);
        if (bytes > CACHE_BYTES) return;
        BufferedImage replaced = cache.remove(key);
        if (replaced != null) cacheBytes -= bytes(replaced);
        while (!cache.isEmpty() && (cacheBytes + bytes > CACHE_BYTES || cache.size() >= 8)) {
            Key oldest = cache.keySet().iterator().next();
            cacheBytes -= bytes(cache.remove(oldest));
        }
        cache.put(key, image);
        cacheBytes += bytes;
    }

    private static long bytes(BufferedImage image) { return (long) image.getWidth() * image.getHeight() * 4; }
    private boolean current(Request request) { return !closed.get() && newest.get() == request.id(); }
    private void emit(Request request, PageGeometry geometry, float scale, BufferedImage image, Quality quality) {
        if (current(request)) post(() -> { if (current(request)) listener.rendered(new Frame(request.id(), request.page(), geometry, scale, image, quality)); });
    }
    private void post(Runnable action) { callbacks.execute(() -> { if (!closed.get()) action.run(); }); }
    CompletableFuture<Void> termination() { return terminated; }

    @Override public void close() {
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) return;
            pending.set(null);
            worker.execute(() -> {
                try { if (backend != null) backend.close(); terminated.complete(null); }
                catch (IOException ex) {
                    terminated.completeExceptionally(ex);
                    System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "PDF preview cleanup failed", ex);
                } finally { cache.clear(); cacheBytes = 0; }
            });
            worker.shutdown(); // Close is sequenced after rendering; the EDT never waits for PDFBox.
        }
    }

    private static final class PdfBoxBackend implements Backend {
        private final PDDocument document;
        private final PDFRenderer renderer;
        PdfBoxBackend(Path path) throws IOException {
            document = Loader.loadPDF(AppPaths.requireLocal(path).toFile());
            renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
        }
        @Override public int pageCount() { return document.getNumberOfPages(); }
        @Override public PageGeometry geometry(int index) {
            var page = document.getPage(index);
            var crop = page.getCropBox();
            return new PageGeometry(crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight(), page.getRotation());
        }
        @Override public BufferedImage render(int page, float scale) throws IOException { return renderer.renderImage(page, scale); }
        @Override public java.util.List<PageGeometry> geometries() {
            java.util.List<PageGeometry> all = new java.util.ArrayList<>();
            for (var page : document.getPages()) { // Sequential traversal avoids repeated page-tree lookups.
                var crop = page.getCropBox();
                all.add(new PageGeometry(crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight(), page.getRotation()));
            }
            return all;
        }
        @Override public void close() throws IOException { document.close(); }
    }
}

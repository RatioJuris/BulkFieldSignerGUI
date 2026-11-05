// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * HTTPS access to GitHub for update discovery and downloads. Redirects are followed manually and only to
 * GitHub's own hosts; anything else is refused. No credentials are sent or logged.
 */
final class UpdateHttp {
    static final Set<String> GITHUB_HOSTS = Set.of("api.github.com", "github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "update-download-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpClient client;
    private final boolean loopbackForTests;
    private final Duration requestTimeout;

    private UpdateHttp(boolean loopbackForTests, Duration requestTimeout) {
        this.loopbackForTests = loopbackForTests;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static UpdateHttp production() { return new UpdateHttp(false, Duration.ofSeconds(30)); }

    /** Tests only: additionally permits plain HTTP to 127.0.0.1. */
    static UpdateHttp forLoopbackTests(Duration requestTimeout) { return new UpdateHttp(true, requestTimeout); }

    static String userAgent() { return "PDF-DSC-TSA-Bulk-Signer/" + Product.VERSION + " (+" + Product.REPOSITORY + ")"; }

    void checkAllowed(URI uri) throws UpdateException {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean loopback = loopbackForTests && "http".equals(uri.getScheme()) && "127.0.0.1".equals(host);
        if (!loopback && !("https".equals(uri.getScheme()) && GITHUB_HOSTS.contains(host) && uri.getPort() == -1 && uri.getUserInfo() == null)) {
            throw new UpdateException(UpdateException.Category.INVALID_RESPONSE, "Refusing to contact " + uri.getScheme() + "://" + host);
        }
    }

    /** A small response read fully into memory (API documents, manifests, signatures). */
    byte[] get(URI uri, String accept, int maxBytes) throws UpdateException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        stream(uri, accept, maxBytes, -1, body, bytes -> { }, () -> false);
        return body.toByteArray();
    }

    /**
     * Streams a response into {@code out}. With {@code expectedSize >= 0}, the response must contain exactly
     * that many bytes. Returns the byte count.
     */
    long stream(URI uri, String accept, long maxBytes, long expectedSize, OutputStream out, LongConsumer progress, BooleanSupplier cancelled)
            throws UpdateException {
        URI current = uri;
        for (int hop = 0; hop <= 5; hop++) {
            checkAllowed(current);
            HttpRequest request = HttpRequest.newBuilder(current).timeout(requestTimeout).header("User-Agent", userAgent())
                    .header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28").GET().build();
            HttpResponse<InputStream> response;
            try { response = client.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
            catch (HttpConnectTimeoutException ex) { throw new UpdateException(UpdateException.Category.TIMEOUT, "Connecting to " + current.getHost() + " timed out.", ex); }
            catch (HttpTimeoutException ex) { throw new UpdateException(UpdateException.Category.TIMEOUT, current.getHost() + " did not respond in time.", ex); }
            catch (ConnectException | java.nio.channels.UnresolvedAddressException ex) {
                throw new UpdateException(UpdateException.Category.NETWORK, "Cannot reach " + current.getHost() + ". Check the internet connection.", ex);
            }
            catch (IOException ex) { throw new UpdateException(UpdateException.Category.NETWORK, "Network error contacting " + current.getHost() + ": " + ex.getMessage(), ex); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new UpdateException(UpdateException.Category.CANCELLED, "Interrupted.", ex); }
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                close(response.body());
                String location = response.headers().firstValue("location").orElseThrow(() ->
                        new UpdateException(UpdateException.Category.INVALID_RESPONSE, "Redirect without a location."));
                current = current.resolve(location);
                continue;
            }
            if (status == 403 || status == 429) {
                close(response.body());
                var headers = response.headers();
                boolean limited = status == 429 || "0".equals(headers.firstValue("x-ratelimit-remaining").orElse(null))
                        || headers.firstValue("retry-after").isPresent();
                if (limited) {
                    Instant retry = headers.firstValue("retry-after").map(v -> Instant.now().plusSeconds(parseLong(v, 60)))
                            .or(() -> headers.firstValue("x-ratelimit-reset").map(v -> Instant.ofEpochSecond(parseLong(v, Instant.now().getEpochSecond() + 3600))))
                            .orElse(Instant.now().plusSeconds(3600));
                    throw new UpdateException(UpdateException.Category.RATE_LIMITED, "GitHub's request limit was reached. Try again later.", status, retry, null);
                }
            }
            if (status != 200) {
                close(response.body());
                throw new UpdateException(UpdateException.Category.HTTP_STATUS, "GitHub returned HTTP " + status + ".", status, null, null);
            }
            long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (expectedSize >= 0 && declared >= 0 && declared != expectedSize) {
                close(response.body());
                throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The server reports " + declared + " bytes; " + expectedSize + " were expected.");
            }
            if (declared > maxBytes) { close(response.body()); throw new UpdateException(UpdateException.Category.INVALID_RESPONSE, "The response is too large."); }
            return copy(response.body(), out, maxBytes, expectedSize, progress, cancelled, current.getHost());
        }
        throw new UpdateException(UpdateException.Category.INVALID_RESPONSE, "Too many redirects.");
    }

    private static long copy(InputStream in, OutputStream out, long maxBytes, long expectedSize, LongConsumer progress,
                             BooleanSupplier cancelled, String host) throws UpdateException {
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        boolean[] stalled = new boolean[1];
        // A stalled body read is aborted by closing the stream.
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - lastActivity.get() > IDLE_TIMEOUT.toNanos()) { stalled[0] = true; close(in); }
        }, 1, 1, TimeUnit.SECONDS);
        long total = 0;
        try (in) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new UpdateException(UpdateException.Category.CANCELLED, "Download cancelled.");
                lastActivity.set(System.nanoTime());
                total += count;
                if (total > maxBytes || expectedSize >= 0 && total > expectedSize) {
                    throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The download is larger than expected.");
                }
                out.write(buffer, 0, count);
                progress.accept(total);
            }
        } catch (IOException ex) {
            if (stalled[0]) throw new UpdateException(UpdateException.Category.TIMEOUT, host + " stopped sending data.", ex);
            throw new UpdateException(UpdateException.Category.INCOMPLETE, "The download was interrupted: " + ex.getMessage(), ex);
        } finally { watchdog.cancel(false); }
        if (expectedSize >= 0 && total != expectedSize) {
            throw new UpdateException(UpdateException.Category.INCOMPLETE, "Only " + total + " of " + expectedSize + " bytes were received.");
        }
        return total;
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value.strip()); } catch (NumberFormatException ex) { return fallback; }
    }

    private static void close(InputStream in) { try { in.close(); } catch (IOException ignored) { /* Already closing. */ } }
}

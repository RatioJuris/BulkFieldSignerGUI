// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Discovery and download against a local HTTP server standing in for GitHub; no external network access. */
class UpdateNetworkTest {
    private static final String REPO = "RatioJuris/BulkFieldSignerGUI";
    private static KeyPair publisher;
    private HttpServer server;
    private String base;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final AtomicInteger listRequests = new AtomicInteger();
    private Path downloads;

    interface Route { void handle(HttpExchange exchange) throws IOException; }

    @BeforeAll static void keys() throws Exception {
        AppPaths.initialize();
        publisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestURI().getRawPath() + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            if (key.endsWith("/releases?per_page=30")) listRequests.incrementAndGet();
            assertTrue(exchange.getRequestHeaders().getFirst("User-Agent").startsWith("PDF-DSC-TSA-Bulk-Signer/"));
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"), "No credentials are ever sent");
            Route route = routes.get(key);
            try {
                if (route == null) { exchange.sendResponseHeaders(404, -1); }
                else route.handle(exchange);
            } finally { exchange.close(); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        downloads = Files.createTempDirectory(AppPaths.TEMP, "update-net-");
    }

    @AfterEach void stopServer() {
        try { server.stop(0); } catch (IllegalStateException alreadyStopped) { /* Stopped by the test. */ }
        UpdateInstaller.deleteTree(downloads);
    }

    private UpdateHttp http(Duration timeout) { return UpdateHttp.forLoopbackTests(timeout); }

    private GitHubReleaseClient client(UpdateHttp http) {
        return new GitHubReleaseClient(http, URI.create(base + "/repos/" + REPO + "/"), base + "/" + REPO, "https://github.com/" + REPO);
    }

    private static Route bytes(byte[] body) {
        return exchange -> { exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); };
    }

    private static byte[] zipLike(int size) {
        byte[] data = new byte[size];
        data[0] = 'P'; data[1] = 'K'; data[2] = 3; data[3] = 4;
        for (int i = 4; i < size; i++) data[i] = (byte) (i * 31);
        return data;
    }

    private static String sha256(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }

    /** Publishes a release: assets are served, and its JSON is returned. {@code githubDigests} can be overridden to simulate tampering. */
    private String publish(String tag, boolean draft, boolean prerelease, Map<String, byte[]> assets, Map<String, String> githubDigests) throws Exception {
        List<String> assetJson = new ArrayList<>();
        for (var asset : assets.entrySet()) {
            String path = "/" + REPO + "/releases/download/" + tag + "/" + asset.getKey();
            routes.put(path, bytes(asset.getValue()));
            String digest = githubDigests.getOrDefault(asset.getKey(), sha256(asset.getValue()));
            assetJson.add("{\"name\":" + Json.quote(asset.getKey()) + ",\"size\":" + asset.getValue().length + ",\"state\":\"uploaded\",\"digest\":\"sha256:"
                    + digest + "\",\"browser_download_url\":" + Json.quote(base + path) + "}");
        }
        return "{\"tag_name\":" + Json.quote(tag) + ",\"name\":" + Json.quote(tag) + ",\"body\":\"Release notes for " + tag + "\",\"draft\":" + draft
                + ",\"prerelease\":" + prerelease + ",\"published_at\":\"2026-11-05T10:00:00Z\",\"html_url\":\"https://github.com/" + REPO
                + "/releases/tag/" + tag + "\",\"assets\":[" + String.join(",", assetJson) + "]}";
    }

    /** A signed release with one Windows portable package. */
    private String signedRelease(String version, String type, byte[] pkg, KeyPair signer, Map<String, String> githubDigests) throws Exception {
        String name = "PDF-DSC-TSA-Bulk-Signer-" + version + "-windows-x64-portable.zip";
        UpdateManifest manifest = new UpdateManifest(Product.NAME, SemanticVersion.parse(version), SemanticVersion.parse("2.0.0"),
                UpdateType.valueOf(type), type.equals("A"), false, type.equals("A") ? "Critical fix." : null,
                URI.create(Product.REPOSITORY + "/releases/tag/v" + version), Map.of("windows-x64-portable", new UpdateManifest.Asset(name, pkg.length, sha256(pkg))));
        byte[] json = manifest.toJson().getBytes(StandardCharsets.UTF_8);
        byte[] signature = UpdateTrust.sign(json, signer.getPrivate()).getBytes(StandardCharsets.US_ASCII);
        return publish("v" + version, false, false, Map.of(UpdateManifest.FILE, json, UpdateManifest.SIGNATURE_FILE, signature, name, pkg), githubDigests);
    }

    private void serveReleases(String latest, String... all) {
        routes.put("/repos/" + REPO + "/releases/latest", bytes(latest.getBytes(StandardCharsets.UTF_8)));
        routes.put("/repos/" + REPO + "/releases?per_page=30", bytes(("[" + String.join(",", all) + "]").getBytes(StandardCharsets.UTF_8)));
    }

    private UpdateDiscovery discovery(String platform, Platform.Channel channel) {
        return new UpdateDiscovery(client(http(Duration.ofSeconds(10))), publisher.getPublic(), platform, channel);
    }

    @Test void signedReleaseIsDiscoveredWithItsPlatformPackageAndDraftsArePassedOver() throws Exception {
        byte[] pkg = zipLike(4096);
        String v210 = signedRelease("2.1.0", "B", pkg, publisher, Map.of());
        String draft = publish("v2.3.0", true, false, Map.of(), Map.of());
        String pre = publish("v2.2.0", false, true, Map.of(), Map.of());
        serveReleases(v210, draft, pre, v210, publish("v1.0.0", false, false, Map.of(), Map.of()));
        var result = discovery("windows-x64", Platform.Channel.WINDOWS_PORTABLE).discover(SemanticVersion.parse("2.0.0"), null);
        assertEquals(1, result.candidates().size(), "Drafts and prereleases are not offered");
        var candidate = result.candidates().getFirst();
        assertEquals(UpdateType.B, candidate.manifest().type());
        assertNotNull(candidate.download());
        assertEquals("Release notes for v2.1.0", candidate.release().body());

        var linux = discovery("linux-x64", Platform.Channel.LINUX_PORTABLE).discover(SemanticVersion.parse("2.0.0"), null);
        assertNull(linux.candidates().getFirst().download(), "A Windows package is never offered to Linux");
        assertTrue(linux.candidates().getFirst().noPackageReason().contains("linux-x64"));
        var store = discovery("windows-x64", Platform.Channel.WINDOWS_STORE).discover(SemanticVersion.parse("2.0.0"), null);
        assertNull(store.candidates().getFirst().download(), "Store installations never download GitHub packages");
    }

    @Test void upToDateCheckUsesOnlyTheLatestEndpoint() throws Exception {
        serveReleases(publish("v1.0.0", false, false, Map.of(), Map.of()));
        var result = discovery("windows-x64", Platform.Channel.WINDOWS_PORTABLE).discover(SemanticVersion.parse("2.0.0"), null);
        assertTrue(result.candidates().isEmpty());
        assertEquals(SemanticVersion.parse("1.0.0"), result.latestPublished());
        assertEquals(0, listRequests.get(), "The full release list is not requested when nothing is newer");
    }

    @Test void untrustedReleasesBecomeProblemsNotUpdates() throws Exception {
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] pkg = zipLike(2048);
        String forged = signedRelease("2.3.0", "C", pkg, attacker, Map.of());
        String tampered = signedRelease("2.2.0", "C", pkg, publisher, Map.of("PDF-DSC-TSA-Bulk-Signer-2.2.0-windows-x64-portable.zip", "b".repeat(64)));
        String unsigned = publish("v2.1.0", false, false, Map.of("PDF-DSC-TSA-Bulk-Signer-2.1.0-windows-x64-portable.zip", pkg), Map.of());
        serveReleases(forged, forged, tampered, unsigned);
        var result = discovery("windows-x64", Platform.Channel.WINDOWS_PORTABLE).discover(SemanticVersion.parse("2.0.0"), null);
        assertTrue(result.candidates().isEmpty());
        assertEquals(3, result.problems().size());
        String messages = result.problems().toString();
        assertTrue(messages.contains("not signed by the publisher"), messages);
        assertTrue(messages.contains("different SHA-256"), messages);
        assertTrue(messages.contains("no signed"), messages);
        var outcome = UpdateDecision.decide(SemanticVersion.parse("2.0.0"), result.latestPublished(), Platform.Channel.WINDOWS_PORTABLE,
                result.candidates(), result.problems(), UpdatePreferences.defaults(), java.time.Instant.now(), false);
        assertEquals(UpdateDecision.Kind.METADATA_PROBLEM, outcome.kind());
    }

    @Test void downloadIsVerifiedAndCorruptOrIncompleteFilesAreDiscarded() throws Exception {
        byte[] pkg = zipLike(200_000);
        String name = "PDF-DSC-TSA-Bulk-Signer-2.1.0-windows-x64-portable.zip";
        URI url = URI.create(base + "/" + REPO + "/releases/download/v2.1.0/" + name);
        routes.put(url.getRawPath(), bytes(pkg));
        var asset = new GitHubReleaseClient.Asset(name, pkg.length, url, null);
        UpdateDownloader downloader = new UpdateDownloader(http(Duration.ofSeconds(5)));
        long[] progress = {0};
        Path file = downloader.download(asset, new UpdateManifest.Asset(name, pkg.length, sha256(pkg)), downloads, b -> progress[0] = b, () -> false);
        assertArrayEquals(pkg, Files.readAllBytes(file));
        assertEquals(pkg.length, progress[0]);

        assertCategory(UpdateException.Category.CHECKSUM, () -> downloader.download(asset, new UpdateManifest.Asset(name, pkg.length, "c".repeat(64)), downloads, b -> { }, () -> false));
        assertCategory(UpdateException.Category.INVALID_PACKAGE, () -> downloader.download(asset, new UpdateManifest.Asset(name, pkg.length + 1, sha256(pkg)), downloads, b -> { }, () -> false));
        // Headers and part of the body arrive, then the connection drops.
        routes.put(url.getRawPath(), exchange -> {
            exchange.sendResponseHeaders(200, pkg.length);
            exchange.getResponseBody().write(pkg, 0, 1000);
            exchange.getResponseBody().flush();
        });
        assertCategory(UpdateException.Category.INCOMPLETE, () -> downloader.download(asset, new UpdateManifest.Asset(name, pkg.length, sha256(pkg)), downloads, b -> { }, () -> false));
        routes.put(url.getRawPath(), exchange -> exchange.sendResponseHeaders(500, -1));
        assertCategory(UpdateException.Category.HTTP_STATUS, () -> downloader.download(asset, new UpdateManifest.Asset(name, pkg.length, sha256(pkg)), downloads, b -> { }, () -> false));
        byte[] notZip = new byte[4096];
        routes.put(url.getRawPath(), bytes(notZip));
        var notZipAsset = new GitHubReleaseClient.Asset(name, notZip.length, url, null);
        assertCategory(UpdateException.Category.INVALID_PACKAGE, () -> downloader.download(notZipAsset, new UpdateManifest.Asset(name, notZip.length, sha256(notZip)), downloads, b -> { }, () -> false));
        try (var left = Files.list(downloads)) {
            assertEquals(List.of(name), left.map(p -> p.getFileName().toString()).toList(), "Only the verified download remains; no partial files");
        }
    }

    @Test void timeoutsNetworkFailuresRedirectsAndRateLimitsAreClassified() throws Exception {
        routes.put("/repos/" + REPO + "/releases/latest", exchange -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(200, -1);
        });
        assertCategory(UpdateException.Category.TIMEOUT, () -> client(http(Duration.ofSeconds(1))).latest());

        routes.put("/repos/" + REPO + "/releases/latest", exchange -> {
            exchange.getResponseHeaders().add("x-ratelimit-remaining", "0");
            exchange.getResponseHeaders().add("x-ratelimit-reset", "4102444800");
            exchange.sendResponseHeaders(403, -1);
        });
        UpdateException limited = assertCategory(UpdateException.Category.RATE_LIMITED, () -> client(http(Duration.ofSeconds(5))).latest());
        assertEquals(java.time.Instant.ofEpochSecond(4102444800L), limited.retryAfter());

        routes.put("/repos/" + REPO + "/releases/latest", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://evil.example/payload.json");
            exchange.sendResponseHeaders(302, -1);
        });
        assertCategory(UpdateException.Category.INVALID_RESPONSE, () -> client(http(Duration.ofSeconds(5))).latest());

        routes.remove("/repos/" + REPO + "/releases/latest");
        assertNull(client(http(Duration.ofSeconds(5))).latest(), "A repository without releases is not an error");

        int port = server.getAddress().getPort();
        server.stop(0);
        var offline = new GitHubReleaseClient(http(Duration.ofSeconds(5)), URI.create("http://127.0.0.1:" + port + "/repos/" + REPO + "/"), base, base);
        assertCategory(UpdateException.Category.NETWORK, offline::latest);

        UpdateHttp production = UpdateHttp.production();
        for (String refused : List.of("http://github.com/x", "https://evil.example/x", "https://github.com:8443/x", "https://user@github.com/x", "file:///etc/passwd")) {
            assertCategory(UpdateException.Category.INVALID_RESPONSE, () -> { production.checkAllowed(URI.create(refused)); return null; });
        }
        production.checkAllowed(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"));
        production.checkAllowed(URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/1"));
    }

    private interface Call { Object run() throws Exception; }

    private static UpdateException assertCategory(UpdateException.Category expected, Call call) {
        UpdateException failure = assertThrows(UpdateException.class, call::run);
        assertEquals(expected, failure.category(), failure.getMessage());
        return failure;
    }
}

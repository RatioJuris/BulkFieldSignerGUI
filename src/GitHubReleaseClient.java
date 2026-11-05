// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Published releases of the canonical repository, from GitHub's public Releases API (no credentials). */
final class GitHubReleaseClient {
    static final String REPOSITORY = "RatioJuris/BulkFieldSignerGUI";
    private static final int MAX_API_BYTES = 4 * 1024 * 1024;

    record Asset(String name, long size, URI downloadUrl, String sha256Digest) { }

    record Release(String tag, String name, String body, boolean draft, boolean prerelease, Instant published, URI page, List<Asset> assets) {
        Asset asset(String assetName) { return assets.stream().filter(a -> a.name().equals(assetName)).findFirst().orElse(null); }
    }

    private final UpdateHttp http;
    private final URI api;
    private final String downloadPrefix;
    private final String pagePrefix;

    GitHubReleaseClient(UpdateHttp http) {
        this(http, URI.create("https://api.github.com/repos/" + REPOSITORY + "/"), "https://github.com/" + REPOSITORY, "https://github.com/" + REPOSITORY);
    }

    /** Release pages must live under {@code pageSite} and downloads under {@code downloadSite} (both the repository's web address in production). */
    GitHubReleaseClient(UpdateHttp http, URI api, String downloadSite, String pageSite) {
        this.http = http;
        this.api = api;
        this.downloadPrefix = downloadSite + "/releases/download/";
        this.pagePrefix = pageSite + "/releases/tag/";
    }

    /** The release GitHub marks as latest, or null if the repository has none. Drafts and prereleases are never "latest". */
    Release latest() throws UpdateException {
        try { return parseRelease(Json.object(json("releases/latest"), "Release")); }
        catch (UpdateException ex) {
            if (ex.httpStatus() == 404) return null;
            throw ex;
        } catch (Json.JsonException ex) { throw invalid(ex); }
    }

    /** The most recent releases, newest first. */
    List<Release> releases() throws UpdateException {
        try {
            List<Release> releases = new ArrayList<>();
            for (Object item : Json.array(json("releases?per_page=30"), "Release list")) releases.add(parseRelease(Json.object(item, "Release")));
            return releases;
        } catch (Json.JsonException ex) { throw invalid(ex); }
    }

    byte[] download(Asset asset, int maxBytes) throws UpdateException {
        if (asset.size() > maxBytes) throw new UpdateException(UpdateException.Category.METADATA, asset.name() + " is unexpectedly large.");
        return http.get(asset.downloadUrl(), "application/octet-stream", maxBytes);
    }

    private Object json(String path) throws UpdateException, Json.JsonException {
        byte[] body = http.get(api.resolve(path), "application/vnd.github+json", MAX_API_BYTES);
        return Json.parse(new String(body, StandardCharsets.UTF_8));
    }

    private Release parseRelease(Map<String, Object> json) throws Json.JsonException, UpdateException {
        String tag = Json.string(json, "tag_name", true);
        URI page = URI.create(Json.string(json, "html_url", true));
        // The release must belong to the canonical repository.
        if (!page.toString().equals(pagePrefix + encode(tag))) throw new UpdateException(UpdateException.Category.METADATA, "Release " + tag + " is not from " + REPOSITORY + ".");
        List<Asset> assets = new ArrayList<>();
        for (Object item : Json.array(json.getOrDefault("assets", List.of()), "Assets")) {
            Map<String, Object> asset = Json.object(item, "Asset");
            if (!"uploaded".equals(Json.string(asset, "state", false) == null ? "uploaded" : Json.string(asset, "state", false))) continue;
            String name = Json.string(asset, "name", true);
            URI url = URI.create(Json.string(asset, "browser_download_url", true));
            // Only files attached to this release of this repository are accepted.
            if (!url.toString().equals(downloadPrefix + encode(tag) + "/" + encode(name))) continue;
            String digest = Json.string(asset, "digest", false);
            String sha = digest != null && digest.startsWith("sha256:") ? digest.substring(7) : null;
            assets.add(new Asset(name, Json.integer(asset, "size"), url, sha));
        }
        String published = Json.string(json, "published_at", false);
        return new Release(tag, String.valueOf(json.getOrDefault("name", tag)), Json.string(json, "body", false) == null ? "" : Json.string(json, "body", false),
                Json.bool(json, "draft"), Json.bool(json, "prerelease"), published == null ? null : Instant.parse(published), page, List.copyOf(assets));
    }

    private static String encode(String segment) { return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static UpdateException invalid(Json.JsonException ex) {
        return new UpdateException(UpdateException.Category.INVALID_RESPONSE, "GitHub returned an unexpected response: " + ex.getMessage(), ex);
    }
}

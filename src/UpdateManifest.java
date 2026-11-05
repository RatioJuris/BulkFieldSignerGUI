// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Validated contents of {@code update-manifest.json}, the signed metadata attached to every GitHub release.
 * The manifest classifies the release (Type A, B or C) and lists one package per platform and channel with
 * its exact size and SHA-256. Anything malformed, inconsistent or unexpected is rejected.
 */
public record UpdateManifest(String product, SemanticVersion version, SemanticVersion minimumSupportedVersion, UpdateType type,
                             boolean mandatory, boolean securityUpdate, String reason, URI releaseNotesUrl, Map<String, Asset> assets) {
    public static final String FILE = "update-manifest.json";
    public static final String SIGNATURE_FILE = "update-manifest.json.sig";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_BYTES = 64 * 1024;
    public static final long MAX_PACKAGE_BYTES = 1024L * 1024 * 1024;
    static final String RELEASE_PAGE_PREFIX = Product.REPOSITORY + "/releases/tag/";
    static final String ASSET_PREFIX = "PDF-DSC-TSA-Bulk-Signer-";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> KEYS = Set.of("formatVersion", "product", "version", "minimumSupportedVersion", "updateType",
            "mandatory", "securityUpdate", "reason", "releaseNotesUrl", "assets");

    /** One downloadable package. */
    public record Asset(String name, long size, String sha256) { }

    /** The package format used by each installation channel, or null when updates are not delivered as a download. */
    static String format(Platform.Channel channel) {
        return switch (channel) {
            case WINDOWS_PORTABLE, LINUX_PORTABLE -> "portable";
            case WINDOWS_MSI -> "msi";
            case LINUX_DEB -> "deb";
            case MACOS_DMG -> "dmg";
            case WINDOWS_STORE, SOURCE -> null;
        };
    }

    /** The manifest key of a package, for example {@code windows-x64-portable}. */
    static String key(String platformId, String format) { return platformId + "-" + format; }

    /** The required file name of a package, derived from its key; release assets with other names are not accepted. */
    public static String expectedName(SemanticVersion version, String key) {
        int split = key.lastIndexOf('-');
        String platform = key.substring(0, split), format = key.substring(split + 1);
        String suffix = switch (format) {
            case "portable" -> platform.startsWith("windows-") ? "-portable.zip" : ".tar.gz";
            case "msi" -> ".msi";
            case "deb" -> ".deb";
            case "dmg" -> ".dmg";
            default -> throw new IllegalArgumentException("Unknown package format: " + format);
        };
        return ASSET_PREFIX + version + "-" + platform + suffix;
    }

    /** Package keys this application can ever install. */
    public static final Set<String> SUPPORTED_KEYS = Set.of("windows-x64-portable", "windows-x64-msi", "windows-arm64-portable", "windows-arm64-msi",
            "linux-x64-portable", "linux-x64-deb", "linux-arm64-portable", "linux-arm64-deb", "macos-x64-dmg", "macos-arm64-dmg");

    /** The package for this platform and channel, or null if the release does not provide one. */
    public Asset assetFor(String platformId, Platform.Channel channel) {
        String format = format(channel);
        return format == null ? null : assets.get(key(platformId, format));
    }

    public static UpdateManifest parse(byte[] bytes) throws InvalidManifestException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new InvalidManifestException("The update manifest is empty or too large.");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException ex) { throw new InvalidManifestException("The update manifest is not valid UTF-8."); }
        try {
            Map<String, Object> root = Json.object(Json.parse(text), "The update manifest");
            for (String key : root.keySet()) if (!KEYS.contains(key)) throw new InvalidManifestException("Unknown manifest field \"" + key + "\".");
            if (Json.integer(root, "formatVersion") != FORMAT_VERSION) throw new InvalidManifestException("Unsupported manifest format version.");
            String product = Json.string(root, "product", true);
            if (!Product.NAME.equals(product)) throw new InvalidManifestException("The manifest is for a different product: " + product);
            SemanticVersion version = version(Json.string(root, "version", true), "version");
            SemanticVersion minimum = version(Json.string(root, "minimumSupportedVersion", true), "minimumSupportedVersion");
            if (minimum.isNewerThan(version)) throw new InvalidManifestException("minimumSupportedVersion is newer than the release.");
            String typeText = Json.string(root, "updateType", true);
            UpdateType type;
            try { type = UpdateType.valueOf(typeText); }
            catch (IllegalArgumentException ex) { throw new InvalidManifestException("Unsupported update classification \"" + typeText + "\"."); }
            boolean mandatory = Json.bool(root, "mandatory");
            boolean security = Json.bool(root, "securityUpdate");
            // The classification table: A is always mandatory; B and C never are; security updates are Type A.
            if (mandatory != type.mandatory()) throw new InvalidManifestException("\"mandatory\" conflicts with update type " + type.name() + ".");
            if (security && type != UpdateType.A) throw new InvalidManifestException("Security updates must be Type A.");
            String reason = Json.string(root, "reason", type == UpdateType.A);
            if (reason != null && (reason.isBlank() || reason.length() > 2000)) throw new InvalidManifestException("\"reason\" must be 1 to 2000 characters.");
            String notes = Json.string(root, "releaseNotesUrl", true);
            if (!(RELEASE_PAGE_PREFIX + version.tag()).equals(notes)) {
                throw new InvalidManifestException("releaseNotesUrl must be " + RELEASE_PAGE_PREFIX + version.tag());
            }
            Map<String, Object> assetObjects = Json.object(root.get("assets"), "\"assets\"");
            if (assetObjects.isEmpty()) throw new InvalidManifestException("The manifest lists no packages.");
            Map<String, Asset> assets = new TreeMap<>();
            for (var entry : assetObjects.entrySet()) {
                String key = entry.getKey();
                if (!SUPPORTED_KEYS.contains(key)) throw new InvalidManifestException("Unsupported package \"" + key + "\".");
                Map<String, Object> asset = Json.object(entry.getValue(), "Package " + key);
                if (!asset.keySet().equals(Set.of("name", "size", "sha256"))) throw new InvalidManifestException("Package " + key + " must have exactly name, size and sha256.");
                String name = Json.string(asset, "name", true);
                if (!expectedName(version, key).equals(name)) throw new InvalidManifestException("Package " + key + " must be named " + expectedName(version, key) + ".");
                long size = Json.integer(asset, "size");
                if (size <= 0 || size > MAX_PACKAGE_BYTES) throw new InvalidManifestException("Package " + key + " has an invalid size.");
                String sha = Json.string(asset, "sha256", true);
                if (!SHA256.matcher(sha).matches()) throw new InvalidManifestException("Package " + key + " has an invalid SHA-256 (64 lowercase hex digits).");
                assets.put(key, new Asset(name, size, sha));
            }
            return new UpdateManifest(product, version, minimum, type, mandatory, security, reason, URI.create(notes), Map.copyOf(assets));
        } catch (Json.JsonException ex) {
            throw new InvalidManifestException("Malformed update manifest: " + ex.getMessage());
        }
    }

    private static SemanticVersion version(String text, String field) throws InvalidManifestException {
        if (text.startsWith("v")) throw new InvalidManifestException("\"" + field + "\" must not start with v.");
        try { return SemanticVersion.parse(text); }
        catch (IllegalArgumentException ex) { throw new InvalidManifestException("\"" + field + "\" is not a semantic version: " + text); }
    }

    /** Canonical JSON (fixed key order, two-space indent), as written by the release tool. */
    public String toJson() {
        StringBuilder out = new StringBuilder("{\n");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("formatVersion", Integer.toString(FORMAT_VERSION));
        fields.put("product", Json.quote(product));
        fields.put("version", Json.quote(version.toString()));
        fields.put("minimumSupportedVersion", Json.quote(minimumSupportedVersion.toString()));
        fields.put("updateType", Json.quote(type.name()));
        fields.put("mandatory", Boolean.toString(mandatory));
        fields.put("securityUpdate", Boolean.toString(securityUpdate));
        if (reason != null) fields.put("reason", Json.quote(reason));
        fields.put("releaseNotesUrl", Json.quote(releaseNotesUrl.toString()));
        fields.forEach((key, value) -> out.append("  ").append(Json.quote(key)).append(": ").append(value).append(",\n"));
        out.append("  \"assets\": {");
        List<Map.Entry<String, Asset>> entries = new TreeMap<>(assets).entrySet().stream().toList();
        for (int i = 0; i < entries.size(); i++) {
            Asset asset = entries.get(i).getValue();
            out.append(i == 0 ? "\n" : ",\n").append("    ").append(Json.quote(entries.get(i).getKey())).append(": { \"name\": ")
                    .append(Json.quote(asset.name())).append(", \"size\": ").append(new BigDecimal(asset.size())).append(", \"sha256\": ")
                    .append(Json.quote(asset.sha256())).append(" }");
        }
        return out.append("\n  }\n}\n").toString();
    }

    public static final class InvalidManifestException extends Exception {
        private static final long serialVersionUID = 1L;
        public InvalidManifestException(String message) { super(message); }
    }
}

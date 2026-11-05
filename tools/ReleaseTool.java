// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maintainer tool: creates and signs a release's update-manifest.json from the built packages.
 * Run offline, with the application's classpath, by whoever holds the update-signing private key:
 *
 *   java -cp "BulkFiledSigner.jar;lib/*" tools/ReleaseTool.java manifest --version 2.1.0 --type B
 *        [--security] [--reason "Why Type A is required"] [--minimum 2.0.0] --packages <dir> --key <private.pk8> --out <dir>
 *   java -cp ... tools/ReleaseTool.java verify --manifest <update-manifest.json> --signature <update-manifest.json.sig>
 */
public class ReleaseTool {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Commands: manifest, verify");
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            options.put(args[i].replaceFirst("^--", ""), flag ? "true" : args[++i]);
        }
        switch (args[0]) {
            case "manifest" -> manifest(options);
            case "verify" -> {
                byte[] manifest = Files.readAllBytes(Path.of(options.get("manifest")));
                UpdateTrust.verify(manifest, Files.readAllBytes(Path.of(options.get("signature"))), UpdateTrust.publisherKey());
                UpdateManifest parsed = UpdateManifest.parse(manifest);
                System.out.println("Valid: " + parsed.product() + " " + parsed.version() + ", type " + parsed.type() + ", packages " + parsed.assets().keySet());
            }
            default -> throw new IllegalArgumentException("Unknown command " + args[0]);
        }
    }

    static void manifest(Map<String, String> options) throws Exception {
        SemanticVersion version = SemanticVersion.parse(required(options, "version"));
        if (version.isPreRelease()) throw new IllegalArgumentException("Pre-release versions are not published to the update channel.");
        UpdateType type = UpdateType.valueOf(required(options, "type"));
        boolean security = options.containsKey("security");
        String reason = options.get("reason");
        if (type == UpdateType.A && (reason == null || reason.isBlank())) throw new IllegalArgumentException("Type A releases need --reason explaining why the update is required.");
        SemanticVersion minimum = SemanticVersion.parse(options.getOrDefault("minimum", "2.0.0"));
        Path packages = Path.of(required(options, "packages"));
        Path key = Path.of(required(options, "key")).toAbsolutePath().normalize();
        Path cwd = Path.of("").toAbsolutePath();
        if (key.startsWith(cwd) && Files.exists(cwd.resolve("pom.xml"))) throw new IllegalArgumentException("Refusing to use a private key stored inside the repository.");
        Path out = Files.createDirectories(Path.of(required(options, "out")));

        Map<String, UpdateManifest.Asset> assets = new TreeMap<>();
        for (String packageKey : UpdateManifest.SUPPORTED_KEYS) {
            Path file = packages.resolve(UpdateManifest.expectedName(version, packageKey));
            if (!Files.isRegularFile(file)) continue;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            assets.put(packageKey, new UpdateManifest.Asset(file.getFileName().toString(), Files.size(file), HexFormat.of().formatHex(digest)));
            System.out.println("Package " + packageKey + ": " + file.getFileName());
        }
        if (assets.isEmpty()) throw new IllegalArgumentException("No packages named " + UpdateManifest.expectedName(version, "windows-x64-portable") + " (or other platforms) in " + packages);
        UpdateManifest manifest = new UpdateManifest(Product.NAME, version, minimum, type, type.mandatory(), security, reason,
                URI.create(Product.REPOSITORY + "/releases/tag/" + version.tag()), Map.copyOf(assets));
        byte[] json = manifest.toJson().getBytes(StandardCharsets.UTF_8);
        UpdateManifest.parse(json); // Same validation as installed copies.
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(key)));
        String signature = UpdateTrust.sign(json, privateKey);
        // Installed copies trust only the embedded public key; catch a mismatched private key now.
        UpdateTrust.verify(json, signature.getBytes(StandardCharsets.US_ASCII), UpdateTrust.publisherKey());
        Files.write(out.resolve(UpdateManifest.FILE), json);
        Files.writeString(out.resolve(UpdateManifest.SIGNATURE_FILE), signature + "\n");
        System.out.println("\nWrote " + out.resolve(UpdateManifest.FILE) + " and " + UpdateManifest.SIGNATURE_FILE + " (Type " + type.name()
                + (security ? ", security" : "") + ").\nAttach both, and every package listed, to the GitHub release " + version.tag() + ", then publish it.");
    }

    static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.equals("true")) throw new IllegalArgumentException("--" + key + " is required");
        return value;
    }
}

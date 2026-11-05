// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateCoreTest {
    static final String SHA = "a".repeat(64);

    /** A valid manifest; {@code fields} replaces or (with a null value) removes top-level fields. */
    static String manifest(String version, String type, boolean mandatory, boolean security, Map<String, String> fields) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("formatVersion", "1");
        values.put("product", Json.quote(Product.NAME));
        values.put("version", Json.quote(version));
        values.put("minimumSupportedVersion", Json.quote("2.0.0"));
        values.put("updateType", Json.quote(type));
        values.put("mandatory", Boolean.toString(mandatory));
        values.put("securityUpdate", Boolean.toString(security));
        if (type.equals("A")) values.put("reason", Json.quote("Fixes a signature validation flaw."));
        values.put("releaseNotesUrl", Json.quote(Product.REPOSITORY + "/releases/tag/v" + version));
        values.put("assets", "{\"windows-x64-portable\": {\"name\": \"PDF-DSC-TSA-Bulk-Signer-" + version + "-windows-x64-portable.zip\", \"size\": 1234, \"sha256\": \"" + SHA + "\"}}");
        fields.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
        StringBuilder json = new StringBuilder("{");
        values.forEach((key, value) -> json.append(json.length() > 1 ? "," : "").append(Json.quote(key)).append(':').append(value));
        return json.append('}').toString();
    }

    static UpdateManifest parse(String json) throws Exception { return UpdateManifest.parse(json.getBytes(StandardCharsets.UTF_8)); }

    // Versions.

    @Test void versionsCompareNumericallyAndHandlePreReleases() {
        SemanticVersion v200 = SemanticVersion.parse("2.0.0");
        assertEquals(0, v200.compareTo(SemanticVersion.parse("2.0")), "2.0 equals 2.0.0");
        assertEquals(0, v200.compareTo(SemanticVersion.parse("v2.0.0")));
        assertTrue(SemanticVersion.parse("2.0.1").isNewerThan(v200));
        assertTrue(SemanticVersion.parse("2.1.0").isNewerThan(SemanticVersion.parse("2.0.9")));
        assertTrue(SemanticVersion.parse("3.0.0").isNewerThan(SemanticVersion.parse("2.99.99")));
        assertTrue(SemanticVersion.parse("2.10.0").isNewerThan(SemanticVersion.parse("2.9.0")), "Numeric, not lexicographic");
        assertTrue(v200.isNewerThan(SemanticVersion.parse("2.0.0-rc.1")), "A release ranks above its pre-release");
        assertTrue(SemanticVersion.parse("2.0.0-rc.10").isNewerThan(SemanticVersion.parse("2.0.0-rc.9")));
        assertTrue(SemanticVersion.parse("2.0.0-beta").isNewerThan(SemanticVersion.parse("2.0.0-alpha")));
        assertEquals("v2.0.0", SemanticVersion.current().tag(), "Product version 2.0 maps to tag v2.0.0");
        for (String invalid : List.of("", "2", "2.0.0.0", "02.0.0", "2.0.x", "2.0.0+build", "-1.0.0", "v", "2..0")) {
            assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse(invalid), invalid);
        }
    }

    // Manifest validation.

    @Test void validManifestsForEachClassificationAreAccepted() throws Exception {
        UpdateManifest a = parse(manifest("2.1.0", "A", true, true, Map.of()));
        assertEquals(UpdateType.A, a.type());
        assertTrue(a.mandatory() && a.securityUpdate());
        assertEquals(URI.create(Product.REPOSITORY + "/releases/tag/v2.1.0"), a.releaseNotesUrl());
        assertEquals(UpdateType.B, parse(manifest("2.1.0", "B", false, false, Map.of())).type());
        UpdateManifest c = parse(manifest("2.1.0", "C", false, false, Map.of()));
        assertEquals(UpdateType.C, c.type());
        assertNotNull(c.assetFor("windows-x64", Platform.Channel.WINDOWS_PORTABLE));
        assertNull(c.assetFor("windows-x64", Platform.Channel.WINDOWS_MSI), "No MSI listed");
        assertNull(c.assetFor("linux-x64", Platform.Channel.LINUX_PORTABLE), "No package for another platform");
        assertNull(c.assetFor("windows-x64", Platform.Channel.WINDOWS_STORE), "The Store channel never downloads");
        assertEquals(c, UpdateManifest.parse(c.toJson().getBytes(StandardCharsets.UTF_8)), "Canonical JSON round-trips");
    }

    @Test void invalidManifestsAreRejected() {
        Map<String, Map<String, String>> cases = new java.util.LinkedHashMap<>();
        cases.put("missing classification", Map.of("updateType", "null"));
        cases.put("unknown classification", Map.of("updateType", "\"D\""));
        cases.put("product mismatch", Map.of("product", "\"Other Product\""));
        cases.put("Type A not mandatory", Map.of("mandatory", "false"));
        cases.put("invalid release notes host", Map.of("releaseNotesUrl", "\"https://example.com/releases/tag/v2.1.0\""));
        cases.put("release notes for another tag", Map.of("releaseNotesUrl", Json.quote(Product.REPOSITORY + "/releases/tag/v9.9.9")));
        cases.put("missing checksum", Map.of("assets", "{\"windows-x64-portable\": {\"name\": \"PDF-DSC-TSA-Bulk-Signer-2.1.0-windows-x64-portable.zip\", \"size\": 1}}"));
        cases.put("invalid checksum", Map.of("assets", "{\"windows-x64-portable\": {\"name\": \"PDF-DSC-TSA-Bulk-Signer-2.1.0-windows-x64-portable.zip\", \"size\": 1, \"sha256\": \"XYZ\"}}"));
        cases.put("wrong package name", Map.of("assets", "{\"windows-x64-portable\": {\"name\": \"setup.exe\", \"size\": 1, \"sha256\": \"" + SHA + "\"}}"));
        cases.put("unsupported platform", Map.of("assets", "{\"solaris-sparc-portable\": {\"name\": \"x\", \"size\": 1, \"sha256\": \"" + SHA + "\"}}"));
        cases.put("no packages", Map.of("assets", "{}"));
        cases.put("unknown field", Map.of("downloadUrl", "\"https://example.com/evil.exe\""));
        cases.put("Type A without reason", Map.of("reason", "null"));
        cases.put("minimum newer than version", Map.of("minimumSupportedVersion", "\"3.0.0\""));
        cases.put("format version", Map.of("formatVersion", "2"));
        for (var entry : cases.entrySet()) {
            Map<String, String> fields = new java.util.HashMap<>(entry.getValue());
            fields.replaceAll((k, v) -> "null".equals(v) ? null : v);
            assertThrows(UpdateManifest.InvalidManifestException.class, () -> parse(manifest("2.1.0", "A", true, true, fields)), entry.getKey());
        }
        assertThrows(UpdateManifest.InvalidManifestException.class, () -> parse(manifest("2.1.0", "B", true, false, Map.of())), "B marked mandatory");
        assertThrows(UpdateManifest.InvalidManifestException.class, () -> parse(manifest("2.1.0", "C", false, true, Map.of())), "Security update not Type A");
        assertThrows(UpdateManifest.InvalidManifestException.class, () -> parse("{\"product\": "), "Malformed JSON");
        assertThrows(UpdateManifest.InvalidManifestException.class, () -> parse("{\"a\":1,\"a\":2}"), "Duplicate keys");
        assertThrows(UpdateManifest.InvalidManifestException.class, () -> UpdateManifest.parse(new byte[0]));
    }

    @Test void onlyThePublishersSignatureIsAccepted() throws Exception {
        KeyPair publisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] body = manifest("2.1.0", "C", false, false, Map.of()).getBytes(StandardCharsets.UTF_8);
        byte[] signature = UpdateTrust.sign(body, publisher.getPrivate()).getBytes(StandardCharsets.US_ASCII);
        UpdateTrust.verify(body, signature, publisher.getPublic());
        assertThrows(java.security.GeneralSecurityException.class, () -> UpdateTrust.verify(body, signature, attacker.getPublic()));
        byte[] tampered = body.clone();
        tampered[tampered.length - 3] ^= 1;
        assertThrows(java.security.GeneralSecurityException.class, () -> UpdateTrust.verify(tampered, signature, publisher.getPublic()));
        assertThrows(java.security.GeneralSecurityException.class, () -> UpdateTrust.verify(body, "not base64!".getBytes(), publisher.getPublic()));
        assertNotNull(UpdateTrust.publisherKey(), "The embedded publisher key is a valid Ed25519 key");
    }

    // Classification and remembered choices.

    static UpdateDecision.Candidate candidate(String version, String type, boolean withPackage) throws Exception {
        UpdateManifest manifest = parse(manifest(version, type, type.equals("A"), false, Map.of()));
        var asset = new GitHubReleaseClient.Asset(manifest.assets().get("windows-x64-portable").name(), 1234,
                URI.create(Product.REPOSITORY + "/releases/download/v" + version + "/x"), SHA);
        var release = new GitHubReleaseClient.Release("v" + version, version, "Notes " + version, false, false, Instant.now(),
                manifest.releaseNotesUrl(), List.of(asset));
        return new UpdateDecision.Candidate(release, manifest, withPackage ? asset : null, withPackage ? null : "No package yet.");
    }

    static UpdateDecision.Outcome decide(List<UpdateDecision.Candidate> candidates, UpdatePreferences preferences, boolean manual) {
        return UpdateDecision.decide(SemanticVersion.parse("2.0.0"), candidates.isEmpty() ? null : candidates.getFirst().version(),
                Platform.Channel.WINDOWS_PORTABLE, candidates, List.of(), preferences, Instant.parse("2026-11-05T10:00:00Z"), manual);
    }

    @Test void typeAIsMandatoryAndIgnoresRememberedChoices() throws Exception {
        var a = candidate("2.1.0", "A", true);
        UpdatePreferences everything = UpdatePreferences.defaults().withSkipped(SemanticVersion.parse("2.1.0"))
                .withReminder(SemanticVersion.parse("2.1.0"), Instant.parse("2030-01-01T00:00:00Z"));
        var outcome = decide(List.of(a), everything, false);
        assertEquals(UpdateDecision.Kind.OFFER, outcome.kind(), "Type A cannot be skipped or postponed");
        assertTrue(outcome.mandatory());
        assertEquals(SemanticVersion.parse("2.1.0"), outcome.mandatoryVersion());
        assertFalse(UpdateType.A.postponable());
        assertFalse(UpdateType.A.skippable());
    }

    @Test void anIntermediateTypeARequirementCannotBeBypassedByANewerOptionalRelease() throws Exception {
        var outcome = decide(List.of(candidate("2.2.0", "C", true), candidate("2.1.0", "A", true)), UpdatePreferences.defaults(), false);
        assertTrue(outcome.mandatory(), "Severity is the most severe newer release");
        assertEquals(SemanticVersion.parse("2.2.0"), outcome.target().version(), "The newest installable release satisfies the requirement");
        assertEquals(SemanticVersion.parse("2.1.0"), outcome.mandatoryVersion());
    }

    @Test void typeBCanBePostponedButNotSkipped() throws Exception {
        var b = candidate("2.1.0", "B", true);
        Instant now = Instant.parse("2026-11-05T10:00:00Z");
        var reminded = decide(List.of(b), UpdatePreferences.defaults().withReminder(b.version(), now.plusSeconds(3600)), false);
        assertEquals(UpdateDecision.Kind.SUPPRESSED, reminded.kind());
        assertFalse(reminded.mandatory());
        var expired = decide(List.of(b), UpdatePreferences.defaults().withReminder(b.version(), now.minusSeconds(1)), false);
        assertEquals(UpdateDecision.Kind.OFFER, expired.kind(), "Offered again after the reminder interval");
        var skipped = decide(List.of(b), UpdatePreferences.defaults().withSkipped(b.version()), false);
        assertEquals(UpdateDecision.Kind.OFFER, skipped.kind(), "Type B has no permanent skip");
        assertTrue(UpdateType.B.postponable());
        assertFalse(UpdateType.B.skippable());
    }

    @Test void skippedTypeCIsRememberedButNewerReleasesAreStillOffered() throws Exception {
        var c210 = candidate("2.1.0", "C", true);
        UpdatePreferences skipped = UpdatePreferences.defaults().withSkipped(c210.version());
        assertEquals(UpdateDecision.Kind.SUPPRESSED, decide(List.of(c210), skipped, false).kind());
        assertEquals(UpdateDecision.Kind.OFFER, decide(List.of(c210), skipped, true).kind(), "A manual check shows it again");
        var newer = decide(List.of(candidate("2.2.0", "C", true), c210), skipped, false);
        assertEquals(UpdateDecision.Kind.OFFER, newer.kind());
        assertEquals(SemanticVersion.parse("2.2.0"), newer.target().version());
    }

    @Test void upToDateNewerInstalledAndMissingPackagesAreReported() throws Exception {
        assertEquals(UpdateDecision.Kind.UP_TO_DATE, decide(List.of(), UpdatePreferences.defaults(), false).kind());
        var ahead = UpdateDecision.decide(SemanticVersion.parse("2.0.0"), SemanticVersion.parse("1.0.0"), Platform.Channel.WINDOWS_PORTABLE,
                List.of(), List.of(), UpdatePreferences.defaults(), Instant.now(), false);
        assertEquals(UpdateDecision.Kind.INSTALLED_NEWER, ahead.kind(), "No downgrade is offered");
        var equal = UpdateDecision.decide(SemanticVersion.parse("2.1.0"), SemanticVersion.parse("2.1.0"), Platform.Channel.WINDOWS_PORTABLE,
                List.of(candidate("2.1.0", "A", true)), List.of(), UpdatePreferences.defaults(), Instant.now(), false);
        assertEquals(UpdateDecision.Kind.UP_TO_DATE, equal.kind(), "Equal version is not an update");
        var noPackage = decide(List.of(candidate("2.1.0", "A", false)), UpdatePreferences.defaults(), false);
        assertTrue(noPackage.mandatory(), "A missing platform package never lifts a Type A requirement");
        assertFalse(noPackage.installable());
        var problem = UpdateDecision.decide(SemanticVersion.parse("2.0.0"), SemanticVersion.parse("2.1.0"), Platform.Channel.WINDOWS_PORTABLE,
                List.of(), List.of(new UpdateDecision.Problem(SemanticVersion.parse("2.1.0"), "v2.1.0", "unsigned")), UpdatePreferences.defaults(), Instant.now(), false);
        assertEquals(UpdateDecision.Kind.METADATA_PROBLEM, problem.kind(), "Unverifiable metadata is reported, never treated as Type C");
        assertNull(problem.type());
        var store = UpdateDecision.decide(SemanticVersion.parse("2.0.0"), SemanticVersion.parse("2.1.0"), Platform.Channel.WINDOWS_STORE,
                List.of(candidate("2.1.0", "A", false)), List.of(), UpdatePreferences.defaults(), Instant.now(), false);
        assertTrue(store.mandatory());
        assertFalse(store.installable(), "Store installations are updated by the Store");
    }

    @Test void onlyCanonicalStableTagsAreConsidered() {
        var releases = List.of(
                new GitHubReleaseClient.Release("v2.3.0", "", "", true, false, null, URI.create("x"), List.of()),
                new GitHubReleaseClient.Release("v2.2.0", "", "", false, true, null, URI.create("x"), List.of()),
                new GitHubReleaseClient.Release("v2.1.0", "", "", false, false, null, URI.create("x"), List.of()),
                new GitHubReleaseClient.Release("2.4.0", "", "", false, false, null, URI.create("x"), List.of()),
                new GitHubReleaseClient.Release("v1.0.0", "", "", false, false, null, URI.create("x"), List.of()));
        var newer = UpdateDecision.newerReleases(releases, SemanticVersion.parse("2.0.0"), 10);
        assertEquals(List.of("v2.1.0"), newer.stream().map(GitHubReleaseClient.Release::tag).toList(), "Drafts, prereleases, odd tags and older releases are ignored");
    }

    // Persistence, state and dialog policy.

    @Test void preferencesPersistAndBoundIntervals() throws Exception {
        AppPaths.initialize();
        Path file = Files.createTempFile(AppPaths.TEMP, "update-", ".properties");
        try {
            UpdatePreferences prefs = UpdatePreferences.defaults().withSkipped(SemanticVersion.parse("2.1.0"))
                    .withReminder(SemanticVersion.parse("2.2.0"), Instant.parse("2026-11-06T10:00:00Z"))
                    .withMandatory(SemanticVersion.parse("2.3.0"), "Security fix").withCheck(Instant.parse("2026-11-05T10:00:00Z"), "OFFER");
            prefs.write(file);
            assertEquals(prefs, UpdatePreferences.read(file));
            String stored = Files.readString(file);
            for (String secret : List.of("password", "pin", "token", "key=")) assertFalse(stored.toLowerCase().contains(secret));
            UpdatePreferences odd = new UpdatePreferences(null, null, null, null, null, Duration.ofMinutes(1), Duration.ofDays(400), null, null, null, null);
            assertEquals(Duration.ofHours(1), odd.checkInterval());
            assertEquals(Duration.ofDays(30), odd.remindInterval());
            assertFalse(prefs.checkDue(Instant.parse("2026-11-05T11:00:00Z")));
            assertTrue(prefs.checkDue(Instant.parse("2026-11-06T11:00:00Z")));
        } finally { Files.deleteIfExists(file); }
    }

    @Test void mandatoryStateBlocksSigningAndDialogPolicyHasNoBypass() {
        UpdateStateManager state = new UpdateStateManager();
        int[] notified = {0};
        state.addListener(() -> notified[0]++);
        assertFalse(state.blocksOperations());
        state.set(UpdateStateManager.State.UPDATE_AVAILABLE, false, "B available");
        assertFalse(state.blocksOperations(), "Type B/C never block");
        state.set(UpdateStateManager.State.MANDATORY_UPDATE_REQUIRED, true, "A required");
        assertTrue(state.blocksOperations());
        state.set(UpdateStateManager.State.UPDATE_FAILED, true, "download failed");
        assertTrue(state.blocksOperations(), "A failed mandatory update stays restricted");
        state.set(UpdateStateManager.State.INSTALLING, false, "installing B");
        assertTrue(state.blocksOperations(), "No signing while files are replaced");
        assertEquals(4, notified[0]);

        var a = UpdateDialog.actionsFor(UpdateType.A);
        assertTrue(a.contains(UpdateDialog.Action.INSTALL) && a.contains(UpdateDialog.Action.RETRY));
        for (var bypass : List.of(UpdateDialog.Action.SKIP, UpdateDialog.Action.REMIND_LATER, UpdateDialog.Action.CLOSE, UpdateDialog.Action.UPDATE_NOW)) {
            assertFalse(a.contains(bypass), "Type A must not offer " + bypass);
        }
        assertTrue(UpdateDialog.actionsFor(UpdateType.B).containsAll(List.of(UpdateDialog.Action.UPDATE_NOW, UpdateDialog.Action.REMIND_LATER, UpdateDialog.Action.RELEASE_NOTES)));
        assertFalse(UpdateDialog.actionsFor(UpdateType.B).contains(UpdateDialog.Action.SKIP));
        assertTrue(UpdateDialog.actionsFor(UpdateType.C).containsAll(List.of(UpdateDialog.Action.UPDATE_NOW, UpdateDialog.Action.SKIP,
                UpdateDialog.Action.REMIND_LATER, UpdateDialog.Action.RELEASE_NOTES)));
        for (var s : UpdateStateManager.State.values()) assertFalse(UpdateDialog.userMayClose(UpdateType.A, s), "Type A cannot be closed in " + s);
        assertTrue(UpdateDialog.userMayClose(UpdateType.B, UpdateStateManager.State.UPDATE_AVAILABLE));
        assertFalse(UpdateDialog.userMayClose(UpdateType.C, UpdateStateManager.State.INSTALLING));
    }

    @Test void platformDetectionUsesOsAndArchitectureIdentifiers() {
        assertEquals(Platform.Os.WINDOWS, Platform.os("Windows 11"));
        assertEquals(Platform.Os.MACOS, Platform.os("Mac OS X"));
        assertEquals(Platform.Os.LINUX, Platform.os("Linux"));
        assertThrows(IllegalStateException.class, () -> Platform.os("SunOS"));
        assertEquals(Platform.Arch.X64, Platform.arch("amd64"));
        assertEquals(Platform.Arch.X64, Platform.arch("x86_64"));
        assertEquals(Platform.Arch.ARM64, Platform.arch("aarch64"));
        assertThrows(IllegalStateException.class, () -> Platform.arch("x86"));
        assertEquals("macos-arm64", Platform.id(Platform.Os.MACOS, Platform.Arch.ARM64));
        assertEquals(Platform.Channel.SOURCE, Platform.Channel.parse(null));
        assertEquals(Platform.Channel.WINDOWS_MSI, Platform.Channel.parse("windows-msi"));
        assertThrows(IllegalStateException.class, () -> Platform.Channel.parse("windows-portable-x"));
        assertEquals("PDF-DSC-TSA-Bulk-Signer-2.0.0-windows-x64-portable.zip", UpdateManifest.expectedName(SemanticVersion.parse("2.0.0"), "windows-x64-portable"));
        assertEquals("PDF-DSC-TSA-Bulk-Signer-2.0.0-linux-x64.tar.gz", UpdateManifest.expectedName(SemanticVersion.parse("2.0.0"), "linux-x64-portable"));
        assertEquals("PDF-DSC-TSA-Bulk-Signer-2.0.0-macos-arm64.dmg", UpdateManifest.expectedName(SemanticVersion.parse("2.0.0"), "macos-arm64-dmg"));
    }
}

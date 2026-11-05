// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

/** Finds newer releases on GitHub and turns each into a verified {@link UpdateDecision.Candidate} or a {@link UpdateDecision.Problem}. */
final class UpdateDiscovery {
    private static final System.Logger LOG = System.getLogger("updates");
    static final int MAX_RELEASES = 10;

    record Result(SemanticVersion latestPublished, List<UpdateDecision.Candidate> candidates, List<UpdateDecision.Problem> problems) { }

    private final GitHubReleaseClient client;
    private final PublicKey publisher;
    private final String platformId;
    private final Platform.Channel channel;

    UpdateDiscovery(GitHubReleaseClient client, PublicKey publisher, String platformId, Platform.Channel channel) {
        this.client = client;
        this.publisher = publisher;
        this.platformId = platformId;
        this.channel = channel;
    }

    /**
     * Uses {@code releases/latest} first. The full release list is read only when a newer release exists, or a
     * mandatory requirement is pending, so that an intermediate Type A release cannot be missed.
     */
    Result discover(SemanticVersion installed, SemanticVersion pendingMandatory) throws UpdateException {
        GitHubReleaseClient.Release latest = client.latest();
        SemanticVersion latestVersion = latest == null ? null : UpdateDecision.tagVersion(latest.tag());
        LOG.log(System.Logger.Level.INFO, "event=update.github.latest installed={0} latest={1}", installed, latest == null ? "none" : latest.tag());
        boolean newer = latestVersion != null && latestVersion.isNewerThan(installed);
        boolean pending = pendingMandatory != null && pendingMandatory.isNewerThan(installed);
        if (!newer && !pending) return new Result(latestVersion, List.of(), List.of());
        List<UpdateDecision.Candidate> candidates = new ArrayList<>();
        List<UpdateDecision.Problem> problems = new ArrayList<>();
        for (var release : UpdateDecision.newerReleases(client.releases(), installed, MAX_RELEASES)) {
            try {
                candidates.add(evaluate(release));
                LOG.log(System.Logger.Level.INFO, "event=update.release.validated tag={0} type={1}", release.tag(), candidates.getLast().manifest().type());
            } catch (UpdateException ex) {
                if (ex.category() == UpdateException.Category.NETWORK || ex.category() == UpdateException.Category.TIMEOUT
                        || ex.category() == UpdateException.Category.RATE_LIMITED) throw ex;
                problems.add(new UpdateDecision.Problem(UpdateDecision.tagVersion(release.tag()), release.tag(), ex.getMessage()));
                LOG.log(System.Logger.Level.WARNING, "event=update.release.rejected tag={0} category={1} reason={2}", release.tag(), ex.category(), ex.getMessage());
            }
        }
        return new Result(latestVersion, candidates, problems);
    }

    UpdateDecision.Candidate evaluate(GitHubReleaseClient.Release release) throws UpdateException {
        var manifestAsset = release.asset(UpdateManifest.FILE);
        var signatureAsset = release.asset(UpdateManifest.SIGNATURE_FILE);
        if (manifestAsset == null || signatureAsset == null) {
            throw new UpdateException(UpdateException.Category.METADATA, "The release has no signed " + UpdateManifest.FILE + ".");
        }
        byte[] manifestBytes = client.download(manifestAsset, UpdateManifest.MAX_BYTES);
        byte[] signature = client.download(signatureAsset, 512);
        try { UpdateTrust.verify(manifestBytes, signature, publisher); }
        catch (GeneralSecurityException ex) { throw new UpdateException(UpdateException.Category.SIGNATURE, ex.getMessage(), ex); }
        UpdateManifest manifest;
        try { manifest = UpdateManifest.parse(manifestBytes); }
        catch (UpdateManifest.InvalidManifestException ex) { throw new UpdateException(UpdateException.Category.METADATA, ex.getMessage(), ex); }
        if (!manifest.version().tag().equals(release.tag())) {
            throw new UpdateException(UpdateException.Category.METADATA, "The manifest describes " + manifest.version() + " but is attached to " + release.tag() + ".");
        }
        if (!manifest.releaseNotesUrl().equals(release.page())) throw new UpdateException(UpdateException.Category.METADATA, "The manifest's release page does not match.");
        // Every package the manifest names must really be attached to the release, with the same size and digest.
        for (UpdateManifest.Asset listed : manifest.assets().values()) {
            var attached = release.asset(listed.name());
            if (attached == null) throw new UpdateException(UpdateException.Category.METADATA, listed.name() + " is listed but not attached to the release.");
            if (attached.size() != listed.size()) throw new UpdateException(UpdateException.Category.METADATA, listed.name() + " has a different size on GitHub.");
            if (attached.sha256Digest() != null && !attached.sha256Digest().equals(listed.sha256())) {
                throw new UpdateException(UpdateException.Category.METADATA, listed.name() + " has a different SHA-256 on GitHub.");
            }
        }
        UpdateManifest.Asset mine = manifest.assetFor(platformId, channel);
        if (mine == null) {
            String reason = UpdateManifest.format(channel) == null ? null
                    : "Version " + manifest.version() + " has no " + channel + " package for " + platformId + " yet. Check the release page or try again later.";
            return new UpdateDecision.Candidate(release, manifest, null, reason);
        }
        return new UpdateDecision.Candidate(release, manifest, release.asset(mine.name()), null);
    }
}

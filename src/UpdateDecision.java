// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure update policy: which release to offer, how it is classified, and whether the user is asked now. */
final class UpdateDecision {
    private UpdateDecision() { }

    /** A newer release with a valid, signed manifest. {@code download} is null when no package exists for this platform. */
    record Candidate(GitHubReleaseClient.Release release, UpdateManifest manifest, GitHubReleaseClient.Asset download, String noPackageReason) {
        SemanticVersion version() { return manifest.version(); }
    }

    /** A newer release whose metadata could not be trusted; it is never classified or installed. */
    record Problem(SemanticVersion version, String tag, String message) { }

    enum Kind { UP_TO_DATE, INSTALLED_NEWER, OFFER, SUPPRESSED, METADATA_PROBLEM }

    record Outcome(Kind kind, UpdateType type, Candidate target, SemanticVersion mandatoryVersion, String mandatoryReason,
                   boolean securityUpdate, boolean installable, String detail, List<Problem> problems) {
        boolean mandatory() { return type == UpdateType.A; }
    }

    static Outcome decide(SemanticVersion installed, SemanticVersion latestPublished, Platform.Channel channel, List<Candidate> candidates,
                          List<Problem> problems, UpdatePreferences preferences, Instant now, boolean manual) {
        List<Candidate> newer = candidates.stream().filter(c -> c.version().isNewerThan(installed) && !c.version().isPreRelease())
                .sorted(Comparator.comparing(Candidate::version).reversed()).toList();
        List<Problem> newerProblems = problems.stream().filter(p -> p.version() != null && p.version().isNewerThan(installed)).toList();
        if (newer.isEmpty()) {
            if (!newerProblems.isEmpty()) {
                Problem first = newerProblems.getFirst();
                return new Outcome(Kind.METADATA_PROBLEM, null, null, null, null, false, false,
                        "Release " + first.tag() + " was found, but its update information could not be verified: " + first.message(), newerProblems);
            }
            boolean ahead = latestPublished != null && installed.isNewerThan(latestPublished);
            return new Outcome(ahead ? Kind.INSTALLED_NEWER : Kind.UP_TO_DATE, null, null, null, null, false, false,
                    ahead ? "This version is newer than the latest published release (" + latestPublished + ")." : "You have the latest version.", newerProblems);
        }
        UpdateType type = newer.stream().map(c -> c.manifest().type()).reduce(UpdateType.C, UpdateType::mostSevere);
        Candidate requirement = newer.stream().filter(c -> c.manifest().type() == UpdateType.A).findFirst().orElse(null);
        boolean security = newer.stream().anyMatch(c -> c.manifest().securityUpdate());

        Candidate target = newer.stream().filter(c -> c.download() != null && !c.manifest().minimumSupportedVersion().isNewerThan(installed))
                .findFirst().orElse(newer.getFirst());
        String detail = null;
        boolean installable = true;
        if (channel == Platform.Channel.WINDOWS_STORE) {
            installable = false;
            detail = "Microsoft Store installs updates for this copy. Open the Store to update.";
        } else if (channel == Platform.Channel.SOURCE) {
            installable = false;
            detail = "This copy was built from source. Update the source from the repository and run the build again, or install a release package.";
        } else if (target.download() == null) {
            installable = false;
            detail = target.noPackageReason();
        } else if (target.manifest().minimumSupportedVersion().isNewerThan(installed)) {
            installable = false;
            detail = "Version " + target.version() + " requires version " + target.manifest().minimumSupportedVersion()
                    + " or newer for an automatic update. Download it from the release page.";
        }
        Outcome offer = new Outcome(Kind.OFFER, type, target, requirement == null ? null : requirement.version(),
                requirement == null ? null : requirement.manifest().reason(), security, installable, detail, newerProblems);
        if (type == UpdateType.A || manual) return offer; // Remembered choices never apply to a mandatory update.
        SemanticVersion version = target.version();
        if (type.skippable() && preferences.skippedVersion() != null && !version.isNewerThan(preferences.skippedVersion())) {
            return suppressed(offer, "Version " + version + " was skipped.");
        }
        if (preferences.remindVersion() != null && preferences.remindVersion().equals(version)
                && preferences.remindUntil() != null && now.isBefore(preferences.remindUntil())) {
            return suppressed(offer, "Reminder for version " + version + " is postponed.");
        }
        return offer;
    }

    private static Outcome suppressed(Outcome offer, String detail) {
        return new Outcome(Kind.SUPPRESSED, offer.type(), offer.target(), null, null, false, offer.installable(), detail, offer.problems());
    }

    /** Newer releases worth evaluating: published, not drafts or prereleases, with a valid version tag, newest first. */
    static List<GitHubReleaseClient.Release> newerReleases(List<GitHubReleaseClient.Release> releases, SemanticVersion installed, int limit) {
        List<GitHubReleaseClient.Release> result = new ArrayList<>();
        for (var release : releases) {
            if (release.draft() || release.prerelease()) continue;
            SemanticVersion version = tagVersion(release.tag());
            if (version != null && !version.isPreRelease() && version.isNewerThan(installed)) result.add(release);
        }
        result.sort(Comparator.comparing((GitHubReleaseClient.Release r) -> tagVersion(r.tag())).reversed());
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /** The version of a release tag of the form {@code vMAJOR.MINOR.PATCH}, or null. */
    static SemanticVersion tagVersion(String tag) {
        if (tag == null || !tag.startsWith("v")) return null;
        try {
            SemanticVersion version = SemanticVersion.parse(tag);
            return version.tag().equals(tag) ? version : null;
        } catch (IllegalArgumentException ex) { return null; }
    }
}

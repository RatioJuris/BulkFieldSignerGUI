// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic version {@code MAJOR.MINOR.PATCH[-prerelease]}, compared numerically. A missing patch
 * component is read as 0, so the product version "2.0" equals 2.0.0. Tags may carry a leading "v".
 */
public record SemanticVersion(int major, int minor, int patch, String preRelease) implements Comparable<SemanticVersion> {
    private static final Pattern FORMAT = Pattern.compile(
            "v?(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})(?:\\.(0|[1-9]\\d{0,8}))?(?:-((?:0|[1-9]\\d{0,8}|\\d*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9]\\d{0,8}|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?");

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("Version components must not be negative.");
        if (preRelease != null && preRelease.isEmpty()) preRelease = null;
    }

    public static SemanticVersion parse(String text) {
        if (text == null || text.length() > 80) throw new IllegalArgumentException("Invalid version: " + text);
        Matcher matcher = FORMAT.matcher(text.strip());
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid version: " + text);
        return new SemanticVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)), matcher.group(4));
    }

    /** The installed product version. */
    public static SemanticVersion current() { return parse(Product.VERSION); }

    public boolean isPreRelease() { return preRelease != null; }

    public boolean isNewerThan(SemanticVersion other) { return compareTo(other) > 0; }

    /** The tag convention used for GitHub releases, for example {@code v2.1.0}. */
    public String tag() { return "v" + this; }

    @Override public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        if (result != 0) return result;
        if (preRelease == null || other.preRelease == null) {
            return preRelease == null ? (other.preRelease == null ? 0 : 1) : -1; // A release ranks above its pre-releases.
        }
        String[] mine = preRelease.split("\\."), theirs = other.preRelease.split("\\.");
        for (int i = 0; i < Math.min(mine.length, theirs.length); i++) {
            boolean myNumber = mine[i].chars().allMatch(Character::isDigit), theirNumber = theirs[i].chars().allMatch(Character::isDigit);
            int step = myNumber && theirNumber ? Long.compare(Long.parseLong(mine[i]), Long.parseLong(theirs[i]))
                    : myNumber ? -1 : theirNumber ? 1 : mine[i].compareTo(theirs[i]);
            if (step != 0) return step;
        }
        return Integer.compare(mine.length, theirs.length);
    }

    @Override public String toString() { return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease); }
}

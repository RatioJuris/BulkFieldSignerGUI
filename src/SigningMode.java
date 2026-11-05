// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
/** How the document is secured after the visible stamps are placed. */
public enum SigningMode {
    DSC("Digital signature (DSC)", "Digitally signed by"),
    TIMESTAMP_SEAL("Timestamp seal (no DSC)", "Declared by");

    private final String label;
    private final String heading;
    SigningMode(String label, String heading) { this.label = label; this.heading = heading; }
    /** Stamp wording. A timestamp seal never claims to be a personal signature. */
    public String heading() { return heading; }
    @Override public String toString() { return label; }

    public static SigningMode parse(String value) {
        try { return value == null ? DSC : valueOf(value); }
        catch (IllegalArgumentException ex) { return DSC; }
    }
}

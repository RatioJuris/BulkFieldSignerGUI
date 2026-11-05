// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
/** Release classification carried by the signed update manifest. */
public enum UpdateType {
    /** Critical or security update: mandatory, cannot be skipped or postponed, blocks signing until installed. */
    A("Critical update", true, false, false),
    /** Important update: may be postponed, never permanently skipped, never blocks. */
    B("Important update", false, true, false),
    /** Optional update: may be postponed or skipped, never blocks. */
    C("Optional update", false, true, true);

    private final String label;
    private final boolean mandatory;
    private final boolean postponable;
    private final boolean skippable;

    UpdateType(String label, boolean mandatory, boolean postponable, boolean skippable) {
        this.label = label;
        this.mandatory = mandatory;
        this.postponable = postponable;
        this.skippable = skippable;
    }

    public boolean mandatory() { return mandatory; }
    public boolean postponable() { return postponable; }
    public boolean skippable() { return skippable; }
    @Override public String toString() { return label; }

    /** The more severe of two classifications (A over B over C). */
    static UpdateType mostSevere(UpdateType first, UpdateType second) { return first.ordinal() <= second.ordinal() ? first : second; }
}

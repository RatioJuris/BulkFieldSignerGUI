// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
public enum ThemeMode {
    SYSTEM("System default"), LIGHT("Light"), DARK("Dark");
    private final String label;
    ThemeMode(String label) { this.label = label; }
    @Override public String toString() { return label; }
    public static ThemeMode parse(String value) {
        try { return value == null ? SYSTEM : valueOf(value); }
        catch (IllegalArgumentException ex) { return SYSTEM; }
    }
    public boolean isDark(boolean windowsDark) { return this == DARK || this == SYSTEM && windowsDark; }
}

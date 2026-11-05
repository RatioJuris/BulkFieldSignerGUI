// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
/** Product identity shown in the application. Change only with the maintainers' approval. */
public final class Product {
    public static final String NAME = "PDF DSC-TSA Bulk Signer";
    /** File name of the Windows launcher (bulksigner.exe): lowercase, without spaces or special characters. */
    public static final String WINDOWS_LAUNCHER = "bulksigner";
    public static final String VERSION = "2.0";
    public static final String DEVELOPER = "PrajnaBytes International Private Limited | RatioJuris";
    public static final String LICENSE = "GNU General Public License v3.0 or later";
    public static final String REPOSITORY = "https://github.com/RatioJuris/BulkFieldSignerGUI";
    public static final String COPYRIGHT = "Copyright (C) 2026 " + DEVELOPER;

    private Product() { }

    public static String title() { return NAME + " " + VERSION; }
}

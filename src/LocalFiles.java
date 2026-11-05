// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Blocking library/file work is invoked only from application workers, never the EDT. */
public final class LocalFiles {
    public static final long MAX_PDF_BYTES = 256L * 1024 * 1024;
    private LocalFiles() { }

    public static Path importPdf(Path source) throws IOException {
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IOException("Select a readable PDF file. Write permission is not required.");
        }
        Path staged = Files.createTempFile(AppPaths.requireLocal(AppPaths.TEMP), "import-", ".pdf");
        try {
            MessageDigest digest = sha256();
            long total = 0;
            try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(staged)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    checkInterrupted();
                    total += count;
                    if (total > MAX_PDF_BYTES) throw new IOException("PDF exceeds the 256 MiB import limit.");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
            }
            Path target = AppPaths.requireLocal(AppPaths.IMPORTS.resolve(HexFormat.of().formatHex(digest.digest()) + ".pdf"));
            if (Files.exists(target)) {
                if (!fingerprint(target).equals(target.getFileName().toString().replace(".pdf", ""))) {
                    throw new IOException("The cached PDF was modified. Remove the damaged import and try again.");
                }
                return target;
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static String fingerprint(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(AppPaths.requireLocal(path))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                checkInterrupted();
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void atomicWrite(Path destination, byte[] data) throws IOException {
        Path target = AppPaths.requireLocal(destination);
        Path staged = Files.createTempFile(target.getParent(), "write-", ".tmp");
        try {
            Files.write(staged, data);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static byte[] readBounded(Path path, int maximum) throws IOException {
        try (InputStream in = Files.newInputStream(AppPaths.requireLocal(path))) {
            byte[] bytes = in.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Stored data exceeds its size limit.");
            return bytes;
        }
    }

    public static void deleteTree(Path path) throws IOException {
        AppPaths.requireLocal(path);
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }

    public static void checkInterrupted() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Operation cancelled.");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}

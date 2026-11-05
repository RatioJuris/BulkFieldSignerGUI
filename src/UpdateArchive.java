// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts a portable package ({@code .zip} on Windows, {@code .tar.gz} on Linux) whose entries all live under
 * one expected top-level folder. Path traversal, absolute names, hard links, links leaving the folder and
 * oversized archives are rejected.
 */
final class UpdateArchive {
    static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    static final int MAX_ENTRIES = 50_000;

    private UpdateArchive() { }

    /** Extracts the contents of {@code topFolder/} into {@code destination}, which must be empty or absent. */
    static void extract(Path archive, String topFolder, Path destination) throws IOException, UpdateException {
        Files.createDirectories(destination);
        try (var existing = Files.list(destination)) {
            if (existing.findAny().isPresent()) throw new UpdateException(UpdateException.Category.INSTALL, "The staging folder is not empty.");
        }
        Path root = destination.toRealPath();
        String name = archive.getFileName().toString();
        if (name.endsWith(".zip")) extractZip(archive, topFolder, root);
        else if (name.endsWith(".tar.gz")) extractTar(archive, topFolder, root);
        else throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "Unsupported portable package: " + name);
    }

    private static void extractZip(Path archive, String top, Path root) throws IOException, UpdateException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > MAX_ENTRIES) throw invalid("too many entries");
            long total = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = target(root, top, entry.getName());
                if (target == null) continue; // The top folder itself.
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                ensureParent(root, target);
                try (InputStream in = zip.getInputStream(entry)) { total = write(in, target, -1, total); }
            }
        }
    }

    private static void extractTar(Path archive, String top, Path root) throws IOException, UpdateException {
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive), 65536))) {
            byte[] header = new byte[512];
            String longName = null, longLink = null;
            Map<String, String> pax = new HashMap<>();
            long total = 0;
            int count = 0, zeroBlocks = 0;
            while (true) {
                if (!readBlock(in, header)) throw invalid("unexpected end of archive");
                if (isZero(header)) { if (++zeroBlocks == 2) return; continue; }
                zeroBlocks = 0;
                if (++count > MAX_ENTRIES) throw invalid("too many entries");
                if (!checksumValid(header)) throw invalid("damaged header");
                char type = (char) header[156];
                long size = octal(header, 124, 12);
                if (size < 0 || size > MAX_TOTAL_BYTES) throw invalid("invalid entry size");
                switch (type) {
                    case 'L', 'K' -> {
                        String value = text(readData(in, size, 4096), 0, (int) size);
                        if (type == 'L') longName = value; else longLink = value;
                        continue;
                    }
                    case 'x' -> { pax.putAll(paxRecords(readData(in, size, 65536))); continue; }
                    case 'g' -> { skip(in, size); continue; }
                    default -> { }
                }
                String entryName = pax.getOrDefault("path", longName != null ? longName : ustarName(header));
                String linkName = pax.getOrDefault("linkpath", longLink != null ? longLink : text(header, 157, 100));
                if (pax.containsKey("size")) size = Long.parseLong(pax.get("size"));
                pax.clear();
                longName = null;
                longLink = null;
                int mode = (int) octal(header, 100, 8);
                Path target = target(root, top, entryName);
                if (target == null) { skip(in, size); continue; }
                switch (type) {
                    case '5' -> { Files.createDirectories(target); skip(in, size); }
                    case '0', '\0', '7' -> {
                        ensureParent(root, target);
                        total = write(in, target, size, total);
                        skip(in, (512 - size % 512) % 512);
                        permissions(target, mode);
                    }
                    case '2' -> {
                        if (linkName.isEmpty() || linkName.startsWith("/") || linkName.contains("\\")) throw invalid("unsafe link " + entryName);
                        ensureParent(root, target);
                        if (!target.getParent().resolve(linkName).normalize().startsWith(root)) throw invalid("link leaves the package: " + entryName);
                        Files.createSymbolicLink(target, Path.of(linkName));
                        skip(in, size);
                    }
                    default -> throw invalid("unsupported entry type '" + type + "' for " + entryName);
                }
            }
        }
    }

    /** Validates an entry name and maps it under the destination; returns null for the top folder itself. */
    static Path target(Path root, String top, String entryName) throws UpdateException {
        String name = entryName;
        if (name.isEmpty() || name.length() > 4096 || name.indexOf('\0') >= 0 || name.contains("\\") || name.startsWith("/") || name.contains(":")) {
            throw invalid("unsafe entry name");
        }
        if (name.startsWith("./")) name = name.substring(2);
        if (name.equals(top) || name.equals(top + "/")) return null;
        if (!name.startsWith(top + "/")) throw invalid("entry outside " + top + ": " + entryName);
        String relative = name.substring(top.length() + 1);
        for (String segment : relative.split("/")) if (segment.equals("..") || segment.equals(".")) throw invalid("path traversal in " + entryName);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw invalid("path traversal in " + entryName);
        return target;
    }

    private static void ensureParent(Path root, Path target) throws IOException, UpdateException {
        Files.createDirectories(target.getParent());
        // A previously extracted link must not redirect later files outside the package.
        if (!target.getParent().toRealPath().startsWith(root)) throw invalid("entry escapes through a link: " + root.relativize(target));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw invalid("duplicate entry " + root.relativize(target));
    }

    private static long write(InputStream in, Path target, long size, long total) throws IOException, UpdateException {
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[65536];
            long remaining = size < 0 ? Long.MAX_VALUE : size;
            while (remaining > 0) {
                int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) { if (size < 0) break; throw invalid("truncated entry " + target.getFileName()); }
                total += count;
                if (total > MAX_TOTAL_BYTES) throw invalid("expanded size exceeds the limit");
                out.write(buffer, 0, count);
                remaining -= count;
            }
        }
        return total;
    }

    private static void permissions(Path target, int mode) throws IOException {
        if (!Files.getFileStore(target).supportsFileAttributeView("posix")) return;
        Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(target, permissions);
    }

    private static String ustarName(byte[] header) {
        String name = text(header, 0, 100);
        boolean ustar = text(header, 257, 5).equals("ustar");
        String prefix = ustar ? text(header, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static Map<String, String> paxRecords(byte[] data) throws UpdateException {
        Map<String, String> records = new HashMap<>();
        int position = 0;
        while (position < data.length) {
            int space = position;
            while (space < data.length && data[space] != ' ') space++;
            if (space >= data.length) throw invalid("damaged extended header");
            int length;
            try { length = Integer.parseInt(new String(data, position, space - position, StandardCharsets.US_ASCII)); }
            catch (NumberFormatException ex) { throw invalid("damaged extended header"); }
            if (length <= 0 || position + length > data.length) throw invalid("damaged extended header");
            String record = new String(data, space + 1, position + length - space - 2, StandardCharsets.UTF_8);
            int equals = record.indexOf('=');
            if (equals > 0) records.put(record.substring(0, equals), record.substring(equals + 1));
            position += length;
        }
        return records;
    }

    private static byte[] readData(InputStream in, long size, int max) throws IOException, UpdateException {
        if (size > max) throw invalid("header data too large");
        byte[] data = in.readNBytes((int) size);
        if (data.length != size) throw new EOFException("Truncated archive.");
        skip(in, (512 - size % 512) % 512);
        return data;
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int read = in.readNBytes(block, 0, 512);
        return read == 512;
    }

    private static void skip(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) { if (in.read() < 0) throw new EOFException("Truncated archive."); skipped = 1; }
            remaining -= skipped;
        }
    }

    private static boolean isZero(byte[] block) { for (byte b : block) if (b != 0) return false; return true; }

    private static boolean checksumValid(byte[] header) {
        long stored = octal(header, 148, 8), unsigned = 0, signed = 0;
        for (int i = 0; i < 512; i++) {
            boolean field = i >= 148 && i < 156;
            unsigned += field ? ' ' : header[i] & 0xff;
            signed += field ? ' ' : header[i];
        }
        return stored == unsigned || stored == signed;
    }

    private static long octal(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) { // GNU base-256 for large sizes.
            long value = header[offset] & 0x7f;
            for (int i = 1; i < length; i++) value = value << 8 | header[offset + i] & 0xff;
            return value;
        }
        int i = offset, end = offset + length;
        while (i < end && (header[i] == ' ' || header[i] == 0)) i++;
        long value = 0;
        while (i < end && header[i] >= '0' && header[i] <= '7') value = value * 8 + (header[i++] - '0');
        return value;
    }

    private static String text(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < data.length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static UpdateException invalid(String detail) {
        return new UpdateException(UpdateException.Category.INVALID_PACKAGE, "Invalid update package: " + detail + ".");
    }
}

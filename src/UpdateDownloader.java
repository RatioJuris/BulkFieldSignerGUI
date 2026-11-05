// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Downloads one release package and accepts it only if its size, SHA-256 and file type match the signed manifest. */
final class UpdateDownloader {
    private static final System.Logger LOG = System.getLogger("updates");
    private final UpdateHttp http;

    UpdateDownloader(UpdateHttp http) { this.http = http; }

    Path download(GitHubReleaseClient.Asset asset, UpdateManifest.Asset expected, Path directory, LongConsumer progress, BooleanSupplier cancelled)
            throws UpdateException {
        if (!asset.name().equals(expected.name()) || asset.size() != expected.size()) {
            throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The release asset does not match the signed manifest.");
        }
        if (asset.name().contains("/") || asset.name().contains("\\") || asset.name().startsWith(".")) {
            throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "Unsafe package name.");
        }
        Path target = directory.resolve(asset.name());
        Path partial = directory.resolve(asset.name() + ".part");
        LOG.log(System.Logger.Level.INFO, "event=update.download.started asset={0} bytes={1}", asset.name(), expected.size());
        MessageDigest digest = sha256();
        try {
            Files.createDirectories(directory);
            Files.deleteIfExists(partial);
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), digest)) {
                http.stream(asset.downloadUrl(), "application/octet-stream", UpdateManifest.MAX_PACKAGE_BYTES, expected.size(), out, progress, cancelled);
            }
            if (Files.size(partial) != expected.size()) throw new UpdateException(UpdateException.Category.INCOMPLETE, "The downloaded file has the wrong size.");
            byte[] actual = digest.digest();
            if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(expected.sha256()))) {
                LOG.log(System.Logger.Level.WARNING, "event=update.checksum.failed asset={0}", asset.name());
                throw new UpdateException(UpdateException.Category.CHECKSUM, "The download's SHA-256 does not match the signed release information. It was discarded.");
            }
            LOG.log(System.Logger.Level.INFO, "event=update.checksum.verified asset={0}", asset.name());
            checkFileType(partial, asset.name());
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOG.log(System.Logger.Level.INFO, "event=update.download.completed asset={0}", asset.name());
            return target;
        } catch (IOException ex) {
            throw new UpdateException(UpdateException.Category.INCOMPLETE, "The update could not be saved: " + ex.getMessage(), ex);
        } finally {
            try { Files.deleteIfExists(partial); } catch (IOException ignored) { /* Reported by the caller's failure. */ }
        }
    }

    /** Magic numbers of the accepted package formats. */
    static void checkFileType(Path file, String name) throws IOException, UpdateException {
        byte[] head = new byte[8];
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            if (in.length() < 512) throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The package is too small.");
            in.readFully(head);
            boolean ok;
            if (name.endsWith(".zip")) ok = head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
            else if (name.endsWith(".tar.gz")) ok = (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b;
            else if (name.endsWith(".msi")) ok = Arrays.equals(head, new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1});
            else if (name.endsWith(".deb")) ok = new String(head, java.nio.charset.StandardCharsets.US_ASCII).equals("!<arch>\n");
            else if (name.endsWith(".dmg")) {
                byte[] trailer = new byte[4];
                in.seek(in.length() - 512);
                in.readFully(trailer);
                ok = new String(trailer, java.nio.charset.StandardCharsets.US_ASCII).equals("koly");
            } else ok = false;
            if (!ok) throw new UpdateException(UpdateException.Category.INVALID_PACKAGE, "The downloaded file is not a valid " + name.substring(name.indexOf('.', name.lastIndexOf('-'))) + " package.");
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}

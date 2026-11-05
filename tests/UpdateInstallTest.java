// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateInstallTest {
    private static final String TOP = UpdateInstaller.APP_FOLDER + " 2.1.0";
    private Path work;

    @BeforeEach void workspace() throws Exception {
        AppPaths.initialize();
        work = Files.createTempDirectory(AppPaths.TEMP, "update-install-");
    }

    @AfterEach void cleanup() { UpdateInstaller.deleteTree(work); }

    private Path zip(String... namesAndContents) throws IOException {
        Path file = work.resolve("package-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    @Test void zipPackagesExtractOnlyUnderTheExpectedFolder() throws Exception {
        Path ok = zip(TOP + "/", "", TOP + "/app/readme.txt", "hello", TOP + "/" + Product.WINDOWS_LAUNCHER + ".exe", "MZ");
        Path destination = work.resolve("ok");
        UpdateArchive.extract(ok, TOP, destination);
        assertEquals("hello", Files.readString(destination.resolve("app/readme.txt")));

        for (String evil : new String[]{TOP + "/../escape.txt", "other/file.txt", "/absolute.txt", TOP + "/a/../../escape.txt", "C:/windows.txt", TOP + "\\back.txt"}) {
            Path bad = zip(evil, "x");
            Path target = work.resolve("bad-" + System.nanoTime());
            UpdateException failure = assertThrows(UpdateException.class, () -> UpdateArchive.extract(bad, TOP, target), evil);
            assertEquals(UpdateException.Category.INVALID_PACKAGE, failure.category());
        }
        assertFalse(Files.exists(work.resolve("escape.txt")));
        Path nonEmpty = Files.createDirectories(work.resolve("non-empty"));
        Files.writeString(nonEmpty.resolve("keep.txt"), "user file");
        assertThrows(UpdateException.class, () -> UpdateArchive.extract(ok, TOP, nonEmpty), "Never extracts over existing files");
        assertEquals("user file", Files.readString(nonEmpty.resolve("keep.txt")));
    }

    // A minimal GNU tar writer, so tar.gz handling is tested without external tools.

    private static void tarEntry(OutputStream out, String name, char type, byte[] data, int mode, String link) throws IOException {
        if (name.getBytes(StandardCharsets.UTF_8).length > 100) {
            byte[] longName = (name + "\0").getBytes(StandardCharsets.UTF_8);
            tarEntry(out, "././@LongLink", 'L', longName, 0644, "");
            name = name.substring(0, 100);
        }
        byte[] header = new byte[512];
        put(header, 0, name, 100);
        put(header, 100, String.format("%07o", mode), 8);
        put(header, 108, "0000000", 8);
        put(header, 116, "0000000", 8);
        put(header, 124, String.format("%011o", data.length), 12);
        put(header, 136, String.format("%011o", 0), 12);
        java.util.Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) type;
        put(header, 157, link, 100);
        put(header, 257, "ustar  ", 8);
        long sum = 0;
        for (byte b : header) sum += b & 0xff;
        put(header, 148, String.format("%06o", sum), 7);
        header[155] = ' ';
        out.write(header);
        out.write(data);
        out.write(new byte[(512 - data.length % 512) % 512]);
    }

    private static void put(byte[] header, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private Path tarGz(TarBody body) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        body.write(raw);
        raw.write(new byte[1024]);
        Path file = work.resolve("package-" + System.nanoTime() + ".tar.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) { out.write(raw.toByteArray()); }
        return file;
    }

    private interface TarBody { void write(OutputStream out) throws IOException; }

    @Test void tarPackagesSupportLongNamesLinksAndPermissions() throws Exception {
        String deep = TOP + "/lib/runtime/legal/" + "very-long-module-name-for-testing.".repeat(4) + "/LICENSE";
        Path archive = tarGz(out -> {
            tarEntry(out, TOP + "/", '5', new byte[0], 0755, "");
            tarEntry(out, TOP + "/bin/" + UpdateInstaller.APP_FOLDER, '0', "#!/bin/sh".getBytes(), 0755, "");
            tarEntry(out, deep, '0', "license".getBytes(), 0644, "");
        });
        Path destination = work.resolve("linux");
        UpdateArchive.extract(archive, TOP, destination);
        assertEquals("license", Files.readString(destination.resolve(deep.substring(TOP.length() + 1))));
        Path launcher = destination.resolve("bin").resolve(UpdateInstaller.APP_FOLDER);
        if (Files.getFileStore(destination).supportsFileAttributeView("posix")) assertTrue(Files.isExecutable(launcher), "Executable mode is preserved");

        Path escape = tarGz(out -> tarEntry(out, TOP + "/lib/link", '2', new byte[0], 0777, "../../../outside"));
        assertEquals(UpdateException.Category.INVALID_PACKAGE, assertThrows(UpdateException.class, () -> UpdateArchive.extract(escape, TOP, work.resolve("escape"))).category());
        Path absolute = tarGz(out -> tarEntry(out, TOP + "/lib/link", '2', new byte[0], 0777, "/etc/passwd"));
        assertThrows(UpdateException.class, () -> UpdateArchive.extract(absolute, TOP, work.resolve("absolute")));
        Path hardLink = tarGz(out -> tarEntry(out, TOP + "/lib/hard", '1', new byte[0], 0644, TOP + "/bin/x"));
        assertThrows(UpdateException.class, () -> UpdateArchive.extract(hardLink, TOP, work.resolve("hard")), "Hard links are rejected");
        Path traversal = tarGz(out -> tarEntry(out, TOP + "/../../evil", '0', "x".getBytes(), 0644, ""));
        assertThrows(UpdateException.class, () -> UpdateArchive.extract(traversal, TOP, work.resolve("traversal")));
        if (Files.getFileStore(work).supportsFileAttributeView("posix")) {
            Path inside = tarGz(out -> {
                tarEntry(out, TOP + "/lib/real.txt", '0', "real".getBytes(), 0644, "");
                tarEntry(out, TOP + "/lib/alias.txt", '2', new byte[0], 0777, "real.txt");
            });
            Path linked = work.resolve("linked");
            UpdateArchive.extract(inside, TOP, linked);
            assertEquals("real", Files.readString(linked.resolve("lib/alias.txt")), "Links inside the package are kept");
        }
        Path truncated = work.resolve("truncated.tar.gz");
        byte[] whole = Files.readAllBytes(archive);
        Files.write(truncated, java.util.Arrays.copyOf(whole, whole.length / 2));
        assertThrows(Exception.class, () -> UpdateArchive.extract(truncated, TOP, work.resolve("truncated")), "Damaged archives fail");
    }

    private Path image(String version, Platform.Channel channel) throws IOException { return image(version, channel, channel); }

    /** An app image laid out like {@code layout}, whose launcher configuration names {@code channel}. */
    private Path image(String version, Platform.Channel channel, Platform.Channel layout) throws IOException {
        Path image = Files.createDirectories(work.resolve("image-" + System.nanoTime()));
        Path app = Files.createDirectories(layout == Platform.Channel.WINDOWS_PORTABLE ? image.resolve("app") : image.resolve("lib/app"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(app.resolve(UpdateInstaller.JAR)), manifest)) { jar.putNextEntry(new ZipEntry("x")); }
        Files.writeString(app.resolve(UpdateInstaller.launcherConfig(layout)), "[JavaOptions]\njava-options=-Dpdfsigner.channel=" + channel.id() + "\n");
        Path launcher = UpdateInstaller.launcher(image, layout);
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "launcher");
        launcher.toFile().setExecutable(true);
        return image;
    }

    @Test void extractedImagesMustBeTheExpectedVersionAndChannel() throws Exception {
        SemanticVersion v210 = SemanticVersion.parse("2.1.0");
        UpdateInstaller.verifyImage(image("2.1.0", Platform.Channel.WINDOWS_PORTABLE), Platform.Channel.WINDOWS_PORTABLE, v210);
        UpdateInstaller.verifyImage(image("2.1.0", Platform.Channel.LINUX_PORTABLE), Platform.Channel.LINUX_PORTABLE, v210);
        assertThrows(UpdateException.class, () -> UpdateInstaller.verifyImage(image("2.0.0", Platform.Channel.WINDOWS_PORTABLE), Platform.Channel.WINDOWS_PORTABLE, v210),
                "A package containing another version is rejected (no downgrade)");
        Path msiImage = image("2.1.0", Platform.Channel.WINDOWS_MSI, Platform.Channel.WINDOWS_PORTABLE);
        assertThrows(UpdateException.class, () -> UpdateInstaller.verifyImage(msiImage, Platform.Channel.WINDOWS_PORTABLE, v210),
                "An image built for another channel (MSI) is rejected by a portable copy");
        assertEquals("bulksigner.exe", UpdateInstaller.launcher(work, Platform.Channel.WINDOWS_PORTABLE).getFileName().toString(),
                "The Windows launcher is lowercase, without spaces or special characters");
        Path missing = image("2.1.0", Platform.Channel.WINDOWS_PORTABLE);
        Files.delete(UpdateInstaller.launcher(missing, Platform.Channel.WINDOWS_PORTABLE));
        assertThrows(UpdateException.class, () -> UpdateInstaller.verifyImage(missing, Platform.Channel.WINDOWS_PORTABLE, v210));
    }

    @Test void launchConfirmationUsesPerVersionMarkers() throws Exception {
        Path markers = Files.createDirectories(work.resolve("markers"));
        UpdateInstaller installer = new UpdateInstaller(markers);
        Instant before = Instant.now();
        UpdateInstaller.recordLaunch(markers);
        assertTrue(installer.awaitLaunch(SemanticVersion.current(), before, Duration.ofSeconds(2)));
        assertFalse(installer.awaitLaunch(SemanticVersion.parse("9.9.9"), before, Duration.ofSeconds(1)), "Another version's start is not confirmed");
        assertFalse(installer.awaitLaunch(SemanticVersion.current(), before.plusSeconds(60), Duration.ofSeconds(1)), "A stale marker does not count");
    }

    @Test void powershellArgumentsAreQuotedLiterally() {
        assertEquals("'C:\\Users\\O''Brien\\x.msi'", UpdateInstaller.powershellString("C:\\Users\\O'Brien\\x.msi"));
        assertEquals("'$(Remove-Item)'", UpdateInstaller.powershellString("$(Remove-Item)"), "Single-quoted strings are not expanded");
    }
}

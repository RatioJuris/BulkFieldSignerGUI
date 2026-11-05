// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Portable, per-user bootstrap. No installers, registry edits, shell elevation, or machine-wide configuration. */
public class Build {
    private static final String JDK_VERSION = "25.0.5.0.2";
    private static final String JDK_HASH = "5da7525c8268a10a66bd76a39f4e9af24d4ebc974d42800a609852c498a68460";
    private static final String MAVEN_VERSION = "3.9.11";
    private static final String MAVEN_HASH = "03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076";

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows") || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))) {
            throw new IOException("This distribution targets Windows x64.");
        }
        Path project = Path.of(args[0]).toAbsolutePath().normalize();
        String local = System.getenv("LOCALAPPDATA");
        Path base = local == null || local.isBlank() ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(local);
        if (!base.isAbsolute()) throw new IOException("Local AppData must be absolute.");
        Path home = base.resolve("RatioJuris/BulkFiledSigner").normalize();
        Files.createDirectories(home);
        if (!home.toRealPath().equals(base.toRealPath().resolve("RatioJuris/BulkFiledSigner"))) {
            throw new IOException("Application directory redirects outside local AppData.");
        }
        for (String directory : List.of("temp", "tools", "runtime", "repository", "logs", "app", "app/releases", "build", "config", "cache", "imports", "output", "placeholders")) {
            Path target = home.resolve(directory);
            Files.createDirectories(target);
            if (!target.toRealPath().startsWith(home.toRealPath())) throw new IOException("Redirected application directory: " + target);
        }
        // Lock only this user's build; it does not require elevated privileges.
        try (var channel = java.nio.channels.FileChannel.open(home.resolve("build.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another build is already running for this user.");
            Path javaHome = home.resolve("runtime/jdk-" + JDK_VERSION);
            installZip(home, javaHome, "https://aka.ms/download-jdk/microsoft-jdk-" + JDK_VERSION + "-windows-x64.zip", JDK_HASH, "SHA-256");
            Path maven = home.resolve("tools/apache-maven-" + MAVEN_VERSION);
            installZip(home, maven, "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/" + MAVEN_VERSION
                    + "/apache-maven-" + MAVEN_VERSION + "-bin.zip", MAVEN_HASH, "SHA-512");
            Properties release = new Properties();
            try (var reader = Files.newBufferedReader(javaHome.resolve("release"))) { release.load(reader); }
            if (!"\"Microsoft\"".equals(release.getProperty("IMPLEMENTOR"))) throw new IOException("Expected Microsoft OpenJDK.");
            Path classworlds;
            try (var entries = Files.list(maven.resolve("boot"))) {
                classworlds = entries.filter(p -> p.getFileName().toString().startsWith("plexus-classworlds-")
                        && p.toString().endsWith(".jar")).findFirst().orElseThrow();
            }
            String action = args.length > 1 ? args[1] : "";
            List<String> command = new ArrayList<>(List.of(javaHome.resolve("bin/java.exe").toString(),
                    "--enable-native-access=ALL-UNNAMED",
                    "-Duser.home=" + home, "-Djava.io.tmpdir=" + home.resolve("temp"),
                    "-Dmaven.home=" + maven, "-Dmaven.multiModuleProjectDirectory=" + project,
                    "-Dclassworlds.conf=" + maven.resolve("bin/m2.conf"), "-cp", classworlds.toString(),
                    "org.codehaus.plexus.classworlds.launcher.Launcher", "-B", "-C", "-ntp", "-f", project.resolve("pom.xml").toString(),
                    "-Dmaven.repo.local=" + home.resolve("repository"), "-Dapp.home=" + home));
            if ("clean".equalsIgnoreCase(action)) command.add("clean");
            else if ("test".equalsIgnoreCase(action)) command.add("test");
            else { command.add("clean"); command.add("verify"); }
            ProcessBuilder builder = new ProcessBuilder(command).directory(home.toFile()).inheritIO();
            builder.environment().put("TEMP", home.resolve("temp").toString());
            builder.environment().put("TMP", home.resolve("temp").toString());
            int exit = builder.start().onExit().get().exitValue();
            if (exit != 0) throw new IOException("Build failed with exit code " + exit);
            if (!"clean".equalsIgnoreCase(action) && !"test".equalsIgnoreCase(action)) {
                Path jar = home.resolve("build/BulkFiledSigner.jar");
                String releaseId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
                Path releaseDirectory = home.resolve("app/releases").resolve(releaseId);
                if (!Files.exists(releaseDirectory)) {
                    Path stage = Files.createTempDirectory(home.resolve("app/releases"), "stage-");
                    try {
                        Path libraries = Files.createDirectory(stage.resolve("lib"));
                        try (var files = Files.list(home.resolve("build/lib"))) {
                            for (Path file : files.toList()) Files.copy(file, libraries.resolve(file.getFileName()));
                        }
                        Files.copy(jar, stage.resolve("BulkFiledSigner.jar"));
                        Files.move(stage, releaseDirectory, StandardCopyOption.ATOMIC_MOVE);
                    } finally {
                        if (Files.exists(stage)) try (var files = Files.walk(stage)) {
                            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                        }
                    }
                }
                Path pointer = Files.createTempFile(home.resolve("app"), "release-", ".tmp");
                try {
                    Files.writeString(pointer, releaseId + System.lineSeparator());
                    Files.move(pointer, home.resolve("app/current.txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(pointer); }
                publish(project.resolve("run.bat"), home.resolve("run.bat"));
                System.out.println("Installed for the current user: " + releaseDirectory);
            }
        }
    }

    private static void publish(Path source, Path target) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "deploy-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void installZip(Path home, Path destination, String url, String expected, String algorithm) throws Exception {
        if (Files.isRegularFile(destination.resolve(".verified-archive"))
                && Files.readString(destination.resolve(".verified-archive")).equals(expected)) return;
        if (Files.exists(destination)) throw new IOException("Incomplete installation exists: " + destination + ". Move it aside and rebuild.");
        Path download = Files.createTempFile(home.resolve("temp"), "download-", ".zip");
        Path unpack = Files.createTempDirectory(home.resolve("temp"), "unpack-");
        try {
            System.out.println("Downloading portable archive: " + url);
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL).build()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build();
                var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(download));
                HttpResponse<Path> response;
                try { response = pending.get(10, TimeUnit.MINUTES); }
                finally { if (!pending.isDone()) pending.cancel(true); }
                if (response.statusCode() != 200) throw new IOException("Download failed: HTTP " + response.statusCode());
            }
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(download)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IOException("Archive checksum mismatch: " + url);
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(download))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path path = unpack.resolve(entry.getName()).normalize();
                    if (!path.startsWith(unpack)) throw new IOException("Unsafe archive path.");
                    if (entry.isDirectory()) Files.createDirectories(path);
                    else {
                        Files.createDirectories(path.getParent());
                        Files.copy(zip, path);
                    }
                }
            }
            Path extracted;
            try (var roots = Files.list(unpack)) {
                List<Path> entries = roots.toList();
                if (entries.size() != 1 || !Files.isDirectory(entries.getFirst())) throw new IOException("Unexpected archive layout.");
                extracted = entries.getFirst();
            }
            Files.writeString(extracted.resolve(".verified-archive"), expected);
            Files.move(extracted, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(download);
            try (var files = Files.walk(unpack)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

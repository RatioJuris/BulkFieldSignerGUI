// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the release packages of the current operating system from a Maven build, using the JDK that runs it
 * (Microsoft Build of OpenJDK 25): a jlink runtime, jpackage app images, and per platform
 *   Windows: portable ZIP, MSI (WiX), MSIX (Windows SDK makeappx)
 *   Linux:   portable tar.gz, .deb
 *   macOS:   .dmg
 * Each image is stamped with its installation channel (-Dpdfsigner.channel) for the update system.
 *
 * Usage: java tools/Package.java --build <dir with BulkFiledSigner.jar and lib/> --out <dir> --formats portable,msi,msix
 *        [--wix <WiX bin dir>] [--makeappx <path>] [--msix-name N --msix-publisher "CN=..." --msix-publisher-name "..."] [--smoke]
 */
public class Package {
    static final String NAME = "PDF DSC-TSA Bulk Signer";
    static final String FILE_PREFIX = "PDF-DSC-TSA-Bulk-Signer-";
    static final String DEVELOPER = "PrajnaBytes International Private Limited | RatioJuris";
    /** Installer manufacturer: jpackage builds registry and directory paths from it, where '|' is not allowed. */
    static final String VENDOR = "PrajnaBytes International Private Limited - RatioJuris";
    static final String DESCRIPTION = "Visible stamps on every page, secured by one DSC signature or RFC 3161 timestamp seal.";
    static final String COPYRIGHT = "Copyright (C) 2026 " + DEVELOPER + ". GPL-3.0-or-later.";
    static final String JAR = "BulkFiledSigner.jar";
    /** Windows launcher file name (bulksigner.exe): lowercase, without spaces or special characters. */
    static final String WINDOWS_LAUNCHER = "bulksigner";
    /** MSI installation folder, under Program Files. */
    static final String WINDOWS_INSTALL_DIR = "RatioJuris\\BulkFieldSignerGUI";

    final Path project = Path.of("").toAbsolutePath();
    final Map<String, String> options = new HashMap<>();
    final String os, arch, platform;
    final Path work, out, build, jdkBin;
    String version;

    public static void main(String[] args) throws Exception { new Package(args).run(); }

    Package(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Unexpected argument " + args[i]);
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            options.put(args[i].substring(2), flag ? "true" : args[++i]);
        }
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        os = osName.startsWith("windows") ? "windows" : osName.startsWith("mac") ? "macos" : osName.startsWith("linux") ? "linux" : null;
        arch = switch (System.getProperty("os.arch")) { case "amd64", "x86_64" -> "x64"; case "aarch64", "arm64" -> "arm64"; default -> null; };
        if (os == null || arch == null) throw new IllegalStateException("Unsupported build platform " + osName + "/" + System.getProperty("os.arch"));
        platform = os + "-" + arch;
        build = Path.of(require("build")).toAbsolutePath();
        out = Files.createDirectories(Path.of(require("out")).toAbsolutePath());
        work = Files.createDirectories(project.resolve("target").resolve("package-work").resolve(platform));
        jdkBin = Path.of(System.getProperty("java.home"), "bin");
    }

    String require(String key) {
        String value = options.get(key);
        if (value == null || value.equals("true")) throw new IllegalArgumentException("--" + key + " is required");
        return value;
    }

    void run() throws Exception {
        if (!"Microsoft".equals(System.getProperty("java.vendor")) || Runtime.version().feature() != 25) {
            throw new IllegalStateException("Package with the Microsoft Build of OpenJDK 25 (found " + System.getProperty("java.vendor") + " " + Runtime.version() + ").");
        }
        try (JarFile jar = new JarFile(build.resolve(JAR).toFile())) {
            String implementation = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
            version = normalize(implementation);
        }
        // Test only: builds installers with a higher version number to verify MSI upgrades. Never used for releases.
        if (options.containsKey("upgrade-test-version")) {
            version = normalize(options.get("upgrade-test-version"));
            log("UPGRADE TEST BUILD: installer version " + version + " (application unchanged). Not for distribution.");
        }
        log("Packaging " + NAME + " " + version + " for " + platform + " (" + Runtime.version() + ")");
        deleteTree(work);
        Files.createDirectories(work);
        Path input = Files.createDirectories(work.resolve("input"));
        Files.copy(build.resolve(JAR), input.resolve(JAR));
        Path lib = Files.createDirectories(input.resolve("lib"));
        try (var jars = Files.list(build.resolve("lib"))) { for (Path jar : jars.toList()) Files.copy(jar, lib.resolve(jar.getFileName())); }
        Path runtime = runtime(input);
        List<String> formats = List.of(options.getOrDefault("formats", "").split(","));
        List<Path> produced = new ArrayList<>();
        for (String format : formats) {
            switch (os + ":" + format.strip()) {
                case "windows:portable" -> produced.add(windowsPortable(input, runtime));
                case "windows:msi" -> produced.add(msi(input, runtime));
                case "windows:msix" -> produced.add(msix(input, runtime));
                case "linux:portable" -> produced.add(linuxPortable(input, runtime));
                case "linux:deb" -> produced.add(deb(input, runtime));
                case "macos:dmg" -> produced.add(dmg(input, runtime));
                default -> throw new IllegalArgumentException("Format '" + format + "' is not available on " + os);
            }
        }
        Path sums = out.resolve("SHA256SUMS-" + platform + ".txt");
        StringBuilder text = new StringBuilder();
        for (Path file : produced) text.append(sha256(file)).append("  ").append(file.getFileName()).append('\n');
        Files.writeString(sums, text.toString());
        log("Checksums:\n" + text);
        if (options.containsKey("smoke")) for (Path file : produced) smoke(file);
    }

    static String normalize(String version) {
        if (version == null || !version.matches("\\d+\\.\\d+(\\.\\d+)?")) throw new IllegalStateException("Invalid Implementation-Version " + version);
        return version.chars().filter(c -> c == '.').count() == 1 ? version + ".0" : version;
    }

    /** A minimal runtime: the modules the application uses plus security providers loaded by name. */
    Path runtime(Path input) throws Exception {
        String modules = capture(tool("jdeps"), "--multi-release", "25", "--ignore-missing-deps", "--print-module-deps",
                "--class-path", input.resolve("lib") + java.io.File.separator + "*", input.resolve(JAR).toString()).strip();
        Set<String> all = new TreeSet<>(List.of(modules.split(",")));
        all.addAll(List.of("jdk.crypto.cryptoki", "jdk.charsets", "jdk.accessibility", "jdk.unsupported", "java.naming", "jdk.zipfs", "jdk.localedata"));
        if (os.equals("windows")) all.add("jdk.crypto.mscapi");
        log("Runtime modules: " + all);
        Path runtime = work.resolve("runtime");
        exec(tool("jlink"), "--add-modules", String.join(",", all), "--strip-debug", "--no-header-files", "--no-man-pages",
                "--strip-native-commands", "--compress=zip-6", "--output", runtime.toString());
        return runtime;
    }

    /** A jpackage app image for one installation channel. Returns the image directory (or .app bundle). */
    Path image(Path input, Path runtime, String channel) throws Exception {
        Path destination = work.resolve("image-" + channel);
        List<String> command = new ArrayList<>(List.of(tool("jpackage"), "--type", "app-image"));
        command.addAll(applicationOptions(input, runtime, channel));
        command.addAll(List.of("--dest", destination.toString()));
        exec(command.toArray(String[]::new));
        return destination.resolve(os.equals("macos") ? NAME + ".app" : launcherName());
    }

    /** jpackage names the launcher (and the app image folder) after --name. */
    String launcherName() { return os.equals("windows") ? WINDOWS_LAUNCHER : NAME; }

    /** jpackage options describing the application itself, stamped with its installation channel. */
    List<String> applicationOptions(Path input, Path runtime, String channel) throws Exception {
        // Windows shows the description as the program's name in Task Manager, so it is the product name there.
        List<String> options = new ArrayList<>(List.of("--name", launcherName(), "--app-version", version, "--vendor", VENDOR, "--copyright", COPYRIGHT,
                "--description", os.equals("windows") ? NAME : DESCRIPTION, "--input", input.toString(), "--main-jar", JAR, "--main-class", "AppLauncher",
                "--runtime-image", runtime.toString(), "--java-options", "--enable-native-access=ALL-UNNAMED",
                "--java-options", "-Dpdfsigner.channel=" + channel, "--icon", icon().toString()));
        if (os.equals("macos")) options.addAll(List.of("--mac-package-identifier", "com.prajnabytes.pdfdsctsabulksigner", "--mac-package-name", "PDF DSC-TSA"));
        return options;
    }

    Path icon() throws Exception {
        return switch (os) {
            case "windows" -> project.resolve("res/app.ico");
            case "linux" -> project.resolve("res/app.png");
            default -> {
                // Exact renders of the splash-screen brand icon (tools/RenderIcons.java); no rescaling.
                Path set = Files.createDirectories(work.resolve("icon.iconset"));
                for (int size : new int[]{16, 32, 128, 256, 512}) {
                    Files.copy(brandIcon(size), set.resolve("icon_" + size + "x" + size + ".png"));
                    Files.copy(brandIcon(size * 2), set.resolve("icon_" + size + "x" + size + "@2x.png"));
                }
                Path icns = work.resolve("app.icns");
                exec("iconutil", "-c", "icns", set.toString(), "-o", icns.toString());
                yield icns;
            }
        };
    }

    /** A PNG of the splash-screen brand icon at an exact size, rendered by tools/RenderIcons.java. */
    Path brandIcon(int size) {
        Path icon = project.resolve("res/icons/app-" + size + ".png");
        if (!Files.isRegularFile(icon)) throw new IllegalStateException("Missing " + icon + "; run tools/RenderIcons.java.");
        return icon;
    }

    // Windows.

    Path windowsPortable(Path input, Path runtime) throws Exception {
        Path image = image(input, runtime, "windows-portable");
        String top = NAME + " " + version;
        Path zip = out.resolve(FILE_PREFIX + version + "-" + platform + "-portable.zip");
        Files.deleteIfExists(zip);
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(zip))) {
            try (var files = Files.walk(image)) {
                for (Path file : files.sorted().toList()) {
                    String name = top + "/" + image.relativize(file).toString().replace('\\', '/');
                    if (Files.isDirectory(file)) { if (!file.equals(image)) zipOut.putNextEntry(new ZipEntry(name + "/")); continue; }
                    zipOut.putNextEntry(new ZipEntry(name));
                    Files.copy(file, zipOut);
                    zipOut.closeEntry();
                }
            }
            addText(zipOut, top + "/README.txt", portableReadme("Windows", "Double-click \"" + WINDOWS_LAUNCHER + ".exe\"."));
            addFile(zipOut, top + "/LICENSE.txt", project.resolve("LICENSE"));
            addFile(zipOut, top + "/THIRD-PARTY-NOTICES.md", project.resolve("THIRD-PARTY-NOTICES.md"));
        }
        log("Portable ZIP: " + zip);
        return zip;
    }

    Path msi(Path input, Path runtime) throws Exception {
        Path destination = work.resolve("msi");
        // The upgrade code of the 1.0 installer (also per-machine), so installing 2.x replaces 1.0.
        String upgrade = Files.readString(project.resolve("res/msi-upgrade-code.txt")).strip();
        if (!upgrade.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalStateException("res/msi-upgrade-code.txt must contain the MSI upgrade UUID");
        }
        // Built from the application files in one step (not from a prepared app image): jpackage then takes the
        // Apps & features icon from the iconed launcher instead of an un-iconed launcher template.
        // Per-machine: C:\Program Files\RatioJuris\BulkFieldSignerGUI\bulksigner.exe, for all users.
        List<String> command = new ArrayList<>(List.of(tool("jpackage"), "--type", "msi"));
        command.addAll(applicationOptions(input, runtime, "windows-msi"));
        command.addAll(List.of("--dest", destination.toString(), "--win-upgrade-uuid", upgrade, "--install-dir", WINDOWS_INSTALL_DIR,
                "--win-menu", "--win-menu-group", NAME, "--win-shortcut", "--license-file", project.resolve("LICENSE").toString()));
        exec(env -> { if (options.containsKey("wix")) env.put("PATH", options.get("wix") + java.io.File.pathSeparator + env.getOrDefault("PATH", env.getOrDefault("Path", ""))); },
                command.toArray(String[]::new));
        Path msi;
        try (var files = Files.list(destination)) { msi = files.filter(p -> p.toString().endsWith(".msi")).findFirst().orElseThrow(); }
        displayProductName(msi);
        Path target = out.resolve(FILE_PREFIX + version + "-" + platform + ".msi");
        Files.move(msi, target, StandardCopyOption.REPLACE_EXISTING);
        log("MSI: " + target);
        return target;
    }

    /**
     * jpackage uses --name (the launcher name, "bulksigner") for the product and shortcut names. Users see the
     * product name instead: in Apps & features, the installer dialogs and the Start menu and desktop shortcuts.
     */
    void displayProductName(Path msi) throws Exception {
        String sql = project.resolve("tools/msi-sql.js").toString();
        List<String> shortcuts = capture("cscript", "//nologo", sql, msi.toString(), "SELECT Shortcut, Name FROM Shortcut").lines()
                .filter(line -> line.contains(" | ")).toList();
        if (shortcuts.isEmpty()) throw new IllegalStateException("The MSI has no shortcuts to rename.");
        List<String> statements = new ArrayList<>(List.of("UPDATE Property SET Value = '" + NAME + "' WHERE Property = 'ProductName'"));
        for (String row : shortcuts) {
            String[] fields = row.split(" \\| ", 2);
            String shortName = fields[1].contains("|") ? fields[1].substring(0, fields[1].indexOf('|')) : fields[1];
            statements.add("UPDATE Shortcut SET Name = '" + shortName + "|" + NAME + "' WHERE Shortcut = '" + fields[0].strip() + "'");
        }
        List<String> run = new ArrayList<>(List.of("cscript", "//nologo", sql, msi.toString()));
        run.addAll(statements);
        exec(run.toArray(String[]::new));
        String check = capture("cscript", "//nologo", sql, msi.toString(), "SELECT Value FROM Property WHERE Property = 'ProductName'",
                "SELECT Name FROM Shortcut");
        log("MSI display names:\n" + check.strip());
        if (!check.contains(NAME) || check.lines().filter(line -> line.endsWith("|" + NAME)).count() != shortcuts.size()) {
            throw new IllegalStateException("The MSI display names could not be set: " + check);
        }
    }

    /**
     * MSIX for Microsoft Store submission. Without a Partner Center identity (--msix-name, --msix-publisher,
     * --msix-publisher-name) a clearly marked validation package is produced; it is not for distribution.
     */
    Path msix(Path input, Path runtime) throws Exception {
        Path image = image(input, runtime, "windows-store");
        boolean store = options.containsKey("msix-name") && options.containsKey("msix-publisher") && options.containsKey("msix-publisher-name");
        String identity = store ? options.get("msix-name") : "PDFDSCTSABulkSigner.Validation";
        String publisher = store ? options.get("msix-publisher") : "CN=Validation Build - Not For Distribution";
        String publisherName = store ? options.get("msix-publisher-name") : "Validation build (not for distribution)";
        Path layout = work.resolve("msix-layout");
        copyTree(image, layout);
        Path assets = Files.createDirectories(layout.resolve("Assets"));
        Files.copy(brandIcon(44), assets.resolve("Square44x44Logo.png"));
        Files.copy(brandIcon(150), assets.resolve("Square150x150Logo.png"));
        Files.copy(brandIcon(50), assets.resolve("StoreLogo.png"));
        String manifest = """
                <?xml version="1.0" encoding="utf-8"?>
                <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
                         xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
                         xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
                         IgnorableNamespaces="uap rescap">
                  <Identity Name="%s" Publisher="%s" Version="%s.0" ProcessorArchitecture="%s"/>
                  <Properties>
                    <DisplayName>%s</DisplayName>
                    <PublisherDisplayName>%s</PublisherDisplayName>
                    <Logo>Assets\\StoreLogo.png</Logo>
                    <Description>%s</Description>
                  </Properties>
                  <Dependencies>
                    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.26100.0"/>
                  </Dependencies>
                  <Resources><Resource Language="en-us"/></Resources>
                  <Applications>
                    <Application Id="App" Executable="%s.exe" EntryPoint="Windows.FullTrustApplication">
                      <uap:VisualElements DisplayName="%s" Description="%s" BackgroundColor="transparent"
                                          Square150x150Logo="Assets\\Square150x150Logo.png" Square44x44Logo="Assets\\Square44x44Logo.png"/>
                    </Application>
                  </Applications>
                  <Capabilities><rescap:Capability Name="runFullTrust"/></Capabilities>
                </Package>
                """.formatted(xml(identity), xml(publisher), version, arch, xml(NAME), xml(publisherName), xml(DESCRIPTION), xml(WINDOWS_LAUNCHER), xml(NAME), xml(DESCRIPTION));
        Files.writeString(layout.resolve("AppxManifest.xml"), manifest);
        Path target = out.resolve(FILE_PREFIX + version + "-" + platform + (store ? "" : "-validation") + ".msix");
        Files.deleteIfExists(target);
        exec(makeappx(), "pack", "/o", "/h", "SHA256", "/d", layout.toString(), "/p", target.toString());
        log("MSIX (" + (store ? "Store identity " + identity : "validation only, not for distribution") + "): " + target);
        return target;
    }

    String makeappx() throws IOException {
        if (options.containsKey("makeappx")) return options.get("makeappx");
        Path kits = Path.of(System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)"), "Windows Kits", "10", "bin");
        if (Files.isDirectory(kits)) {
            try (var found = Files.walk(kits, 3)) {
                Optional<Path> tool = found.filter(p -> p.getFileName().toString().equalsIgnoreCase("makeappx.exe") && p.getParent().getFileName().toString().equals("x64"))
                        .max(Comparator.naturalOrder());
                if (tool.isPresent()) return tool.get().toString();
            }
        }
        throw new IllegalStateException("makeappx.exe (Windows SDK) was not found; MSIX cannot be built on this machine.");
    }

    // Linux.

    Path linuxPortable(Path input, Path runtime) throws Exception {
        Path image = image(input, runtime, "linux-portable");
        String top = NAME + " " + version;
        Path staging = Files.createDirectories(work.resolve("portable"));
        Path root = staging.resolve(top);
        Files.move(image, root);
        Files.writeString(root.resolve("README.txt"), portableReadme("Linux", "Run \"bin/" + NAME + "\" (for example: ./bin/\"" + NAME + "\")."));
        Files.copy(project.resolve("LICENSE"), root.resolve("LICENSE.txt"));
        Files.copy(project.resolve("THIRD-PARTY-NOTICES.md"), root.resolve("THIRD-PARTY-NOTICES.md"));
        Path tar = out.resolve(FILE_PREFIX + version + "-" + platform + ".tar.gz");
        Files.deleteIfExists(tar);
        exec("tar", "-C", staging.toString(), "-czf", tar.toString(), top);
        log("Portable tar.gz: " + tar);
        return tar;
    }

    Path deb(Path input, Path runtime) throws Exception {
        Path image = image(input, runtime, "linux-deb");
        Path destination = work.resolve("deb");
        exec(tool("jpackage"), "--type", "deb", "--app-image", image.toString(), "--name", NAME, "--app-version", version, "--vendor", VENDOR,
                "--copyright", COPYRIGHT, "--description", DESCRIPTION, "--dest", destination.toString(), "--linux-package-name", "pdf-dsc-tsa-bulk-signer",
                "--linux-shortcut", "--linux-menu-group", "Office", "--linux-app-category", "office", "--license-file", project.resolve("LICENSE").toString());
        Path deb;
        try (var files = Files.list(destination)) { deb = files.filter(p -> p.toString().endsWith(".deb")).findFirst().orElseThrow(); }
        Path target = out.resolve(FILE_PREFIX + version + "-" + platform + ".deb");
        Files.move(deb, target, StandardCopyOption.REPLACE_EXISTING);
        log("deb: " + target);
        return target;
    }

    // macOS.

    Path dmg(Path input, Path runtime) throws Exception {
        Path app = image(input, runtime, "macos-dmg");
        Path destination = work.resolve("dmg");
        exec(tool("jpackage"), "--type", "dmg", "--app-image", app.toString(), "--name", NAME, "--app-version", version, "--vendor", VENDOR,
                "--copyright", COPYRIGHT, "--description", DESCRIPTION, "--dest", destination.toString(), "--license-file", project.resolve("LICENSE").toString(),
                "--mac-package-identifier", "com.prajnabytes.pdfdsctsabulksigner");
        Path dmg;
        try (var files = Files.list(destination)) { dmg = files.filter(p -> p.toString().endsWith(".dmg")).findFirst().orElseThrow(); }
        Path target = out.resolve(FILE_PREFIX + version + "-" + platform + ".dmg");
        Files.move(dmg, target, StandardCopyOption.REPLACE_EXISTING);
        log("dmg (unsigned, not notarized): " + target);
        return target;
    }

    // Smoke tests of the produced packages: start the real application and read its report.

    void smoke(Path artifact) throws Exception {
        String name = artifact.getFileName().toString();
        Path report = work.resolve("smoke-" + name + ".txt");
        Files.deleteIfExists(report);
        if (name.endsWith("-portable.zip")) {
            Path extracted = work.resolve("smoke-portable");
            deleteTree(extracted);
            unzip(artifact, extracted);
            Path exe = extracted.resolve(NAME + " " + version).resolve(WINDOWS_LAUNCHER + ".exe");
            runSmoke(report, exe.toString());
        } else if (name.endsWith(".tar.gz")) {
            Path extracted = Files.createDirectories(work.resolve("smoke-tar"));
            exec("tar", "-C", extracted.toString(), "-xzf", artifact.toString());
            runSmoke(report, extracted.resolve(NAME + " " + version).resolve("bin").resolve(NAME).toString());
        } else if (name.endsWith(".msi")) {
            smokeMsi(artifact, report);
        } else {
            log("Smoke test of " + name + " is performed by the workflow (installation requires the system installer).");
            return;
        }
        log("Smoke test " + name + ":\n" + Files.readString(report));
        if (!Files.readString(report).contains("result=OK")) throw new IllegalStateException("Smoke test failed for " + name);
    }

    void smokeMsi(Path msi, Path report) throws Exception {
        msiexec("/i", msi, work.resolve("msi-install.log"));
        try {
            Path exe = installedExe();
            if (!Files.isRegularFile(exe)) throw new IllegalStateException("The MSI did not install " + exe);
            log("MSI installed launcher: " + exe);
            runSmoke(report, exe.toString());
        } finally {
            msiexec("/x", msi, work.resolve("msi-uninstall.log"));
            log("MSI uninstalled. Launcher still present: " + Files.exists(installedExe()));
        }
    }

    /** Per-machine installations need administrator rights: an elevated build (CI) runs directly, otherwise Windows asks once. */
    void msiexec(String action, Path msi, Path log) throws Exception {
        String arguments = action + " \"" + msi + "\" /qn /norestart /l*v \"" + log + "\"";
        exec("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$p = Start-Process msiexec.exe -ArgumentList '" + arguments.replace("'", "''") + "' -Verb RunAs -Wait -PassThru; exit $p.ExitCode");
    }

    Path installedExe() {
        return Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files")).resolve(WINDOWS_INSTALL_DIR).resolve(WINDOWS_LAUNCHER + ".exe");
    }

    void runSmoke(Path report, String launcher) throws Exception {
        Process process = new ProcessBuilder(launcher, "--smoke-test=" + report).redirectErrorStream(true)
                .redirectOutput(work.resolve("smoke-output.log").toFile()).start();
        if (!process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("The application did not finish its smoke test."); }
        if (!Files.exists(report)) throw new IllegalStateException("No smoke report from " + launcher + "; output: " + Files.readString(work.resolve("smoke-output.log")));
    }

    // Helpers.

    String portableReadme(String system, String launch) {
        return NAME + " " + version + " (portable, " + system + " " + arch + ")\n\n"
                + "This is a portable copy, not an installer. Extract the folder anywhere you can write to and start it:\n  " + launch + "\n\n"
                + "No separate Java installation is needed: the Microsoft Build of OpenJDK 25 runtime is included.\n"
                + "Settings and documents are stored in your user profile, never in this folder.\n"
                + "Updates are offered automatically; a new version is installed next to this folder.\n\n"
                + "Developed by " + DEVELOPER + ".\nLicensed under the GNU General Public License v3.0 or later (LICENSE.txt).\n"
                + "Source code and downloads: https://github.com/RatioJuris/BulkFieldSignerGUI\n";
    }

    String tool(String name) { return jdkBin.resolve(name + (os.equals("windows") ? ".exe" : "")).toString(); }

    interface EnvEditor { void edit(Map<String, String> env); }

    void exec(String... command) throws Exception { exec(env -> { }, command); }

    void exec(EnvEditor editor, String... command) throws Exception {
        log("$ " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        editor.edit(builder.environment());
        int exit = builder.start().waitFor();
        if (exit != 0) throw new IllegalStateException(command[0] + " failed with exit code " + exit);
    }

    String capture(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(command[0] + " failed:\n" + output);
        return output;
    }

    static void addText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static void addFile(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    static void unzip(Path zip, Path target) throws IOException {
        try (var file = new java.util.zip.ZipFile(zip.toFile())) {
            for (var entry : Collections.list(file.entries())) {
                Path path = target.resolve(entry.getName()).normalize();
                if (!path.startsWith(target)) throw new IOException("Unsafe entry " + entry.getName());
                if (entry.isDirectory()) { Files.createDirectories(path); continue; }
                Files.createDirectories(path.getParent());
                try (var in = file.getInputStream(entry); OutputStream outStream = Files.newOutputStream(path)) { in.transferTo(outStream); }
            }
        }
    }

    static void copyTree(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path destination = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true); // jpackage marks launchers read-only on Windows.
                Files.delete(path);
            }
        }
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    static void log(String message) { System.out.println("[package] " + message); }
}

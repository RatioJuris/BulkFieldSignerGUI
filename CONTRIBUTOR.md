# Contributing to PDF DSC-TSA Bulk Signer

This document is for developers, contributors and maintainers. User documentation is in
[`README.md`](README.md). Release publication is described in [`docs/RELEASING.md`](docs/RELEASING.md).

PDF DSC-TSA Bulk Signer 2.0 is developed by **PrajnaBytes International Private Limited | RatioJuris**
and licensed under the **GNU General Public License v3.0 or later** ([`LICENSE`](LICENSE)).
Third-party components are listed in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

## Contents

1. [Development environment](#development-environment)
2. [Project structure](#project-structure)
3. [Building, testing and running](#building-testing-and-running)
4. [Architecture](#architecture)
5. [Signing implementation](#signing-implementation)
6. [Certificates and trust](#certificates-and-trust)
7. [User interface](#user-interface)
8. [Data and file locations](#data-and-file-locations)
9. [Automatic updates](#automatic-updates)
10. [Packaging and distribution](#packaging-and-distribution)
11. [Testing](#testing)
12. [Contribution guidelines](#contribution-guidelines)

## Development environment

| Requirement | Version / notes |
| --- | --- |
| JDK | **Microsoft Build of OpenJDK 25**. `AppLauncher` refuses other vendors and versions at runtime, and `tools/Package.java` refuses them for packaging. |
| Build tool | Apache Maven 3.9 (`pom.xml`, Java release 25, `-Xlint:all`) |
| Platforms | Windows x64 (primary). Linux x64/arm64 and macOS arm64/x64 are built and tested in GitHub Actions. |
| Packaging (optional) | WiX Toolset 3.14 (MSI), Windows SDK `makeappx` (MSIX), `fakeroot`/`dpkg` (.deb), macOS (`.dmg`) |

Runtime dependencies are declared in `pom.xml`:

- iText 9.8.0 (`sign`, `bouncy-castle-adapter`) for PDF signing
- Bouncy Castle 1.86 (`bcprov`, `bcpkix`, `bcutil`) for CMS and RFC 3161
- Apache PDFBox 3.0.8 for page previews
- FlatLaf 3.7.2 for the look and feel
- JNA 5.19.1 for reading the Windows theme
- slf4j-simple 2.0.16
- JUnit 5.14 for tests

The generated JAR manifest lists each runtime dependency in its `Class-Path` and carries
`Implementation-Version`.

The jar name `BulkFiledSigner` and the data folder `RatioJuris\BulkFiledSigner` are intentional and
must not be renamed: existing installations and their data depend on them.

## Project structure

| Path | Contents |
| --- | --- |
| `src/` | Application source (default package; every file carries an SPDX `GPL-3.0-or-later` header) |
| `tests/` | JUnit 5 tests |
| `res/` | Packaged resources: themes, Noto fonts, GPL text (`LICENSE.txt`). Packaging-only files (excluded from the JAR): `app.ico`, `app.png`, `icons/`, `msi-upgrade-code.txt` |
| `tools/Build.java` | Windows bootstrap used by `build.bat` |
| `tools/Package.java` | Builds release packages with `jlink`/`jpackage` |
| `tools/ReleaseTool.java` | Creates and signs `update-manifest.json` (maintainers, offline) |
| `tools/RenderIcons.java` | Renders the brand icon into `res/app.ico`, `res/app.png`, `res/icons/` |
| `tools/ProductScreenshots.java` | Captures the product screenshots with sample data |
| `.github/workflows/release.yml` | Multi-platform build, test, package and draft-release workflow |
| `docs/` | Installation (`INSTALL.md`), releasing (`RELEASING.md`), product page (`products/prajnabytes.md`) |
| `screenshots/prajnabytes/` | Genuine screenshots of the application |
| `build.bat`, `run.bat` | Windows source build and launch |
| `jdk/` (not committed) | Optional bootstrap JDK for the first `build.bat` run |

## Building, testing and running

### Clone

```text
git clone https://github.com/RatioJuris/BulkFieldSignerGUI.git
cd BulkFieldSignerGUI
```

### Windows: build.bat and run.bat

Run `build.bat` from an ordinary command prompt, then `run.bat`.

- **Bootstrap Java.** A JDK 21 or newer, either in `jdk\` or via `JAVA_HOME`, is needed only to start
  the bootstrap.
- **What the bootstrap installs.** It downloads SHA-256-pinned Microsoft OpenJDK **25.0.5.0.2** and
  SHA-512-pinned Maven **3.9.11** into `%LOCALAPPDATA%\RatioJuris\BulkFiledSigner`. It then runs Maven
  with strict checksum verification.
- **What it never does.** No installer, UAC elevation, registry change, service, PATH change or
  certificate-store write is involved.
- **Commands.**
  - `build.bat` cleans, builds, runs the tests and installs a release.
  - `build.bat test` runs the tests.
  - `build.bat clean` cleans the build output.
  - A failed build returns a nonzero exit code.
- **Side-by-side installs.** Builds are installed side by side under `app\releases\<jar SHA-256>\`.
  `app\current.txt` is switched atomically, so a running copy keeps its JARs open while a new build
  installs. `run.bat` launches the selected release with `javaw.exe`, so no console window stays open.
- **Environment isolation.** The scripts clear `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and
  `_JAVA_OPTIONS` within their own `setlocal` scope only.

### Any platform: Maven

```text
mvn -B -ntp verify -Dapp.home=<scratch folder>
```

`app.home` decides where build output and the test working directory go. Its defaults are:

- **Windows:** `%LOCALAPPDATA%\RatioJuris\BulkFiledSigner`
- **macOS:** `~/Library/Application Support/RatioJuris/BulkFiledSigner`
- **Linux:** `~/.local/share/RatioJuris/BulkFiledSigner`

The build output goes to `${app.home}/build`, with the JAR in `build/BulkFiledSigner.jar` and its
dependencies in `build/lib/`. Tests run headless (`-Djava.awt.headless=true`), with the temp folder in
the build directory.

Run from source with `java --enable-native-access=ALL-UNNAMED -jar <app.home>/build/BulkFiledSigner.jar`.
A source-build JAR relaunches itself with the runtime it was started with, so its temp folder and
working folder are the application's own. Packaged launchers skip that relaunch.

### Smoke test

```text
<launcher> --smoke-test=<report file>
```

This starts the real application, waits for the main window and the startup update check, then writes
a report (`result=OK`, version, channel, platform, update status) and exits. It is used by
`tools/Package.java --smoke` and by the workflow.

### Common development issues

- **"This application requires the Microsoft Build of OpenJDK 25".** Run with that JDK. Other vendors
  are rejected on purpose.
- **Font loading fails in tests ("Cannot load font").** The JVM's temp folder doesn't exist.
  `Font.createFont` needs a writable temp folder.
- **`Picked up JAVA_TOOL_OPTIONS`.** A machine-wide JVM option is set. `build.bat` and `run.bat` clear
  it for their own process; direct `mvn` and `java` runs inherit it.
- **Windows: a jpackage launcher cannot be deleted.** jpackage marks launchers read-only. Clear the
  attribute first; the packaging and update code already does.

## Architecture

The application is a Java Swing desktop program. Every class is in the default package.

| Area | Classes | Responsibility |
| --- | --- | --- |
| Start-up | `AppLauncher`, `SplashScreen`, `AppPaths`, `Platform`, `Product` | Product identity, platform and channel detection, data folders, splash, background initialization (crypto provider, fonts, settings, certificates, update preferences), smoke test |
| Main window | `BulkFieldSignerGUI`, `SigningController`, `BulkSigningWorker` | Document, signing method, details, stamp appearance, permissions and activity cards; import, placement editor and signing actions; update gating |
| Placement editor | `VisualSignaturePicker`, `SignatureCanvas`, `PlaceholderEditorModel`, `PdfPreviewService`, `PageGeometry`, `PlaceholderStore` | Page previews, drawing and editing stamp areas, undo/redo, repeat on all pages, layout persistence |
| Signing | `PdfSignerService`, `PdfStamps`, `FileBackedPdfSigner`, `PdfReaders`, `PdfSecurityManager`, `SigningRequest`, `SigningMode` | Stamping, one DSC signature or document timestamp, permissions, verification |
| Stamps | `StampDesign`, `StampStore`, `StampRenderer`, `StampDesigner` | Designs, templates, images, rendering on screen and to PDF |
| Certificates and TSA | `KeystoreMonitorThread`, `CertificateValidator`, `Pkcs11Token`, `SignerIdentity`, `RegistryContext`, `VerifiedTimestampClient`, `TimestampProvider` | Certificate discovery and filtering, chain validation, PKCS#11 login, RFC 3161 timestamps |
| Settings and appearance | `SettingsManager`, `SettingsPanel`, `UiPreferences`, `ThemeManager`, `ThemeMode`, `WindowsThemeDetector`, `UIComponentFactory`, `UIConstants`, `AppIcon` | Persisted preferences, theme, token library, About, shared components, vector icons |
| Updates | `UpdateService`, `UpdateDiscovery`, `GitHubReleaseClient`, `UpdateHttp`, `UpdateManifest`, `UpdateTrust`, `UpdateDecision`, `UpdateDownloader`, `UpdateArchive`, `UpdateInstaller`, `UpdateStateManager`, `UpdateDialog`, `UpdatePreferences`, `UpdateType`, `UpdateException`, `SemanticVersion`, `Json` | GitHub release discovery, signed metadata, Type A/B/C policy, verified download and per-channel installation |
| Files | `LocalFiles` | Bounded reads, atomic writes, SHA-256, import of external PDFs |

**Threading.** Disk, network, certificate-store, PDF and rendering work runs on workers (`SwingWorker`
and single-thread executors). Swing state changes only on the event thread.

- `PdfPreviewService` owns PDFBox on one thread.
- `ThemeManager` and `UpdateStateManager` are confined to the event thread.
- Network requests use `java.net.http.HttpClient`.
- Blocking NIO and PDF library calls are not presented as asynchronous; they simply run on workers.

**Error handling.**
- Workers report failures to the activity log and to error dialogs.
- A failed signing never publishes partial output.
- Update failures are categorized (`UpdateException.Category`) and offer a Retry.

## Signing implementation

### Modes

The application has two signing modes (`SigningMode`). There are no other modes.

| Mode | Secures the PDF with | Stamp heading | Proves |
| --- | --- | --- | --- |
| `DSC` | One invisible CMS (`adbe.pkcs7.detached`) signature from the selected certificate, optionally with an RFC 3161 signature timestamp | "Digitally signed by *certificate name*" | Signer identity and integrity |
| `TIMESTAMP_SEAL` | One RFC 3161 document timestamp (`ETSI.RFC3161`) | "Declared by *typed name*" and "Sealed by *authority* trusted timestamp" | Integrity since sealing only |

A seal never says "Digitally signed"; the typed name is unverified. Seal mode requires a name and a
timestamp authority (`SigningRequest` enforces both).

### Pipeline (`PdfSignerService`)

1. **DSC mode: certificate checks.**
   - Read the certificate chain; for PKCS#11, log in first, which prompts for the PIN.
   - Validate the chain against the trust anchors.
   - Confirm that the leaf's SHA-256 fingerprint still matches the selection.
   - Obtain the private key.
2. **Input checks.** Open the imported copy (at most 256 MiB and 10,000 pages). Verify every existing
   signature. Reject:
   - encrypted input
   - certified PDFs (`/Perms`)
   - signatures with transform restrictions (`/Reference`)
   - permission restrictions requested on already-signed input
   - stamp rectangles outside the crop box
3. **Stamping (`PdfStamps`), into `temp/sign-*/prepared.pdf`.**
   - *Unsigned input:* the PDF is rewritten, optionally encrypted (AES-256, with a random owner password
     that is discarded). Stamps are drawn into page content, wrapped so that existing graphics state
     cannot distort them.
   - *Signed input:* an incremental update adds locked, printable stamp annotations. Earlier signatures
     stay valid.
   - Each distinct stamp becomes one form XObject. On rotated pages it is counter-rotated so that it
     reads upright.
4. **One signature or timestamp, into `secured.pdf`.** `FileBackedPdfSigner` (an iText `PdfSigner`
   in append mode) does the signing.
   - *DSC:* an invisible field (zero rectangle), with reason, location and signature creator.
   - *Seal:* `PdfSigner.timestamp`.
   - `FileBackedPdfSigner` closes its scratch handles even if a token or TSA fails halfway.
5. **Verification.**
   - Every signature verifies.
   - The final one covers the whole document.
   - Original signature names are preserved, and exactly one was added.
   - The subfilter matches the mode.
6. **Publication.** The file is moved atomically into a new `output/signed-*/` folder as
   `document_signed.pdf` or `document_sealed.pdf`. The source PDF is never modified. Temporary files
   are always deleted.
7. **Save As (`SignedFileSaver`).** A native save dialog asks where to save the verified file.
   - The dialog starts in the original's folder and suggests "<name> - signed.pdf".
   - The original's path is refused.
   - Linux has an extra replace confirmation, because its AWT dialog has none.
   - The file is copied through a temporary file in the destination folder, then moved into place.
   - The user can then open the PDF or its folder. The workspace copy is kept even if the user cancels.

   Automated tools can call `SignedFileSaver.saveAutomaticallyTo(folder)` to skip the dialog.

**Not established:** online revocation status (OCSP/CRL). The output is not PAdES LT/LTA.

### Stamp rendering (`StampRenderer`)

- **Text.** Shaped by Java (HarfBuzz) with Noto Sans and Noto Sans Devanagari, chosen per character
  run, and embedded in the PDF as vector outlines. Devanagari conjuncts and matras therefore render
  correctly without iText's paid shaping add-on. Stamp text is not searchable.
- **One renderer for both outputs.** The same `Composition` is painted on screen (in the designer,
  the main window preview and the placement editor) and written to PDF. Tests compare the two pixel by
  pixel.
- **Templates.** Placeholders: `{heading} {name} {reason} {location} {date} {issuer} {tsa} {page} {pages}`.
  A line is dropped when all its placeholders are empty. In seal mode, the timestamp authority line is
  always added.
- **Layouts.** Text only; image beside text; image above text; image only; name (or image) beside
  details.
- **Built-in designs.**
  - *Standard:* bordered text.
  - *Classic (name | details):* two columns, no border, with dates written as `yyyy.MM.dd HH:mm:ss +05'30'`.
- **Saved designs.** Stored in `config/stamps`; at most 50. Signature images are PNG, JPEG, GIF or
  BMP, up to 10 MiB and 40 megapixels when decoded; they are normalized to PNG with an edge of at most
  1,200 px.

### Timestamp authorities (`TimestampProvider`, `VerifiedTimestampClient`)

DigiCert, Sectigo, GlobalSign, SSL.com and Entrust. Several serve RFC 3161 only over HTTP, and
DigiCert does not answer over HTTPS. That is acceptable because every token is checked before use:

- the nonce and the message imprint
- the response size (at most 1 MiB) and deadlines
- the authority's signature
- its timestamping certificate, and the certificate path to a trust anchor

The authority's identity is never substituted for the signer's.

### Constraints

- Plain read-only random access is used for PDF input, which avoids memory-mapped file locks on Windows.
- Signature field names are random (`RJ_<uuid>`).
- Areas are limited to 10,000 per layout. Layout files are bounded to 1 MiB, use a primitive text
  format, and are keyed by the PDF's SHA-256.

## Certificates and trust

**Sources (`KeystoreMonitorThread.readRegistry`)**
- **Windows:** the current-user `Windows-MY` store (never LocalMachine; stores are never modified).
- **All platforms:** a PKCS#11 library chosen in Settings → DSC token (`Pkcs11Token`, stored in
  `config/token.properties`).

PKCS#11 certificates are listed without logging in. The PIN is requested through a Swing dialog at
signing time.

**Filtering (`CertificateValidator.isPersonalSigningCertificate`)**
- **Hidden:**
  - self-signed and CA certificates
  - issuers containing `MS-Organization-` or `DC=windows`
  - key usage without digitalSignature or nonRepudiation
  - an extended key usage that includes none of: document signing, secure email, Adobe authentic
    documents, any purpose
- **Shown but disabled, with the reason:**
  - expired or not yet valid
  - a Windows-store DSC whose USB token is unplugged (no reachable key)
- **Refresh:** the list is rebuilt every 5 seconds and updates only when something changes.
- **Selection:** bound to the SHA-256 fingerprint and alias, not the list position.

**Trust anchors (`CertificateValidator.currentUserTrustAnchors`)**
- The operating system's roots:
  - Windows: `Windows-ROOT`
  - macOS: `KeychainStore-ROOT`
  - Linux: the system CA bundle
- The Java runtime's trust store.
- CA certificates the user places in `config/trusted-roots`, for example a national root that is not
  in the system store.

**Validation** is offline PKIX, without revocation checking.

## User interface

- **Look and feel.** FlatLaf with semantic palette keys (`RJ.surface`, `RJ.text`, `RJ.accent`, …) in
  `res/themes`. The default font is Segoe UI, falling back to Helvetica Neue, Noto Sans or DejaVu Sans.
- **Themes.** System, Light or Dark (Settings → Appearance), applied to every open window immediately.
  "System" follows the Windows setting, which is read through JNA every 5 seconds. Window decorations
  are FlatLaf's on Windows and Linux and native on macOS.
- **Layout.** The workspace switches from two columns to one on narrow windows.
- **Shortcuts.** Ctrl+O imports, Ctrl+, opens Settings, Alt+S signs. Form labels are associated with
  their controls.
- **Placement editor.**
  - Fit page/width and 25–400% zoom; Ctrl+wheel zooms around the pointer, Shift+wheel scrolls
    horizontally, and Space+drag or the Pan tool moves the page.
  - Drag to draw; move or resize with the eight handles.
  - Delete, Ctrl+D (duplicate), Ctrl+Z / Ctrl+Shift+Z (undo/redo), arrow-key nudging (Shift: 10 pt),
    Ctrl+0 (fit), Ctrl+1 (100%), Ctrl+S (save).
  - "Repeat on all pages" keeps each stamp's size and its distance from the nearest edges.
  - Previews: draft first, then refined; at most 8 megapixels per raster; a 64 MiB / 8-image cache;
    zoom debounced at 140 ms; stale frames are discarded.
- **Brand icon.** `AppIcon.Symbol.BRAND` is the authoritative icon: the splash screen, every window
  icon (`AppIcon.windowImages()`) and the title area draw it in code. Packages use raster renders of
  it, produced by `tools/RenderIcons.java`; after changing the icon, re-run that tool and commit
  `res/app.ico`, `res/app.png` and `res/icons/`:

  ```text
  java -Djava.awt.headless=true -Dflatlaf.uiScale=1 -cp "<build>/classes;<build>/lib/*" tools/RenderIcons.java res
  ```

## Data and file locations

All application data lives in the user's data folder (`AppPaths`), never in the installation folder:

- **Windows:** `%LOCALAPPDATA%\RatioJuris\BulkFiledSigner`
- **macOS:** `~/Library/Application Support/RatioJuris/BulkFiledSigner`
- **Linux:** `$XDG_DATA_HOME/RatioJuris/BulkFiledSigner`

On macOS and Linux the folder is set to `rwx------`. `AppPaths.requireLocal` rejects traversal and
links that escape it.

| Relative location | Purpose |
| --- | --- |
| `config/settings.properties` | Signing mode, reason, location, declared name, authorities, design |
| `config/ui.properties` | Theme and window size |
| `config/update.properties` | Update checks, reminders, skipped version, pending mandatory update |
| `config/token.properties` | PKCS#11 library path |
| `config/stamps/` | Saved stamp designs and images |
| `config/trusted-roots/` | User-added root certificates |
| `imports/<SHA-256>.pdf` | Read-only copies of selected PDFs (the original is only read) |
| `placeholders/<SHA-256>.txt` | Saved stamp layouts |
| `output/signed-<unique>/` | Signed or sealed documents |
| `updates/` | Downloaded update packages and launch confirmations |
| `temp/`, `cache/`, `logs/` | Temporary files, PDFBox font cache, `application.log` |
| `app/`, `runtime/`, `tools/`, `repository/`, `build/` | Source-build installation (Windows `build.bat` only) |

## Automatic updates

See [`docs/RELEASING.md`](docs/RELEASING.md) for the full behaviour and the release procedure.

**Discovery.**
- Reads `releases/latest` and, when needed, `releases` of the canonical repository over HTTPS, with no
  credentials.
- Redirects are followed manually and only to GitHub hosts.
- Respects rate limits and stores the next allowed check time.

**Trust.**
- Each release must carry `update-manifest.json`, signed with Ed25519 (`update-manifest.json.sig`).
- The public key is in `UpdateTrust.PUBLISHER_KEY`. The private key is kept offline and never in the
  repository.
- The manifest lists one package per platform and channel, with its size and SHA-256.

**Policy (`UpdateDecision`).**
- The most severe type among newer releases applies.
- Type A is mandatory, ignores remembered choices, persists across restarts, and blocks import,
  placement and signing (`UpdateStateManager.blocksOperations`, enforced in the GUI and
  `SigningController`).
- Type B can be postponed.
- Type C can be skipped.

**Installation (`UpdateInstaller`), by channel:**
- *Portable:* installed side by side, started, then confirmed.
- *MSI:* a Windows Installer upgrade, run after the app exits.
- *Store:* left to the Microsoft Store.
- *.deb / .dmg:* opened with the system installer.
- *Source builds:* notified only.

Installation starts only when no operation is running.

## Packaging and distribution

`tools/Package.java` builds the packages for the platform it runs on:

1. `jdeps` and `jlink` produce a minimal runtime.
2. `jpackage` builds one app image per installation channel, stamped with `-Dpdfsigner.channel=<id>`.

| Platform | Package | Type | Status |
| --- | --- | --- | --- |
| Windows x64 | `…-windows-x64-portable.zip` | Portable app image with `PDF DSC-TSA Bulk Signer.exe` and bundled runtime, **not an installer** | Built, extracted and started locally; portable update tested end to end locally |
| Windows x64 | `…-windows-x64.msi` | Per-user installer (no admin), Start menu and desktop shortcuts, own upgrade code (`res/msi-upgrade-code.txt`) | Built with WiX 3.14; install, start, upgrade (2.0.0→2.0.1) and uninstall tested locally |
| Windows x64 | `…-windows-x64.msix` | Microsoft Store package (full trust) | Built only in CI (needs Windows SDK). Without Partner Center identity variables it is a `-validation` package. **Not submitted or published.** |
| Linux x64 / arm64 | `…-linux-<arch>.tar.gz`, `.deb` | Portable archive; Debian package installing to `/opt/pdf-dsc-tsa-bulk-signer` | Built and started in CI |
| macOS arm64 / x64 | `…-macos-<arch>.dmg` | Disk image | Built in CI. **Not signed with a Developer ID and not notarized.** |

**Packaging notes.**
- **Vendor field.** The installer manufacturer is "PrajnaBytes International Private Limited -
  RatioJuris", because jpackage builds registry paths from it and `|` is not allowed there. The exact
  attribution stays in the copyright and the app.
- **Windows 1.0.** Version 1.0 was a per-machine MSI with a different upgrade code. 2.x installs
  alongside it.
- **macOS menu name.** It is "PDF DSC-TSA", because macOS limits it to 15 characters.
- **Checksums.** Every package build writes `SHA256SUMS-<platform>.txt`. Draft releases combine them
  into `SHA256SUMS.txt`.

**GitHub Actions (`.github/workflows/release.yml`).**
- *Triggers:* `vX.Y.Z` tags, release-preparation branches (`release-*-prep`) and manual runs.
- *What it does:* builds, tests, packages and smoke-tests each platform; installs and removes the MSI,
  `.deb` and (with a throwaway certificate) MSIX; uploads packages and logs.
- *Releases:* creates only **draft** releases, from existing tags, on tag pushes or explicit manual
  requests. Nothing is published automatically.

## Testing

```text
build.bat test                      (Windows source build)
mvn -B -ntp verify -Dapp.home=...   (any platform)
```

| Test class | Covers |
| --- | --- |
| `SigningIntegrationTest` | DSC signing over stamps on several pages; incremental re-signing preserving original bytes; document-timestamp seals with a local in-memory TSA; permissions; rejected inputs; certificate filtering (personal, expired, unplugged token, system certificates); corrupted certificates; crop/rotation mapping and PDF stamps against the preview on every rotation, in both content and annotation modes; Classic layout; Devanagari shaping; import isolation; layout persistence; scratch-file release on token failure |
| `UpdateCoreTest` | Semantic versions; manifest validation (valid A/B/C, malformed, conflicting, wrong product, URLs, checksums); signatures; Type A/B/C rules and remembered choices; intermediate Type A; preferences; state blocking; dialog actions; platform detection |
| `UpdateNetworkTest` | Discovery against a local HTTP server: drafts, prereleases, platform packages, forged or tampered releases; downloads (success, checksum mismatch, size mismatch, truncation, HTTP errors, wrong file type), timeouts, rate limits, refused redirects and hosts, offline |
| `UpdateInstallTest` | Safe extraction of ZIP and tar.gz (traversal, links, hard links, long names, permissions, truncation), image version and channel checks, launch confirmation, PowerShell quoting |
| `PlaceholderEditorTest` | Undo/redo, drawing and resizing independent of raster size, panning, repeat on all pages |
| `PdfPreviewServiceTest` | Latest-request rendering, progressive refinement, caching, asynchronous cleanup |
| `AppearanceTest` | Theme persistence, malformed preferences, theme resolution and palette loading |

**What the tests don't use.** No test touches the Windows certificate store, a hardware token or the
network. Real DSC tokens, PKCS#11 libraries and live timestamp authorities must be tested manually
before a release.

**Platform coverage.** The full suite runs on all five CI platforms.

**Regression expectations.**
- Changes to signing must keep `SigningIntegrationTest` passing.
- Changes to the update system must keep the `Update*Test` classes passing.

## Contribution guidelines

**Getting started.**
- Fork the repository, create a topic branch from `main`, and build with `build.bat` (Windows) or Maven.

**Code.**
- Follow the existing style: default package, records for values, small focused classes, comments only
  where behaviour isn't obvious.
- Keep blocking work off the Swing event thread.
- New source files need the SPDX header:

  ```text
  // SPDX-License-Identifier: GPL-3.0-or-later
  // Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
  ```

**Commits and pull requests.**
- One logical change per commit, with a descriptive message.
- Pull requests describe the change, the tests added, and any user-visible effect.
- All tests must pass. New behaviour needs tests. Signing changes need integration tests that verify
  the produced PDF.

**Documentation.**
- Update [`README.md`](README.md) for anything users see, and this file for technical changes.
- Never claim untested platforms, packages or Store publication.

**Security.**
- Never commit private keys, certificates, PINs, tokens or real documents (see `.gitignore`).
- Don't weaken certificate filtering, signature verification, update-signature verification or path
  checks.
- Report vulnerabilities privately to the maintainers rather than in a public issue.

**Licensing.**
- Contributions are accepted under GPL-3.0-or-later.
- New dependencies must be GPL-3-compatible and must be added to `THIRD-PARTY-NOTICES.md`.
- Don't add assets you are not permitted to redistribute (the commercial Helvetica fonts were removed
  for this reason).

**Product identity.**
- Use the name "PDF DSC-TSA Bulk Signer" exactly, keep the developer attribution "PrajnaBytes
  International Private Limited | RatioJuris", and change the version (`src/Product.java`, `pom.xml`)
  only with the maintainers' approval.

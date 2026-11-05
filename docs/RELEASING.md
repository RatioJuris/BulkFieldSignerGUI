# Releasing PDF DSC-TSA Bulk Signer

This guide is for maintainers. It covers building the packages, classifying a release as Type A, B or C,
signing its update metadata and publishing it on GitHub.

## How installed copies find updates

1. At startup (at most once per check interval, 24 hours by default) and from **Settings → About →
   Check for updates**, the application reads
   `https://api.github.com/repos/RatioJuris/BulkFieldSignerGUI/releases/latest`. It uses no token, sends
   no credentials, and sets a `User-Agent`.
2. If that release is newer, or a mandatory update is already known, it reads the release list and
   considers up to 10 newer, published, non-draft, non-prerelease releases whose tags look like `vMAJOR.MINOR.PATCH`.
3. For each release it downloads `update-manifest.json` and `update-manifest.json.sig` and checks them:
   - The signature must verify against the Ed25519 publisher key built into the application (`src/UpdateTrust.java`).
   - The manifest must be strictly valid.
   - The packages it lists must be attached to the release with the same size, and the same SHA-256
     when GitHub reports one.
   A release that fails any of these checks is reported as unverifiable. It is never classified and never installed.
4. The most severe classification among all newer releases applies. An intermediate Type A release
   therefore cannot be bypassed by a later Type C release.
5. The package for the copy's platform and installation channel is downloaded over HTTPS (GitHub hosts
   only), checked against the manifest's size and SHA-256, and then installed with that channel's own
   mechanism.

A release published without a valid signed manifest is shown as "found, but its update information
could not be verified". It is never offered as an update.

## Update classifications

| Type | Use for | Mandatory | Postpone | Skip | Blocks signing |
| --- | --- | --- | --- | --- | --- |
| **A** | Security vulnerabilities, vulnerable crypto dependencies, critical DSC/TSA/PDF-integrity defects, data corruption, serious crashes | Yes | No | No | Yes, until installed |
| **B** | Important fixes and compatibility improvements | No | Yes ("Remind Me Later", default 24 h) | No | No |
| **C** | Minor, cosmetic or optional changes | No | Yes | Yes ("Skip This Version"; a newer release is still offered) | No |

How each type is enforced:

- **Type A.** The dialog is modal and ignores the close button and Escape. It offers no skip, later,
  dismiss or continue action. Signing, PDF import and stamp placement stay disabled until the required
  version runs. The requirement is saved (`config/update.properties`), so it survives a restart and
  remains in force offline until a successful check shows it no longer applies.
- **Type A without a package.** If no package exists for a platform, the copy stays restricted and
  explains that the platform's package has not been published yet.
- **What Type A does not do.** Client-side enforcement works through the supported interface only. It
  cannot stop a determined user from modifying their copy, and it never interferes with the operating system.
- **Running work is finished first.** A running signing operation is allowed to finish. Files are only
  replaced after the update state has switched to *installing*, so no new operation can start.

## Packages and update mechanisms

| Channel (`-Dpdfsigner.channel`) | Package | Manifest key | How the update is applied |
| --- | --- | --- | --- |
| `windows-portable` | `PDF-DSC-TSA-Bulk-Signer-<v>-windows-x64-portable.zip` | `windows-x64-portable` | Extracted and verified next to the current folder, started, and confirmed; the old folder is kept |
| `windows-msi` | `PDF-DSC-TSA-Bulk-Signer-<v>-windows-x64.msi` | `windows-x64-msi` | After the app exits, Windows Installer upgrades the per-machine installation in `C:\Program Files\RatioJuris\BulkFieldSignerGUI` (UAC prompt, `/passive`, logged) and restarts it |
| `windows-store` | `.msix` (Partner Center only) | none | Microsoft Store only; GitHub packages are never installed over it |
| `linux-portable` | `PDF-DSC-TSA-Bulk-Signer-<v>-linux-<arch>.tar.gz` | `linux-<arch>-portable` | Extracted and verified next to the current folder, started, and confirmed |
| `linux-deb` | `PDF-DSC-TSA-Bulk-Signer-<v>-linux-<arch>.deb` | `linux-<arch>-deb` | Verified, then opened with the system installer (administrator rights) |
| `macos-dmg` | `PDF-DSC-TSA-Bulk-Signer-<v>-macos-<arch>.dmg` | `macos-<arch>-dmg` | Verified, then opened; the user replaces the app in Applications |
| `source` (`build.bat`) | none | none | Notification only; rebuild from the repository |

Package names are fixed by the version and key. The application rejects any other name, any
unexpected file type (ZIP, gzip, MSI, deb and DMG signatures are checked), archives with paths outside
the expected top folder, and packages containing a different application version or channel.

## Building packages

Every push of a `vX.Y.Z` tag, and every manual run of **Actions → Release build**, does the following
on Windows x64, Linux x64, Linux arm64, macOS arm64 and macOS x64:

- builds the application and runs the full test suite;
- packages it with `tools/Package.java` and starts each package in smoke-test mode;
- installs and removes the MSI, `.deb` and (with a throwaway test certificate) MSIX;
- uploads the packages, `SHA256SUMS-<platform>.txt` and logs as artifacts.

To package locally, build with Maven first (`build.bat` on Windows). Then run:

```text
java tools/Package.java --build <app-home>/build --out dist --formats portable,msi,msix --smoke [--wix "<WiX 3.14 bin>"]
```

Requirements: Microsoft Build of OpenJDK 25 (the build checks this). For MSI, WiX Toolset 3.14. For
MSIX, the Windows SDK (`makeappx`). For `.deb`, `fakeroot` and `dpkg`. For `.dmg`, macOS.

### Microsoft Store identity

Without Partner Center values, the MSIX is a clearly named **validation** package
(`…-windows-x64-validation.msix`, publisher `CN=Validation Build - Not For Distribution`). It is never
attached to a release. For a Store submission, set these repository **variables** (not secrets; they are
not confidential) to the exact values shown in Partner Center → Product identity:

- `MSIX_IDENTITY_NAME`
- `MSIX_PUBLISHER`
- `MSIX_PUBLISHER_NAME`

The Store signs packages during submission.

### macOS signing and notarization

The `.dmg` is **not** signed with an Apple Developer ID and **not** notarized, so Gatekeeper warns on
first launch. Signing it requires an Apple Developer account, a "Developer ID Application" certificate
and notarization credentials. Store these as GitHub secrets (never in the repository), add
`--mac-sign --mac-signing-key-user-name …` to `tools/Package.java`, and run `xcrun notarytool submit`
and `xcrun stapler staple` in the workflow.

## Publishing a release

1. Set the version in `src/Product.java` (`VERSION`) and `pom.xml` (`<version>`). Update
   `CHANGELOG.md`. Commit.
2. Create and push the tag (`git tag v2.1.0 && git push origin v2.1.0`). The workflow builds everything
   and creates a **draft** release with the packages and `SHA256SUMS.txt`.
3. Download the draft's packages into a folder. On the machine that holds the private key, run the
   command for the release type below:

   ```text
   java -cp "BulkFiledSigner.jar;lib/*" tools/ReleaseTool.java manifest --version 2.1.0 --type B \
        --packages <folder> --key C:\pdfsign\release-signing\update-signing-ed25519.pk8 --out <folder>
   ```

   - **Type A security release:** `--type A --security --reason "Fixes …; signing is unsafe until updated."`
   - **Type A critical (non-security) release:** `--type A --reason "…"`
   - **Type B:** `--type B`
   - **Type C:** `--type C`
   - Add `--minimum X.Y.Z` if older versions cannot update automatically to this one.

   The tool refuses a key stored inside the repository. Before writing anything, it checks that the
   signature verifies with the key built into the application.
4. Attach `update-manifest.json` and `update-manifest.json.sig` to the draft. Write the release notes;
   users see them in the update dialog. For Type A, explain the issue and its impact.
5. Publish the draft. Installed copies see it at their next check. No rebuild of installed copies is needed.

## The signing key

- The private key (`update-signing-ed25519.pk8`) is kept outside the repository, at
  `C:\pdfsign\release-signing\` on the maintainer's machine. Back it up securely and offline.
- If the key is lost, installed copies cannot verify new releases. Users must then reinstall a version
  built with a new key.
- To rotate the key, publish a release, signed with the **old** key, that contains the new public key
  in `UpdateTrust.PUBLISHER_KEY`. Releases after that are signed with the new key.
- Never place the private key in GitHub secrets for routine builds. Signing is done offline, after the
  packages have been reviewed.

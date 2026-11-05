# Installing PDF DSC-TSA Bulk Signer 2.0

Each package includes its own Microsoft Build of OpenJDK 25 runtime, so no separate Java installation
is needed. Settings, stamp designs, imported copies and signed output are always stored in your user
profile, never in the program folder:

| System | Data folder |
| --- | --- |
| Windows | `%LOCALAPPDATA%\RatioJuris\BulkFiledSigner` |
| Linux | `$XDG_DATA_HOME/RatioJuris/BulkFiledSigner` (default `~/.local/share/...`), readable by your user only |
| macOS | `~/Library/Application Support/RatioJuris/BulkFiledSigner`, readable by your user only |

Downloads: <https://github.com/RatioJuris/BulkFieldSignerGUI/releases>. Each release lists a SHA-256 for
every file in `SHA256SUMS.txt`.

## Windows

Requires 64-bit Windows 10 or 11. Tested on Windows 11 Pro 23H2.

**Portable (`…-windows-x64-portable.zip`).** This is not an installer.
1. Extract the ZIP to any folder you can write to.
2. Open `PDF DSC-TSA Bulk Signer 2.0.0` and run `bulksigner.exe`.

No administrator rights are needed. When an update is installed, the new version is placed in a
folder next to the current one and started; the old folder can then be deleted.

**Installer (`…-windows-x64.msi`).**
- Installs for all users in `C:\Program Files\RatioJuris\BulkFieldSignerGUI` (launcher `bulksigner.exe`),
  with a Start menu entry and a desktop shortcut named **PDF DSC-TSA Bulk Signer**. Windows asks for
  administrator approval (UAC) during installation.
- Uninstall from Settings → Apps. Your settings and documents are kept.
- Later versions upgrade the installation in place; Windows asks for administrator approval again.
- **Version 1.0 ("BulkFieldSignerGUI")** is removed automatically when 2.0 is installed (both share
  the same installer upgrade code).

**Microsoft Store.** Not yet published. The Store installs updates for Store copies; the application
never installs GitHub packages over them.

**DSC tokens on Windows.** Insert the token. Its certificates appear through your Windows certificate
store, using the token driver from your certifying authority.

## Linux

Packages are built for x64 and arm64.

**Portable (`…-linux-x64.tar.gz`).**
```sh
tar -xzf PDF-DSC-TSA-Bulk-Signer-2.0.0-linux-x64.tar.gz
"./PDF DSC-TSA Bulk Signer 2.0.0/bin/PDF DSC-TSA Bulk Signer"
```
No root rights are needed. Updates are installed next to the extracted folder.

**Debian/Ubuntu package (`…-linux-x64.deb`).**
```sh
sudo apt install ./PDF-DSC-TSA-Bulk-Signer-2.0.0-linux-x64.deb
```
This installs to `/opt/pdf-dsc-tsa-bulk-signer` with a menu entry (Office). The package is
meant for desktop Linux; on minimal or server systems without desktop menus, use the portable
`.tar.gz` instead. When an update is
available, the application downloads and verifies the new `.deb` and opens it with your software
installer, which asks for administrator rights.

**DSC tokens on Linux.** Linux has no Windows certificate store. Install your token vendor's Linux
driver, then choose its PKCS#11 library (a `.so` file supplied by the vendor) in **Settings → DSC
token**. The token PIN is asked for only when you sign.

**Trusted roots.** The application trusts the system CA bundle and the bundled Java trust store. If
your DSC chains to a root they don't include (for example a national CA root), place that root
certificate (`.cer`, `.crt` or `.pem`) in `config/trusted-roots` inside the data folder.

## macOS

Packages are built for Apple silicon (arm64) and Intel (x64).

**Disk image (`…-macos-arm64.dmg` / `…-macos-x64.dmg`).**
1. Open the disk image.
2. Drag **PDF DSC-TSA Bulk Signer** to Applications.

The app is **not signed with an Apple Developer ID and not notarized**, so Gatekeeper blocks the first
launch. Control-click the app, choose **Open**, and confirm. On recent macOS versions, use System
Settings → Privacy & Security → **Open Anyway** instead.

The menu bar shows the short name "PDF DSC-TSA", because macOS limits that name to 15 characters.

**DSC tokens on macOS.** Choose the token vendor's PKCS#11 library (`.dylib`) in **Settings → DSC
token**. Roots in the macOS system keychain are trusted. Additional roots can be placed in
`config/trusted-roots`.

## Building from source (developers)

On Windows, `build.bat` then `run.bat` (see the README). On other systems, use Maven with JDK 25:
`mvn verify`. Source builds are notified about updates but not updated automatically.

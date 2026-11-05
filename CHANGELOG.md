# Changelog

## 2.0.0 (2026-11-05)

PDF DSC-TSA Bulk Signer 2.0, developed by PrajnaBytes International Private Limited | RatioJuris.
Licensed under the GNU General Public License v3.0 or later (previously a proprietary EULA).

- Visible stamps on every chosen page, secured by one cryptographic operation: an invisible DSC
  signature, or an RFC 3161 timestamp seal (DigiCert, Sectigo, GlobalSign, SSL.com, Entrust)
- Stamp designer with saved designs, signature images, Classic two-column layout and Devanagari shaping
- "Repeat on all pages" in the stamp placement editor
- After signing, a Save window asks where to save the signed PDF (starting in the original's folder,
  never overwriting the original), then offers to open the PDF or its folder
- File dialogs start in Documents or the last folder used
- Certificate list limited to personal signing certificates; expired DSCs and unplugged tokens shown disabled
- DSC tokens through PKCS#11 libraries (needed on Linux and macOS)
- Packages for Windows (portable ZIP; MSI installing for all users in
  `C:\Program Files\RatioJuris\BulkFieldSignerGUI` with the launcher `bulksigner.exe`; MSIX for the
  Microsoft Store), Linux (tar.gz, .deb)
  and macOS (.dmg), each with a bundled Microsoft Build of OpenJDK 25 runtime
- GitHub-based automatic updates with signed release metadata and Type A (mandatory), B (important)
  and C (optional) classifications
- The 2.0 installer replaces version 1.0 automatically
- After signing, the application comes to the front before the Save window opens, so the Save window
  is not hidden behind other programs
- Verified after release: DSC signing with a real HyperPKI HYP2003 token (Verasys DSC) on Windows 11,
  with and without a DigiCert timestamp

## 1.0.0

Initial release

- Bulk PDF digital signing
- DSC token support
- MSI installer

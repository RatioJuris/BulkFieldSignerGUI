// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertPathValidator;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CertificateValidator {
    private CertificateValidator() { }

    /** Linux distributions' system CA bundles; the first readable one is used. */
    private static final List<java.nio.file.Path> LINUX_BUNDLES = List.of(java.nio.file.Path.of("/etc/ssl/certs/ca-certificates.crt"),
            java.nio.file.Path.of("/etc/pki/tls/certs/ca-bundle.crt"), java.nio.file.Path.of("/etc/ssl/cert.pem"));

    /**
     * Roots trusted by the operating system (Windows current-user store, macOS Keychain, or the Linux system
     * bundle), the Java runtime's trust store, and CA certificates the user placed in {@code config/trusted-roots}.
     */
    public static Set<TrustAnchor> currentUserTrustAnchors() throws Exception {
        Set<X509Certificate> roots = new HashSet<>();
        switch (Platform.os()) {
            case WINDOWS -> roots.addAll(keyStoreCertificates(KeyStore.getInstance("Windows-ROOT", "SunMSCAPI")));
            case MACOS -> {
                try { roots.addAll(keyStoreCertificates(KeyStore.getInstance("KeychainStore-ROOT"))); }
                catch (java.security.KeyStoreException ex) { /* Older runtimes: the Java trust store below applies. */ }
            }
            case LINUX -> {
                for (java.nio.file.Path bundle : LINUX_BUNDLES) {
                    if (java.nio.file.Files.isReadable(bundle)) {
                        try (var in = java.nio.file.Files.newInputStream(bundle)) {
                            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                                if (c instanceof X509Certificate x) roots.add(x);
                            }
                        }
                        break;
                    }
                }
            }
        }
        var factory = javax.net.ssl.TrustManagerFactory.getInstance("PKIX");
        factory.init((KeyStore) null);
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof javax.net.ssl.X509TrustManager x509) roots.addAll(List.of(x509.getAcceptedIssuers()));
        }
        roots.addAll(userRoots());
        Set<TrustAnchor> anchors = new HashSet<>();
        for (X509Certificate root : roots) anchors.add(new TrustAnchor(root, null));
        return Set.copyOf(anchors);
    }

    private static List<X509Certificate> keyStoreCertificates(KeyStore store) throws Exception {
        store.load(null, null);
        List<X509Certificate> certificates = new ArrayList<>();
        var aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            if (store.getCertificate(aliases.nextElement()) instanceof X509Certificate certificate) certificates.add(certificate);
        }
        return certificates;
    }

    /** CA certificates (.cer, .crt, .pem) in {@code config/trusted-roots}; other files and non-CA certificates are ignored. */
    static List<X509Certificate> userRoots() throws Exception {
        List<X509Certificate> roots = new ArrayList<>();
        java.nio.file.Path directory = AppPaths.requireLocal(AppPaths.TRUST);
        if (!java.nio.file.Files.isDirectory(directory)) return roots;
        try (var files = java.nio.file.Files.list(directory)) {
            for (var file : files.sorted().limit(200).toList()) {
                String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!(name.endsWith(".cer") || name.endsWith(".crt") || name.endsWith(".pem"))) continue;
                try (var in = new ByteArrayInputStream(LocalFiles.readBounded(file, 262_144))) {
                    for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                        if (c instanceof X509Certificate x && x.getBasicConstraints() >= 0) { x.checkValidity(); roots.add(x); }
                    }
                } catch (java.io.IOException | GeneralSecurityException ex) {
                    System.getLogger(CertificateValidator.class.getName()).log(System.Logger.Level.WARNING, "Ignoring trusted-root file " + file, ex);
                }
            }
        }
        return roots;
    }

    /** Offline PKIX validation. It does not assert revocation status or long-term validity. */
    public static void validate(Certificate[] chain, Set<TrustAnchor> anchors) throws GeneralSecurityException {
        List<X509Certificate> certificates = inspectSigningChain(chain);
        X509Certificate last = certificates.getLast();
        if (anchors.stream().anyMatch(a -> last.equals(a.getTrustedCert()))) certificates.removeLast();
        if (certificates.isEmpty()) return; // Explicitly trusted leaf, checked for validity and key usage above.
        PKIXParameters parameters = new PKIXParameters(anchors);
        parameters.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(
                CertificateFactory.getInstance("X.509").generateCertPath(certificates), parameters);
    }

    /** Validate available certificate data without private-key access, PIN prompts, or network requests. */
    static List<X509Certificate> inspectSigningChain(Certificate[] chain) throws GeneralSecurityException {
        return inspectSigningChain(chain, true);
    }

    /**
     * Personal document-signing certificates only. Windows device keys, work/school account
     * (MS-Organization-Access) certificates, CA certificates and TLS-only certificates are not offered.
     */
    static boolean isPersonalSigningCertificate(X509Certificate leaf) throws java.security.cert.CertificateParsingException {
        if (leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal())) return false;
        if (leaf.getBasicConstraints() >= 0) return false;
        String issuer = leaf.getIssuerX500Principal().getName().toUpperCase(java.util.Locale.ROOT);
        if (issuer.contains("MS-ORGANIZATION-") || issuer.contains("DC=WINDOWS")) return false;
        boolean[] usage = leaf.getKeyUsage();
        if (usage != null && !(usage.length > 0 && usage[0] || usage.length > 1 && usage[1])) return false;
        List<String> purposes = leaf.getExtendedKeyUsage();
        return purposes == null || purposes.stream().anyMatch(SIGNING_PURPOSES::contains);
    }

    /** A user-facing reason when a genuine signing certificate cannot currently be used, otherwise null. */
    static String unavailableReason(List<X509Certificate> chain, java.util.Date now) {
        var format = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy");
        for (int i = 0; i < chain.size(); i++) {
            X509Certificate certificate = chain.get(i);
            String subject = i == 0 ? "Certificate" : "Issuing CA certificate";
            if (now.after(certificate.getNotAfter())) {
                return subject + " expired on " + format.format(certificate.getNotAfter().toInstant().atZone(java.time.ZoneId.systemDefault()));
            }
            if (now.before(certificate.getNotBefore())) {
                return subject + " is valid from " + format.format(certificate.getNotBefore().toInstant().atZone(java.time.ZoneId.systemDefault()));
            }
        }
        return null;
    }

    static String commonName(javax.security.auth.x500.X500Principal principal) {
        String name = principal.getName();
        try {
            for (var rdn : new javax.naming.ldap.LdapName(name).getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN")) return rdn.getValue().toString();
            }
        } catch (javax.naming.InvalidNameException ex) { /* Fall back to the complete subject. */ }
        return name;
    }

    private static final Set<String> SIGNING_PURPOSES = Set.of(
            "1.3.6.1.4.1.311.10.3.12", // Microsoft document signing
            "1.3.6.1.5.5.7.3.4",       // Secure email
            "1.2.840.113583.1.1.5",    // Adobe authentic documents trust
            "2.5.29.37.0");            // Any purpose

    static List<X509Certificate> inspectSigningChain(Certificate[] chain, boolean checkDates) throws GeneralSecurityException {
        if (chain == null || chain.length == 0 || chain.length > 16) {
            throw new GeneralSecurityException("Certificate chain is missing or invalid.");
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : chain) {
            if (!(certificate instanceof X509Certificate)) throw new GeneralSecurityException("Expected X.509 certificates.");
            byte[] encoded = certificate.getEncoded();
            if (encoded == null || encoded.length == 0 || encoded.length > 1_048_576) {
                throw new GeneralSecurityException("Invalid certificate encoding.");
            }
            ByteArrayInputStream input = new ByteArrayInputStream(encoded);
            X509Certificate parsed = (X509Certificate) factory.generateCertificate(input);
            if (input.available() != 0 || parsed.getPublicKey() == null) {
                throw new GeneralSecurityException("Malformed certificate data.");
            }
            if (checkDates) parsed.checkValidity();
            certificates.add(parsed);
        }
        boolean[] usage = certificates.getFirst().getKeyUsage();
        if (usage != null && !(usage.length > 0 && usage[0] || usage.length > 1 && usage[1])) {
            throw new GeneralSecurityException("Certificate is not permitted to sign documents.");
        }
        for (int i = 0; i < certificates.size() - 1; i++) {
            X509Certificate certificate = certificates.get(i);
            X509Certificate issuer = certificates.get(i + 1);
            if (!certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()) || issuer.getBasicConstraints() < 0) {
                throw new GeneralSecurityException("Broken certificate chain.");
            }
            boolean[] issuerUsage = issuer.getKeyUsage();
            if (issuerUsage != null && (issuerUsage.length <= 5 || !issuerUsage[5])) {
                throw new GeneralSecurityException("Issuer is not permitted to sign certificates.");
            }
            certificate.verify(issuer.getPublicKey());
        }
        X509Certificate last = certificates.getLast();
        if (last.getIssuerX500Principal().equals(last.getSubjectX500Principal())) last.verify(last.getPublicKey());
        return certificates;
    }
}

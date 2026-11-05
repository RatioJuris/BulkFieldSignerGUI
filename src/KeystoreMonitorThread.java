// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class KeystoreMonitorThread extends Thread {
    public interface KeystoreCallback {
        void onRegistryUpdated(RegistryContext registry);
        void onLog(String message);
    }

    private final KeystoreCallback callback;
    private volatile boolean running = true;
    private String lastState;
    private String lastError;

    public KeystoreMonitorThread(KeystoreCallback callback) {
        super("current-user-certificate-monitor");
        this.callback = callback;
        setDaemon(true);
    }

    public void stopMonitoring() { running = false; interrupt(); }

    /**
     * Windows: the current-user certificate store (never LocalMachine; stores are never modified).
     * All platforms: a configured PKCS#11 DSC token library.
     */
    public static RegistryContext readRegistry() throws Exception {
        List<SignerIdentity> signers = new ArrayList<>();
        Exception failure = null;
        if (Platform.os() == Platform.Os.WINDOWS) {
            KeyStore store = KeyStore.getInstance("Windows-MY", "SunMSCAPI");
            store.load(null, null);
            signers.addAll(readRegistry(store, "SunMSCAPI").signers());
        }
        java.nio.file.Path library = Pkcs11Token.configuredLibrary();
        if (library != null) {
            try { signers.addAll(Pkcs11Token.read(library).signers()); }
            catch (Exception ex) { failure = ex; }
        }
        if (signers.isEmpty() && failure != null) throw failure;
        signers.sort(ORDER);
        return new RegistryContext(signers);
    }

    private static final Comparator<SignerIdentity> ORDER = Comparator.comparing((SignerIdentity signer) -> !signer.usable())
            .thenComparing(SignerIdentity::displayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(SignerIdentity::fingerprint).thenComparing(SignerIdentity::alias);

    static RegistryContext readRegistry(KeyStore store, String provider) throws java.security.KeyStoreException {
        return readRegistry(store, provider, false);
    }

    /** {@code loginRequired}: private keys become visible only after a PIN login (PKCS#11 tokens). */
    static RegistryContext readRegistry(KeyStore store, String provider, boolean loginRequired) throws java.security.KeyStoreException {
        List<SignerIdentity> signers = new ArrayList<>();
        var aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            try {
                if (!(store.getCertificate(alias) instanceof X509Certificate cert)) continue;
                // A DSC whose USB token is unplugged remains in the store without a reachable private key.
                boolean keyAvailable = store.isKeyEntry(alias);
                java.security.cert.Certificate[] stored = store.getCertificateChain(alias);
                List<X509Certificate> chain = CertificateValidator.inspectSigningChain(
                        stored == null || stored.length == 0 ? new java.security.cert.Certificate[]{cert} : stored, false);
                X509Certificate leaf = chain.getFirst();
                if (!java.util.Arrays.equals(cert.getEncoded(), leaf.getEncoded())) continue;
                if (!CertificateValidator.isPersonalSigningCertificate(leaf)) continue;
                String reason = CertificateValidator.unavailableReason(chain, new java.util.Date());
                if (reason == null && !keyAvailable && !loginRequired) reason = "DSC token not connected. Insert it to sign";
                signers.add(new SignerIdentity(store, alias, SignerIdentity.fingerprint(leaf),
                        CertificateValidator.commonName(leaf.getSubjectX500Principal()), provider,
                        CertificateValidator.commonName(leaf.getIssuerX500Principal()),
                        leaf.getNotAfter().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                        reason, loginRequired));
            } catch (java.security.GeneralSecurityException | RuntimeException ex) {
                // A damaged provider entry must not discard the other healthy certificates.
                System.getLogger(KeystoreMonitorThread.class.getName()).log(System.Logger.Level.DEBUG,
                        "Skipping an invalid signing certificate entry", ex);
            }
        }
        signers.sort(ORDER);
        return new RegistryContext(signers);
    }

    @Override public void run() {
        while (running) {
            try {
                RegistryContext registry = readRegistry();
                String state = registry.signers().stream().map(s -> s.fingerprint() + ":" + s.alias() + ":" + s.unavailableReason()).collect(java.util.stream.Collectors.joining("|"));
                if (!state.equals(lastState)) {
                    callback.onRegistryUpdated(registry);
                    lastState = state;
                }
                lastError = null;
            } catch (Exception ex) {
                String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                if (!error.equals(lastError)) callback.onLog("Certificate store unavailable: " + error);
                if (!"".equals(lastState)) callback.onRegistryUpdated(new RegistryContext(List.of()));
                lastState = "";
                lastError = error;
            }
            try { Thread.sleep(5000); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
        }
    }
}

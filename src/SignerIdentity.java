// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * A stable selection captured with the keystore snapshot which produced it. A non-null
 * {@code unavailableReason} marks a genuine signing certificate that is listed but cannot be used.
 */
public record SignerIdentity(KeyStore keyStore, String alias, String fingerprint, String displayName, String provider,
                             String issuer, LocalDate notAfter, String unavailableReason, boolean loginRequired) {
    public SignerIdentity(KeyStore keyStore, String alias, String fingerprint, String displayName, String provider) {
        this(keyStore, alias, fingerprint, displayName, provider, "", null, null, false);
    }

    public SignerIdentity(KeyStore keyStore, String alias, String fingerprint, String displayName, String provider,
                          String issuer, LocalDate notAfter, String unavailableReason) {
        this(keyStore, alias, fingerprint, displayName, provider, issuer, notAfter, unavailableReason, false);
    }

    public SignerIdentity {
        issuer = issuer == null ? "" : issuer;
    }

    public boolean usable() { return unavailableReason == null; }

    public static String fingerprint(X509Certificate certificate) throws java.security.GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    @Override public String toString() { return displayName; }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Publisher authenticity of update metadata. Each release's {@code update-manifest.json} is signed with the
 * publisher's Ed25519 private key (kept offline, never in the repository); the matching public key is built
 * into the application. Because the manifest carries every package's SHA-256, a valid signature also
 * authenticates the packages. A checksum published next to a file proves nothing on its own.
 */
public final class UpdateTrust {
    /** Ed25519 public key (X.509 SubjectPublicKeyInfo, Base64) of the update publisher. */
    static final String PUBLISHER_KEY = "MCowBQYDK2VwAyEA8MeggiVQ/dRZmI3/63ULSegXUNrut3Q6zS6njRoHqdI=";

    private UpdateTrust() { }

    public static PublicKey publisherKey() throws GeneralSecurityException { return decode(PUBLISHER_KEY); }

    static PublicKey decode(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    /** {@code signatureFile} is the Base64 Ed25519 signature over the exact manifest bytes, on a single line. */
    public static void verify(byte[] manifest, byte[] signatureFile, PublicKey key) throws GeneralSecurityException {
        if (signatureFile == null || signatureFile.length == 0 || signatureFile.length > 512) throw new SignatureException("Missing or oversized manifest signature.");
        byte[] signature;
        try { signature = Base64.getDecoder().decode(new String(signatureFile, StandardCharsets.US_ASCII).strip()); }
        catch (IllegalArgumentException ex) { throw new SignatureException("The manifest signature is not valid Base64."); }
        if (signature.length != 64) throw new SignatureException("The manifest signature has an invalid length.");
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(manifest);
        if (!verifier.verify(signature)) throw new SignatureException("The update manifest was not signed by the publisher.");
    }

    /** Used by the release tool. */
    public static String sign(byte[] manifest, java.security.PrivateKey key) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(manifest);
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}

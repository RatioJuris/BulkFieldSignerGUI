// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.signatures.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Visible stamps on every placed area, then exactly one cryptographic operation over the whole file:
 * an invisible DSC signature, or an RFC 3161 document timestamp (seal).
 */
public final class PdfSignerService {
    private PdfSignerService() { }

    public static Path executeSigningPipeline(SigningRequest request, Consumer<String> logger) throws Exception {
        return executeSigningPipeline(request, logger, CertificateValidator.currentUserTrustAnchors());
    }

    // Explicit trust anchors allow deterministic integration tests without touching the Windows store.
    static Path executeSigningPipeline(SigningRequest request, Consumer<String> logger, Set<TrustAnchor> anchors) throws Exception {
        return executeSigningPipeline(request, logger, anchors, provider -> new VerifiedTimestampClient(provider, anchors));
    }

    static Path executeSigningPipeline(SigningRequest request, Consumer<String> logger, Set<TrustAnchor> anchors,
                                       Function<TimestampProvider, ITSAClient> timestamps) throws Exception {
        Path source = AppPaths.requireLocal(request.sourcePdf());
        if (!Files.isRegularFile(source) || Files.size(source) > LocalFiles.MAX_PDF_BYTES) throw new IOException("Invalid or oversized PDF.");
        boolean dsc = request.mode() == SigningMode.DSC;
        Certificate[] chain = null;
        PrivateKey privateKey = null;
        X509Certificate leaf = null;
        if (dsc) {
            SignerIdentity identity = request.signer();
            // PKCS#11 tokens expose private keys only after a PIN login; the Windows store needs none here.
            java.security.KeyStore store = identity.loginRequired() ? Pkcs11Token.login(identity) : identity.keyStore();
            chain = identity.loginRequired() ? Pkcs11Token.chain(store, identity.alias()) : store.getCertificateChain(identity.alias());
            CertificateValidator.validate(chain, anchors);
            leaf = (X509Certificate) chain[0];
            if (!SignerIdentity.fingerprint(leaf).equals(identity.fingerprint())) {
                throw new IOException("Selected certificate changed. Select it again.");
            }
            var key = identity.loginRequired() ? Pkcs11Token.privateKey(store, identity.alias()) : store.getKey(identity.alias(), null);
            if (!(key instanceof PrivateKey candidate)) throw new IOException("The selected private key is unavailable.");
            privateKey = candidate;
            logger.accept("Certificate path checked. Online revocation status is not established.");
        }

        List<String> originalSignatures;
        try (PdfReader reader = PdfReaders.open(source); PdfDocument document = new PdfDocument(reader)) {
            if (document.getNumberOfPages() < 1 || document.getNumberOfPages() > 10_000) throw new IOException("Unsupported page count.");
            if (document.getReader().isEncrypted()) throw new IOException("Import an unencrypted PDF; encrypted input needs an explicit owner-credential workflow.");
            originalSignatures = new SignatureUtil(document).getSignatureNames();
            validateSignatures(document, false);
            if (document.getCatalog().getPdfObject().containsKey(PdfName.Perms)) {
                throw new IOException("This PDF has certification permissions. Adding new signature fields is not supported for certified PDFs.");
            }
            for (String name : originalSignatures) {
                var dictionary = new SignatureUtil(document).getSignatureDictionary(name);
                if (dictionary.containsKey(PdfName.Reference)) throw new IOException("This PDF contains signature transform restrictions.");
            }
            if (request.securityRequested() && !originalSignatures.isEmpty()) {
                throw new IOException("Cannot add encryption to an already signed PDF without invalidating its signatures.");
            }
            for (SignaturePlaceholder placeholder : request.placeholders()) validateArea(document, placeholder);
        }

        String tsaLabel = request.timestampProvider() == TimestampProvider.NONE ? "" : request.timestampProvider().label();
        StampRenderer.Values values = new StampRenderer.Values(request.mode(),
                dsc ? CertificateValidator.commonName(leaf.getSubjectX500Principal()) : request.declaredName(),
                request.reason(), request.location(), OffsetDateTime.now(),
                dsc ? CertificateValidator.commonName(leaf.getIssuerX500Principal()) : "", tsaLabel);
        byte[] stampImage = request.design().usesImage() ? StampStore.imageBytes(request.design()) : null;

        Path work = Files.createTempDirectory(AppPaths.requireLocal(AppPaths.TEMP), "sign-");
        byte[] ownerPassword = new byte[32];
        new SecureRandom().nextBytes(ownerPassword);
        try {
            Path prepared = work.resolve("prepared.pdf");
            boolean signedInput = !originalSignatures.isEmpty();
            try (PdfReader reader = PdfReaders.open(source);
                 PdfWriter writer = request.securityRequested()
                         ? new PdfWriter(prepared.toString(), PdfSecurityManager.properties(request, ownerPassword))
                         : new PdfWriter(prepared.toString());
                 PdfDocument document = signedInput
                         ? new PdfDocument(reader, writer, new StampingProperties().useAppendMode())
                         : new PdfDocument(reader, writer)) {
                int placed = PdfStamps.apply(document, request.placeholders(), request.design(), stampImage, values, signedInput);
                logger.accept("Placed " + placed + " visible stamp" + (placed == 1 ? "" : "s") + ".");
            }
            LocalFiles.checkInterrupted();

            Path secured = work.resolve("secured.pdf");
            String field = "RJ_" + UUID.randomUUID().toString().replace("-", "");
            ReaderProperties properties = new ReaderProperties();
            if (request.securityRequested()) properties.setPassword(ownerPassword);
            try (PdfReader reader = PdfReaders.open(prepared, properties); var output = Files.newOutputStream(secured)) {
                if (dsc) {
                    SignerProperties signing = new SignerProperties().setFieldName(field).setPageNumber(1)
                            .setPageRect(new Rectangle(0, 0, 0, 0)) // Invisible: the stamps are the visible marks.
                            .setReason(request.reason()).setLocation(request.location())
                            .setSignatureCreator(Product.title());
                    IExternalSignature signature = new PrivateKeySignature(privateKey, "SHA256", request.signer().provider());
                    ITSAClient tsa = request.timestampProvider() == TimestampProvider.NONE ? null : timestamps.apply(request.timestampProvider());
                    try (FileBackedPdfSigner signer = new FileBackedPdfSigner(reader, output, work, signing)) {
                        signer.signDetached(new BouncyCastleDigest(), signature, chain, null, null, tsa, 0, PdfSigner.CryptoStandard.CMS);
                    }
                    logger.accept("Document signed with one DSC signature covering every page and stamp.");
                } else {
                    ITSAClient tsa = timestamps.apply(request.timestampProvider());
                    try (FileBackedPdfSigner signer = new FileBackedPdfSigner(reader, output, work, new SignerProperties().setFieldName(field))) {
                        signer.timestamp(tsa, field);
                    }
                    logger.accept("Document sealed with a " + tsaLabel + " RFC 3161 document timestamp.");
                }
            }
            Files.deleteIfExists(prepared);

            try (PdfReader reader = PdfReaders.open(secured, properties); PdfDocument result = new PdfDocument(reader)) {
                validateSignatures(result, true);
                SignatureUtil signatures = new SignatureUtil(result);
                List<String> names = signatures.getSignatureNames();
                if (names.size() != originalSignatures.size() + 1 || !names.containsAll(originalSignatures) || !names.getLast().equals(field)) {
                    throw new IOException("Signature preservation check failed.");
                }
                PdfName subFilter = signatures.getSignatureDictionary(field).getAsName(PdfName.SubFilter);
                if (dsc == PdfName.ETSI_RFC3161.equals(subFilter)) throw new IOException("Unexpected signature type in output.");
            }
            LocalFiles.checkInterrupted();
            Path outputDirectory = Files.createTempDirectory(AppPaths.requireLocal(AppPaths.OUTPUTS), "signed-");
            Path destination = outputDirectory.resolve(dsc ? "document_signed.pdf" : "document_sealed.pdf");
            try {
                Files.move(secured, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.deleteIfExists(outputDirectory);
                throw ex;
            }
            return destination;
        } finally {
            Arrays.fill(ownerPassword, (byte) 0);
            try { LocalFiles.deleteTree(work); }
            catch (IOException ex) { logger.accept("Temporary cleanup failed: " + ex.getMessage()); }
        }
    }

    private static void validateArea(PdfDocument document, SignaturePlaceholder placeholder) throws IOException {
        if (placeholder.page() > document.getNumberOfPages()) throw new IOException("Stamp page is outside the document.");
        var crop = document.getPage(placeholder.page()).getCropBox();
        var area = placeholder.toRectangle();
        if (area.getLeft() < crop.getLeft() - 0.01 || area.getBottom() < crop.getBottom() - 0.01
                || area.getRight() > crop.getRight() + 0.01 || area.getTop() > crop.getTop() + 0.01) {
            throw new IOException("Stamp rectangle is outside the visible page.");
        }
    }

    private static void validateSignatures(PdfDocument document, boolean requireFinalCoverage) throws Exception {
        SignatureUtil signatures = new SignatureUtil(document);
        List<String> names = signatures.getSignatureNames();
        for (String name : names) {
            if (!signatures.readSignatureData(name).verifySignatureIntegrityAndAuthenticity()) {
                throw new IOException("Invalid signature: " + name);
            }
        }
        if (requireFinalCoverage && (names.isEmpty() || !signatures.signatureCoversWholeDocument(names.getLast()))) {
            throw new IOException("The final signature does not cover the complete document.");
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.signatures.ITSAClient;
import com.itextpdf.signatures.SignatureUtil;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SigningIntegrationTest {
    private static final StampDesign PLAIN = new StampDesign("test-plain", "Plain", "{heading} {name}\nReason: {reason}\nPage {page} of {pages}",
            StampDesign.Layout.TEXT_ONLY, StampDesign.Ink.BLACK, true, false);
    /** Classic two-column layout without the (unpredictable) signing time, for pixel comparison. */
    private static final StampDesign CLASSIC_UNDATED = new StampDesign("test-classic", "Classic", "{heading} {name}\nReason: {reason}",
            StampDesign.Layout.NAME_LEFT, StampDesign.Ink.BLACK, false, false, StampDesign.DateStyle.CLASSIC);
    private static KeyPair caKeys;
    private static X509Certificate ca;
    private static SignerIdentity identity;
    private static Set<TrustAnchor> anchors;
    private static TimeStampTokenGenerator tsaGenerator;
    private Path workspace;
    private final List<Path> outputs = new ArrayList<>();

    @BeforeAll static void setupCrypto() throws Exception {
        AppPaths.initialize();
        Security.addProvider(new BouncyCastleProvider());
        caKeys = keys();
        X500Name caName = new X500Name("CN=Ratio Juris Test CA");
        ca = certificate(caName, caKeys.getPublic(), caName, caKeys.getPrivate(), Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(7200), true, KeyUsage.keyCertSign | KeyUsage.cRLSign, null);
        KeyPair signerKeys = keys();
        X509Certificate leaf = certificate(new X500Name("CN=Integration Signer"), signerKeys.getPublic(), caName, caKeys.getPrivate(),
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), false,
                KeyUsage.digitalSignature | KeyUsage.nonRepudiation, new KeyPurposeId[]{KeyPurposeId.id_kp_emailProtection});
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("test", signerKeys.getPrivate(), new char[0], new Certificate[]{leaf, ca});
        identity = new SignerIdentity(store, "test", SignerIdentity.fingerprint(leaf), "Integration Signer", "BC");
        anchors = Set.of(new TrustAnchor(ca, null));

        KeyPair tsaKeys = keys();
        X509Certificate tsa = certificate(new X500Name("CN=Local Test TSA"), tsaKeys.getPublic(), caName, caKeys.getPrivate(),
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), false, KeyUsage.digitalSignature,
                new KeyPurposeId[]{KeyPurposeId.id_kp_timeStamping});
        tsaGenerator = new TimeStampTokenGenerator(new JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withRSA", tsaKeys.getPrivate(), tsa),
                new JcaDigestCalculatorProviderBuilder().build().get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)),
                new ASN1ObjectIdentifier("1.3.6.1.4.1.55555.1"));
        tsaGenerator.addCertificates(new JcaCertStore(List.of(tsa, ca)));
    }

    private static KeyPair keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(X500Name subject, PublicKey key, X500Name issuer, PrivateKey issuerKey, Instant from,
                                               Instant to, boolean authority, int usage, KeyPurposeId[] purposes) throws Exception {
        var builder = new JcaX509v3CertificateBuilder(issuer, new BigInteger(64, new SecureRandom()), Date.from(from), Date.from(to), subject, key);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(authority));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(usage));
        if (purposes != null) builder.addExtension(Extension.extendedKeyUsage, purposes[0].equals(KeyPurposeId.id_kp_timeStamping), new ExtendedKeyUsage(purposes));
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey)));
    }

    /** An in-memory RFC 3161 authority; no network access. */
    private static ITSAClient localTsa() {
        return new ITSAClient() {
            @Override public int getTokenSizeEstimate() { return 12_000; }
            @Override public MessageDigest getMessageDigest() throws java.security.GeneralSecurityException { return MessageDigest.getInstance("SHA-256"); }
            @Override public byte[] getTimeStampToken(byte[] imprint) throws Exception {
                var generator = new TimeStampRequestGenerator();
                generator.setCertReq(true); // As VerifiedTimestampClient requests.
                var request = generator.generate(NISTObjectIdentifiers.id_sha256, imprint, BigInteger.valueOf(System.nanoTime()));
                return tsaGenerator.generate(request, BigInteger.valueOf(System.nanoTime()), new Date()).getEncoded();
            }
        };
    }

    @BeforeEach void createWorkspace() throws Exception {
        workspace = Files.createTempDirectory(AppPaths.TEMP, "integration-");
    }

    @AfterEach void cleanup() throws Exception {
        for (Path output : outputs) LocalFiles.deleteTree(output.getParent());
        LocalFiles.deleteTree(workspace);
    }

    private Path pdf(String name) throws Exception { return pdf(name, 1); }

    private Path pdf(String name, int pages) throws Exception {
        Path path = workspace.resolve(name);
        try (PdfDocument document = new PdfDocument(new PdfWriter(path.toString()))) { for (int i = 0; i < pages; i++) document.addNewPage(); }
        return path;
    }

    private SigningRequest request(Path input, boolean restricted, List<SignaturePlaceholder> areas) {
        return request(input, restricted, areas, PLAIN);
    }

    private SigningRequest request(Path input, boolean restricted, List<SignaturePlaceholder> areas, StampDesign design) {
        return new SigningRequest(input, SigningMode.DSC, "", "Integration test", "Local AppData", restricted, restricted, restricted,
                identity, TimestampProvider.NONE, areas, design);
    }

    private SigningRequest seal(Path input, List<SignaturePlaceholder> areas) {
        return new SigningRequest(input, SigningMode.TIMESTAMP_SEAL, "Declared Person", "Filing", "Lucknow", false, false, false,
                null, TimestampProvider.DIGICERT, areas, PLAIN);
    }

    private Path sign(SigningRequest request) throws Exception {
        Path result = PdfSignerService.executeSigningPipeline(request, message -> { }, anchors, provider -> localTsa());
        outputs.add(result);
        return result;
    }

    @Test void stampsOnEveryPageAreSecuredByOneSignatureAndResigningIsIncremental() throws Exception {
        Path input = pdf("original.pdf", 3);
        byte[] original = Files.readAllBytes(input);
        List<SignaturePlaceholder> everyPage = List.of(new SignaturePlaceholder(1, 30, 30, 180, 60),
                new SignaturePlaceholder(2, 30, 30, 180, 60), new SignaturePlaceholder(3, 30, 30, 180, 60));
        Path first = sign(request(input, false, everyPage));
        assertArrayEquals(original, Files.readAllBytes(input), "Source must never be rewritten.");
        try (PdfDocument document = new PdfDocument(PdfReaders.open(first))) {
            SignatureUtil signatures = new SignatureUtil(document);
            assertEquals(1, signatures.getSignatureNames().size(), "Stamps are visual; one signature secures the document.");
            String name = signatures.getSignatureNames().getFirst();
            assertTrue(signatures.readSignatureData(name).verifySignatureIntegrityAndAuthenticity());
            assertTrue(signatures.signatureCoversWholeDocument(name));
            var widget = signatures.getSignatureDictionary(name);
            assertNotNull(widget);
        }
        Path second = sign(request(first, false, List.of(new SignaturePlaceholder(1, 30, 180, 180, 60))));
        byte[] firstBytes = Files.readAllBytes(first);
        byte[] secondBytes = Files.readAllBytes(second);
        assertArrayEquals(firstBytes, java.util.Arrays.copyOf(secondBytes, firstBytes.length), "An incremental update preserves the complete input prefix.");
        try (PdfDocument document = new PdfDocument(PdfReaders.open(second))) {
            SignatureUtil signatures = new SignatureUtil(document);
            assertEquals(2, signatures.getSignatureNames().size());
            for (String name : signatures.getSignatureNames()) assertTrue(signatures.readSignatureData(name).verifySignatureIntegrityAndAuthenticity());
            assertTrue(signatures.signatureCoversWholeDocument(signatures.getSignatureNames().getLast()));
            var annotations = document.getPage(1).getAnnotations();
            assertTrue(annotations.stream().anyMatch(a -> PdfName.Stamp.equals(a.getSubtype())), "Signed input receives a locked stamp annotation.");
        }
        assertTrue(second.startsWith(AppPaths.OUTPUTS));
    }

    @Test void timestampSealUsesDocumentTimestampAndNeverClaimsPersonalSignature() throws Exception {
        Path output = sign(seal(pdf("seal.pdf", 2), List.of(new SignaturePlaceholder(1, 30, 30, 220, 80),
                new SignaturePlaceholder(2, 30, 30, 220, 80))));
        assertTrue(output.getFileName().toString().contains("sealed"));
        try (PdfDocument document = new PdfDocument(PdfReaders.open(output))) {
            SignatureUtil signatures = new SignatureUtil(document);
            assertEquals(1, signatures.getSignatureNames().size());
            String name = signatures.getSignatureNames().getFirst();
            assertEquals(PdfName.ETSI_RFC3161, signatures.getSignatureDictionary(name).getAsName(PdfName.SubFilter));
            var data = signatures.readSignatureData(name);
            assertTrue(data.isTsp());
            assertTrue(data.verifySignatureIntegrityAndAuthenticity());
            assertTrue(signatures.signatureCoversWholeDocument(name));
        }
        List<String> lines = StampRenderer.lines(PLAIN, new StampRenderer.Values(SigningMode.TIMESTAMP_SEAL, "Declared Person",
                "", "", null, "", "DigiCert"), 1, 2);
        assertEquals("Declared by Declared Person", lines.getFirst());
        assertFalse(String.join("\n", lines).contains("Digitally signed"));
        assertTrue(lines.contains("Sealed by DigiCert trusted timestamp"), "A seal always names its timestamp authority.");
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("Reason")), "Lines with only empty placeholders are omitted.");
        assertThrows(IllegalArgumentException.class, () -> new SigningRequest(pdf("x.pdf"), SigningMode.TIMESTAMP_SEAL, "", "", "",
                false, false, false, null, TimestampProvider.DIGICERT, List.of(new SignaturePlaceholder(1, 1, 1, 50, 20)), PLAIN));
        assertThrows(IllegalArgumentException.class, () -> new SigningRequest(pdf("y.pdf"), SigningMode.TIMESTAMP_SEAL, "Name", "", "",
                false, false, false, null, TimestampProvider.NONE, List.of(new SignaturePlaceholder(1, 1, 1, 50, 20)), PLAIN));
    }

    @Test void permissionsAreAppliedBeforeSigningAndSignatureRemainsValid() throws Exception {
        Path output = sign(request(pdf("permissions.pdf"), true, List.of(new SignaturePlaceholder(1, 30, 30, 180, 100),
                new SignaturePlaceholder(1, 250, 30, 180, 100))));
        try (PdfDocument document = new PdfDocument(PdfReaders.open(output))) {
            PdfReader reader = document.getReader();
            assertTrue(reader.isEncrypted());
            assertEquals(0, reader.getPermissions() & EncryptionConstants.ALLOW_COPY);
            assertEquals(0, reader.getPermissions() & EncryptionConstants.ALLOW_PRINTING);
            assertEquals(0, reader.getPermissions() & EncryptionConstants.ALLOW_MODIFY_CONTENTS);
            SignatureUtil signatures = new SignatureUtil(document);
            assertEquals(1, signatures.getSignatureNames().size());
            for (String name : signatures.getSignatureNames()) assertTrue(signatures.readSignatureData(name).verifySignatureIntegrityAndAuthenticity());
        }
    }

    @Test void addingEncryptionToSignedInputFailsWithoutPublishingOutput() throws Exception {
        Path signed = sign(request(pdf("signed.pdf"), false, List.of(new SignaturePlaceholder(1, 30, 30, 180, 100))));
        byte[] before = Files.readAllBytes(signed);
        var exception = assertThrows(java.io.IOException.class, () -> sign(request(signed, true,
                List.of(new SignaturePlaceholder(1, 30, 180, 180, 100)))));
        assertTrue(exception.getMessage().contains("already signed"));
        assertArrayEquals(before, Files.readAllBytes(signed));
    }

    @Test void invalidAreasAndChangedSignerAreRejected() throws Exception {
        Path input = pdf("validation.pdf");
        assertThrows(java.io.IOException.class, () -> sign(request(input, false, List.of(new SignaturePlaceholder(2, 20, 20, 80, 40)))));
        assertThrows(java.io.IOException.class, () -> sign(request(input, false, List.of(new SignaturePlaceholder(1, -20, 20, 80, 40)))));
        SignerIdentity changed = new SignerIdentity(identity.keyStore(), identity.alias(), "wrong-fingerprint", "Changed", "BC");
        assertThrows(java.io.IOException.class, () -> sign(new SigningRequest(input, "", "", false, false, false, changed,
                TimestampProvider.NONE, List.of(new SignaturePlaceholder(1, 30, 30, 100, 100)))));
        SignerIdentity expired = new SignerIdentity(identity.keyStore(), identity.alias(), identity.fingerprint(), "Expired", "BC",
                "Test CA", java.time.LocalDate.now().minusDays(1), "Certificate expired");
        assertThrows(IllegalArgumentException.class, () -> new SigningRequest(input, "", "", false, false, false, expired,
                TimestampProvider.NONE, List.of(new SignaturePlaceholder(1, 30, 30, 100, 100))));
    }

    @Test void importNeedsOnlyReadAccessAndDoesNotChangeSource() throws Exception {
        Path source = pdf("source.pdf");
        byte[] before = Files.readAllBytes(source);
        Path imported = LocalFiles.importPdf(source);
        try {
            assertTrue(imported.startsWith(AppPaths.IMPORTS));
            assertArrayEquals(before, Files.readAllBytes(imported));
            assertArrayEquals(before, Files.readAllBytes(source));
            assertNotEquals(source, imported);
        } finally { Files.deleteIfExists(imported); }
    }

    @Test void placeholderDataIsBoundedValidatedAndBoundToPdfContent() throws Exception {
        Path source = pdf("layout.pdf");
        Path stored = AppPaths.PLACEHOLDERS.resolve(LocalFiles.fingerprint(source) + ".txt");
        List<SignaturePlaceholder> areas = List.of(new SignaturePlaceholder(1, 10, 20, 30, 40));
        try {
            PlaceholderStore.save(source, areas);
            assertEquals(areas, PlaceholderStore.load(source));
            Files.writeString(stored, "RJ-PLACEHOLDERS-1\n1 NaN 2 3 4\n", StandardCharsets.UTF_8);
            assertThrows(java.io.IOException.class, () -> PlaceholderStore.load(source));
            Files.write(stored, new byte[]{(byte) 0xac, (byte) 0xed, 0, 5});
            assertThrows(java.io.IOException.class, () -> PlaceholderStore.load(source));
            Files.write(stored, new byte[1_100_000]);
            assertThrows(java.io.IOException.class, () -> PlaceholderStore.load(source));
        } finally { Files.deleteIfExists(stored); }
    }

    @Test void pathsAndRequestSnapshotsAreIsolated() throws Exception {
        assertEquals(Platform.dataHome().normalize().resolve("RatioJuris").resolve("BulkFiledSigner"), AppPaths.ROOT);
        if (Platform.os() == Platform.Os.WINDOWS) {
            assertEquals(Path.of(System.getenv("LOCALAPPDATA"), "RatioJuris", "BulkFiledSigner"), AppPaths.ROOT);
        }
        assertEquals(AppPaths.TEMP.toString(), System.getProperty("java.io.tmpdir"));
        assertThrows(java.io.IOException.class, () -> AppPaths.requireLocal(AppPaths.ROOT.resolve("../escaped.txt")));
        assertThrows(java.io.IOException.class, () -> LocalFiles.atomicWrite(AppPaths.ROOT.getParent().resolve("outside.txt"), new byte[0]));
        var areas = new ArrayList<>(List.of(new SignaturePlaceholder(1, 10, 10, 80, 40)));
        SigningRequest request = request(pdf("snapshot.pdf"), false, areas);
        areas.clear();
        assertEquals(1, request.placeholders().size());
        assertThrows(UnsupportedOperationException.class, () -> request.placeholders().clear());
        assertThrows(IllegalArgumentException.class, () -> new SignaturePlaceholder(1, 0, 0, 0, 10));
    }

    @Test void cropAndRotationMappingMatchesActualPdfboxRenderedPixels() throws Exception {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            Path input = workspace.resolve("rotated-" + rotation + ".pdf");
            SignaturePlaceholder marker = new SignaturePlaceholder(1, 45, 80, 30, 20);
            try (PdfDocument document = new PdfDocument(new PdfWriter(input.toString()))) {
                var page = document.addNewPage();
                page.setCropBox(new Rectangle(20, 40, 200, 300));
                page.setRotation(rotation);
                new PdfCanvas(page).setFillColor(com.itextpdf.kernel.colors.ColorConstants.RED)
                        .rectangle(marker.x(), marker.y(), marker.width(), marker.height()).fill();
            }
            PageGeometry geometry = new PageGeometry(20, 40, 200, 300, rotation);
            var screen = geometry.toDisplay(marker, 1);
            var restored = geometry.toPdf(1, screen, 1);
            assertEquals(marker, restored);
            try (var document = org.apache.pdfbox.Loader.loadPDF(input.toFile())) {
                var image = new org.apache.pdfbox.rendering.PDFRenderer(document).renderImage(0, 1);
                java.awt.Color pixel = new java.awt.Color(image.getRGB((int) screen.getCenterX(), (int) screen.getCenterY()));
                assertTrue(pixel.getRed() > 200 && pixel.getGreen() < 50, "Marker must map onto red pixels at rotation " + rotation);
            }
        }
    }

    /** The PDF stamp must look like the on-screen preview, upright, on every page rotation and in both stamping modes. */
    @Test void stampsRenderUprightAndMatchThePreviewOnRotatedPages() throws Exception {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            Path input = workspace.resolve("stamp-" + rotation + ".pdf");
            try (PdfDocument document = new PdfDocument(new PdfWriter(input.toString()))) {
                var page = document.addNewPage();
                page.setCropBox(new Rectangle(20, 40, 400, 500));
                page.setRotation(rotation);
            }
            PageGeometry geometry = new PageGeometry(20, 40, 400, 500, rotation);
            SignaturePlaceholder area = geometry.toPdf(1, new java.awt.geom.Rectangle2D.Double(60, 70, 220, 90), 1);
            Path first = sign(request(input, false, List.of(area)));
            assertStampMatchesPreview(first, geometry, area, PLAIN, "content, rotation " + rotation);
            SignaturePlaceholder second = geometry.toPdf(1, new java.awt.geom.Rectangle2D.Double(60, 250, 220, 90), 1);
            Path resigned = sign(request(first, false, List.of(second), CLASSIC_UNDATED));
            assertStampMatchesPreview(resigned, geometry, second, CLASSIC_UNDATED, "classic annotation, rotation " + rotation);
        }
    }

    @Test void classicDesignUsesTwoColumnsAndPdfReaderDateFormat() {
        var time = java.time.OffsetDateTime.parse("2026-11-05T15:30:00+05:30");
        assertEquals("2026.11.05 15:30:00 +05'30'", StampDesign.DateStyle.CLASSIC.format(time));
        assertEquals("05-11-2026 15:30:00 +05:30", StampDesign.DateStyle.INDIAN.format(time));
        StampDesign classic = StampDesign.classic();
        assertFalse(classic.border());
        var values = new StampRenderer.Values(SigningMode.DSC, "Sample Advocate", "", "", time, "", "");
        List<String> lines = StampRenderer.lines(classic, values, 1, 1);
        assertEquals(List.of("Digitally signed by Sample Advocate", "Date: 2026.11.05 15:30:00 +05'30'"), lines);
        var composition = StampRenderer.compose(classic, lines, values.name(), 0, SigningMode.DSC, 240, 70);
        double middle = 120;
        assertTrue(composition.glyphs().stream().anyMatch(g -> g.getBounds2D().getMaxX() < middle), "Name in the left column");
        assertTrue(composition.glyphs().stream().anyMatch(g -> g.getBounds2D().getMinX() > middle), "Details in the right column");
    }

    private void assertStampMatchesPreview(Path pdf, PageGeometry geometry, SignaturePlaceholder area, StampDesign design, String label) throws Exception {
        float scale = 2;
        BufferedImage page;
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            page = new org.apache.pdfbox.rendering.PDFRenderer(document).renderImage(0, scale);
        }
        var shown = geometry.toDisplay(area, scale);
        int x0 = (int) Math.round(shown.getX()), y0 = (int) Math.round(shown.getY());
        int w = (int) Math.round(shown.getWidth()), h = (int) Math.round(shown.getHeight());
        BufferedImage expected = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = expected.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.scale(scale, scale);
            var values = new StampRenderer.Values(SigningMode.DSC, "Integration Signer", "Integration test", "", null, "", "");
            StampRenderer.paint(g, StampRenderer.compose(design, StampRenderer.lines(design, values, 1, 1), values.name(), 0, SigningMode.DSC,
                    shown.getWidth() / scale, shown.getHeight() / scale), null);
        } finally { g.dispose(); }
        int ink = 0, mismatched = 0;
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                boolean actual = dark(page.getRGB(x0 + x, y0 + y)), wanted = dark(expected.getRGB(x, y));
                if (wanted) ink++;
                if (actual != wanted) mismatched++;
            }
        }
        assertTrue(ink > 200, "The expected stamp must contain visible text (" + label + ")");
        assertTrue(mismatched < ink * 0.25, "PDF stamp must match the preview (" + label + "): " + mismatched + " of " + ink);
    }

    private static boolean dark(int rgb) {
        java.awt.Color color = new java.awt.Color(rgb);
        return color.getRed() + color.getGreen() + color.getBlue() < 3 * 160;
    }

    @Test void devanagariIsShapedByJavaAndEmbeddedAsVectorOutlines() {
        StampDesign hindi = new StampDesign("test-hindi", "Hindi", "{reason}", StampDesign.Layout.TEXT_ONLY, StampDesign.Ink.BLACK, false, false);
        var values = new StampRenderer.Values(SigningMode.DSC, "", "दिवानी वाद क्षेत्र", "", null, "", "");
        var composition = StampRenderer.compose(hindi, StampRenderer.lines(hindi, values, 1, 1), 0, SigningMode.DSC, 200, 40);
        assertEquals(1, composition.glyphs().size());
        var bounds = composition.glyphs().getFirst().getBounds2D();
        assertTrue(bounds.getWidth() > 40 && bounds.getHeight() > 5, "Devanagari must produce visible glyph outlines");
    }

    @Test void onlyPersonalSigningCertificatesAreListedAndExpiredOnesAreDisabled() throws Exception {
        X500Name caName = new X500Name("CN=Ratio Juris Test CA");
        KeyPair keys = keys();
        Instant now = Instant.now();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        X509Certificate valid = certificate(new X500Name("CN=Valid Person"), keys.getPublic(), caName, caKeys.getPrivate(), now.minusSeconds(60),
                now.plus(Duration.ofDays(30)), false, KeyUsage.digitalSignature | KeyUsage.nonRepudiation,
                new KeyPurposeId[]{KeyPurposeId.id_kp_clientAuth, KeyPurposeId.getInstance(new ASN1ObjectIdentifier("1.3.6.1.4.1.311.10.3.12"))});
        X509Certificate expired = certificate(new X500Name("CN=Expired Person"), keys.getPublic(), caName, caKeys.getPrivate(),
                now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(1)), false, KeyUsage.digitalSignature | KeyUsage.nonRepudiation, null);
        X500Name device = new X500Name("CN=fd3d2568-cadb-4db0-9e5b-2781a63c976e");
        X509Certificate selfSigned = certificate(device, keys.getPublic(), device, keys.getPrivate(), now.minusSeconds(60),
                now.plus(Duration.ofDays(30)), false, KeyUsage.digitalSignature, null);
        X500Name workName = new X500Name("DC=net+DC=windows+CN=MS-Organization-Access,OU=82dbaca4");
        KeyPair workKeys = keys();
        X509Certificate workCa = certificate(workName, workKeys.getPublic(), workName, workKeys.getPrivate(), now.minusSeconds(60),
                now.plus(Duration.ofDays(30)), true, KeyUsage.keyCertSign, null);
        X509Certificate workAccount = certificate(new X500Name("CN=1a77c35a-4c37-4981-b6af-6ffcb1707fe9"), keys.getPublic(), workName,
                workKeys.getPrivate(), now.minusSeconds(60), now.plus(Duration.ofDays(30)), false, KeyUsage.digitalSignature,
                new KeyPurposeId[]{KeyPurposeId.id_kp_clientAuth});
        X509Certificate tlsOnly = certificate(new X500Name("CN=localhost"), keys.getPublic(), caName, caKeys.getPrivate(), now.minusSeconds(60),
                now.plus(Duration.ofDays(30)), false, KeyUsage.digitalSignature | KeyUsage.keyEncipherment,
                new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth});
        store.setKeyEntry("valid", keys.getPrivate(), new char[0], new Certificate[]{valid, ca});
        store.setKeyEntry("expired", keys.getPrivate(), new char[0], new Certificate[]{expired, ca});
        store.setKeyEntry("device", keys.getPrivate(), new char[0], new Certificate[]{selfSigned});
        store.setKeyEntry("work", keys.getPrivate(), new char[0], new Certificate[]{workAccount, workCa});
        store.setKeyEntry("tls", keys.getPrivate(), new char[0], new Certificate[]{tlsOnly, ca});
        X509Certificate unplugged = certificate(new X500Name("CN=Token Person"), keys.getPublic(), caName, caKeys.getPrivate(),
                now.minusSeconds(60), now.plus(Duration.ofDays(30)), false, KeyUsage.digitalSignature | KeyUsage.nonRepudiation, null);
        store.setCertificateEntry("token", unplugged); // Certificate present, private key absent.

        RegistryContext registry = KeystoreMonitorThread.readRegistry(store, "BC");
        assertEquals(List.of("valid", "expired", "token"), registry.signers().stream().map(SignerIdentity::alias).toList());
        SignerIdentity first = registry.signers().get(0), second = registry.signers().get(1), third = registry.signers().get(2);
        assertTrue(first.usable());
        assertEquals("Ratio Juris Test CA", first.issuer());
        assertFalse(second.usable());
        assertTrue(second.unavailableReason().contains("expired"));
        assertFalse(third.usable());
        assertTrue(third.unavailableReason().contains("not connected"));
    }

    @Test void corruptedCertificateIsHiddenWithoutDiscardingHealthyEntries() throws Exception {
        X509Certificate healthy = (X509Certificate) identity.keyStore().getCertificate(identity.alias());
        byte[] damaged = healthy.getEncoded().clone();
        damaged[damaged.length - 1] ^= 1; // Valid DER structure, but a corrupted certificate signature.
        Certificate corrupted = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(damaged));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        var key = identity.keyStore().getKey(identity.alias(), null);
        store.setKeyEntry("healthy", key, new char[0], new Certificate[]{healthy, ca});
        store.setKeyEntry("corrupted", key, new char[0], new Certificate[]{corrupted, ca});

        RegistryContext registry = KeystoreMonitorThread.readRegistry(store, "BC");
        assertEquals(List.of("healthy"), registry.signers().stream().map(SignerIdentity::alias).toList());
        assertThrows(java.security.GeneralSecurityException.class,
                () -> CertificateValidator.validate(new Certificate[]{corrupted, ca}, anchors));
    }

    @Test void failedHardwareSignatureReleasesScratchFilesImmediately() throws Exception {
        Path source = pdf("failure.pdf");
        Path scratch = Files.createDirectory(workspace.resolve("scratch"));
        var failure = new com.itextpdf.signatures.IExternalSignature() {
            @Override public String getDigestAlgorithmName() { return "SHA-256"; }
            @Override public String getSignatureAlgorithmName() { return "RSA"; }
            @Override public com.itextpdf.signatures.ISignatureMechanismParams getSignatureMechanismParameters() { return null; }
            @Override public byte[] sign(byte[] message) throws java.security.GeneralSecurityException {
                throw new java.security.GeneralSecurityException("Simulated token removal");
            }
        };
        Certificate[] chain = identity.keyStore().getCertificateChain(identity.alias());
        assertThrows(java.security.GeneralSecurityException.class, () -> {
            try (var reader = PdfReaders.open(source);
                 var output = Files.newOutputStream(workspace.resolve("partial.pdf"));
                 var signer = new FileBackedPdfSigner(reader, output, scratch,
                         new com.itextpdf.signatures.SignerProperties().setFieldName("FailureTest"))) {
                signer.signDetached(new com.itextpdf.signatures.BouncyCastleDigest(), failure, chain,
                        null, null, null, 0, com.itextpdf.signatures.PdfSigner.CryptoStandard.CMS);
            }
        });
        try (var entries = Files.list(scratch)) { assertEquals(0, entries.count()); }
        Files.delete(source); // Fails on Windows if a reader was left open without delete sharing.
    }
}

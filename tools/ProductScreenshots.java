// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Captures genuine product screenshots of the running application with non-sensitive sample data.
 * Run with LOCALAPPDATA pointing to an empty, neutral directory so no personal settings or paths appear.
 * Uses a generated sample certificate in place of the Windows certificate list, and seals a generated
 * sample PDF through a live RFC 3161 authority.
 */
class ProductScreenshots {
    public static void main(String[] args) throws Exception {
        Path out = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        BulkFieldSignerGUI gui = AppLauncher.startUi().get(60, TimeUnit.SECONDS);
        try {
            onEdt(() -> { gui.setExtendedState(JFrame.NORMAL); gui.setSize(1366, 900); gui.setLocation(0, 0); });
            Thread.sleep(3000); // Let the certificate monitor finish its first poll before showing sample data.
            SignerIdentity sample = sampleIdentity();
            gui.onRegistryUpdated(new RegistryContext(List.of(sample)));
            Thread.sleep(500);
            onEdt(() -> ((JList<?>) find(gui, JList.class, "Signing certificate")).setSelectedIndex(0));

            Path pdf = LocalFiles.importPdf(samplePdf());
            List<SignaturePlaceholder> areas = new ArrayList<>();
            for (int page = 1; page <= 3; page++) areas.add(new SignaturePlaceholder(page, 335, 48, 220, 72));
            onEdt(() -> gui.setPdf(pdf, areas, "Sample service agreement.pdf"));
            Thread.sleep(700);
            save(capture(gui), out.resolve("01-main-window.png"));

            VisualSignaturePicker[] editor = new VisualSignaturePicker[1];
            onEdt(() -> {
                editor[0] = new VisualSignaturePicker(gui, pdf, areas, "Sample service agreement.pdf", gui.stampPreview());
                editor[0].setSize(1280, 860);
                editor[0].setVisible(true);
            });
            waitFor(() -> ((JLabel) find(editor[0], JLabel.class, "preview-status")).getText().contains("Preview ready"), 30);
            onEdt(() -> ((JList<?>) find(editor[0], JList.class, "signature-areas")).setSelectedIndex(0));
            Thread.sleep(500);
            save(capture(editor[0]), out.resolve("03-signature-fields.png"));
            onEdt(editor[0]::dispose);

            onEdt(() -> ((AbstractButton) find(gui, JToggleButton.class, "mode-seal")).doClick(0));
            Thread.sleep(500);
            save(capture(gui), out.resolve("02-signing-mode.png"));

            onEdt(() -> {
                for (String option : List.of("Restrict editing", "Restrict content copying")) {
                    JCheckBox box = (JCheckBox) findText(gui, option);
                    box.setSelected(true);
                    box.scrollRectToVisible(new Rectangle(0, 0, box.getWidth(), box.getHeight() + 260));
                }
            });
            Thread.sleep(500);
            save(capture(gui), out.resolve("04-security-options.png"));

            // The native Save dialog cannot be driven here; save into a neutral sample "Documents" folder instead.
            SignedFileSaver.saveAutomaticallyTo(Files.createDirectories(Path.of(System.getenv("LOCALAPPDATA"), "Documents")));
            onEdt(() -> ((AbstractButton) find(gui, JButton.class, "sign-document")).doClick(0));
            JDialog[] done = new JDialog[1];
            waitFor(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog dialog && dialog.isShowing()) {
                        if (Product.NAME.equals(dialog.getTitle())) throw new IllegalStateException("Sealing failed; see the error dialog.");
                        if ("Sealing complete".equals(dialog.getTitle())) { done[0] = dialog; return true; }
                    }
                }
                return false;
            }, 120);
            Thread.sleep(500);
            BufferedImage[] composite = new BufferedImage[1];
            onEdt(() -> {
                BufferedImage frame = paint(gui.getRootPane());
                BufferedImage dialog = paint(done[0].getRootPane());
                Point offset = SwingUtilities.convertPoint(done[0].getRootPane(), 0, 0, gui.getRootPane());
                Graphics2D g = frame.createGraphics();
                g.setColor(new Color(0, 0, 0, 60));
                g.fillRect(0, 0, frame.getWidth(), frame.getHeight());
                g.drawImage(dialog, offset.x, offset.y, null);
                g.dispose();
                composite[0] = frame;
                done[0].dispose();
            });
            save(composite[0], out.resolve("05-completed-signing.png"));

            Path sealed;
            try (var files = Files.walk(AppPaths.OUTPUTS)) {
                sealed = files.filter(p -> p.getFileName().toString().equals("document_sealed.pdf"))
                        .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow();
            }
            try (var document = org.apache.pdfbox.Loader.loadPDF(sealed.toFile())) {
                save(new org.apache.pdfbox.rendering.PDFRenderer(document).renderImage(0, 1.6f), out.resolve("06-sealed-output.png"));
            }

            onEdt(() -> {
                StampDesigner designer = new StampDesigner(gui, List.of(StampDesign.standard(), StampDesign.classic()), StampDesign.CLASSIC_ID,
                        gui.stampValues(), 220, 72);
                try {
                    designer.addNotify(); // Lay out the modal dialog without blocking on setVisible.
                    designer.validate();
                    images.add(new Object[]{paint(designer.getRootPane()), out.resolve("07-stamp-designer.png")});
                } finally { designer.dispose(); }
            });
            for (Object[] pending : images) save((BufferedImage) pending[0], (Path) pending[1]);

            onEdt(() -> gui.getRootPane().getActionMap().get("settings").actionPerformed(null));
            Thread.sleep(300);
            onEdt(() -> {
                gui.validate(); // Lay out the newly shown page before scrolling within it.
                Component repository = find(gui, JTextField.class, "source-repository");
                JScrollBar bar = ((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, repository)).getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
            Thread.sleep(500);
            save(capture(gui), out.resolve("08-about.png"));
            System.out.println("Screenshots written to " + out);
        } finally {
            onEdt(gui::dispose);
            System.exit(0);
        }
    }

    private static final List<Object[]> images = new ArrayList<>();

    private static Path samplePdf() throws Exception {
        Path path = AppPaths.TEMP.resolve("sample-service-agreement.pdf");
        try (Document document = new Document(new PdfDocument(new PdfWriter(path.toString())))) {
            String[] titles = {"Sample Service Agreement", "Schedule A: Scope of Work", "Schedule B: Acknowledgement"};
            for (int page = 0; page < 3; page++) {
                if (page > 0) document.add(new com.itextpdf.layout.element.AreaBreak());
                document.add(new Paragraph(titles[page]).setFontSize(20).simulateBold());
                document.add(new Paragraph("Reference: SAMPLE-0001 · Page " + (page + 1) + " of 3").setFontSize(9));
                document.add(new Paragraph("This is a sample document prepared to demonstrate " + Product.NAME + ". It contains no "
                        + "real parties, case details or personal information. Each page carries a visible stamp, and the "
                        + "complete file is secured by one signature or timestamp seal.").setFontSize(11));
                for (int i = 1; i <= 6; i++) {
                    document.add(new Paragraph(i + ". Sample clause text for illustration only. The parties agree that this paragraph "
                            + "has no legal effect and exists solely to fill the page for a screenshot.").setFontSize(10));
                }
            }
        }
        return path;
    }

    /** An in-memory sample signer; nothing is added to any Windows certificate store. */
    private static SignerIdentity sampleIdentity() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var ca = generator.generateKeyPair();
        var signer = generator.generateKeyPair();
        X500Name caName = new X500Name("CN=Sample Certifying Authority, O=Example, C=IN");
        var builder = new JcaX509v3CertificateBuilder(caName, new BigInteger(64, new SecureRandom()),
                Date.from(Instant.now().minusSeconds(3600)), Date.from(Instant.now().plusSeconds(365L * 24 * 3600)),
                new X500Name("CN=Sample Signer, O=Example, C=IN"), signer.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(
                KeyPurposeId.getInstance(new ASN1ObjectIdentifier("1.3.6.1.4.1.311.10.3.12"))));
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(ca.getPrivate())));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("sample", signer.getPrivate(), new char[0], new Certificate[]{certificate});
        return new SignerIdentity(store, "sample", SignerIdentity.fingerprint(certificate), "Sample Signer", "SunRsaSign",
                "Sample Certifying Authority", certificate.getNotAfter().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate(), null);
    }

    private interface Check { boolean ok() throws Exception; }

    private static void waitFor(Check check, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            boolean[] ok = new boolean[1];
            Exception[] failure = new Exception[1];
            SwingUtilities.invokeAndWait(() -> { try { ok[0] = check.ok(); } catch (Exception ex) { failure[0] = ex; } });
            if (failure[0] != null) throw failure[0];
            if (ok[0]) return;
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out waiting for the application.");
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }

    private static BufferedImage capture(Window window) throws Exception {
        BufferedImage[] image = new BufferedImage[1];
        onEdt(() -> { window.validate(); image[0] = paint(((RootPaneContainer) window).getRootPane()); });
        return image[0];
    }

    private static BufferedImage paint(JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        return image;
    }

    private static void save(BufferedImage image, Path path) throws Exception { ImageIO.write(image, "png", path.toFile()); }

    private static Component find(Container parent, Class<?> type, String nameOrAccessible) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component) && (nameOrAccessible.equals(component.getName())
                    || component.getAccessibleContext() != null && nameOrAccessible.equals(component.getAccessibleContext().getAccessibleName()))) {
                return component;
            }
            if (component instanceof Container child) {
                Component found = find(child, type, nameOrAccessible);
                if (found != null) return found;
            }
        }
        if (parent instanceof RootPaneContainer root && parent != root.getRootPane()) return find(root.getRootPane(), type, nameOrAccessible);
        return null;
    }

    private static Component findText(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof AbstractButton button && text.equals(button.getText())) return component;
            if (component instanceof Container child) {
                Component found = findText(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}

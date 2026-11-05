// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.StampingProperties;
import com.itextpdf.signatures.PdfSigner;
import com.itextpdf.signatures.SignerProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** iText's normal close path is not reached if the token/TSA throws after preClose. */
final class FileBackedPdfSigner extends PdfSigner implements AutoCloseable {
    FileBackedPdfSigner(PdfReader reader, OutputStream output, Path scratch, SignerProperties properties) throws IOException {
        super(reader, output, AppPaths.requireLocal(scratch).toString(),
                new StampingProperties().useAppendMode().preserveEncryption(), properties);
    }

    @Override public void close() throws IOException {
        IOException failure = null;
        try {
            if (document != null && !document.isClosed()) document.close();
        } catch (RuntimeException ex) { failure = new IOException("Closing signing document failed.", ex); }
        try { if (raf != null) raf.close(); }
        catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        try { if (tempFile != null) Files.deleteIfExists(AppPaths.requireLocal(tempFile.toPath())); }
        catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        if (failure != null) throw failure;
    }
}

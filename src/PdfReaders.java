// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.io.source.RandomAccessSourceFactory;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.ReaderProperties;
import java.io.IOException;
import java.nio.file.Path;

public final class PdfReaders {
    private PdfReaders() { }

    public static PdfReader open(Path path) throws IOException { return open(path, new ReaderProperties()); }

    public static PdfReader open(Path path, ReaderProperties properties) throws IOException {
        // Plain read-only random access avoids memory-mapped file locks and Unsafe cleaner dependencies.
        var source = new RandomAccessSourceFactory().setUsePlainRandomAccess(true).setExclusivelyLockFile(false)
                .createBestSource(AppPaths.requireLocal(path).toString());
        try {
            PdfReader reader = new PdfReader(source, properties);
            reader.setCloseStream(true);
            return reader.setMemorySavingMode(true);
        } catch (IOException | RuntimeException ex) {
            try { source.close(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }
}

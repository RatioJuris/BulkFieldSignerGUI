// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.kernel.pdf.EncryptionConstants;
import com.itextpdf.kernel.pdf.WriterProperties;

public final class PdfSecurityManager {
    private PdfSecurityManager() { }

    /** Encryption is established once, on unsigned input, before signing any byte ranges. */
    public static WriterProperties properties(SigningRequest request, byte[] ownerPassword) {
        int permissions = EncryptionConstants.ALLOW_SCREENREADERS | EncryptionConstants.ALLOW_FILL_IN;
        if (!request.disablePrinting()) permissions |= EncryptionConstants.ALLOW_PRINTING;
        if (!request.disableCopying()) permissions |= EncryptionConstants.ALLOW_COPY;
        if (!request.disableEditing()) permissions |= EncryptionConstants.ALLOW_MODIFY_CONTENTS
                | EncryptionConstants.ALLOW_MODIFY_ANNOTATIONS | EncryptionConstants.ALLOW_ASSEMBLY;
        return new WriterProperties().setStandardEncryption(new byte[0], ownerPassword, permissions,
                EncryptionConstants.ENCRYPTION_AES_256);
    }
}

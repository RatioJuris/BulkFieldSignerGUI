// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.nio.file.Path;
import java.util.List;

/**
 * One signing operation: visible stamps in every area, then either one invisible DSC signature
 * or one RFC 3161 document timestamp covering the whole file.
 */
public record SigningRequest(Path sourcePdf, SigningMode mode, String declaredName, String reason, String location,
                             boolean disableEditing, boolean disableCopying, boolean disablePrinting, SignerIdentity signer,
                             TimestampProvider timestampProvider, List<SignaturePlaceholder> placeholders, StampDesign design) {
    public SigningRequest {
        if (sourcePdf == null) throw new IllegalArgumentException("Please import a PDF file.");
        mode = mode == null ? SigningMode.DSC : mode;
        if (timestampProvider == null) timestampProvider = TimestampProvider.NONE;
        if (design == null) design = StampDesign.standard();
        declaredName = declaredName == null ? "" : declaredName.strip();
        reason = reason == null ? "" : reason.strip();
        location = location == null ? "" : location.strip();
        if (reason.length() > 2048 || location.length() > 512 || declaredName.length() > 200) {
            throw new IllegalArgumentException("Signing metadata is too long.");
        }
        if (mode == SigningMode.DSC) {
            if (signer == null) throw new IllegalArgumentException("Please select a signing certificate.");
            if (!signer.usable()) throw new IllegalArgumentException(signer.displayName() + ": " + signer.unavailableReason());
        } else {
            if (timestampProvider == TimestampProvider.NONE) throw new IllegalArgumentException("Choose a timestamp authority for the seal.");
            if (declaredName.isEmpty()) throw new IllegalArgumentException("Enter the name to declare on the seal.");
            signer = null;
        }
        placeholders = List.copyOf(placeholders);
        if (placeholders.isEmpty() || placeholders.size() > PlaceholderStore.MAX_COUNT) {
            throw new IllegalArgumentException("Place between 1 and " + PlaceholderStore.MAX_COUNT + " stamps.");
        }
    }

    /** Convenience form used for DSC signing with the standard stamp design. */
    public SigningRequest(Path sourcePdf, String reason, String location, boolean disableEditing, boolean disableCopying,
                          boolean disablePrinting, SignerIdentity signer, TimestampProvider timestampProvider,
                          List<SignaturePlaceholder> placeholders) {
        this(sourcePdf, SigningMode.DSC, "", reason, location, disableEditing, disableCopying, disablePrinting, signer,
                timestampProvider, placeholders, StampDesign.standard());
    }

    public boolean securityRequested() { return disableEditing || disableCopying || disablePrinting; }
}

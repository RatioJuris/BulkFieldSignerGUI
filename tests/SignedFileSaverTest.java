// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SignedFileSaverTest {
    private Path work;

    @BeforeEach void workspace() throws Exception {
        AppPaths.initialize();
        work = Files.createTempDirectory(AppPaths.TEMP, "save-test-");
    }

    @AfterEach void cleanup() { UpdateInstaller.deleteTree(work); }

    @Test void suggestedNamesKeepTheOriginalNameAndAreSafeForWindows() {
        assertEquals("Agreement - signed.pdf", SignedFileSaver.suggestedName("Agreement.pdf", false));
        assertEquals("Agreement - sealed.pdf", SignedFileSaver.suggestedName("Agreement.PDF", true));
        assertEquals("a_b_c - signed.pdf", SignedFileSaver.suggestedName("a:b?c", false));
        assertEquals("document - signed.pdf", SignedFileSaver.suggestedName("", false));
    }

    @Test void copiesCompletelyReplacesOnlyTheChosenFileAndLeavesNoTemporaryFiles() throws Exception {
        Path source = Files.write(work.resolve("verified.pdf"), "%PDF-1.7 signed".getBytes());
        Path folder = Files.createDirectories(work.resolve("My Documents"));
        Path target = folder.resolve("Agreement - signed.pdf");
        assertEquals(target, SignedFileSaver.copy(source, target));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(target));
        Files.writeString(target, "older file");
        SignedFileSaver.copy(source, target);
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(target), "A confirmed replacement overwrites the chosen file");
        assertArrayEquals("%PDF-1.7 signed".getBytes(), Files.readAllBytes(source), "The verified workspace copy is kept");
        try (var files = Files.list(folder)) { assertEquals(1, files.count(), "No temporary files are left behind"); }
        assertThrows(java.io.IOException.class, () -> SignedFileSaver.copy(source, work.resolve("missing").resolve("x.pdf")));
    }
}

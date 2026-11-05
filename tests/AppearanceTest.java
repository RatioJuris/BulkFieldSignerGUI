// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceTest {
    private Path directory;

    @BeforeEach void createWorkspace() throws Exception {
        AppPaths.initialize();
        directory = Files.createTempDirectory(AppPaths.TEMP, "appearance-test-");
    }

    @AfterEach void cleanup() throws Exception { LocalFiles.deleteTree(directory); }

    @Test void allThemePreferencesAndWindowStateSurviveRestart() throws Exception {
        Path file = directory.resolve("ui.properties");
        for (ThemeMode mode : ThemeMode.values()) {
            UiPreferences before = new UiPreferences(mode, 1170, 820, true);
            before.write(file);
            assertEquals(before, UiPreferences.read(file));
        }
    }

    @Test void missingAndMalformedPreferencesFallBackToSafeWindowAndSystemTheme() throws Exception {
        Path file = directory.resolve("ui.properties");
        assertEquals(UiPreferences.DEFAULT, UiPreferences.read(file));
        Files.writeString(file, "theme=obsolete\nwidth=oops\nheight=999999\nmaximized=false\n");
        UiPreferences loaded = UiPreferences.read(file);
        assertEquals(ThemeMode.SYSTEM, loaded.theme());
        assertEquals(UiPreferences.DEFAULT.width(), loaded.width());
        assertEquals(1600, loaded.height());
        Files.write(file, new byte[9000]);
        assertThrows(java.io.IOException.class, () -> UiPreferences.read(file));
    }

    @Test void systemThemeFollowsWindowsAndExplicitChoicesOverrideIt() {
        assertTrue(ThemeMode.SYSTEM.isDark(true));
        assertFalse(ThemeMode.SYSTEM.isDark(false));
        assertFalse(ThemeMode.LIGHT.isDark(true));
        assertTrue(ThemeMode.DARK.isDark(false));
    }

    @Test void themeResourcesLoadAndSwitchSemanticColorsOnEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.install(false);
            assertEquals("FlatLaf Light", UIManager.getLookAndFeel().getName());
            Color lightSurface = UIConstants.color("surface");
            assertNotNull(UIManager.getColor("RJ.border"));
            ThemeManager.install(true);
            assertEquals("FlatLaf Dark", UIManager.getLookAndFeel().getName());
            Color darkSurface = UIConstants.color("surface");
            assertTrue(lightSurface.getRed() > darkSurface.getRed());
            assertTrue(UIConstants.color("text").getRed() > darkSurface.getRed());
            ThemeManager.install(false);
        });
    }
}

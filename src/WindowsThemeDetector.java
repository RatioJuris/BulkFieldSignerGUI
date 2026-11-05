// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinReg;
import java.util.Optional;

/** Read-only HKCU access. Never invokes reg.exe, changes Windows preferences, or requests elevation. */
public final class WindowsThemeDetector {
    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private WindowsThemeDetector() { }

    public static Optional<Boolean> detectDarkMode() {
        if (!System.getProperty("os.name").startsWith("Windows")) return Optional.empty();
        try {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, "AppsUseLightTheme")) {
                return Optional.of(false); // Windows default when no personalization override exists.
            }
            return Optional.of(Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "AppsUseLightTheme") == 0);
        } catch (Win32Exception | UnsatisfiedLinkError | NoClassDefFoundError ex) {
            System.getLogger(WindowsThemeDetector.class.getName()).log(System.Logger.Level.DEBUG, "Windows theme unavailable", ex);
            return Optional.empty();
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.Color;
import javax.swing.UIManager;

/** Resolve semantic colors at paint time so an open window can change theme. */
public final class UIConstants {
    private UIConstants() { }
    public static Color color(String token) {
        Color color = UIManager.getColor("RJ." + token);
        return color != null ? color : UIManager.getColor("Label.foreground");
    }
}

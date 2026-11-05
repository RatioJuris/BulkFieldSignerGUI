// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.*;

/** Lightweight EDT-owned splash, driven by real initialization stages rather than artificial delays. */
public final class SplashScreen extends JWindow {
    private static final long serialVersionUID = 1L;
    private final JLabel status = UIComponentFactory.muted("Preparing your workspace…");
    private final JProgressBar progress = new JProgressBar();

    public SplashScreen() {
        setIconImages(AppIcon.windowImages());
        JPanel card = new UIComponentFactory.RoundedPanel("surface");
        card.setLayout(new BorderLayout(0, UIScale.scale(38)));
        card.setBorder(UIComponentFactory.padding(38));
        JLabel logo = new JLabel(new AppIcon(AppIcon.Symbol.BRAND, 52));
        card.add(UIComponentFactory.stack(18, logo,
                UIComponentFactory.label(Product.NAME, "font: bold +16"),
                UIComponentFactory.muted("Version " + Product.VERSION + " · Developed by " + Product.DEVELOPER)), BorderLayout.CENTER);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(UIScale.scale(430), UIScale.scale(4)));
        card.add(UIComponentFactory.stack(14, status, progress), BorderLayout.SOUTH);
        setContentPane(card);
        pack();
        setLocationRelativeTo(null);
    }

    public void updateStatus(String message) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Splash updates must use the EDT.");
        status.setText(message);
    }

    @Override public void dispose() { progress.setIndeterminate(false); super.dispose(); }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import javax.swing.*;

@SuppressWarnings("serial")
public final class SettingsPanel extends JPanel {
    private final JLabel saveStatus = UIComponentFactory.muted("Preferences are stored locally");

    public SettingsPanel(ThemeManager themes) {
        super(new BorderLayout(0, UIScale.scale(26)));
        setOpaque(false);
        setBorder(UIComponentFactory.padding(30));
        add(UIComponentFactory.stack(8, UIComponentFactory.title("Make it feel like yours."),
                UIComponentFactory.muted("Choose an appearance that fits your workspace.")), BorderLayout.NORTH);
        JPanel choices = UIComponentFactory.transparent(new GridLayout(1, 3, UIScale.scale(14), 0));
        ButtonGroup group = new ButtonGroup();
        for (ThemeMode mode : ThemeMode.values()) {
            JToggleButton choice = new JToggleButton(mode.toString(), new ThemePreview(mode));
            choice.setName("theme-" + mode.name());
            choice.setHorizontalTextPosition(SwingConstants.CENTER);
            choice.setVerticalTextPosition(SwingConstants.BOTTOM);
            choice.setIconTextGap(UIScale.scale(16));
            choice.setMargin(new Insets(18, 14, 18, 14));
            choice.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 16; selectedBackground: $RJ.accentSoft; selectedForeground: $RJ.accent");
            choice.setSelected(themes.preferences().theme() == mode);
            choice.getAccessibleContext().setAccessibleDescription("Use " + mode + " appearance");
            choice.addActionListener(event -> themes.select(mode));
            group.add(choice);
            choices.add(choice);
        }
        JPanel appearance = UIComponentFactory.card("", "Appearance", "Changes apply immediately. No restart needed.",
                UIComponentFactory.stack(18, choices,
                        UIComponentFactory.description("System default follows your Windows app theme, including changes while you work."), saveStatus));
        JTextField path = new JTextField(AppPaths.ROOT.toString());
        path.setEditable(false);
        path.setToolTipText("Select and copy your local application directory");
        JLabel locationLabel = UIComponentFactory.muted("Application data location");
        locationLabel.setLabelFor(path);
        JPanel storage = UIComponentFactory.card("", "Your local workspace", "Settings and working files belong to your user account.",
                UIComponentFactory.stack(10, locationLabel, path,
                        UIComponentFactory.muted("Appearance is saved in " + java.nio.file.Path.of("config", "ui.properties") + ". "
                                + "Add extra trusted root certificates (.cer, .crt, .pem) to " + java.nio.file.Path.of("config", "trusted-roots") + ".")));
        JPanel cards = new UIComponentFactory.WidthTrackingPanel(new BorderLayout());
        cards.setOpaque(false);
        cards.add(UIComponentFactory.stack(22, appearance, token(), storage, about()), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(cards);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(18));
        add(scroll, BorderLayout.CENTER);
        themes.onSaveStatus(saveStatus::setText);
    }

    private final JTextField tokenLibrary = new JTextField();
    private final JLabel tokenStatus = UIComponentFactory.muted(" ");
    private final JButton checkUpdates = new JButton("Check for updates");
    private final JLabel updateStatus = UIComponentFactory.muted(" ");
    private Runnable updateAction = () -> { };

    /** Wires the About card's update check; called once the update service exists. */
    public void setUpdateAction(Runnable action) { updateAction = action; }
    public void setUpdateStatus(String status) { updateStatus.setText(status == null || status.isBlank() ? " " : status); }
    public void setUpdateCheckEnabled(boolean enabled) { checkUpdates.setEnabled(enabled); }

    private JPanel token() {
        tokenLibrary.setEditable(false);
        tokenLibrary.setName("token-library");
        tokenLibrary.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "No token library selected");
        tokenLibrary.getAccessibleContext().setAccessibleName("PKCS#11 token library");
        JButton browse = new JButton("Choose library…");
        JButton remove = new JButton("Remove");
        browse.addActionListener(event -> {
            FileDialog dialog = new FileDialog((Frame) SwingUtilities.getWindowAncestor(this), "Choose the token's PKCS#11 library", FileDialog.LOAD);
            java.nio.file.Path selected;
            try {
                dialog.setVisible(true);
                if (dialog.getFile() == null) return;
                selected = java.nio.file.Path.of(dialog.getDirectory(), dialog.getFile());
            } finally { dialog.dispose(); }
            saveToken(selected);
        });
        remove.addActionListener(event -> saveToken(null));
        JPanel actions = UIComponentFactory.transparent(new FlowLayout(FlowLayout.LEFT, UIScale.scale(8), 0));
        actions.add(browse);
        actions.add(remove);
        new SwingWorker<java.nio.file.Path, Void>() {
            @Override protected java.nio.file.Path doInBackground() throws Exception { return Pkcs11Token.configuredLibrary(); }
            @Override protected void done() {
                try { java.nio.file.Path library = get(); tokenLibrary.setText(library == null ? "" : library.toString()); }
                catch (Exception ex) { tokenStatus.setText("Token setting could not be read: " + rootMessage(ex)); }
            }
        }.execute();
        String hint = Platform.os() == Platform.Os.WINDOWS
                ? "On Windows, DSC tokens are normally available through your Windows certificates and this is not needed."
                : "Select the PKCS#11 library (.so or .dylib) supplied by your DSC token's vendor. The PIN is asked only when you sign.";
        return UIComponentFactory.card("", "DSC token", "Use a USB token through its PKCS#11 library.",
                UIComponentFactory.stack(10, tokenLibrary, actions, UIComponentFactory.description(hint), tokenStatus));
    }

    private void saveToken(java.nio.file.Path library) {
        tokenStatus.setText("Saving…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { Pkcs11Token.configure(library); return null; }
            @Override protected void done() {
                try {
                    get();
                    tokenLibrary.setText(library == null ? "" : library.toAbsolutePath().normalize().toString());
                    tokenStatus.setText(library == null ? "Token library removed." : "Token library saved. Certificates refresh within a few seconds.");
                } catch (Exception ex) { tokenStatus.setText(rootMessage(ex)); }
            }
        }.execute();
    }

    private static String rootMessage(Exception ex) {
        Throwable cause = ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
        return String.valueOf(cause.getMessage());
    }

    private JPanel about() {
        checkUpdates.setName("check-updates");
        checkUpdates.addActionListener(event -> updateAction.run());
        JPanel updates = UIComponentFactory.transparent(new BorderLayout(UIScale.scale(12), 0));
        updates.add(checkUpdates, BorderLayout.WEST);
        updates.add(updateStatus, BorderLayout.CENTER);
        JTextField repository = new JTextField(Product.REPOSITORY);
        repository.setEditable(false);
        repository.setName("source-repository");
        repository.getAccessibleContext().setAccessibleName("Source code repository");
        JButton open = new JButton("Open in browser");
        open.addActionListener(event -> {
            try { Desktop.getDesktop().browse(java.net.URI.create(Product.REPOSITORY)); }
            catch (Exception ex) { open.setText("Copy the address instead"); open.setEnabled(false); }
        });
        open.setEnabled(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE));
        JLabel sourceLabel = UIComponentFactory.muted("Source code and downloads");
        sourceLabel.setLabelFor(repository);
        return UIComponentFactory.card("", "About", Product.NAME + " · Version " + Product.VERSION,
                UIComponentFactory.stack(10,
                        UIComponentFactory.label("Developed by " + Product.DEVELOPER, "font: bold"),
                        UIComponentFactory.description(Product.COPYRIGHT + ". This program is free software: you can redistribute it "
                                + "and/or modify it under the terms of the " + Product.LICENSE + ", as published by the Free Software "
                                + "Foundation. It is distributed WITHOUT ANY WARRANTY. See the LICENSE file for details."),
                        sourceLabel, UIComponentFactory.row(repository, open),
                        UIComponentFactory.muted("Installed as: " + Platform.channel() + " · " + Platform.id()), updates,
                        UIComponentFactory.description("Includes iText (AGPL-3.0), Bouncy Castle (MIT), Apache PDFBox (Apache-2.0), "
                                + "FlatLaf (Apache-2.0), JNA (Apache-2.0) and Noto fonts (SIL OFL 1.1). See THIRD-PARTY-NOTICES.md.")));
    }

    private record ThemePreview(ThemeMode mode) implements Icon {
        @Override public int getIconWidth() { return UIScale.scale(146); }
        @Override public int getIconHeight() { return UIScale.scale(84); }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.scale(getIconWidth() / 146.0, getIconHeight() / 84.0);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                paintPreview(g, mode == ThemeMode.DARK);
                if (mode == ThemeMode.SYSTEM) {
                    g.clipRect(73, 0, 73, 84);
                    paintPreview(g, true);
                }
            } finally { g.dispose(); }
        }
        private static void paintPreview(Graphics2D g, boolean dark) {
            g.setColor(new Color(dark ? 0x11151e : 0xf0f3f8));
            g.fillRoundRect(0, 0, 145, 83, 10, 10);
            g.setColor(new Color(dark ? 0x273246 : 0xe1e7f0));
            g.fillRoundRect(8, 9, 29, 65, 5, 5);
            g.setColor(new Color(dark ? 0x1b2230 : 0xffffff));
            g.fillRoundRect(45, 24, 91, 50, 6, 6);
            g.setColor(new Color(dark ? 0x8799b8 : 0x8090a9));
            g.fillRoundRect(46, 10, 51, 5, 4, 4);
            g.fillRoundRect(53, 34, 45, 4, 3, 3);
            g.setColor(new Color(0x2563eb));
            g.fillRoundRect(101, 56, 26, 10, 4, 4);
            g.fillRoundRect(13, 24, 19, 5, 3, 3);
        }
    }
}

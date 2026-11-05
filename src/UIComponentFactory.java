// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public final class UIComponentFactory {
    private UIComponentFactory() { }
    public static JLabel createModernLabel(String text) { return label(text, null); }
    public static JLabel label(String text, String style) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        if (style != null) label.putClientProperty(FlatClientProperties.STYLE, style);
        return label;
    }
    public static JLabel muted(String text) { return label(text, "foreground: $RJ.muted"); }
    public static JTextArea description(String text) {
        JTextArea area = new JTextArea(text, 2, 0);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.putClientProperty(FlatClientProperties.STYLE, "foreground: $RJ.muted; font: $Label.font");
        return area;
    }
    public static JLabel title(String text) { return label(text, "font: bold +14"); }
    public static JTextField createModernTextField() { return new JTextField(); }
    public static JCheckBox createModernCheckBox(String text) {
        JCheckBox box = new JCheckBox(text);
        box.setOpaque(false);
        return box;
    }
    public static JButton createModernButton(String text, boolean primary) {
        JButton button = new JButton(text);
        if (primary) button.putClientProperty(FlatClientProperties.STYLE, "background: #2563eb; foreground: #ffffff; focusedBackground: #2563eb; hoverBackground: #1d4ed8; pressedBackground: #1e40af");
        return button;
    }
    public static JPanel row(JTextField field, JButton button) {
        JPanel panel = transparent(new BorderLayout(UIScale.scale(10), 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }
    public static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }
    public static EmptyBorder padding(int value) { return new EmptyBorder(UIScale.scale(value), UIScale.scale(value), UIScale.scale(value), UIScale.scale(value)); }
    public static JPanel stack(int gap, Component... components) {
        JPanel panel = transparent(null);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (int i = 0; i < components.length; i++) {
            if (i > 0) panel.add(Box.createVerticalStrut(UIScale.scale(gap)));
            if (components[i] instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(components[i]);
        }
        return panel;
    }
    public static JPanel card(String step, String title, String subtitle, JComponent content) {
        JPanel header = transparent(new BorderLayout(UIScale.scale(12), 0));
        JLabel badge = label(step, "font: bold; foreground: $RJ.accent");
        header.add(badge, BorderLayout.WEST);
        header.add(stack(4, label(title, "font: bold +3"), muted(subtitle)), BorderLayout.CENTER);
        RoundedPanel card = new RoundedPanel("surface");
        card.setLayout(new BorderLayout(0, UIScale.scale(16)));
        card.setBorder(padding(20));
        card.add(header, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }
    @SuppressWarnings("serial")
    public static class WidthTrackingPanel extends JPanel implements Scrollable {
        public WidthTrackingPanel(LayoutManager layout) { super(layout); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return UIScale.scale(18); }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return visible.height - UIScale.scale(18); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
    @SuppressWarnings("serial")
    public static final class RoundedPanel extends JPanel {
        private final String color;
        public RoundedPanel(String color) { this.color = color; setOpaque(false); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = UIScale.scale(18);
                g.setColor(UIConstants.color(color));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g.setColor(UIConstants.color("border"));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            } finally { g.dispose(); }
            super.paintComponent(graphics);
        }
    }
}

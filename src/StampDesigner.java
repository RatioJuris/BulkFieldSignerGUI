// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Modal editor for saved stamp designs, with a live preview rendered by the same code that writes the PDF. */
@SuppressWarnings("serial")
public final class StampDesigner extends JDialog {
    private final DefaultListModel<StampDesign> designs = new DefaultListModel<>();
    private final JList<StampDesign> list = new JList<>(designs);
    private final JTextField name = new JTextField();
    private final JTextArea template = new JTextArea(5, 30);
    private final JComboBox<StampDesign.Layout> layout = new JComboBox<>(StampDesign.Layout.values());
    private final JComboBox<StampDesign.Ink> ink = new JComboBox<>(StampDesign.Ink.values());
    private final JComboBox<StampDesign.DateStyle> dateStyle = new JComboBox<>(StampDesign.DateStyle.values());
    private final JCheckBox border = UIComponentFactory.createModernCheckBox("Draw a border");
    private final JLabel imageStatus = UIComponentFactory.muted("No image");
    private final JButton chooseImage = new JButton("Choose image…");
    private final JButton removeImage = new JButton("Remove image");
    private final JButton save = UIComponentFactory.createModernButton("Save design", true);
    private final JButton delete = new JButton("Delete");
    private final JLabel status = UIComponentFactory.muted(" ");
    private final PreviewPanel preview = new PreviewPanel();
    private final StampRenderer.Values values;
    private final double sampleWidth, sampleHeight;
    private String editingId;
    private boolean editingHasImage;
    private BufferedImage image;
    private BufferedImage pendingImage;
    private boolean pendingRemove;
    private boolean dirty;
    private boolean loading;
    private String chosenId;

    public StampDesigner(Window owner, List<StampDesign> initial, String selectedId, StampRenderer.Values values, double sampleWidth, double sampleHeight) {
        super(owner, "Stamp designs", ModalityType.APPLICATION_MODAL);
        this.values = values;
        this.sampleWidth = Math.max(60, sampleWidth);
        this.sampleHeight = Math.max(24, sampleHeight);
        chosenId = selectedId;
        setIconImages(AppIcon.windowImages());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(UIScale.scale(18), 0));
        root.setBorder(UIComponentFactory.padding(18));
        setContentPane(root);
        root.add(sidebar(), BorderLayout.WEST);
        root.add(editor(), BorderLayout.CENTER);
        initial.forEach(designs::addElement);
        bind();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(UIScale.scale(860), screen.width), Math.min(UIScale.scale(640), screen.height)));
        setSize(Math.min(UIScale.scale(1040), screen.width), Math.min(UIScale.scale(800), screen.height));
        setLocationRelativeTo(owner);
        select(selectedId);
    }

    /** The design chosen when the dialog closed. */
    public String chosenId() { return chosenId; }

    private JPanel sidebar() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setName("stamp-designs");
        list.getAccessibleContext().setAccessibleName("Saved stamp designs");
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(UIScale.scale(210), 0));
        JButton create = new JButton("New");
        JButton duplicate = new JButton("Duplicate");
        create.addActionListener(event -> { if (confirmDiscard()) startNew(); });
        duplicate.addActionListener(event -> { if (confirmDiscard()) duplicate(); });
        delete.addActionListener(event -> deleteSelected());
        JPanel buttons = UIComponentFactory.transparent(new GridLayout(1, 3, UIScale.scale(6), 0));
        buttons.add(create); buttons.add(duplicate); buttons.add(delete);
        JPanel side = UIComponentFactory.transparent(new BorderLayout(0, UIScale.scale(10)));
        side.add(UIComponentFactory.label("Saved designs", "font: bold +2"), BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        side.add(buttons, BorderLayout.SOUTH);
        return side;
    }

    private JPanel editor() {
        template.setLineWrap(true);
        template.setWrapStyleWord(true);
        template.getAccessibleContext().setAccessibleName("Stamp text template");
        JScrollPane text = new JScrollPane(template);
        text.setPreferredSize(new Dimension(0, UIScale.scale(104)));
        JTextArea tokens = UIComponentFactory.description("Placeholders: " + StampDesign.TOKENS
                + ". Lines with only empty placeholders are skipped. {heading} reads “Digitally signed by” or, for a seal, “Declared by”.");
        tokens.setRows(3);
        JPanel imageRow = UIComponentFactory.transparent(new FlowLayout(FlowLayout.LEFT, UIScale.scale(8), 0));
        imageRow.add(chooseImage); imageRow.add(removeImage); imageRow.add(imageStatus);
        JPanel layoutRow = UIComponentFactory.transparent(new FlowLayout(FlowLayout.LEFT, UIScale.scale(10), 0));
        layoutRow.add(UIComponentFactory.muted("Layout")); layoutRow.add(layout);
        layoutRow.add(UIComponentFactory.muted("Ink")); layoutRow.add(ink);
        JPanel dateRow = UIComponentFactory.transparent(new FlowLayout(FlowLayout.LEFT, UIScale.scale(10), 0));
        dateRow.add(UIComponentFactory.muted("Date format")); dateRow.add(dateStyle);
        dateRow.add(border);
        JPanel options = UIComponentFactory.stack(8, layoutRow, dateRow);
        dateStyle.getAccessibleContext().setAccessibleName("Date format");
        JPanel form = UIComponentFactory.stack(8, label("Design name", name), name, label("Stamp text", template), text, tokens,
                UIComponentFactory.label("Signature image", "font: bold"), imageRow,
                UIComponentFactory.muted("PNG with a transparent background looks best. The file is copied; the original is not changed."),
                options);
        preview.setPreferredSize(new Dimension(0, UIScale.scale(230)));
        JPanel footer = UIComponentFactory.transparent(new BorderLayout(UIScale.scale(12), 0));
        footer.add(status, BorderLayout.CENTER);
        JButton done = new JButton("Use selected");
        done.addActionListener(event -> { if (confirmDiscard()) dispose(); });
        save.setName("save-design");
        footer.add(row(save, done), BorderLayout.EAST);
        JPanel editor = UIComponentFactory.transparent(new BorderLayout(0, UIScale.scale(14)));
        editor.add(form, BorderLayout.NORTH);
        JPanel previewCard = UIComponentFactory.transparent(new BorderLayout(0, UIScale.scale(6)));
        previewCard.add(UIComponentFactory.muted(String.format(java.util.Locale.ROOT,
                "Preview · %.0f × %.0f pt · sample values", sampleWidth, sampleHeight)), BorderLayout.NORTH);
        previewCard.add(preview, BorderLayout.CENTER);
        editor.add(previewCard, BorderLayout.CENTER);
        editor.add(footer, BorderLayout.SOUTH);
        return editor;
    }

    private void bind() {
        list.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || loading) return;
            StampDesign selected = list.getSelectedValue();
            if (selected == null || selected.id().equals(editingId)) return;
            if (!confirmDiscard()) {
                loading = true;
                try { int index = indexOf(editingId); if (index >= 0) list.setSelectedIndex(index); else list.clearSelection(); }
                finally { loading = false; }
                return;
            }
            showDesign(selected);
        });
        DocumentListener changed = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(); }
            @Override public void removeUpdate(DocumentEvent e) { edited(); }
            @Override public void changedUpdate(DocumentEvent e) { edited(); }
        };
        name.getDocument().addDocumentListener(changed);
        template.getDocument().addDocumentListener(changed);
        layout.addActionListener(event -> edited());
        ink.addActionListener(event -> edited());
        dateStyle.addActionListener(event -> edited());
        border.addActionListener(event -> edited());
        chooseImage.addActionListener(event -> chooseImage());
        removeImage.addActionListener(event -> {
            pendingImage = null; pendingRemove = true; image = null;
            if (layout.getSelectedItem() != StampDesign.Layout.TEXT_ONLY) layout.setSelectedItem(StampDesign.Layout.TEXT_ONLY);
            edited();
        });
        save.addActionListener(event -> saveDesign());
    }

    private void select(String id) {
        for (int i = 0; i < designs.size(); i++) {
            if (designs.get(i).id().equals(id)) { list.setSelectedIndex(i); if (!id.equals(editingId)) showDesign(designs.get(i)); return; }
        }
        if (!designs.isEmpty()) { list.setSelectedIndex(0); if (!designs.get(0).id().equals(editingId)) showDesign(designs.get(0)); }
    }

    private void showDesign(StampDesign design) {
        loading = true;
        try {
            editingId = design.id();
            editingHasImage = design.hasImage();
            name.setText(design.name());
            template.setText(design.template());
            template.setCaretPosition(0);
            layout.setSelectedItem(design.layout());
            ink.setSelectedItem(design.ink());
            dateStyle.setSelectedItem(design.dateStyle());
            border.setSelected(design.border());
            pendingImage = null; pendingRemove = false; image = null;
            delete.setText(StampDesign.builtIn(design.id()) ? "Reset" : "Delete");
        } finally { loading = false; }
        dirty = false;
        if (indexOf(design.id()) >= 0) chosenId = design.id();
        refresh();
        if (design.hasImage()) {
            background(() -> StampStore.image(design), loaded -> { if (design.id().equals(editingId) && pendingImage == null && !pendingRemove) { image = loaded; refresh(); } });
        }
    }

    private void startNew() {
        list.clearSelection();
        StampDesign fresh = new StampDesign(StampStore.newId(), "New design", StampDesign.DEFAULT_TEMPLATE,
                StampDesign.Layout.TEXT_ONLY, StampDesign.Ink.BLUE, true, false);
        showDesign(fresh);
        dirty = true;
        name.requestFocusInWindow();
        name.selectAll();
        refresh();
    }

    private void duplicate() {
        StampDesign source;
        try { source = current(); } catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); return; }
        BufferedImage copy = image;
        list.clearSelection();
        String copyName = source.name() + " copy";
        showDesign(new StampDesign(StampStore.newId(), copyName.substring(0, Math.min(60, copyName.length())),
                source.template(), source.layout(), source.ink(), source.border(), false, source.dateStyle()));
        if (copy != null) { pendingImage = copy; image = copy; }
        dirty = true;
        refresh();
    }

    private void deleteSelected() {
        if (editingId == null || indexOf(editingId) < 0) { if (confirmDiscard()) select(StampDesign.DEFAULT_ID); return; }
        boolean standard = StampDesign.builtIn(editingId);
        int answer = JOptionPane.showConfirmDialog(this, standard ? "Reset “" + name.getText() + "” to its original appearance?"
                : "Delete the design “" + name.getText() + "”?", "Stamp designs", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        String id = editingId;
        background(() -> { StampStore.delete(id); return StampStore.list(); }, reloaded -> {
            dirty = false;
            editingId = null;
            reload(reloaded, standard ? id : StampDesign.DEFAULT_ID);
            status.setText(standard ? "Design reset." : "Design deleted.");
        });
    }

    private void chooseImage() {
        FileDialog dialog = new FileDialog(this, "Choose a signature image (read access only)", FileDialog.LOAD);
        dialog.setFile("*.png;*.jpg;*.jpeg;*.gif;*.bmp");
        Path selected;
        try {
            dialog.setVisible(true);
            if (dialog.getFile() == null) return;
            selected = Path.of(dialog.getDirectory(), dialog.getFile());
        } finally { dialog.dispose(); }
        status.setText("Reading image…");
        background(() -> StampStore.importImage(selected), loaded -> {
            pendingImage = loaded; pendingRemove = false; image = loaded;
            if (layout.getSelectedItem() == StampDesign.Layout.TEXT_ONLY) layout.setSelectedItem(StampDesign.Layout.IMAGE_LEFT);
            edited();
            status.setText("Image ready. Save the design to keep it.");
        });
    }

    private void saveDesign() {
        StampDesign design;
        try { design = current(); } catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); return; }
        BufferedImage newImage = pendingImage;
        boolean remove = pendingRemove;
        save.setEnabled(false);
        status.setText("Saving…");
        background(() -> {
            StampDesign saved = StampStore.save(design, newImage, remove);
            return new Object[]{saved, StampStore.list()};
        }, result -> {
            StampDesign saved = (StampDesign) result[0];
            @SuppressWarnings("unchecked") List<StampDesign> reloaded = (List<StampDesign>) result[1];
            dirty = false;
            pendingImage = null; pendingRemove = false;
            editingId = null;
            reload(reloaded, saved.id());
            status.setText("Design saved on this device.");
        });
    }

    private void reload(List<StampDesign> reloaded, String select) {
        loading = true;
        try { designs.clear(); reloaded.forEach(designs::addElement); }
        finally { loading = false; }
        select(select);
    }

    private StampDesign current() {
        boolean hasImage = pendingImage != null || !pendingRemove && editingHasImage;
        return new StampDesign(editingId, name.getText(), template.getText(), (StampDesign.Layout) layout.getSelectedItem(),
                (StampDesign.Ink) ink.getSelectedItem(), border.isSelected(), hasImage, (StampDesign.DateStyle) dateStyle.getSelectedItem());
    }

    private void edited() {
        if (loading) return;
        dirty = true;
        refresh();
    }

    private void refresh() {
        boolean hasImage = image != null;
        imageStatus.setText(pendingImage != null ? "New image (unsaved)" : hasImage ? "Image attached" : editingHasImage && !pendingRemove ? "Loading image…" : "No image");
        removeImage.setEnabled(hasImage || editingHasImage && !pendingRemove);
        save.setEnabled(dirty);
        try {
            StampDesign design = current();
            preview.setStamp(new StampRenderer.Preview(design, image, values));
            if (!status.getText().startsWith("Saving")) status.setText(dirty ? "Unsaved changes" : " ");
        } catch (IllegalArgumentException ex) {
            preview.setStamp(null);
            status.setText(ex.getMessage());
            save.setEnabled(false);
        }
    }

    private boolean confirmDiscard() {
        if (!dirty) return true;
        return JOptionPane.showConfirmDialog(this, "Discard unsaved changes to this design?", "Stamp designs",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private int indexOf(String id) {
        for (int i = 0; i < designs.size(); i++) if (designs.get(i).id().equals(id)) return i;
        return -1;
    }

    private <T> void background(Callable<T> work, Consumer<T> success) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                try { if (isDisplayable()) success.accept(get()); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (ExecutionException ex) { status.setText(ex.getCause().getMessage()); save.setEnabled(dirty); }
            }
        }.execute();
    }

    private static JLabel label(String text, JComponent target) {
        JLabel label = UIComponentFactory.label(text, "font: bold");
        label.setLabelFor(target);
        return label;
    }

    private static JPanel row(Component... components) {
        JPanel row = UIComponentFactory.transparent(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(8), 0));
        for (Component component : components) row.add(component);
        return row;
    }

    private final class PreviewPanel extends JPanel {
        private StampRenderer.Preview stamp;
        PreviewPanel() {
            putClientProperty(FlatClientProperties.STYLE, "background: $RJ.preview");
            getAccessibleContext().setAccessibleName("Stamp preview");
        }
        void setStamp(StampRenderer.Preview stamp) { this.stamp = stamp; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double scale = Math.min((getWidth() - 40) / sampleWidth, (getHeight() - 40) / sampleHeight);
                if (scale <= 0) return;
                double w = sampleWidth * scale, h = sampleHeight * scale;
                double x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                g.setColor(Color.WHITE);
                g.fill(new java.awt.geom.Rectangle2D.Double(x - 14, y - 14, w + 28, h + 28));
                g.setColor(new Color(37, 99, 235, 90));
                g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{4, 4}, 0));
                g.draw(new java.awt.geom.Rectangle2D.Double(x, y, w, h));
                if (stamp != null) {
                    g.translate(x, y);
                    g.scale(scale, scale);
                    stamp.paint(g, sampleWidth, sampleHeight, 1, 1);
                }
            } finally { g.dispose(); }
        }
    }
}

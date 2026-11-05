// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

@SuppressWarnings("serial")
public final class BulkFieldSignerGUI extends JFrame implements KeystoreMonitorThread.KeystoreCallback {
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private final JTextField pdfField = new JTextField();
    private final JTextField reasonField = new JTextField();
    private final JTextField locationField = new JTextField();
    private final JTextField declaredName = new JTextField();
    private final DefaultComboBoxModel<TimestampProvider> dscAuthorities = new DefaultComboBoxModel<>(TimestampProvider.values());
    private final DefaultComboBoxModel<TimestampProvider> sealAuthorities = new DefaultComboBoxModel<>(
            java.util.Arrays.stream(TimestampProvider.values()).filter(p -> p != TimestampProvider.NONE).toArray(TimestampProvider[]::new));
    private final JComboBox<TimestampProvider> timestamp = new JComboBox<>(dscAuthorities);
    private final JLabel timestampLabel = UIComponentFactory.label("Trusted timestamp (optional)", "font: bold");
    private final JToggleButton dscMode = new JToggleButton(SigningMode.DSC.toString(), new AppIcon(AppIcon.Symbol.SHIELD, 16));
    private final JToggleButton sealMode = new JToggleButton(SigningMode.TIMESTAMP_SEAL.toString(), new AppIcon(AppIcon.Symbol.CHECK, 16));
    private final JCheckBox editing = UIComponentFactory.createModernCheckBox("Restrict editing");
    private final JCheckBox copying = UIComponentFactory.createModernCheckBox("Restrict content copying");
    private final JCheckBox printing = UIComponentFactory.createModernCheckBox("Restrict printing");
    private final DefaultListModel<SignerIdentity> signerModel = new DefaultListModel<>();
    private final JList<SignerIdentity> signerList = new JList<>(signerModel);
    private final JTextArea logArea = new JTextArea();
    private final JLabel areaStatus = UIComponentFactory.muted("No stamp areas yet");
    private final JLabel documentName = UIComponentFactory.label("Start with a PDF", "font: bold +4");
    private final JLabel documentHint = UIComponentFactory.muted("Your original stays untouched.");
    private final JLabel readiness = UIComponentFactory.muted("Import a PDF to get started");
    private final JLabel certificateCount = UIComponentFactory.muted("Checking certificates…");
    private final JProgressBar progress = new JProgressBar();
    private final JButton browse = UIComponentFactory.createModernButton("Import PDF", true);
    private final JButton configure = UIComponentFactory.createModernButton("Place stamps", false);
    private final JButton sign = UIComponentFactory.createModernButton("Sign document", true);
    private final JButton designButton = UIComponentFactory.createModernButton("Design stamps…", false);
    private final JComboBox<StampDesign> designChoice = new JComboBox<>();
    private final StampPreviewPanel stampPreview = new StampPreviewPanel();
    private final JPanel certificateContent = UIComponentFactory.transparent(new CardLayout());
    private final JPanel identityContent = UIComponentFactory.transparent(new CardLayout());
    private final JPanel pages = UIComponentFactory.transparent(new CardLayout());
    private final SigningController controller;
    private final KeystoreMonitorThread monitor;
    private final ThemeManager themes;
    private Dimension normalSize;
    private Path pdf;
    private List<SignaturePlaceholder> placeholders = List.of();
    private SigningMode mode = SigningMode.DSC;
    private StampDesign design = StampDesign.standard();
    private BufferedImage designImage;
    private boolean designsLoading;
    private SettingsPanel settingsPanel;
    private UpdateStateManager updates;
    private String busyMessage = "";
    private Path sourceFile;
    private Path lastFolder;
    private boolean busy;
    private boolean disposed;

    public static void main(String[] args) { AppLauncher.main(args); }

    public BulkFieldSignerGUI(Properties settings, ThemeManager themes, RegistryContext initialRegistry) {
        super(Product.title());
        this.themes = themes;
        controller = new SigningController(this);
        setIconImages(AppIcon.windowImages());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (busy) { onLog("Wait for the current operation to finish before closing."); return; }
                dispose();
            }
        });
        JPanel root = new JPanel(new BorderLayout());
        setContentPane(root);
        root.add(navigation(), BorderLayout.WEST);
        pages.add(workspace(), "workspace");
        settingsPanel = new SettingsPanel(themes);
        pages.add(settingsPanel, "settings");
        root.add(pages, BorderLayout.CENTER);
        browse.addActionListener(event -> controller.choosePDF());
        configure.addActionListener(event -> controller.openPicker());
        sign.addActionListener(event -> controller.startSigningProcessAsync());
        designButton.addActionListener(event -> openDesigner());
        designChoice.addActionListener(event -> { if (!designsLoading && designChoice.getSelectedItem() instanceof StampDesign chosen) useDesign(chosen); });
        signerList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            SignerIdentity selected = signerList.getSelectedValue();
            if (selected != null && !selected.usable()) {
                signerList.clearSelection();
                onLog(selected.displayName() + " cannot be used: " + selected.unavailableReason() + ".");
                return;
            }
            updateReadiness();
            stampPreview.repaint();
        });
        dscMode.addActionListener(event -> applyMode(SigningMode.DSC));
        sealMode.addActionListener(event -> applyMode(SigningMode.TIMESTAMP_SEAL));
        timestamp.addActionListener(event -> { updateReadiness(); stampPreview.repaint(); });
        DocumentListener changed = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { textChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { textChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { textChanged(); }
        };
        reasonField.setText(settings.getProperty("reason", ""));
        locationField.setText(settings.getProperty("location", ""));
        declaredName.setText(settings.getProperty("declaredName", ""));
        for (JTextField field : new JTextField[]{reasonField, locationField, declaredName}) field.getDocument().addDocumentListener(changed);
        dscAuthorities.setSelectedItem(TimestampProvider.parse(settings.getProperty("tsa"), TimestampProvider.NONE));
        sealAuthorities.setSelectedItem(TimestampProvider.parse(settings.getProperty("sealTsa"), TimestampProvider.DIGICERT));
        if (sealAuthorities.getSelectedItem() == TimestampProvider.NONE) sealAuthorities.setSelectedItem(TimestampProvider.DIGICERT);
        keyboardShortcut("control O", "import", () -> controller.choosePDF());
        keyboardShortcut("control COMMA", "settings", () -> showPage("settings"));
        keyboardShortcut("alt S", "sign", () -> { if (sign.isEnabled()) controller.startSigningProcessAsync(); });
        getRootPane().setDefaultButton(sign);
        restoreWindow(themes.preferences());
        monitor = new KeystoreMonitorThread(this);
        applyRegistry(initialRegistry);
        applyMode(SigningMode.parse(settings.getProperty("mode")));
        loadDesigns(settings.getProperty("design", StampDesign.DEFAULT_ID));
        monitor.start();
        onLog("Ready. Documents and preferences are stored in your local AppData workspace.");
        setVisible(true);
    }

    private JPanel navigation() {
        JPanel side = new JPanel(new BorderLayout());
        side.putClientProperty(FlatClientProperties.STYLE, "background: $RJ.sidebar");
        side.setBorder(UIComponentFactory.padding(20));
        side.setPreferredSize(new Dimension(UIScale.scale(210), 0));
        JLabel logo = new JLabel(new AppIcon(AppIcon.Symbol.BRAND, 32));
        JTextArea brand = UIComponentFactory.description(Product.NAME);
        brand.putClientProperty(FlatClientProperties.STYLE, "font: bold +4; foreground: $RJ.text");
        brand.getAccessibleContext().setAccessibleName(Product.NAME);
        JPanel top = UIComponentFactory.stack(8, logo, brand, UIComponentFactory.muted("Version " + Product.VERSION));
        JToggleButton workspace = navButton("Sign document", AppIcon.Symbol.SIGN, "workspace");
        JToggleButton settings = navButton("Settings", AppIcon.Symbol.SETTINGS, "settings");
        ButtonGroup group = new ButtonGroup();
        group.add(workspace);
        group.add(settings);
        workspace.setSelected(true);
        // Keep navigation and shortcuts synchronized through a shared page property.
        pages.addPropertyChangeListener("activePage", event -> {
            workspace.setSelected("workspace".equals(event.getNewValue()));
            settings.setSelected("settings".equals(event.getNewValue()));
        });
        JPanel center = UIComponentFactory.transparent(new BorderLayout());
        center.setBorder(new EmptyBorder(UIScale.scale(38), 0, 0, 0));
        center.add(UIComponentFactory.stack(8, workspace, settings), BorderLayout.NORTH);
        side.add(top, BorderLayout.NORTH);
        side.add(center, BorderLayout.CENTER);
        JTextArea developer = UIComponentFactory.description(Product.DEVELOPER);
        developer.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $RJ.muted");
        side.add(UIComponentFactory.stack(6, UIComponentFactory.label("Developed by", "font: bold -1; foreground: $RJ.muted"),
                developer, UIComponentFactory.label("Licensed under GNU GPL v3", "font: -1; foreground: $RJ.muted")), BorderLayout.SOUTH);
        return side;
    }

    private JToggleButton navButton(String text, AppIcon.Symbol symbol, String page) {
        JToggleButton button = new JToggleButton(text, new AppIcon(symbol, 19));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setIconTextGap(UIScale.scale(10));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(44)));
        button.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; borderWidth: 0; background: $RJ.sidebar; selectedBackground: $RJ.accentSoft; selectedForeground: $RJ.accent");
        button.addActionListener(event -> showPage(page));
        return button;
    }

    private void showPage(String page) {
        ((CardLayout) pages.getLayout()).show(pages, page);
        pages.putClientProperty("activePage", page);
        getRootPane().setDefaultButton("workspace".equals(page) ? sign : null);
    }

    private JPanel workspace() {
        JPanel workspace = UIComponentFactory.transparent(new BorderLayout(0, UIScale.scale(22)));
        workspace.setBorder(UIComponentFactory.padding(30));
        workspace.add(UIComponentFactory.stack(8, UIComponentFactory.title("Sign with confidence."),
                UIComponentFactory.muted("Prepare your PDF, choose how to secure it, and make it official.")), BorderLayout.NORTH);
        JPanel grid = new ResponsiveGrid();
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = .56;
        c.gridx = 0; c.gridy = 0;
        c.insets = new Insets(0, 0, UIScale.scale(18), UIScale.scale(18));
        grid.add(documentCard(), c);
        c.gridx = 1; c.weightx = .44; c.insets = new Insets(0, 0, UIScale.scale(18), 0);
        grid.add(identityCard(), c);
        c.gridx = 0; c.gridy = 1; c.weightx = .56; c.insets = new Insets(0, 0, UIScale.scale(18), UIScale.scale(18));
        grid.add(detailsCard(), c);
        c.gridx = 1; c.weightx = .44; c.insets = new Insets(0, 0, UIScale.scale(18), 0);
        grid.add(appearanceCard(), c);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2; c.weightx = 1; c.insets = new Insets(0, 0, UIScale.scale(18), 0);
        grid.add(permissionsCard(), c);
        c.gridy = 3; c.insets = new Insets(0, 0, 0, 0);
        grid.add(activityPanel(), c);
        c.gridy = 4; c.weighty = 1;
        grid.add(Box.createVerticalGlue(), c);
        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(18));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        workspace.add(scroll, BorderLayout.CENTER);
        JPanel footer = UIComponentFactory.transparent(new BorderLayout(UIScale.scale(20), 0));
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(UIScale.scale(180), UIScale.scale(4)));
        footer.add(UIComponentFactory.stack(10, readiness, progress), BorderLayout.CENTER);
        sign.setIcon(new AppIcon(AppIcon.Symbol.SIGN, 18));
        sign.setMnemonic(KeyEvent.VK_S);
        sign.setName("sign-document");
        footer.add(sign, BorderLayout.EAST);
        workspace.add(footer, BorderLayout.SOUTH);
        return workspace;
    }

    private JPanel documentCard() {
        JLabel icon = UIComponentFactory.label("", "foreground: $RJ.accent");
        icon.setIcon(new AppIcon(AppIcon.Symbol.DOCUMENT, 38));
        JPanel document = UIComponentFactory.transparent(new BorderLayout(UIScale.scale(16), 0));
        document.add(icon, BorderLayout.WEST);
        document.add(UIComponentFactory.stack(6, documentName, documentHint), BorderLayout.CENTER);
        pdfField.setEditable(false);
        pdfField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "No document selected");
        pdfField.getAccessibleContext().setAccessibleName("Imported PDF path");
        browse.setIcon(new AppIcon(AppIcon.Symbol.UPLOAD, 17));
        browse.setToolTipText("Import a PDF (Ctrl+O)");
        configure.setIcon(new AppIcon(AppIcon.Symbol.BOX, 17));
        configure.setToolTipText("Choose where the visible stamps appear");
        JPanel actions = UIComponentFactory.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.add(browse);
        actions.add(Box.createHorizontalStrut(UIScale.scale(10)));
        actions.add(configure);
        return UIComponentFactory.card("01", "Your document", "Start with an original PDF.",
                UIComponentFactory.stack(12, document, pdfField, actions, areaStatus));
    }

    private JPanel identityCard() {
        signerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        signerList.setCellRenderer(new CertificateRenderer());
        signerList.setVisibleRowCount(3);
        signerList.getAccessibleContext().setAccessibleName("Signing certificate");
        JScrollPane scroll = new JScrollPane(signerList);
        scroll.setPreferredSize(new Dimension(0, UIScale.scale(160)));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel empty = UIComponentFactory.transparent(new GridBagLayout());
        JLabel shield = UIComponentFactory.label("", "foreground: $RJ.muted");
        shield.setIcon(new AppIcon(AppIcon.Symbol.SHIELD, 34));
        empty.add(UIComponentFactory.stack(12, shield, UIComponentFactory.label("No DSC found", "font: bold"),
                UIComponentFactory.muted("Connect your DSC token, or use the"), UIComponentFactory.muted("timestamp seal instead."),
                UIComponentFactory.muted(Platform.os() == Platform.Os.WINDOWS ? " " : "Set up your token in Settings → DSC token.")));
        certificateContent.add(empty, "empty");
        certificateContent.add(scroll, "list");
        certificateContent.setPreferredSize(new Dimension(0, UIScale.scale(155)));
        declaredName.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. Rajan Kumar, Advocate");
        declaredName.setName("declared-name");
        JPanel seal = UIComponentFactory.stack(8, fieldLabel("Name to declare", declaredName), declaredName,
                UIComponentFactory.description("A timestamp seal proves the PDF has not changed since it was sealed. It does not prove "
                        + "who you are: your name is shown as declared, and the stamp never says “Digitally signed”."));
        identityContent.add(UIComponentFactory.stack(12, certificateContent, certificateCount), SigningMode.DSC.name());
        identityContent.add(seal, SigningMode.TIMESTAMP_SEAL.name());
        ButtonGroup modes = new ButtonGroup();
        JPanel switcher = UIComponentFactory.transparent(new GridLayout(1, 2, UIScale.scale(8), 0));
        for (JToggleButton button : new JToggleButton[]{dscMode, sealMode}) {
            button.putClientProperty(FlatClientProperties.STYLE, "arc: 10; selectedBackground: $RJ.accentSoft; selectedForeground: $RJ.accent");
            modes.add(button);
            switcher.add(button);
        }
        dscMode.setName("mode-dsc");
        sealMode.setName("mode-seal");
        return UIComponentFactory.card("02", "Signing method", "Sign with your DSC, or seal with a trusted timestamp.",
                UIComponentFactory.stack(14, switcher, identityContent));
    }

    private JPanel detailsCard() {
        reasonField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. Approved for submission");
        locationField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. Lucknow, India");
        timestampLabel.setLabelFor(timestamp);
        timestamp.getAccessibleContext().setAccessibleName("Timestamp authority");
        JPanel form = UIComponentFactory.stack(8, fieldLabel("Reason", reasonField), reasonField,
                fieldLabel("Location", locationField), locationField, timestampLabel, timestamp);
        return UIComponentFactory.card("03", "Signature details", "Shown on every stamp and stored in the PDF.", form);
    }

    private JPanel appearanceCard() {
        designChoice.getAccessibleContext().setAccessibleName("Stamp design");
        designChoice.setName("stamp-design");
        designButton.setIcon(new AppIcon(AppIcon.Symbol.SETTINGS, 16));
        JPanel chooser = UIComponentFactory.transparent(new BorderLayout(UIScale.scale(10), 0));
        chooser.add(designChoice, BorderLayout.CENTER);
        chooser.add(designButton, BorderLayout.EAST);
        stampPreview.setPreferredSize(new Dimension(0, UIScale.scale(150)));
        return UIComponentFactory.card("04", "Stamp appearance", "Every placed area shows this stamp.",
                UIComponentFactory.stack(12, chooser, stampPreview));
    }

    private JPanel permissionsCard() {
        JPanel boxes = UIComponentFactory.transparent(new FlowLayout(FlowLayout.LEFT, UIScale.scale(18), 0));
        boxes.add(editing); boxes.add(copying); boxes.add(printing);
        return UIComponentFactory.card("", "Document permissions", "Optional restrictions for PDF readers, applied before signing. "
                + "They cannot be removed later, and are only available for PDFs that are not already signed.", boxes);
    }

    private JPanel activityPanel() {
        JPanel card = new UIComponentFactory.RoundedPanel("surface");
        card.setLayout(new BorderLayout(0, UIScale.scale(12)));
        card.setBorder(UIComponentFactory.padding(14));
        JToggleButton toggle = new JToggleButton("Activity", new AppIcon(AppIcon.Symbol.ACTIVITY, 16));
        toggle.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        toggle.setToolTipText("Show or hide processing details");
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.putClientProperty(FlatClientProperties.STYLE, "font: -1; background: $RJ.surface; foreground: $RJ.muted");
        logArea.getAccessibleContext().setAccessibleName("Processing activity");
        JScrollPane logs = new JScrollPane(logArea);
        logs.setBorder(BorderFactory.createEmptyBorder());
        logs.setPreferredSize(new Dimension(0, UIScale.scale(130)));
        logs.setVisible(false);
        toggle.addActionListener(event -> { logs.setVisible(toggle.isSelected()); card.revalidate(); });
        card.add(toggle, BorderLayout.NORTH);
        card.add(logs, BorderLayout.CENTER);
        return card;
    }

    private JLabel fieldLabel(String text, JComponent component) {
        JLabel label = UIComponentFactory.label(text, "font: bold");
        label.setLabelFor(component);
        return label;
    }

    private void keyboardShortcut(String key, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private void restoreWindow(UiPreferences preferences) {
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(UIScale.scale(960), screen.width), Math.min(UIScale.scale(640), screen.height)));
        normalSize = new Dimension(Math.min(UIScale.scale(preferences.width()), screen.width), Math.min(UIScale.scale(preferences.height()), screen.height));
        setSize(normalSize);
        setLocationRelativeTo(null);
        setExtendedState(JFrame.MAXIMIZED_BOTH); // Value 6: always launch the main window maximized.
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (isShowing() && (getExtendedState() & MAXIMIZED_BOTH) == 0) normalSize = getSize();
            }
        });
    }

    private void applyMode(SigningMode selected) {
        mode = selected;
        (mode == SigningMode.DSC ? dscMode : sealMode).setSelected(true);
        ((CardLayout) identityContent.getLayout()).show(identityContent, mode.name());
        timestamp.setModel(mode == SigningMode.DSC ? dscAuthorities : sealAuthorities);
        timestampLabel.setText(mode == SigningMode.DSC ? "Trusted timestamp (optional)" : "Timestamp authority");
        if (!busy) sign.setText(actionText());
        setPlaceholders(placeholders); // Refreshes the "secured by one signature/seal" summary and readiness.
    }

    private String actionText() { return mode == SigningMode.DSC ? "Sign document" : "Seal document"; }

    private void textChanged() { updateReadiness(); stampPreview.repaint(); }

    private void loadDesigns(String selectId) {
        designsLoading = true;
        new SwingWorker<List<StampDesign>, Void>() {
            @Override protected List<StampDesign> doInBackground() throws Exception { return StampStore.list(); }
            @Override protected void done() {
                List<StampDesign> designs;
                try { designs = get(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
                catch (ExecutionException ex) { onLog("Stamp designs could not be loaded: " + ex.getCause().getMessage()); designs = List.of(StampDesign.standard()); }
                designChoice.setModel(new DefaultComboBoxModel<>(designs.toArray(StampDesign[]::new)));
                StampDesign chosen = designs.stream().filter(d -> d.id().equals(selectId)).findFirst().orElse(designs.getFirst());
                designChoice.setSelectedItem(chosen);
                designsLoading = false;
                useDesign(chosen);
            }
        }.execute();
    }

    private void useDesign(StampDesign chosen) {
        design = chosen;
        designImage = null;
        stampPreview.repaint();
        if (!chosen.hasImage()) return;
        new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() throws Exception { return StampStore.image(chosen); }
            @Override protected void done() {
                try { if (design == chosen) { designImage = get(); stampPreview.repaint(); } }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (ExecutionException ex) { onLog("Stamp image could not be loaded: " + ex.getCause().getMessage()); }
            }
        }.execute();
    }

    private void openDesigner() {
        List<StampDesign> designs = new ArrayList<>();
        for (int i = 0; i < designChoice.getItemCount(); i++) designs.add(designChoice.getItemAt(i));
        if (designs.isEmpty()) designs.add(StampDesign.standard());
        double[] sample = sampleSize();
        StampDesigner designer = new StampDesigner(this, designs, design.id(), stampValues(), sample[0], sample[1]);
        designer.setVisible(true); // Modal: returns after the designer closes.
        loadDesigns(designer.chosenId() == null ? design.id() : designer.chosenId());
    }

    /** The first placed area's size in points (as seen upright), or a typical stamp size. */
    private double[] sampleSize() {
        if (placeholders.isEmpty()) return new double[]{200, 80};
        SignaturePlaceholder first = placeholders.getFirst();
        return new double[]{first.width(), first.height()};
    }

    public StampRenderer.Values stampValues() {
        SignerIdentity signer = signerList.getSelectedValue();
        String name = mode == SigningMode.DSC ? signer == null ? "Your name" : signer.displayName()
                : declaredName.getText().isBlank() ? "Your name" : declaredName.getText();
        TimestampProvider authority = (TimestampProvider) timestamp.getSelectedItem();
        return new StampRenderer.Values(mode, name, reasonField.getText(), locationField.getText(),
                OffsetDateTime.now(), mode == SigningMode.DSC && signer != null ? signer.issuer() : "",
                authority == null || authority == TimestampProvider.NONE ? "" : authority.label());
    }

    /** Snapshot used by the placement editor to draw real stamps instead of empty boxes. */
    public StampRenderer.Preview stampPreview() { return new StampRenderer.Preview(design, designImage, stampValues()); }

    public Path getPdf() { return pdf; }

    /** The user's original PDF (only read, never written). */
    public Path getSourceFile() { return sourceFile; }

    /** Where file dialogs start: the last folder used in this session, otherwise Documents. */
    public Path lastFolder() {
        return lastFolder != null && java.nio.file.Files.isDirectory(lastFolder) ? lastFolder : Platform.documentsFolder();
    }

    public void rememberFolder(Path folder) { if (folder != null) lastFolder = folder; }

    /** Records the file the user chose; the imported working copy is set separately with {@link #setPdf}. */
    public void setSourceFile(Path original) {
        sourceFile = original;
        if (original != null) rememberFolder(original.getParent());
    }
    public String getDocumentName() { return documentName.getText(); }
    public List<SignaturePlaceholder> getPlaceholders() { return placeholders; }
    public boolean isBusy() { return busy; }
    public void setPdf(Path pdf, List<SignaturePlaceholder> areas) { setPdf(pdf, areas, pdf.getFileName().toString()); }
    public void setPdf(Path pdf, List<SignaturePlaceholder> areas, String originalName) {
        this.pdf = pdf;
        pdfField.setText(pdf.toString());
        pdfField.setToolTipText(pdf.toString());
        documentName.setText(originalName);
        documentName.setToolTipText("Document: " + originalName);
        documentHint.setText("Imported into your local workspace");
        browse.setText("Change PDF");
        setPlaceholders(areas);
    }
    public void setPlaceholders(List<SignaturePlaceholder> areas) {
        placeholders = List.copyOf(areas);
        long pagesWithStamps = areas.stream().mapToInt(SignaturePlaceholder::page).distinct().count();
        areaStatus.setText(areas.isEmpty() ? "No stamp areas yet" : areas.size() + " stamp" + (areas.size() == 1 ? "" : "s")
                + " on " + pagesWithStamps + " page" + (pagesWithStamps == 1 ? "" : "s") + " · secured by one " + (mode == SigningMode.DSC ? "signature" : "seal"));
        updateReadiness();
        stampPreview.repaint();
    }
    /** Connects the update service: its state gates signing, and Settings → About can check for updates. */
    public void attachUpdates(UpdateService service) {
        updates = service.state();
        updates.addListener(this::updateReadiness);
        settingsPanel.setUpdateAction(service::checkNow);
        updateReadiness();
    }

    /** True while a mandatory update is pending or an update is being installed. */
    public boolean updatesBlockOperations() { return updates != null && updates.blocksOperations(); }

    public String busyDescription() { return busyMessage.isBlank() ? "an operation in progress" : busyMessage.replace("…", "").replace("...", ""); }

    private volatile String lastUpdateStatus = "";

    public void onUpdateStatus(String text) {
        lastUpdateStatus = text == null ? "" : text;
        settingsPanel.setUpdateStatus(text);
    }

    /** The most recent update-check message; read by the packaged-application smoke test. */
    public String lastUpdateStatus() { return lastUpdateStatus; }

    public void setUpdateCheckEnabled(boolean enabled) { settingsPanel.setUpdateCheckEnabled(enabled); }

    /** Normal shutdown so that a new version or an installer can take over; the process ends shortly after. */
    public void exitForUpdate() {
        for (java.awt.Window window : java.awt.Window.getWindows()) if (window != this) window.dispose();
        dispose();
        Thread exit = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            System.exit(0);
        }, "update-exit");
        exit.setDaemon(true);
        exit.start();
    }

    public void setBusy(boolean value, String message) {
        busy = value;
        busyMessage = value ? message : "";
        progress.setVisible(value);
        sign.setText(value ? message : actionText());
        updateReadiness();
        if (value) readiness.setText(message);
    }
    public void setEditorOpen() {
        setBusy(true, "Editor open");
        progress.setVisible(false);
        readiness.setText("Place stamp areas in the editor, then save your layout.");
    }
    private void updateReadiness() {
        SignerIdentity signer = signerList.getSelectedValue();
        TimestampProvider authority = (TimestampProvider) timestamp.getSelectedItem();
        boolean identity = mode == SigningMode.DSC ? signer != null && signer.usable()
                : !declaredName.getText().isBlank() && authority != null && authority != TimestampProvider.NONE;
        boolean blocked = updatesBlockOperations();
        browse.setEnabled(!busy && !blocked);
        configure.setEnabled(!busy && !blocked && pdf != null);
        sign.setEnabled(!busy && !blocked && pdf != null && !placeholders.isEmpty() && identity);
        signerList.setEnabled(!busy);
        for (JComponent field : new JComponent[]{reasonField, locationField, declaredName, timestamp, editing, copying, printing,
                dscMode, sealMode, designChoice, designButton}) field.setEnabled(!busy);
        if (!busy && blocked) readiness.setText(updates.mandatory() ? "A required update must be installed before signing. " + updates.detail()
                : "Installing an update…");
        else if (!busy) readiness.setText(pdf == null ? "Import a PDF to get started" : placeholders.isEmpty() ? "Place at least one stamp on your PDF"
                : !identity ? (mode == SigningMode.DSC ? "Choose a signing certificate to continue" : "Enter the name to declare on the seal")
                : "Ready to " + (mode == SigningMode.DSC ? "sign" : "seal") + " · you will choose where to save the "
                        + (mode == SigningMode.DSC ? "signed" : "sealed") + " PDF");
    }
    public SigningRequest buildSigningRequest() {
        return new SigningRequest(pdf, mode, declaredName.getText(), reasonField.getText(), locationField.getText(),
                editing.isSelected(), copying.isSelected(), printing.isSelected(), signerList.getSelectedValue(),
                (TimestampProvider) timestamp.getSelectedItem(), placeholders, design);
    }
    @Override public void onRegistryUpdated(RegistryContext registry) { SwingUtilities.invokeLater(() -> { if (!disposed) applyRegistry(registry); }); }
    private void applyRegistry(RegistryContext registry) {
        SignerIdentity selected = signerList.getSelectedValue();
        signerModel.clear();
        for (SignerIdentity identity : registry.signers()) {
            signerModel.addElement(identity);
            if (selected != null && identity.usable() && selected.fingerprint().equals(identity.fingerprint()) && selected.alias().equals(identity.alias())) {
                signerList.setSelectedValue(identity, false);
            }
        }
        long usable = registry.signers().stream().filter(SignerIdentity::usable).count();
        ((CardLayout) certificateContent.getLayout()).show(certificateContent, signerModel.isEmpty() ? "empty" : "list");
        certificateCount.setText(usable + " ready to sign" + (usable == signerModel.size() ? "" : " · " + (signerModel.size() - usable)
                + " unavailable") + " · refreshed every few seconds");
        updateReadiness();
    }
    @Override public void onLog(String message) {
        SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            String entry = message.length() > 8192 ? message.substring(0, 8192) : message;
            logArea.append("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + entry + "\n");
            if (logArea.getDocument().getLength() > 64_000) logArea.replaceRange("", 0, 16_000);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
    public void showError(String message) { JOptionPane.showMessageDialog(this, message, Product.NAME, JOptionPane.ERROR_MESSAGE); }
    @Override public void dispose() {
        if (!disposed) {
            disposed = true;
            if (monitor != null) monitor.stopMonitoring();
            if (normalSize != null) themes.rememberWindow(UIScale.unscale(normalSize.width), UIScale.unscale(normalSize.height), (getExtendedState() & MAXIMIZED_BOTH) != 0);
            themes.close();
        }
        super.dispose();
    }

    /** Live stamp preview using the current design and form values. */
    private final class StampPreviewPanel extends JPanel {
        StampPreviewPanel() {
            putClientProperty(FlatClientProperties.STYLE, "background: $RJ.preview");
            getAccessibleContext().setAccessibleName("Stamp preview");
            setToolTipText("How each stamp will look. Sample date shown; the real time is used when signing.");
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                double[] size = sampleSize();
                double scale = Math.min((getWidth() - 24) / size[0], (getHeight() - 24) / size[1]);
                if (scale <= 0) return;
                double w = size[0] * scale, h = size[1] * scale;
                double x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.WHITE);
                g.fill(new java.awt.geom.Rectangle2D.Double(x - 8, y - 8, w + 16, h + 16));
                g.translate(x, y);
                g.scale(scale, scale);
                stampPreview().paint(g, size[0], size[1], 1, 1);
            } finally { g.dispose(); }
        }
    }

    private static final class ResponsiveGrid extends UIComponentFactory.WidthTrackingPanel {
        private java.util.List<GridBagConstraints> wideConstraints;
        private Boolean narrow;
        ResponsiveGrid() { super(new GridBagLayout()); setOpaque(false); }
        private void updateColumns() {
            GridBagLayout layout = (GridBagLayout) getLayout();
            if (wideConstraints == null) wideConstraints = java.util.Arrays.stream(getComponents()).map(layout::getConstraints).toList();
            int width = getParent() instanceof JViewport viewport ? viewport.getExtentSize().width : getWidth();
            boolean useNarrow = width > 0 && width < UIScale.scale(820);
            if (narrow == null || useNarrow != narrow) {
                narrow = useNarrow;
                for (int i = 0; i < getComponentCount(); i++) {
                    GridBagConstraints constraints = (GridBagConstraints) wideConstraints.get(i).clone();
                    if (useNarrow) {
                        constraints.gridx = 0; constraints.gridy = i; constraints.gridwidth = 1; constraints.weightx = 1;
                        constraints.insets = new Insets(0, 0, UIScale.scale(18), 0);
                    }
                    layout.setConstraints(getComponent(i), constraints);
                }
                // The viewport may have measured the old column arrangement earlier in
                // this layout pass. Schedule a second measurement before the next paint.
                SwingUtilities.invokeLater(() -> {
                    revalidate();
                    if (getParent() instanceof JViewport viewport) viewport.revalidate();
                });
            }
        }
        @Override public Dimension getPreferredSize() { updateColumns(); return super.getPreferredSize(); }
        @Override public void doLayout() { updateColumns(); super.doLayout(); }
    }

    private static final class CertificateRenderer extends JPanel implements ListCellRenderer<SignerIdentity> {
        private final JLabel name = UIComponentFactory.label("", "font: bold");
        private final JLabel detail = UIComponentFactory.muted("");
        private final JLabel icon = new JLabel(new AppIcon(AppIcon.Symbol.SHIELD, 22));
        CertificateRenderer() {
            super(new BorderLayout(UIScale.scale(10), 0));
            setBorder(UIComponentFactory.padding(12));
            add(icon, BorderLayout.WEST);
            add(UIComponentFactory.stack(6, name, detail), BorderLayout.CENTER);
        }
        @Override public Component getListCellRendererComponent(JList<? extends SignerIdentity> list, SignerIdentity value, int index, boolean selected, boolean focus) {
            boolean usable = value.usable();
            name.setText(value.displayName());
            detail.setText(usable ? (value.issuer().isEmpty() ? "" : value.issuer() + " · ")
                    + (value.notAfter() == null ? "SHA-256 " + value.fingerprint().substring(0, 16) + "…" : "valid to " + EXPIRY.format(value.notAfter()))
                    : "Unavailable · " + value.unavailableReason());
            setBackground(selected && usable ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(!usable ? UIConstants.color("muted") : selected ? list.getSelectionForeground() : UIConstants.color("text"));
            detail.setForeground(usable ? UIConstants.color("muted") : UIConstants.color("warning"));
            icon.setEnabled(usable);
            icon.setForeground(UIConstants.color(usable ? "accent" : "muted"));
            setToolTipText(usable ? "Certificate: " + value.displayName() + " · SHA-256 " + value.fingerprint()
                    : value.displayName() + " cannot be used: " + value.unavailableReason());
            return this;
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Modeless editor: the window opens immediately, with asynchronous, progressively refined page previews. */
@SuppressWarnings("serial")
public final class VisualSignaturePicker extends JDialog {
    private final Path source;
    private final PlaceholderEditorModel model;
    private final SignatureCanvas canvas;
    private final JScrollPane scroll;
    private final JList<Integer> pageList = new JList<>();
    private final DefaultListModel<SignaturePlaceholder> areaListModel = new DefaultListModel<>();
    private final JList<SignaturePlaceholder> areaList = new JList<>(areaListModel);
    private final JSpinner pageNumber = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
    private final JLabel pageTotal = UIComponentFactory.muted("of —");
    private final JLabel zoomValue = UIComponentFactory.muted("—");
    private final JComboBox<String> zoomChoice = new JComboBox<>(new String[]{"Fit page", "Fit width", "25%", "50%", "75%", "100%", "125%", "150%", "200%", "300%", "400%"});
    private final JLabel status = UIComponentFactory.muted("Opening PDF…");
    private final JLabel selectionInfo = UIComponentFactory.muted("No areas placed");
    private final JLabel hint = UIComponentFactory.muted("Drag to draw · Space to pan · Ctrl+wheel to zoom");
    private final JLabel errorMessage = UIComponentFactory.label("", "foreground: $RJ.warning");
    private final JPanel errorBar = UIComponentFactory.transparent(new BorderLayout(12, 0));
    private final JProgressBar progress = new JProgressBar();
    private final JButton previous = tool("", AppIcon.Symbol.PREVIOUS, "Previous page (Page Up)", "previous-page");
    private final JButton next = tool("", AppIcon.Symbol.NEXT, "Next page (Page Down)", "next-page");
    private final JButton zoomOut = tool("", AppIcon.Symbol.ZOOM_OUT, "Zoom out (Ctrl+−)", "zoom-out");
    private final JButton zoomIn = tool("", AppIcon.Symbol.ZOOM_IN, "Zoom in (Ctrl++)", "zoom-in");
    private final JButton undo = tool("", AppIcon.Symbol.UNDO, "Undo (Ctrl+Z)", "undo-area");
    private final JButton redo = tool("", AppIcon.Symbol.REDO, "Redo (Ctrl+Shift+Z)", "redo-area");
    private final JButton duplicate = tool("Duplicate", AppIcon.Symbol.DUPLICATE, "Duplicate selected area (Ctrl+D)", "duplicate-area");
    private final JButton delete = tool("Delete", AppIcon.Symbol.TRASH, "Delete selected area (Delete)", "delete-area");
    private final JButton clear = new JButton("Clear this page");
    private final JButton repeat = tool("Repeat on all pages", AppIcon.Symbol.DOCUMENT, "Copy the selected stamp to the same position on every other page", "repeat-area");
    private final JButton finish = UIComponentFactory.createModernButton("Save layout", true);
    private final JToggleButton draw = new JToggleButton("Draw", new AppIcon(AppIcon.Symbol.BOX, 17));
    private final JToggleButton pan = new JToggleButton("Pan", new AppIcon(AppIcon.Symbol.HAND, 17));
    private final Map<Integer, PageGeometry> geometryCache = new HashMap<>();
    private final Timer debounce = new Timer(140, event -> submitRender());
    private final Timer busyDelay = new Timer(180, event -> progress.setVisible(true));
    private PdfPreviewService service;
    private PdfPreviewService.Request desired;
    private PdfPreviewService.ZoomMode zoomMode = PdfPreviewService.ZoomMode.FIT_PAGE;
    private float customZoom = 1;
    private long generation;
    private int epoch;
    private int page;
    private int pageCount;
    private boolean syncing;
    private boolean loaded;
    private boolean closed;
    private boolean accepted;
    private boolean revealSelection;

    public VisualSignaturePicker(JFrame parent, Path pdf, List<SignaturePlaceholder> existing) {
        this(parent, pdf, existing, "PDF document");
    }

    public VisualSignaturePicker(JFrame parent, Path pdf, List<SignaturePlaceholder> existing, String documentName) {
        this(parent, pdf, existing, documentName, null);
    }

    public VisualSignaturePicker(JFrame parent, Path pdf, List<SignaturePlaceholder> existing, String documentName, StampRenderer.Preview stamp) {
        super(parent, "Stamp placement", false);
        source = pdf;
        model = new PlaceholderEditorModel(existing);
        canvas = new SignatureCanvas(model, this::modelChanged, hint::setText);
        canvas.setStamp(stamp);
        scroll = new JScrollPane(canvas);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImages(AppIcon.windowImages());
        setLayout(new BorderLayout());
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(UIScale.scale(960), screen.width), Math.min(UIScale.scale(600), screen.height)));
        setSize(Math.min(UIScale.scale(1200), screen.width), Math.min(UIScale.scale(820), screen.height));
        setLocationRelativeTo(parent);
        add(header(documentName), BorderLayout.NORTH);
        JPanel body = UIComponentFactory.transparent(new BorderLayout());
        body.add(sidebar(), BorderLayout.WEST);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setWheelScrollingEnabled(false);
        scroll.addMouseWheelListener(this::wheel);
        body.add(scroll, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        debounce.setRepeats(false);
        busyDelay.setRepeats(false);
        bindActions();
        modelChanged();
        updateControls();
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) { openDocument(); }
        });
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (loaded && zoomMode != PdfPreviewService.ZoomMode.CUSTOM) requestRender(false, null);
            }
        });
    }

    private JPanel header(String name) {
        JPanel header = UIComponentFactory.transparent(new BorderLayout(0, UIScale.scale(14)));
        header.setBorder(UIComponentFactory.padding(16));
        JLabel document = UIComponentFactory.muted(name);
        document.setToolTipText("Document: " + name);
        JPanel title = UIComponentFactory.transparent(new BorderLayout(18, 0));
        title.add(UIComponentFactory.label("Stamp placement", "font: bold +6"), BorderLayout.WEST);
        title.add(document, BorderLayout.CENTER);
        header.add(title, BorderLayout.NORTH);
        JPanel toolbar = UIComponentFactory.transparent(new BorderLayout(UIScale.scale(16), 0));
        JPanel paging = row(previous, pageNumber, pageTotal, next);
        pageNumber.setPreferredSize(new Dimension(UIScale.scale(72), pageNumber.getPreferredSize().height));
        pageNumber.setName("page-number");
        pageNumber.getAccessibleContext().setAccessibleName("Page number");
        toolbar.add(paging, BorderLayout.WEST);
        zoomChoice.setEditable(true);
        zoomChoice.setName("zoom-choice");
        zoomChoice.setPreferredSize(new Dimension(UIScale.scale(116), zoomChoice.getPreferredSize().height));
        zoomChoice.getAccessibleContext().setAccessibleName("Zoom percentage or fit mode");
        zoomValue.setPreferredSize(new Dimension(UIScale.scale(48), zoomValue.getPreferredSize().height));
        JPanel zoomTools = row(UIComponentFactory.muted("Zoom"), zoomOut, zoomChoice, zoomIn, zoomValue);
        toolbar.add(zoomTools, BorderLayout.CENTER);
        draw.setSelected(true);
        draw.setToolTipText("Draw, select, move and resize signature areas (V)");
        pan.setToolTipText("Drag to pan (H), or hold Space on the page");
        ButtonGroup modes = new ButtonGroup();
        modes.add(draw); modes.add(pan);
        toolbar.add(row(draw, pan, undo, redo), BorderLayout.EAST);
        JPanel tools = UIComponentFactory.transparent(new BorderLayout(0, UIScale.scale(10)));
        tools.add(toolbar, BorderLayout.NORTH);
        JButton retry = new JButton("Retry");
        retry.addActionListener(event -> { if (loaded) requestRender(true, null); else openDocument(); });
        errorBar.add(errorMessage, BorderLayout.CENTER);
        errorBar.add(retry, BorderLayout.EAST);
        errorBar.setVisible(false);
        tools.add(errorBar, BorderLayout.SOUTH);
        header.add(tools, BorderLayout.CENTER);
        return header;
    }

    private JPanel sidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.putClientProperty(FlatClientProperties.STYLE, "background: $RJ.surface");
        sidebar.setPreferredSize(new Dimension(UIScale.scale(220), 0));
        JTabbedPane tabs = new JTabbedPane();
        pageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pageList.setFixedCellHeight(UIScale.scale(58));
        pageList.setCellRenderer(new PageRenderer());
        pageList.getAccessibleContext().setAccessibleName("Document pages");
        pageList.setName("document-pages");
        JScrollPane pages = new JScrollPane(pageList);
        pages.setBorder(BorderFactory.createEmptyBorder());
        tabs.addTab("Pages", pages);
        areaList.setCellRenderer(new AreaRenderer());
        areaList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        areaList.setFixedCellHeight(UIScale.scale(60));
        areaList.getAccessibleContext().setAccessibleName("Signature areas");
        areaList.setName("signature-areas");
        JScrollPane areas = new JScrollPane(areaList);
        areas.setBorder(BorderFactory.createEmptyBorder());
        tabs.addTab("Areas", areas);
        sidebar.add(tabs, BorderLayout.CENTER);
        JPanel areaActions = UIComponentFactory.transparent(new GridLayout(1, 2, UIScale.scale(6), 0));
        duplicate.setMargin(new Insets(UIScale.scale(7), UIScale.scale(5), UIScale.scale(7), UIScale.scale(5)));
        areaActions.add(duplicate); areaActions.add(delete);
        repeat.setHorizontalAlignment(SwingConstants.LEFT);
        JPanel controls = UIComponentFactory.stack(10, selectionInfo, areaActions, repeat, clear);
        controls.setBorder(UIComponentFactory.padding(12));
        sidebar.add(controls, BorderLayout.SOUTH);
        return sidebar;
    }

    private JPanel footer() {
        JPanel footer = UIComponentFactory.transparent(new BorderLayout(UIScale.scale(16), 0));
        footer.setBorder(UIComponentFactory.padding(14));
        progress.setIndeterminate(true);
        status.setName("preview-status");
        progress.setPreferredSize(new Dimension(UIScale.scale(80), UIScale.scale(4)));
        footer.add(UIComponentFactory.stack(6, row(status, progress), hint), BorderLayout.CENTER);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dispose());
        finish.setName("save-layout");
        finish.setIcon(new AppIcon(AppIcon.Symbol.CHECK, 17));
        finish.setToolTipText("Save these positions and return to the document (Ctrl+S)");
        finish.addActionListener(event -> { canvas.cancelGesture(); accepted = true; dispose(); });
        footer.add(row(cancel, finish), BorderLayout.EAST);
        return footer;
    }

    private void bindActions() {
        previous.addActionListener(event -> selectPage(page - 1));
        next.addActionListener(event -> selectPage(page + 1));
        pageNumber.addChangeListener(event -> { if (!syncing) selectPage((int) pageNumber.getValue() - 1); });
        pageList.addListSelectionListener(event -> { if (!syncing && !event.getValueIsAdjusting() && pageList.getSelectedIndex() >= 0) selectPage(pageList.getSelectedIndex()); });
        areaList.addListSelectionListener(event -> {
            if (syncing || event.getValueIsAdjusting()) return;
            canvas.cancelGesture();
            model.select(areaList.getSelectedIndex());
            if (model.selected() >= 0) {
                int target = model.areas().get(model.selected()).page() - 1;
                if (target != page) { revealSelection = true; selectPage(target); }
                else canvas.revealSelection();
            }
            updateControls(); canvas.repaint();
        });
        zoomOut.addActionListener(event -> zoomBy(1 / 1.25, null));
        zoomIn.addActionListener(event -> zoomBy(1.25, null));
        zoomChoice.addActionListener(event -> {
            if (syncing) return;
            String value = String.valueOf(zoomChoice.getSelectedItem()).trim();
            if ("Fit page".equals(value)) zoomMode = PdfPreviewService.ZoomMode.FIT_PAGE;
            else if ("Fit width".equals(value)) zoomMode = PdfPreviewService.ZoomMode.FIT_WIDTH;
            else {
                try {
                    float percentage = Float.parseFloat(value.replace("%", ""));
                    if (!Float.isFinite(percentage) || percentage < 25 || percentage > 400) throw new NumberFormatException();
                    customZoom = percentage / 100;
                    zoomMode = PdfPreviewService.ZoomMode.CUSTOM;
                } catch (NumberFormatException ex) { hint.setText("Enter a zoom from 25% to 400%, or choose Fit page / Fit width."); syncZoom(); return; }
            }
            requestRender(false, null);
        });
        draw.addActionListener(event -> { canvas.setMode(SignatureCanvas.Mode.DRAW); hint.setText("Drag to draw · Drag a box to move · Drag its handles to resize"); });
        pan.addActionListener(event -> { canvas.setMode(SignatureCanvas.Mode.PAN); hint.setText("Drag the page to pan · Ctrl+wheel to zoom"); });
        undo.addActionListener(event -> history(false));
        redo.addActionListener(event -> history(true));
        duplicate.addActionListener(event -> canvas.duplicateSelected());
        delete.addActionListener(event -> deleteSelected());
        repeat.addActionListener(event -> repeatOnAllPages());
        clear.addActionListener(event -> { canvas.cancelGesture(); model.clearPage(page + 1); modelChanged(); });
        bind("control EQUALS", "zoom-in", () -> zoomBy(1.25, null));
        bind("control shift EQUALS", "zoom-in-plus", () -> zoomBy(1.25, null));
        bind("control ADD", "zoom-in-keypad", () -> zoomBy(1.25, null));
        bind("control MINUS", "zoom-out", () -> zoomBy(1 / 1.25, null));
        bind("control SUBTRACT", "zoom-out-keypad", () -> zoomBy(1 / 1.25, null));
        bind("control 0", "fit-page", () -> { zoomMode = PdfPreviewService.ZoomMode.FIT_PAGE; requestRender(true, null); });
        bind("control 1", "actual-size", () -> { zoomMode = PdfPreviewService.ZoomMode.CUSTOM; customZoom = 1; requestRender(true, null); });
        bind("control Z", "undo", () -> history(false));
        bind("control shift Z", "redo", () -> history(true));
        bind("control Y", "redo-alt", () -> history(true));
        bind("control D", "duplicate", canvas::duplicateSelected);
        bind("DELETE", "delete", this::deleteSelected);
        bind("ESCAPE", "cancel-gesture", () -> { canvas.cancelGesture(); model.select(-1); modelChanged(); });
        bind("PAGE_UP", "previous", () -> selectPage(page - 1));
        bind("PAGE_DOWN", "next", () -> selectPage(page + 1));
        bind("V", "draw", () -> draw.doClick(0));
        bind("H", "pan", () -> pan.doClick(0));
        bind("control S", "save", () -> { if (finish.isEnabled()) finish.doClick(0); });
        String[] arrows = {"LEFT", "RIGHT", "UP", "DOWN"};
        int[][] deltas = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int i = 0; i < arrows.length; i++) {
            int dx = deltas[i][0], dy = deltas[i][1];
            bindCanvas(arrows[i], () -> canvas.nudge(dx, dy));
            bindCanvas("shift " + arrows[i], () -> canvas.nudge(dx * 10, dy * 10));
        }
    }

    private void openDocument() {
        if (closed) return;
        if (service != null) service.close();
        loaded = false;
        geometryCache.clear();
        errorBar.setVisible(false);
        status.setText("Opening PDF…");
        progress.setVisible(true);
        canvas.loadingPage(-1);
        canvas.setEmptyMessage("Opening PDF…");
        int session = ++epoch;
        updateControls();
        service = new PdfPreviewService(source, new PdfPreviewService.Listener() {
            @Override public void opened(int count) {
                if (closed || session != epoch) return;
                loaded = true;
                pageCount = count;
                page = Math.min(page, count - 1);
                syncing = true;
                pageList.setModel(new AbstractListModel<>() {
                    @Override public int getSize() { return count; }
                    @Override public Integer getElementAt(int index) { return index; }
                });
                pageNumber.setModel(new SpinnerNumberModel(page + 1, 1, count, 1));
                pageList.setSelectedIndex(page);
                syncing = false;
                pageTotal.setText("of " + count);
                canvas.setPageCount(count);
                canvas.loadingPage(page);
                updateControls();
                requestRender(true, null);
            }
            @Override public void rendered(PdfPreviewService.Frame frame) {
                if (closed || session != epoch || frame.id() != generation || frame.page() != page) return;
                geometryCache.put(page, frame.geometry());
                if (frame.quality() == PdfPreviewService.Quality.GEOMETRY) {
                    if (canvas.geometry() == null) applyScale(frame.geometry(), frame.scale(), null, false);
                    syncZoom();
                    return;
                }
                canvas.setImage(frame.image());
                if (revealSelection) { canvas.revealSelection(); revealSelection = false; }
                boolean complete = frame.quality() == PdfPreviewService.Quality.READY;
                status.setText(complete ? "Page " + (page + 1) + " of " + pageCount + " · Preview ready" : "Page " + (page + 1) + " · Refining preview…");
                if (complete) { busyDelay.stop(); progress.setVisible(false); }
                updateControls();
            }
            @Override public void failed(long id, int failedPage, Exception failure) {
                if (closed || session != epoch || id != 0 && id != generation) return;
                busyDelay.stop(); progress.setVisible(false);
                status.setText(id == 0 ? "Could not open this PDF" : "Could not preview page " + (failedPage + 1));
                errorMessage.setText(status.getText() + ". You can retry or choose another page.");
                errorMessage.setToolTipText(String.valueOf(failure.getMessage()));
                errorBar.setVisible(true);
                canvas.setEmptyMessage("Preview unavailable");
                revalidate();
            }
        });
    }

    private void selectPage(int target) {
        if (!loaded || target < 0 || target >= pageCount || target == page) return;
        canvas.cancelGesture();
        page = target;
        if (model.selected() >= 0 && model.areas().get(model.selected()).page() != page + 1) {
            model.select(-1);
            modelChanged();
        }
        syncing = true;
        pageNumber.setValue(page + 1);
        pageList.setSelectedIndex(page);
        pageList.ensureIndexIsVisible(page);
        syncing = false;
        canvas.loadingPage(page);
        scroll.getViewport().setViewPosition(new Point());
        updateControls();
        requestRender(true, null);
    }

    private PdfPreviewService.Request requestParameters(long id) {
        Dimension extent = scroll.getViewport().getExtentSize();
        int extraMargin = UIScale.scale(48) - 48;
        double ratio = getGraphicsConfiguration() == null ? 1 : getGraphicsConfiguration().getDefaultTransform().getScaleX();
        return new PdfPreviewService.Request(id, page, zoomMode, customZoom, Math.max(100, extent.width - extraMargin), Math.max(100, extent.height - extraMargin), ratio);
    }

    private void requestRender(boolean immediate, Point anchor) {
        if (!loaded || closed) return;
        canvas.cancelGesture();
        errorBar.setVisible(false);
        desired = requestParameters(++generation);
        service.supersede(generation);
        PageGeometry geometry = geometryCache.get(page);
        if (geometry != null) applyScale(geometry, PdfPreviewService.displayScale(geometry, desired), anchor, canvas.geometry() != null);
        syncZoom();
        status.setText(canvas.hasImage() ? "Updating preview…" : "Loading page " + (page + 1) + "…");
        busyDelay.restart();
        if (immediate) { debounce.stop(); submitRender(); } else debounce.restart();
    }

    private void submitRender() { if (!closed && loaded && desired != null) service.request(desired); }

    private void applyScale(PageGeometry geometry, float scale, Point anchor, boolean preservePosition) {
        JViewport viewport = scroll.getViewport();
        Point fixed = anchor == null ? new Point(viewport.getWidth() / 2, viewport.getHeight() / 2) : anchor;
        Rectangle2D before = canvas.pageBounds();
        Point origin = viewport.getViewPosition();
        double x = (origin.x + fixed.x - before.getX()) / canvas.scale();
        double y = (origin.y + fixed.y - before.getY()) / canvas.scale();
        canvas.setGeometry(page, geometry, scale);
        Dimension preferred = canvas.getPreferredSize();
        viewport.setViewSize(new Dimension(Math.max(preferred.width, viewport.getWidth()), Math.max(preferred.height, viewport.getHeight())));
        if (preservePosition) {
            Rectangle2D after = canvas.pageBounds();
            int left = (int) Math.round(after.getX() + x * scale - fixed.x);
            int top = (int) Math.round(after.getY() + y * scale - fixed.y);
            viewport.setViewPosition(new Point(Math.max(0, Math.min(left, canvas.getWidth() - viewport.getWidth())),
                    Math.max(0, Math.min(top, canvas.getHeight() - viewport.getHeight()))));
        } else viewport.setViewPosition(new Point());
    }

    private void zoomBy(double factor, Point anchor) {
        if (!loaded) return;
        customZoom = (float) Math.max(.25, Math.min(4, canvas.scale() * factor));
        zoomMode = PdfPreviewService.ZoomMode.CUSTOM;
        requestRender(false, anchor);
    }

    private void wheel(MouseWheelEvent event) {
        if (event.isControlDown()) {
            zoomBy(Math.pow(1.15, -event.getPreciseWheelRotation()), SwingUtilities.convertPoint(scroll, event.getPoint(), scroll.getViewport()));
        } else {
            JScrollBar bar = event.isShiftDown() ? scroll.getHorizontalScrollBar() : scroll.getVerticalScrollBar();
            bar.setValue(bar.getValue() + (int) Math.round(event.getPreciseWheelRotation() * UIScale.scale(48)));
        }
        event.consume();
    }

    private void syncZoom() {
        syncing = true;
        zoomChoice.setSelectedItem(switch (zoomMode) {
            case FIT_PAGE -> "Fit page";
            case FIT_WIDTH -> "Fit width";
            case CUSTOM -> Math.round(customZoom * 100) + "%";
        });
        zoomValue.setText(canvas.geometry() == null ? "—" : Math.round(canvas.scale() * 100) + "%");
        syncing = false;
    }

    private void modelChanged() {
        syncing = true;
        areaListModel.clear();
        model.areas().forEach(areaListModel::addElement);
        areaList.setSelectedIndex(model.selected());
        syncing = false;
        selectionInfo.setText(model.areas().size() + " stamp" + (model.areas().size() == 1 ? "" : "s"));
        finish.setText(model.areas().isEmpty() ? "Save layout" : "Use " + model.areas().size() + " stamp" + (model.areas().size() == 1 ? "" : "s"));
        pageList.repaint();
        canvas.repaint();
        updateControls();
        hint.setText(model.selected() >= 0 ? "Drag to move · Handles to resize · Delete to remove · Ctrl+D to duplicate" : "Drag to draw · Space to pan · Ctrl+wheel to zoom");
    }

    private void updateControls() {
        previous.setEnabled(loaded && page > 0); next.setEnabled(loaded && page + 1 < pageCount);
        pageNumber.setEnabled(loaded); pageList.setEnabled(loaded);
        zoomChoice.setEnabled(loaded); zoomIn.setEnabled(loaded); zoomOut.setEnabled(loaded);
        undo.setEnabled(model.canUndo()); redo.setEnabled(model.canRedo());
        boolean selected = model.selected() >= 0;
        delete.setEnabled(selected);
        duplicate.setEnabled(selected && canvas.hasImage() && model.areas().size() < PlaceholderStore.MAX_COUNT
                && model.areas().get(model.selected()).page() == page + 1);
        clear.setEnabled(loaded && model.areas().stream().anyMatch(area -> area.page() == page + 1));
        repeat.setEnabled(loaded && selected && pageCount > 1);
        finish.setEnabled(loaded);
    }

    private void deleteSelected() { canvas.cancelGesture(); model.deleteSelected(); modelChanged(); }

    private void repeatOnAllPages() {
        if (!loaded || model.selected() < 0) return;
        canvas.cancelGesture();
        SignaturePlaceholder area = model.areas().get(model.selected());
        int session = epoch;
        repeat.setEnabled(false);
        status.setText("Reading page sizes…");
        service.geometries(pages -> {
            if (closed || session != epoch) return;
            int added;
            try { added = model.addAll(PageGeometry.repeatOnAllPages(area, pages)); }
            catch (IllegalArgumentException ex) { hint.setText(ex.getMessage()); updateControls(); return; }
            modelChanged();
            status.setText("Page " + (page + 1) + " of " + pageCount);
            hint.setText(added == 0 ? "Every page already has this stamp." : "Added " + added + " stamp" + (added == 1 ? "" : "s")
                    + (model.areas().size() >= PlaceholderStore.MAX_COUNT ? " (limit reached)" : "") + " · Ctrl+Z to undo");
        }, failure -> {
            if (closed || session != epoch) return;
            hint.setText("Could not read page sizes: " + failure.getMessage());
            updateControls();
        });
    }
    private void history(boolean forward) {
        canvas.cancelGesture();
        if (forward) model.redo(); else model.undo();
        modelChanged();
        if (model.selected() >= 0) {
            int target = model.areas().get(model.selected()).page() - 1;
            if (target != page) { revealSelection = true; selectPage(target); } else canvas.revealSelection();
        }
    }

    private void bind(String key, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (!(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent)) action.run();
            }
        });
    }
    private void bindCanvas(String key, Runnable action) {
        canvas.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        canvas.getActionMap().put(key, new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { action.run(); } });
    }
    private static JPanel row(Component... components) {
        JPanel row = UIComponentFactory.transparent(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), 0));
        for (Component component : components) row.add(component);
        return row;
    }
    private static JButton tool(String text, AppIcon.Symbol icon, String tooltip, String name) {
        JButton button = new JButton(text, new AppIcon(icon, 17));
        button.setToolTipText(tooltip); button.setName(name);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setMargin(new Insets(UIScale.scale(7), UIScale.scale(8), UIScale.scale(7), UIScale.scale(8)));
        return button;
    }

    public boolean isAccepted() { return accepted; }
    public List<SignaturePlaceholder> getPlaceholders() { return model.areas(); }
    @Override public void dispose() {
        if (!closed) {
            closed = true;
            debounce.stop(); busyDelay.stop(); progress.setIndeterminate(false);
            if (service != null) service.close();
        }
        super.dispose();
    }

    private final class PageRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            long count = model.areas().stream().filter(area -> area.page() == index + 1).count();
            setText("Page " + (index + 1) + (count == 0 ? "" : "   ·   " + count + " area" + (count == 1 ? "" : "s")));
            setIcon(new AppIcon(AppIcon.Symbol.DOCUMENT, 26));
            setIconTextGap(UIScale.scale(10));
            setBorder(UIComponentFactory.padding(10));
            return this;
        }
    }
    private final class AreaRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            SignaturePlaceholder area = (SignaturePlaceholder) value;
            setText("Stamp " + (index + 1) + "  ·  Page " + area.page());
            setToolTipText(String.format(java.util.Locale.ROOT, "%.0f × %.0f pt", area.width(), area.height()));
            setIcon(new AppIcon(AppIcon.Symbol.BOX, 20));
            setBorder(UIComponentFactory.padding(10));
            return this;
        }
    }
}

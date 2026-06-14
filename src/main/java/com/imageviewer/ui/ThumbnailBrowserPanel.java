package com.imageviewer.ui;

import com.imageviewer.core.ImageLoader;
import com.imageviewer.core.AutoTagger;
import com.imageviewer.core.TagManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Scrollable thumbnail grid with:
 *  - Scrollable/WrapLayout fix for proper row wrapping
 *  - Async thumbnail loading
 *  - Drag-and-drop (files and folders)
 *  - Rich tag dialog on right-click (existing tags as checkboxes + New tag field)
 */
public class ThumbnailBrowserPanel extends JPanel {

    public interface SelectionListener { void selectionChanged(List<File> selected); }
    public interface OpenListener      { void openRequested(File file); }
    public interface DropFolderListener{ void folderDropped(File folder); }

    private static final int DEFAULT_THUMB = 140;
    private static final int MIN_THUMB     = 80;
    private static final int MAX_THUMB     = 280;

    // Initialized at field level so lambdas created in the constructor can safely reference them.
    private final JPanel      grid       = new ScrollablePanel(new WrapLayout(FlowLayout.LEFT, 8, 8));
    private final JScrollPane scrollPane = new JScrollPane(grid);

    private final JSlider    sizeSlider;
    private final JLabel     countLabel;
    private final JTextField filterField;

    private final List<ThumbnailCell>      cells              = new ArrayList<>();
    private final List<SelectionListener>  selLis             = new ArrayList<>();
    private final List<OpenListener>       openLis            = new ArrayList<>();
    private final List<DropFolderListener> dropFolderListeners = new ArrayList<>();
    private final ExecutorService          pool               = Executors.newFixedThreadPool(4);
    private final List<Future<?>>          pending            = new ArrayList<>();
    private final Map<File, BufferedImage> cache              = Collections.synchronizedMap(
        new LinkedHashMap<File, BufferedImage>() {
            protected boolean removeEldestEntry(Map.Entry<File, BufferedImage> e) { return size() > 300; }
        });

    private int        thumbSize = DEFAULT_THUMB;
    private String     tagFilter = null;
    private SortMode   sortMode  = SortMode.NAME;
    private List<File> allFiles  = new ArrayList<>();

    public enum SortMode { NAME, DATE, SIZE }

    // ─────────────────────────────────────────────────────────────────────────
    public ThumbnailBrowserPanel() {
        super(new BorderLayout());

        // ── Toolbar ───────────────────────────────────────────────────────────
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 60, 60)));

        filterField = new JTextField(12);
        filterField.putClientProperty("JTextField.placeholderText", "Filter by name...");
        filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });

        JComboBox<SortMode> sortBox = new JComboBox<>(SortMode.values());
        sortBox.addActionListener(e -> { sortMode = (SortMode) sortBox.getSelectedItem(); reload(); });

        sizeSlider = new JSlider(MIN_THUMB, MAX_THUMB, thumbSize);
        sizeSlider.setPreferredSize(new Dimension(110, 24));
        sizeSlider.addChangeListener(e -> {
            thumbSize = sizeSlider.getValue();
            for (ThumbnailCell c : cells) c.updateThumbSize(thumbSize);
            grid.revalidate();
        });

        countLabel = new JLabel("0 images");

        toolbar.add(new JLabel("Filter:")); toolbar.add(filterField);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(new JLabel("Sort:"));   toolbar.add(sortBox);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(new JLabel("Size:"));   toolbar.add(sizeSlider);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(countLabel);

        // ── Grid ──────────────────────────────────────────────────────────────
        grid.setBackground(new Color(35, 35, 35));
        grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        add(toolbar,    BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        setupDragAndDrop();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void loadDirectory(File dir) {
        cancelPending();
        SwingUtilities.invokeLater(this::clearGrid);
        allFiles = new ArrayList<>(ImageLoader.getImageFiles(dir, true));
        reload();
    }

    public void loadFiles(List<File> files) {
        cancelPending();
        SwingUtilities.invokeLater(this::clearGrid);
        allFiles = new ArrayList<>(files);
        reload();
    }

    public void setTagFilter(String tag) { this.tagFilter = tag; applyFilter(); }

    public List<File> getSelectedFiles() {
        List<File> sel = new ArrayList<>();
        for (ThumbnailCell c : cells) if (c.isSelected()) sel.add(c.getFile());
        return sel;
    }

    public void addSelectionListener(SelectionListener l)   { selLis.add(l); }
    public void addOpenListener(OpenListener l)             { openLis.add(l); }
    public void addDropFolderListener(DropFolderListener l) { dropFolderListeners.add(l); }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void reload() {
        cancelPending();
        List<File> files = new ArrayList<>(allFiles);
        if (sortMode == SortMode.DATE) {
            files.sort(Comparator.comparingLong(File::lastModified).reversed());
        } else if (sortMode == SortMode.SIZE) {
            files.sort(Comparator.comparingLong(File::length).reversed());
        } else {
            files.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        }
        allFiles = files;
        applyFilter();
    }

    private void applyFilter() {
        String text = filterField.getText().toLowerCase().trim();
        List<File> visible = new ArrayList<>();
        for (File f : allFiles) {
            if (!text.isEmpty() && !f.getName().toLowerCase().contains(text)) continue;
            if (tagFilter != null && !tagFilter.isEmpty()
                    && !TagManager.getInstance().getTags(f).contains(tagFilter)) continue;
            visible.add(f);
        }
        populateGrid(visible);
    }

    private void populateGrid(List<File> files) {
        cancelPending();
        SwingUtilities.invokeLater(() -> {
            clearGrid();
            countLabel.setText(files.size() + " image" + (files.size() == 1 ? "" : "s"));

            if (files.isEmpty()) {
                JLabel hint = new JLabel("No images found. Drop a folder or images here.",
                        SwingConstants.CENTER);
                hint.setForeground(new Color(90, 90, 90));
                hint.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
                grid.add(hint);
                grid.revalidate();
                return;
            }

            for (File f : files) {
                ThumbnailCell cell = new ThumbnailCell(f, thumbSize);
                attachCellListeners(cell);
                cells.add(cell);
                grid.add(cell);
            }
            grid.revalidate();
            grid.repaint();

            for (ThumbnailCell cell : new ArrayList<>(cells)) {
                File cf = cell.getFile();
                Future<?> fut = pool.submit(() -> {
                    BufferedImage img = cache.computeIfAbsent(cf,
                        k -> ImageLoader.generateThumbnail(k, MAX_THUMB));
                    SwingUtilities.invokeLater(() -> cell.setThumbnail(img));
                });
                pending.add(fut);
            }
        });
    }

    private void clearGrid() { cells.clear(); grid.removeAll(); grid.revalidate(); grid.repaint(); }
    private void cancelPending() { for (Future<?> f : pending) f.cancel(false); pending.clear(); }

    private void attachCellListeners(ThumbnailCell cell) {
        cell.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (!e.isControlDown() && !e.isShiftDown()) clearSelection();
                    cell.setSelected(true); fireSelection();
                    if (e.getClickCount() == 2) openLis.forEach(l -> l.openRequested(cell.getFile()));
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    if (!cell.isSelected()) { clearSelection(); cell.setSelected(true); fireSelection(); }
                    showContextMenu(cell, e.getX(), e.getY());
                }
            }
        });
    }

    private void clearSelection() { cells.forEach(c -> c.setSelected(false)); }
    private void fireSelection()  { selLis.forEach(l -> l.selectionChanged(getSelectedFiles())); }

    // ── Context menu ──────────────────────────────────────────────────────────

    private void showContextMenu(ThumbnailCell cell, int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem open = new JMenuItem("Open / View");
        open.addActionListener(e -> openLis.forEach(l -> l.openRequested(cell.getFile())));
        menu.add(open);

        JMenuItem reveal = new JMenuItem("Reveal in Explorer");
        reveal.addActionListener(e -> {
            try { Desktop.getDesktop().open(cell.getFile().getParentFile()); } catch (Exception ex) {}
        });
        menu.add(reveal);

        menu.addSeparator();

        // ── Tag menu item → opens TagSelectionDialog ───────────────────────────
        JMenuItem tagItem = new JMenuItem("Set Tags...");
        tagItem.addActionListener(e -> {
            List<File> targets = getSelectedFiles();
            if (targets.isEmpty()) targets = List.of(cell.getFile());

            Window owner = SwingUtilities.getWindowAncestor(ThumbnailBrowserPanel.this);
            TagSelectionDialog dlg = new TagSelectionDialog(owner, targets);
            dlg.setVisible(true);   // blocks until closed

            if (dlg.isConfirmed()) {
                TagManager tm = TagManager.getInstance();
                for (File f : targets) {
                    // Add every checked tag
                    for (String tag : dlg.getCheckedTags())   tm.addTag(f, tag);
                    // Remove every tag the user explicitly unchecked
                    for (String tag : dlg.getUncheckedTags()) tm.removeTag(f, tag);
                }
                cells.forEach(Component::repaint);
            }
        });
        menu.add(tagItem);

        // ── Auto-tag menu items ────────────────────────────────────────────────
        menu.addSeparator();

        JMenuItem autoTagStat = new JMenuItem("Auto-tag  (Statistical, instant)");
        autoTagStat.addActionListener(e -> {
            List<File> targets = getSelectedFiles();
            if (targets.isEmpty()) targets = java.util.List.of(cell.getFile());
            final List<File> finalTargets = targets;
            SwingWorker<java.util.Set<String>, Void> sw = new SwingWorker<>() {
                @Override protected java.util.Set<String> doInBackground() {
                    java.util.Set<String> all = new java.util.LinkedHashSet<>();
                    for (File f : finalTargets) all.addAll(AutoTagger.autoTag(f, false));
                    return all;
                }
                @Override protected void done() {
                    try {
                        java.util.Set<String> detected = get();
                        Window owner = SwingUtilities.getWindowAncestor(ThumbnailBrowserPanel.this);
                        File ref = finalTargets.get(0);
                        java.util.Set<String> chosen = AutoTagger.showPreviewDialog(owner, ref, detected);
                        if (chosen != null && !chosen.isEmpty()) {
                            TagManager tm = TagManager.getInstance();
                            for (File f : finalTargets)
                                for (String t : chosen) tm.addTag(f, t);
                            cells.forEach(java.awt.Component::repaint);
                        }
                    } catch (Exception ex) { ex.printStackTrace(); }
                }
            };
            sw.execute();
        });
        menu.add(autoTagStat);

        JMenuItem autoTagAI = new JMenuItem("Auto-tag  (AI object detection…)");
        autoTagAI.addActionListener(e -> {
            List<File> targets = getSelectedFiles();
            if (targets.isEmpty()) targets = java.util.List.of(cell.getFile());
            final List<File> finalTargets = targets;
            if (!AutoTagger.isDJLAvailable()) {
                int ok = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(ThumbnailBrowserPanel.this),
                    "<html><b>AI Auto-Tag</b> needs a one-time download (~250 MB, cached).<br>Continue?</html>",
                    "AI Model Download", JOptionPane.YES_NO_OPTION);
                if (ok != JOptionPane.YES_OPTION) return;
            }
            SwingWorker<java.util.Set<String>, Void> sw = new SwingWorker<>() {
                @Override protected java.util.Set<String> doInBackground() {
                    java.util.Set<String> all = new java.util.LinkedHashSet<>();
                    for (File f : finalTargets) all.addAll(AutoTagger.autoTag(f, true));
                    return all;
                }
                @Override protected void done() {
                    try {
                        java.util.Set<String> detected = get();
                        Window owner = SwingUtilities.getWindowAncestor(ThumbnailBrowserPanel.this);
                        File ref = finalTargets.get(0);
                        java.util.Set<String> chosen = AutoTagger.showPreviewDialog(owner, ref, detected);
                        if (chosen != null && !chosen.isEmpty()) {
                            TagManager tm = TagManager.getInstance();
                            for (File f : finalTargets)
                                for (String t : chosen) tm.addTag(f, t);
                            cells.forEach(java.awt.Component::repaint);
                        }
                    } catch (Exception ex) { ex.printStackTrace(); }
                }
            };
            sw.execute();
        });
        menu.add(autoTagAI);

        menu.show(cell, x, y);
    }

    // ── Drag-and-drop ─────────────────────────────────────────────────────────

    private void setupDragAndDrop() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override
            public boolean importData(TransferSupport s) {
                if (!canImport(s)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<File> dropped = (List<File>)
                        s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : dropped) {
                        if (f.isDirectory()) {
                            loadDirectory(f);
                            dropFolderListeners.forEach(l -> l.folderDropped(f));
                            return true;
                        }
                    }
                    List<File> imgs = new ArrayList<>();
                    for (File f : dropped) if (ImageLoader.isSupported(f)) imgs.add(f);
                    if (!imgs.isEmpty()) {
                        File parent = imgs.get(0).getParentFile();
                        boolean sameDir = imgs.stream().allMatch(f -> parent.equals(f.getParentFile()));
                        if (sameDir && imgs.size() > 1) {
                            loadDirectory(parent);
                            dropFolderListeners.forEach(l -> l.folderDropped(parent));
                        } else {
                            loadFiles(imgs);
                            openLis.forEach(l -> l.openRequested(imgs.get(0)));
                        }
                        return true;
                    }
                } catch (Exception ex) { ex.printStackTrace(); }
                return false;
            }
        };
        setTransferHandler(handler);
        grid.setTransferHandler(handler);
        scrollPane.setTransferHandler(handler);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Inner class: TagSelectionDialog
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Modal dialog for applying tags to one or more images.
     *
     * Layout:
     *  ┌──────────────────────────────────────────┐
     *  │  Existing Tags                           │
     *  │  ┌──────────────────────────────────┐   │
     *  │  │  ☑ vacation   (all files)        │   │
     *  │  │  ☐ nature                        │   │
     *  │  │  ☑ family     (italic=partial)   │   │
     *  │  └──────────────────────────────────┘   │
     *  │  New Tag                                 │
     *  │  ┌─────────────────────┐  [+ Add]        │
     *  │  └─────────────────────┘                 │
     *  │                        [Apply] [Cancel]  │
     *  └──────────────────────────────────────────┘
     *
     * Checked  → tag added to every selected file
     * Unchecked (was originally checked) → tag removed from every selected file
     * New tag field → adds a brand-new tag (checkbox is created and pre-checked)
     */
    private static class TagSelectionDialog extends JDialog {

        private final JPanel            checkBoxPanel = new JPanel();
        private final JTextField        newTagField   = new JTextField(18);
        private final List<JCheckBox>   checkBoxes    = new ArrayList<>();
        private final Map<String, Boolean> initialState = new LinkedHashMap<>();
        private boolean confirmed = false;

        TagSelectionDialog(Window owner, List<File> files) {
            super(owner,
                  files.size() == 1
                      ? "Set Tags  –  " + files.get(0).getName()
                      : "Set Tags  –  " + files.size() + " files selected",
                  ModalityType.APPLICATION_MODAL);

            TagManager tm = TagManager.getInstance();

            // Which tags do ALL files have? (pre-tick these fully)
            Set<String> allHave = new HashSet<>(tm.getTags(files.get(0)));
            for (int i = 1; i < files.size(); i++) allHave.retainAll(tm.getTags(files.get(i)));

            // Which tags do SOME files have? (pre-tick italic, tooltip)
            Set<String> someHave = new HashSet<>();
            for (File f : files) someHave.addAll(tm.getTags(f));

            List<String> libraryTags = tm.getAllTags();

            // ── Checkbox list ──────────────────────────────────────────────────
            checkBoxPanel.setLayout(new BoxLayout(checkBoxPanel, BoxLayout.Y_AXIS));
            checkBoxPanel.setOpaque(false);

            if (libraryTags.isEmpty()) {
                JLabel empty = new JLabel("  (no tags yet – add one below)");
                empty.setForeground(new Color(130, 130, 130));
                empty.setFont(empty.getFont().deriveFont(Font.ITALIC));
                checkBoxPanel.add(empty);
            } else {
                for (String tag : libraryTags) {
                    addCheckBox(tag, allHave.contains(tag),
                                someHave.contains(tag) && !allHave.contains(tag));
                }
            }

            JScrollPane tagScroll = new JScrollPane(checkBoxPanel);
            tagScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            tagScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            int rows = Math.max(libraryTags.size(), 1);
            tagScroll.setPreferredSize(new Dimension(300, Math.min(rows * 28 + 12, 260)));
            tagScroll.setBorder(new TitledBorder("Existing Tags"));

            // ── New tag row ────────────────────────────────────────────────────
            newTagField.putClientProperty("JTextField.placeholderText", "Type new tag name...");
            JButton addBtn = new JButton("+ Add");
            addBtn.setFocusPainted(false);
            addBtn.addActionListener(e -> commitNewTag());
            newTagField.addActionListener(e -> commitNewTag());

            JPanel newRow = new JPanel(new BorderLayout(6, 0));
            newRow.setBorder(new TitledBorder("New Tag"));
            newRow.add(newTagField, BorderLayout.CENTER);
            newRow.add(addBtn,      BorderLayout.EAST);

            // ── Buttons ────────────────────────────────────────────────────────
            JButton applyBtn  = new JButton("Apply");
            JButton cancelBtn = new JButton("Cancel");
            applyBtn.setPreferredSize(new Dimension(80, 28));
            cancelBtn.setPreferredSize(new Dimension(80, 28));
            applyBtn.addActionListener(e -> { confirmed = true; dispose(); });
            cancelBtn.addActionListener(e -> dispose());
            getRootPane().setDefaultButton(applyBtn);

            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            btnRow.add(applyBtn); btnRow.add(cancelBtn);

            // ── Assemble ───────────────────────────────────────────────────────
            JPanel center = new JPanel(new BorderLayout(0, 8));
            center.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
            center.add(tagScroll, BorderLayout.CENTER);
            center.add(newRow,    BorderLayout.SOUTH);

            setLayout(new BorderLayout());
            add(center,  BorderLayout.CENTER);
            add(btnRow,  BorderLayout.SOUTH);

            pack();
            setMinimumSize(new Dimension(320, 220));
            setResizable(true);
            setLocationRelativeTo(owner);
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        /** Add a checkbox row; italic = only some of the selected files have this tag. */
        private void addCheckBox(String tag, boolean checked, boolean partial) {
            JCheckBox cb = new JCheckBox(tag, checked);
            if (partial) {
                cb.setFont(cb.getFont().deriveFont(Font.ITALIC));
                cb.setToolTipText("Some (not all) of the selected files already have this tag");
                cb.setForeground(new Color(180, 160, 100));
            }
            cb.setOpaque(false);
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            checkBoxes.add(cb);
            initialState.put(tag, checked);
            checkBoxPanel.add(cb);
        }

        /** Called when user clicks "+ Add" or presses Enter in the new-tag field. */
        private void commitNewTag() {
            String raw = newTagField.getText().trim().toLowerCase();
            if (raw.isEmpty()) return;

            // If it already exists in the list, just tick it
            for (JCheckBox cb : checkBoxes) {
                if (cb.getText().equalsIgnoreCase(raw)) {
                    cb.setSelected(true);
                    newTagField.setText("");
                    return;
                }
            }

            // Otherwise create a new pre-checked box
            addCheckBox(raw, true, false);
            checkBoxPanel.revalidate();
            checkBoxPanel.repaint();
            // Scroll to bottom to reveal the new item
            SwingUtilities.invokeLater(() -> {
                JScrollBar vsb = ((JScrollPane) checkBoxPanel.getParent().getParent())
                    .getVerticalScrollBar();
                vsb.setValue(vsb.getMaximum());
            });
            newTagField.setText("");
            pack(); // resize if needed
        }

        // ── Result accessors ──────────────────────────────────────────────────

        boolean isConfirmed() { return confirmed; }

        /** Tags the user wants to ADD (currently checked). */
        List<String> getCheckedTags() {
            List<String> result = new ArrayList<>();
            for (JCheckBox cb : checkBoxes) if (cb.isSelected()) result.add(cb.getText());
            return result;
        }

        /**
         * Tags the user wants to REMOVE:
         * those that were originally checked but have since been unchecked.
         */
        List<String> getUncheckedTags() {
            List<String> result = new ArrayList<>();
            for (JCheckBox cb : checkBoxes) {
                Boolean was = initialState.get(cb.getText());
                if (was != null && was && !cb.isSelected()) result.add(cb.getText());
            }
            return result;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WrapLayout – FlowLayout that wraps into multiple rows
    // ═════════════════════════════════════════════════════════════════════════
    public static class WrapLayout extends FlowLayout {
        public WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

        @Override public Dimension preferredLayoutSize(Container t) { return layout(t, true); }
        @Override public Dimension minimumLayoutSize(Container t) {
            Dimension d = layout(t, false); d.width -= (getHgap() + 1); return d;
        }

        private Dimension layout(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int tw = 0;
                Container p = target.getParent();
                if (p instanceof JViewport) tw = p.getWidth();
                if (tw == 0) tw = target.getSize().width;
                if (tw == 0) tw = Integer.MAX_VALUE;

                int hg = getHgap(), vg = getVgap();
                Insets ins = target.getInsets();
                int maxW = tw - ins.left - ins.right - hg * 2;
                int x = 0, y = ins.top + vg, rowH = 0;
                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component c = target.getComponent(i);
                    if (!c.isVisible()) continue;
                    Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                    if (x == 0 || x + d.width <= maxW) { x += d.width + hg; rowH = Math.max(rowH, d.height); }
                    else { y += rowH + vg; x = d.width + hg; rowH = d.height; }
                }
                y += rowH + vg + ins.bottom;
                return new Dimension(tw, y);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ScrollablePanel – forces panel width == viewport width (enables wrapping)
    // ═════════════════════════════════════════════════════════════════════════
    private static class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel(LayoutManager lm) { super(lm); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d)  { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(r.height, 20); }
        @Override public boolean getScrollableTracksViewportWidth()  { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}

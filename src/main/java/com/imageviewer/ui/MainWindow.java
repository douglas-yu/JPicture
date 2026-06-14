package com.imageviewer.ui;

import com.imageviewer.core.AutoTagger;
import com.imageviewer.core.ImageLoader;
import com.imageviewer.core.TagManager;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Main application window.
 *
 * v4 additions:
 *  • Search tab backed by SearchPanel
 *  • Tools menu with "Auto-tag" variants (statistical-only + AI + batch)
 *  • Progress dialog for batch auto-tagging
 *  • All prior features (DnD, dark/light, slideshow, big menus) retained
 */
public class MainWindow extends JFrame {

    private final FileNavigatorPanel    fileNav;
    private final ThumbnailBrowserPanel thumbBrowser;
    private final ImageViewerPanel      imageViewer;
    private final MetadataPanel         metaPanel;
    private final TagPanel              tagPanel;
    private final StatusBar             statusBar;
    private final SearchPanel           searchPanel;
    private final JTabbedPane           centerTabs;

    private final JButton       btnZoomIn, btnZoomOut, btnFit, btnActual;
    private final JButton       btnRotCW, btnRotCCW, btnPrev, btnNext;
    private final JToggleButton btnSlideshow;
    private final JSpinner      slideshowInterval;

    // ── Search toolbar widgets (persistent top bar) ───────────────────────────
    private final JTextField  tfSearch   = new JTextField(20);
    private final JButton     btnGoSearch = new JButton("Search");

    private List<File> currentFileList = new ArrayList<>();
    private Timer      slideshowTimer;

    public MainWindow() {
        super("Java Image Viewer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1440, 900);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(900, 600));

        fileNav      = new FileNavigatorPanel();
        thumbBrowser = new ThumbnailBrowserPanel();
        imageViewer  = new ImageViewerPanel();
        metaPanel    = new MetadataPanel();
        tagPanel     = new TagPanel();
        statusBar    = new StatusBar();
        searchPanel  = new SearchPanel();

        // ── Toolbar buttons ───────────────────────────────────────────────────
        btnZoomIn    = toolBtn("+",        "Zoom In (+)");
        btnZoomOut   = toolBtn("-",        "Zoom Out (-)");
        btnFit       = toolBtn("Fit",      "Fit to Window (F)");
        btnActual    = toolBtn("1:1",      "Actual Size (1)");
        btnRotCW     = toolBtn("Rot CW",   "Rotate CW (R)");
        btnRotCCW    = toolBtn("Rot CCW",  "Rotate CCW (E)");
        btnPrev      = toolBtn("< Prev",   "Previous image (←)");
        btnNext      = toolBtn("Next >",   "Next image (→)");
        btnSlideshow = new JToggleButton("Slideshow");
        btnSlideshow.setFocusPainted(false);
        slideshowInterval = new JSpinner(new SpinnerNumberModel(3, 1, 60, 1));
        slideshowInterval.setPreferredSize(new Dimension(52, 26));

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(btnPrev);    toolbar.add(btnNext);    toolbar.addSeparator();
        toolbar.add(btnZoomIn);  toolbar.add(btnZoomOut);
        toolbar.add(btnFit);     toolbar.add(btnActual);  toolbar.addSeparator();
        toolbar.add(btnRotCCW);  toolbar.add(btnRotCW);   toolbar.addSeparator();
        toolbar.add(btnSlideshow);
        toolbar.add(new JLabel("  sec:")); toolbar.add(slideshowInterval);

        // ── Quick-search bar (always visible, right side of toolbar) ──────────
        toolbar.addSeparator();
        tfSearch.putClientProperty("JTextField.placeholderText", "Quick search…");
        tfSearch.setMaximumSize(new Dimension(220, 28));
        btnGoSearch.setFocusPainted(false);
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(new JLabel(" 🔍 "));
        toolbar.add(tfSearch);
        toolbar.add(btnGoSearch);

        // ── Center tabs ───────────────────────────────────────────────────────
        centerTabs = new JTabbedPane();
        centerTabs.addTab("Thumbnails", thumbBrowser);
        centerTabs.addTab("Viewer",     imageViewer);
        centerTabs.addTab("Search",     searchPanel);

        // ── Right info panel ──────────────────────────────────────────────────
        JTabbedPane rightTabs = new JTabbedPane(JTabbedPane.TOP);
        rightTabs.addTab("Metadata", metaPanel);
        rightTabs.addTab("Tags",     tagPanel);
        rightTabs.setPreferredSize(new Dimension(320, 0));

        // ── Layout ────────────────────────────────────────────────────────────
        JSplitPane leftSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileNav, centerTabs);
        leftSplit.setDividerLocation(220);
        leftSplit.setDividerSize(5);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, rightTabs);
        mainSplit.setDividerLocation(1100);
        mainSplit.setDividerSize(5);
        mainSplit.setResizeWeight(1.0);

        setLayout(new BorderLayout());
        add(toolbar,   BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        setJMenuBar(buildMenuBar());
        wireEvents();
        setupWindowDragAndDrop();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Directory / file loading
    // ─────────────────────────────────────────────────────────────────────────

    public void loadDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        thumbBrowser.loadDirectory(dir);
        currentFileList = ImageLoader.getImageFiles(dir, true);
        imageViewer.setFileList(currentFileList);
        searchPanel.setSearchRoots(currentFileList);
        setTitle("Java Image Viewer  –  " + dir.getAbsolutePath());
        centerTabs.setSelectedComponent(thumbBrowser);
    }

    public void openFile(File file) {
        if (file == null || !file.isFile()) return;
        imageViewer.loadImage(file);
        metaPanel.loadFile(file);
        tagPanel.setCurrentFile(file);
        centerTabs.setSelectedComponent(imageViewer);
        statusBar.update(file, 0, 0, imageViewer.getZoom());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event wiring
    // ─────────────────────────────────────────────────────────────────────────
    private void wireEvents() {
        fileNav.addDirSelectedListener(this::loadDirectory);

        thumbBrowser.addDropFolderListener(dir -> {
            fileNav.navigateTo(dir);
            currentFileList = ImageLoader.getImageFiles(dir, true);
            imageViewer.setFileList(currentFileList);
            searchPanel.setSearchRoots(currentFileList);
            setTitle("Java Image Viewer  –  " + dir.getAbsolutePath());
        });

        thumbBrowser.addSelectionListener(selected -> {
            if (!selected.isEmpty()) {
                File f = selected.get(0);
                metaPanel.loadFile(f);
                tagPanel.setCurrentFile(f);
                statusBar.update(f, 0, 0, 0);
            }
        });

        thumbBrowser.addOpenListener(file -> {
            imageViewer.loadImage(file);
            metaPanel.loadFile(file);
            tagPanel.setCurrentFile(file);
            centerTabs.setSelectedComponent(imageViewer);
            statusBar.update(file, 0, 0, imageViewer.getZoom());
        });

        imageViewer.addNavListener(this::navigateViewer);

        btnZoomIn.addActionListener(e  -> { imageViewer.zoomIn();      statusBar.setZoom(imageViewer.getZoom()); centerTabs.setSelectedComponent(imageViewer); });
        btnZoomOut.addActionListener(e -> { imageViewer.zoomOut();     statusBar.setZoom(imageViewer.getZoom()); centerTabs.setSelectedComponent(imageViewer); });
        btnFit.addActionListener(e     -> { imageViewer.fitToWindow(); imageViewer.repaint(); statusBar.setZoom(imageViewer.getZoom()); });
        btnActual.addActionListener(e  -> { imageViewer.actualSize();  statusBar.setZoom(imageViewer.getZoom()); });
        btnRotCW.addActionListener(e   -> imageViewer.rotate(90));
        btnRotCCW.addActionListener(e  -> imageViewer.rotate(-90));
        btnPrev.addActionListener(e    -> navigateViewer(-1));
        btnNext.addActionListener(e    -> navigateViewer(+1));
        btnSlideshow.addActionListener(e -> { if (btnSlideshow.isSelected()) startSlideshow(); else stopSlideshow(); });

        tagPanel.addFilterListener(tag -> thumbBrowser.setTagFilter(tag));

        // Search panel: open a result in viewer
        searchPanel.addOpenListener(file -> {
            imageViewer.loadImage(file);
            metaPanel.loadFile(file);
            tagPanel.setCurrentFile(file);
            centerTabs.setSelectedComponent(imageViewer);
            statusBar.update(file, 0, 0, imageViewer.getZoom());
        });

        // Quick-search bar: jump to Search tab and trigger
        ActionListener quickSearch = e -> {
            String q = tfSearch.getText().trim();
            if (q.isEmpty()) { centerTabs.setSelectedComponent(searchPanel); return; }
            centerTabs.setSelectedComponent(searchPanel);
            // Pass keyword to search panel by simulating a search
            searchPanel.searchByKeyword(q);
        };
        btnGoSearch.addActionListener(quickSearch);
        tfSearch.addActionListener(quickSearch);

        // ← → keys switch to viewer tab
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED &&
                (e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_RIGHT))
                centerTabs.setSelectedComponent(imageViewer);
            return false;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Window-level DnD
    // ─────────────────────────────────────────────────────────────────────────
    private void setupWindowDragAndDrop() {
        TransferHandler handler = new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override public boolean importData(TransferSupport s) {
                if (!canImport(s)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<File> dropped = (List<File>)
                        s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : dropped) {
                        if (f.isDirectory()) {
                            loadDirectory(f); fileNav.navigateTo(f); return true;
                        }
                    }
                    List<File> imgs = new ArrayList<>();
                    for (File f : dropped) if (ImageLoader.isSupported(f)) imgs.add(f);
                    if (!imgs.isEmpty()) {
                        File parent = imgs.get(0).getParentFile();
                        boolean same = imgs.stream().allMatch(f -> parent.equals(f.getParentFile()));
                        if (same) { loadDirectory(parent); fileNav.navigateTo(parent); }
                        else      { thumbBrowser.loadFiles(imgs); currentFileList = imgs; imageViewer.setFileList(imgs); searchPanel.setSearchRoots(imgs); }
                        openFile(imgs.get(0));
                        return true;
                    }
                } catch (Exception ex) { ex.printStackTrace(); }
                return false;
            }
        };
        setTransferHandler(handler);
        ((JComponent) getContentPane()).setTransferHandler(handler);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation / slideshow
    // ─────────────────────────────────────────────────────────────────────────
    private void navigateViewer(int delta) {
        File current = imageViewer.getCurrentFile();
        List<File> list = currentFileList;
        if (list.isEmpty()) return;
        int idx = list.indexOf(current);
        if (idx < 0) idx = 0;
        else idx = (idx + delta + list.size()) % list.size();
        File next = list.get(idx);
        imageViewer.loadImage(next);
        metaPanel.loadFile(next);
        tagPanel.setCurrentFile(next);
        statusBar.update(next, 0, 0, imageViewer.getZoom());
    }

    private void startSlideshow() {
        int sec = (int) slideshowInterval.getValue();
        slideshowTimer = new Timer("slideshow", true);
        slideshowTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                SwingUtilities.invokeLater(() -> { navigateViewer(+1); centerTabs.setSelectedComponent(imageViewer); });
            }
        }, sec * 1000L, sec * 1000L);
    }

    private void stopSlideshow() {
        if (slideshowTimer != null) { slideshowTimer.cancel(); slideshowTimer = null; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-tag helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Auto-tag the current single file (statistical only, instant). */
    private void autoTagCurrent(boolean useAI) {
        File f = imageViewer.getCurrentFile();
        if (f == null || !f.isFile()) {
            JOptionPane.showMessageDialog(this, "No image is open in the viewer.",
                "Auto-Tag", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (useAI) {
            // Warn about first-time download
            if (!AutoTagger.isDJLAvailable()) {
                int ok = JOptionPane.showConfirmDialog(this,
                    "<html><b>AI Auto-Tag</b> requires a one-time download of model files.<br>" +
                    "Estimated size: ~250 MB (cached in ~/.djl.ai/ after first use).<br><br>" +
                    "Continue?</html>",
                    "AI Model Download", JOptionPane.YES_NO_OPTION);
                if (ok != JOptionPane.YES_OPTION) return;
            }
        }

        SwingWorker<Set<String>, Void> sw = new SwingWorker<>() {
            @Override protected Set<String> doInBackground() throws Exception {
                return AutoTagger.autoTag(f, useAI);
            }
            @Override protected void done() {
                try {
                    Set<String> detected = get();
                    Set<String> chosen   = AutoTagger.showPreviewDialog(MainWindow.this, f, detected);
                    if (chosen != null && !chosen.isEmpty()) {
                        TagManager tm = TagManager.getInstance();
                        for (String t : chosen) tm.addTag(f, t);
                        tagPanel.setCurrentFile(f);   // refresh tag panel
                        thumbBrowser.repaint();
                        JOptionPane.showMessageDialog(MainWindow.this,
                            chosen.size() + " tag(s) applied to " + f.getName(),
                            "Auto-Tag Done", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(MainWindow.this,
                        "Auto-tag failed: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        sw.execute();
    }

    /** Batch auto-tag all images in the current directory. */
    private void autoTagDirectory(boolean useAI) {
        if (currentFileList.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No images loaded. Open a directory first.",
                "Auto-Tag", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (useAI && !AutoTagger.isDJLAvailable()) {
            int ok = JOptionPane.showConfirmDialog(this,
                "<html><b>AI Auto-Tag</b> requires a one-time download of model files (~250 MB).<br>Continue?</html>",
                "AI Model Download", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) return;
        }

        // Build progress dialog
        JProgressBar bar = new JProgressBar(0, currentFileList.size());
        bar.setStringPainted(true);
        JLabel lbl = new JLabel("Starting…");
        lbl.setFont(lbl.getFont().deriveFont(11f));
        JPanel content = new JPanel(new BorderLayout(0,6));
        content.setBorder(BorderFactory.createEmptyBorder(12,16,12,16));
        content.add(new JLabel("<html><b>Auto-tagging " + currentFileList.size() + " images…</b></html>"),
            BorderLayout.NORTH);
        content.add(bar, BorderLayout.CENTER);
        content.add(lbl, BorderLayout.SOUTH);

        JDialog dlg = new JDialog(this, "Auto-Tagging Progress", false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dlg.add(content);
        dlg.setSize(400, 120);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);

        List<File> files = new ArrayList<>(currentFileList);
        AutoTagger.batchAutoTag(files, useAI, true, new AutoTagger.ProgressCallback() {
            @Override public void onProgress(int cur, int tot, String name) {
                SwingUtilities.invokeLater(() -> {
                    bar.setValue(cur);
                    lbl.setText("[" + cur + "/" + tot + "]  " + name);
                });
            }
            @Override public void onComplete(int tagged, int tot) {
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    thumbBrowser.repaint();
                    JOptionPane.showMessageDialog(MainWindow.this,
                        "Done!  Tagged " + tagged + " of " + tot + " images.",
                        "Auto-Tag Complete", JOptionPane.INFORMATION_MESSAGE);
                });
            }
            @Override public void onError(String msg) {
                SwingUtilities.invokeLater(() -> lbl.setText("⚠ " + msg));
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Menu bar
    // ─────────────────────────────────────────────────────────────────────────
    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        // ── File ──────────────────────────────────────────────────────────────
        JMenu file = new JMenu("File");
        file.setMnemonic('F');

        JMenuItem openDir = new JMenuItem("Open Directory…");
        openDir.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openDir.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(System.getProperty("user.home"));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                loadDirectory(fc.getSelectedFile());
                fileNav.navigateTo(fc.getSelectedFile());
            }
        });

        JMenuItem openFile = new JMenuItem("Open File…");
        openFile.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        openFile.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(System.getProperty("user.home"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                openFile(fc.getSelectedFile());
        });

        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));

        file.add(openDir); file.add(openFile); file.addSeparator(); file.add(exit);

        // ── View ──────────────────────────────────────────────────────────────
        JMenu view = new JMenu("View");
        JCheckBoxMenuItem toggleDark = new JCheckBoxMenuItem("Dark Theme", true);
        toggleDark.addActionListener(e -> {
            try {
                if (toggleDark.isSelected()) com.formdev.flatlaf.FlatDarkLaf.setup();
                else                         com.formdev.flatlaf.FlatLightLaf.setup();
                SwingUtilities.updateComponentTreeUI(this);
            } catch (Exception ex) { ex.printStackTrace(); }
        });
        view.add(toggleDark);
        view.addSeparator();
        JMenuItem fitItem = new JMenuItem("Fit to Window");
        fitItem.setAccelerator(KeyStroke.getKeyStroke('f'));
        fitItem.addActionListener(e -> { imageViewer.fitToWindow(); imageViewer.repaint(); });
        view.add(fitItem);
        JMenuItem actualItem = new JMenuItem("Actual Size");
        actualItem.setAccelerator(KeyStroke.getKeyStroke('1'));
        actualItem.addActionListener(e -> imageViewer.actualSize());
        view.add(actualItem);

        // ── Tools ─────────────────────────────────────────────────────────────
        JMenu tools = new JMenu("Tools");

        JMenuItem tagCurrent     = new JMenuItem("Auto-tag Current Image  (Statistical)");
        JMenuItem tagCurrentAI   = new JMenuItem("Auto-tag Current Image  (AI + Statistical)");
        JMenuItem tagDirStat     = new JMenuItem("Auto-tag All in Directory  (Statistical)");
        JMenuItem tagDirAI       = new JMenuItem("Auto-tag All in Directory  (AI + Statistical)");
        JMenuItem openSearch     = new JMenuItem("Open Search Tab");
        openSearch.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));

        tagCurrent.addActionListener(e   -> autoTagCurrent(false));
        tagCurrentAI.addActionListener(e -> autoTagCurrent(true));
        tagDirStat.addActionListener(e   -> autoTagDirectory(false));
        tagDirAI.addActionListener(e     -> autoTagDirectory(true));
        openSearch.addActionListener(e   -> centerTabs.setSelectedComponent(searchPanel));

        tools.add(tagCurrent);
        tools.add(tagCurrentAI);
        tools.addSeparator();
        tools.add(tagDirStat);
        tools.add(tagDirAI);
        tools.addSeparator();
        tools.add(openSearch);

        // ── Help ──────────────────────────────────────────────────────────────
        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
            "<html><b>Java Image Viewer</b> v1.0.0<br><br>" +
            "Drag &amp; drop folders or images anywhere on the window.<br><br>" +
            "Formats: JPEG · PNG · GIF · BMP · TIFF · WebP · PSD · HDR · ICNS · PCX · PNM<br>" +
            "Metadata: EXIF · GPS · IPTC · XMP<br>" +
            "AI Auto-tag: DJL + PyTorch SSD (downloads ~250 MB on first use)<br>" +
            "Statistical auto-tag: instant, no download<br>" +
            "Tags stored in: ~/.imageviewer/tags.json<br><br>" +
            "Keys: + / - zoom &nbsp; F fit &nbsp; 1 actual &nbsp; R/E rotate<br>" +
            "      ← → navigate &nbsp; Ctrl+F search</html>",
            "About", JOptionPane.INFORMATION_MESSAGE));
        help.add(about);

        bar.add(file); bar.add(view); bar.add(tools); bar.add(help);
        return bar;
    }

    private JButton toolBtn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        return b;
    }
}

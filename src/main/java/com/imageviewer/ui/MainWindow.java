package com.imageviewer.ui;

import com.imageviewer.core.ImageLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Main application window.
 *
 * New in this version:
 *  • Window-level drag-and-drop (folders and image files accepted anywhere)
 *  • loadDirectory() helper centralises all "change directory" logic
 *  • Bigger menu fonts configured in Main.java via UIManager
 */
public class MainWindow extends JFrame {

    private final FileNavigatorPanel    fileNav;
    private final ThumbnailBrowserPanel thumbBrowser;
    private final ImageViewerPanel      imageViewer;
    private final MetadataPanel         metaPanel;
    private final TagPanel              tagPanel;
    private final StatusBar             statusBar;
    private final JTabbedPane           centerTabs;

    private final JButton       btnZoomIn, btnZoomOut, btnFit, btnActual;
    private final JButton       btnRotCW, btnRotCCW, btnPrev, btnNext;
    private final JToggleButton btnSlideshow;
    private final JSpinner      slideshowInterval;

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

        // ── Toolbar ───────────────────────────────────────────────────────────
        btnZoomIn    = toolBtn("+",       "Zoom In (+)");
        btnZoomOut   = toolBtn("-",       "Zoom Out (-)");
        btnFit       = toolBtn("Fit",     "Fit to Window (F)");
        btnActual    = toolBtn("1:1",     "Actual Size (1)");
        btnRotCW     = toolBtn("Rot CW",  "Rotate CW (R)");
        btnRotCCW    = toolBtn("Rot CCW", "Rotate CCW (E)");
        btnPrev      = toolBtn("< Prev",  "Previous image (Left)");
        btnNext      = toolBtn("Next >",  "Next image (Right)");
        btnSlideshow = new JToggleButton("Slideshow");
        btnSlideshow.setFocusPainted(false);
        slideshowInterval = new JSpinner(new SpinnerNumberModel(3, 1, 60, 1));
        slideshowInterval.setPreferredSize(new Dimension(52, 26));

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(btnPrev);   toolbar.add(btnNext);   toolbar.addSeparator();
        toolbar.add(btnZoomIn); toolbar.add(btnZoomOut);
        toolbar.add(btnFit);    toolbar.add(btnActual); toolbar.addSeparator();
        toolbar.add(btnRotCCW); toolbar.add(btnRotCW); toolbar.addSeparator();
        toolbar.add(btnSlideshow);
        toolbar.add(new JLabel("  sec:")); toolbar.add(slideshowInterval);

        // ── Center tabs ───────────────────────────────────────────────────────
        centerTabs = new JTabbedPane();
        centerTabs.addTab("Thumbnails", thumbBrowser);
        centerTabs.addTab("Viewer",     imageViewer);

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
        setupWindowDragAndDrop();   // ← window-level DnD
    }

    // ── Central directory-load helper ─────────────────────────────────────────
    /**
     * Single method that loads a directory everywhere in the app.
     * Called from: file navigator, menu open, and all drag-and-drop paths.
     */
    public void loadDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        thumbBrowser.loadDirectory(dir);
        currentFileList = ImageLoader.getImageFiles(dir, true);
        imageViewer.setFileList(currentFileList);
        setTitle("Java Image Viewer  –  " + dir.getAbsolutePath());
        centerTabs.setSelectedComponent(thumbBrowser);
    }

    /** Open a single image file directly in the viewer. */
    public void openFile(File file) {
        if (file == null || !file.isFile()) return;
        imageViewer.loadImage(file);
        metaPanel.loadFile(file);
        tagPanel.setCurrentFile(file);
        centerTabs.setSelectedComponent(imageViewer);
        statusBar.update(file, 0, 0, imageViewer.getZoom());
    }

    // ── Event wiring ──────────────────────────────────────────────────────────
    private void wireEvents() {
        // File navigator tree click → load directory
        fileNav.addDirSelectedListener(this::loadDirectory);

        // Thumbnail panel reports a dropped folder
        thumbBrowser.addDropFolderListener(dir -> {
            fileNav.navigateTo(dir);
            currentFileList = ImageLoader.getImageFiles(dir, true);
            imageViewer.setFileList(currentFileList);
            setTitle("Java Image Viewer  –  " + dir.getAbsolutePath());
        });

        // Single-click thumbnail → update info panels
        thumbBrowser.addSelectionListener(selected -> {
            if (!selected.isEmpty()) {
                File f = selected.get(0);
                metaPanel.loadFile(f);
                tagPanel.setCurrentFile(f);
                statusBar.update(f, 0, 0, 0);
            }
        });

        // Double-click thumbnail → open in viewer
        thumbBrowser.addOpenListener(file -> {
            imageViewer.loadImage(file);
            metaPanel.loadFile(file);
            tagPanel.setCurrentFile(file);
            centerTabs.setSelectedComponent(imageViewer);
            statusBar.update(file, 0, 0, imageViewer.getZoom());
        });

        // Viewer keyboard navigation ← →
        imageViewer.addNavListener(this::navigateViewer);

        // Toolbar buttons
        btnZoomIn.addActionListener(e  -> { imageViewer.zoomIn();  statusBar.setZoom(imageViewer.getZoom()); centerTabs.setSelectedComponent(imageViewer); });
        btnZoomOut.addActionListener(e -> { imageViewer.zoomOut(); statusBar.setZoom(imageViewer.getZoom()); centerTabs.setSelectedComponent(imageViewer); });
        btnFit.addActionListener(e     -> { imageViewer.fitToWindow(); imageViewer.repaint(); statusBar.setZoom(imageViewer.getZoom()); });
        btnActual.addActionListener(e  -> { imageViewer.actualSize(); statusBar.setZoom(imageViewer.getZoom()); });
        btnRotCW.addActionListener(e   -> imageViewer.rotate(90));
        btnRotCCW.addActionListener(e  -> imageViewer.rotate(-90));
        btnPrev.addActionListener(e    -> navigateViewer(-1));
        btnNext.addActionListener(e    -> navigateViewer(+1));
        btnSlideshow.addActionListener(e -> { if (btnSlideshow.isSelected()) startSlideshow(); else stopSlideshow(); });

        // Tag panel filter
        tagPanel.addFilterListener(tag -> thumbBrowser.setTagFilter(tag));

        // When user presses ←/→ anywhere, switch to viewer tab
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED &&
                    (e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_RIGHT))
                centerTabs.setSelectedComponent(imageViewer);
            return false;
        });
    }

    // ── Window-level drag-and-drop ────────────────────────────────────────────
    /**
     * Attaches a TransferHandler to the JFrame so that files or folders can be
     * dropped anywhere on the window (title bar excluded – OS limitation).
     */
    private void setupWindowDragAndDrop() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<File> dropped = (List<File>)
                        support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);

                    // Directory → load thumbnails
                    for (File f : dropped) {
                        if (f.isDirectory()) {
                            loadDirectory(f);
                            fileNav.navigateTo(f);
                            return true;
                        }
                    }

                    // Image files
                    List<File> imgs = new ArrayList<>();
                    for (File f : dropped) if (ImageLoader.isSupported(f)) imgs.add(f);
                    if (!imgs.isEmpty()) {
                        // If all from same parent, load the whole directory for context
                        File parent = imgs.get(0).getParentFile();
                        boolean sameDir = imgs.stream().allMatch(f -> parent.equals(f.getParentFile()));
                        if (sameDir) {
                            loadDirectory(parent);
                            fileNav.navigateTo(parent);
                        } else {
                            thumbBrowser.loadFiles(imgs);
                            currentFileList = imgs;
                            imageViewer.setFileList(currentFileList);
                        }
                        // Open first image in viewer
                        openFile(imgs.get(0));
                        return true;
                    }
                } catch (Exception ex) { ex.printStackTrace(); }
                return false;
            }
        };

        // Apply to frame and its glass pane for maximum coverage
        setTransferHandler(handler);
        getGlassPane().setVisible(false); // keep glass pane invisible but register handler
        ((JComponent) getContentPane()).setTransferHandler(handler);
    }

    // ── Navigation ────────────────────────────────────────────────────────────
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

    // ── Menu bar ──────────────────────────────────────────────────────────────
    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        // File menu
        JMenu file = new JMenu("File");
        file.setMnemonic('F');

        JMenuItem openDir = new JMenuItem("Open Directory...");
        openDir.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openDir.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(System.getProperty("user.home"));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File dir = fc.getSelectedFile();
                loadDirectory(dir);
                fileNav.navigateTo(dir);
            }
        });

        JMenuItem openFile = new JMenuItem("Open File...");
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

        // View menu
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

        // Help menu
        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
            "<html><b>Java Image Viewer</b> v1.0.0<br><br>" +
            "Drag &amp; drop folders or images anywhere on the window.<br><br>" +
            "Formats: JPEG · PNG · GIF · BMP · TIFF · WebP · PSD · HDR · ICNS · PCX · PNM<br>" +
            "Metadata: EXIF · GPS · IPTC · XMP<br>" +
            "Tags stored in: ~/.imageviewer/tags.json<br><br>" +
            "Keys: + / - zoom &nbsp; F fit &nbsp; 1 actual &nbsp; R/E rotate &nbsp; &larr;&rarr; navigate</html>",
            "About", JOptionPane.INFORMATION_MESSAGE));
        help.add(about);

        bar.add(file); bar.add(view); bar.add(help);
        return bar;
    }

    private JButton toolBtn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        return b;
    }
}

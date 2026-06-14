package com.imageviewer.ui;

import com.imageviewer.core.ImageLoader;
import com.imageviewer.core.MetadataUtil;
import com.imageviewer.core.TagManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * SearchPanel — full-featured image search across the loaded library.
 *
 * Search criteria (combinable):
 *   • Filename keyword  (case-insensitive substring)
 *   • Tags              (match any / match all)
 *   • File format       (JPEG, PNG, TIFF, WebP, GIF, BMP, ALL)
 *   • Date range        (from / to, leave blank to ignore)
 *   • EXIF camera model (substring)
 *
 * Results are displayed as a scrollable thumbnail list.
 * Clicking a result fires an OpenListener to open the image in the viewer.
 *
 * Search scope is set by the parent window via setSearchRoots().
 */
public class SearchPanel extends JPanel {

    public interface OpenListener { void openRequested(File file); }

    // ── Listeners ─────────────────────────────────────────────────────────────
    private final List<OpenListener> openListeners = new ArrayList<>();

    // ── Search root files  ────────────────────────────────────────────────────
    private List<File> searchRoots = new ArrayList<>();

    // ── UI controls ───────────────────────────────────────────────────────────
    private final JTextField  tfKeyword   = new JTextField();
    private final JTextField  tfCamera    = new JTextField();
    private final JTextField  tfDateFrom  = new JTextField(10);
    private final JTextField  tfDateTo    = new JTextField(10);
    private final JComboBox<String> cbFormat   = new JComboBox<>(
        new String[]{"All Formats","JPEG","PNG","GIF","BMP","TIFF","WebP","PSD","HEIC","RAW"});
    private final JComboBox<String> cbTagMode  = new JComboBox<>(
        new String[]{"Match ANY tag","Match ALL tags"});
    private final JList<String>   tagList;
    private final DefaultListModel<String> tagListModel = new DefaultListModel<>();

    private final JButton    btnSearch  = new JButton("🔍  Search");
    private final JButton    btnClear   = new JButton("Clear");
    private final JLabel     lblStatus  = new JLabel("Ready");

    // ── Results ────────────────────────────────────────────────────────────────
    private final JPanel                 resultsPanel = new JPanel();
    private final List<ResultCard>       resultCards  = new ArrayList<>();
    private final ExecutorService        thumbPool    = Executors.newFixedThreadPool(3);
    private final Map<File,BufferedImage> thumbCache  = Collections.synchronizedMap(
        new LinkedHashMap<File,BufferedImage>() {
            protected boolean removeEldestEntry(Map.Entry<File,BufferedImage> e) { return size() > 200; }
        });

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd");

    // ─────────────────────────────────────────────────────────────────────────
    public SearchPanel() {
        super(new BorderLayout(0, 0));

        tagList = new JList<>(tagListModel);
        tagList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tagList.setVisibleRowCount(6);
        tagList.setToolTipText("Ctrl-click to select multiple tags");

        // Register tag change listener to keep tag list up-to-date
        TagManager.getInstance().addListener(this::refreshTagList);

        add(buildLeftPane(),   BorderLayout.WEST);
        add(buildCenterPane(), BorderLayout.CENTER);
        add(buildStatusBar(),  BorderLayout.SOUTH);

        wireEvents();
        refreshTagList();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Set the pool of files that will be searched.  Call after loading a directory. */
    public void setSearchRoots(List<File> files) {
        this.searchRoots = new ArrayList<>(files);
        lblStatus.setText(files.size() + " images available for search");
        refreshTagList();
    }

    public void addOpenListener(OpenListener l) { openListeners.add(l); }

    /** Programmatically kick off a search for a given tag (called from TagPanel). */
    public void searchForTag(String tag) {
        // Select the tag in the list
        int idx = tagListModel.indexOf(tag);
        if (idx >= 0) tagList.setSelectedIndex(idx);
        tfKeyword.setText("");
        doSearch();
    }
    /** Called from the quick-search bar in the toolbar. */
    public void searchByKeyword(String keyword) {
        tfKeyword.setText(keyword);
        tagList.clearSelection();
        doSearch();
    }

    // ── Left pane: search criteria ────────────────────────────────────────────
    private JPanel buildLeftPane() {
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setPreferredSize(new Dimension(240, 0));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));

        // -- Keyword + format block -------------------------------------------
        JPanel topBlock = new JPanel(new GridBagLayout());
        topBlock.setBorder(new TitledBorder("Search Criteria"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3,3,3,3);
        gbc.fill   = GridBagConstraints.HORIZONTAL;

        // Keyword
        gbc.gridx=0; gbc.gridy=0; gbc.gridwidth=1; gbc.weightx=0;
        topBlock.add(new JLabel("Keyword:"), gbc);
        gbc.gridx=1; gbc.weightx=1;
        tfKeyword.putClientProperty("JTextField.placeholderText","filename, path...");
        topBlock.add(tfKeyword, gbc);

        // Camera
        gbc.gridx=0; gbc.gridy=1; gbc.weightx=0;
        topBlock.add(new JLabel("Camera:"), gbc);
        gbc.gridx=1; gbc.weightx=1;
        tfCamera.putClientProperty("JTextField.placeholderText","EXIF camera model...");
        topBlock.add(tfCamera, gbc);

        // Format
        gbc.gridx=0; gbc.gridy=2; gbc.weightx=0;
        topBlock.add(new JLabel("Format:"), gbc);
        gbc.gridx=1; gbc.weightx=1;
        topBlock.add(cbFormat, gbc);

        // Date from
        gbc.gridx=0; gbc.gridy=3; gbc.weightx=0;
        topBlock.add(new JLabel("Date from:"), gbc);
        gbc.gridx=1; gbc.weightx=1;
        tfDateFrom.putClientProperty("JTextField.placeholderText","yyyy-MM-dd");
        topBlock.add(tfDateFrom, gbc);

        // Date to
        gbc.gridx=0; gbc.gridy=4; gbc.weightx=0;
        topBlock.add(new JLabel("Date to:"), gbc);
        gbc.gridx=1; gbc.weightx=1;
        tfDateTo.putClientProperty("JTextField.placeholderText","yyyy-MM-dd");
        topBlock.add(tfDateTo, gbc);

        // -- Tag selection block -----------------------------------------------
        JPanel tagBlock = new JPanel(new BorderLayout(0,4));
        tagBlock.setBorder(new TitledBorder("Filter by Tags"));
        tagBlock.add(cbTagMode, BorderLayout.NORTH);
        JScrollPane tagScroll = new JScrollPane(tagList);
        tagScroll.setPreferredSize(new Dimension(0, 140));
        tagBlock.add(tagScroll, BorderLayout.CENTER);
        JButton clearTagSel = new JButton("Clear tag selection");
        clearTagSel.setFont(clearTagSel.getFont().deriveFont(10f));
        clearTagSel.addActionListener(e -> tagList.clearSelection());
        tagBlock.add(clearTagSel, BorderLayout.SOUTH);

        // -- Button row --------------------------------------------------------
        JPanel btnRow = new JPanel(new GridLayout(1,2,6,0));
        btnSearch.setPreferredSize(new Dimension(0,32));
        btnClear.setPreferredSize(new Dimension(0,32));
        btnRow.add(btnSearch);
        btnRow.add(btnClear);

        // -- Assemble left pane ------------------------------------------------
        JPanel inner = new JPanel(new BorderLayout(0,8));
        inner.add(topBlock,  BorderLayout.NORTH);
        inner.add(tagBlock,  BorderLayout.CENTER);
        inner.add(btnRow,    BorderLayout.SOUTH);
        left.add(inner, BorderLayout.CENTER);

        return left;
    }

    // ── Center pane: results ─────────────────────────────────────────────────
    private JScrollPane buildCenterPane() {
        resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
        resultsPanel.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        JLabel hint = new JLabel("Enter search criteria and click Search.");
        hint.setForeground(new Color(100,100,100));
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 13f));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        resultsPanel.add(hint);

        JScrollPane sp = new JScrollPane(resultsPanel);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    // ── Status bar ────────────────────────────────────────────────────────────
    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT,8,3));
        bar.setBorder(BorderFactory.createMatteBorder(1,0,0,0,new Color(60,60,60)));
        lblStatus.setFont(lblStatus.getFont().deriveFont(11f));
        bar.add(lblStatus);
        return bar;
    }

    // ── Events ────────────────────────────────────────────────────────────────
    private void wireEvents() {
        btnSearch.addActionListener(e -> doSearch());
        btnClear.addActionListener(e -> clearAll());

        // Enter key in any text field triggers search
        ActionListener enterSearch = e -> doSearch();
        tfKeyword.addActionListener(enterSearch);
        tfCamera.addActionListener(enterSearch);
        tfDateFrom.addActionListener(enterSearch);
        tfDateTo.addActionListener(enterSearch);
    }

    // ── Search logic ─────────────────────────────────────────────────────────
    private void doSearch() {
        String keyword  = tfKeyword.getText().trim().toLowerCase();
        String camera   = tfCamera.getText().trim().toLowerCase();
        String fmt      = (String) cbFormat.getSelectedItem();
        String dateFrom = tfDateFrom.getText().trim();
        String dateTo   = tfDateTo.getText().trim();
        List<String> selTags = tagList.getSelectedValuesList();
        boolean matchAll     = cbTagMode.getSelectedIndex() == 1;

        if (searchRoots.isEmpty()) {
            lblStatus.setText("No images loaded. Open a directory first.");
            return;
        }

        lblStatus.setText("Searching…");
        clearResults();

        // Parse date range
        Date dateFromParsed = parseDate(dateFrom);
        Date dateToParsed   = parseDate(dateTo);
        // If "to" date parsed, include the whole day
        if (dateToParsed != null) dateToParsed = new Date(dateToParsed.getTime() + 86_399_999L);

        // Capture for lambda
        final Date dfrom = dateFromParsed;
        final Date dto   = dateToParsed;

        List<File> roots = new ArrayList<>(searchRoots);

        SwingWorker<List<File>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<File> doInBackground() {
                List<File> matches = new ArrayList<>();
                TagManager tm = TagManager.getInstance();

                for (File f : roots) {
                    if (!f.isFile()) continue;

                    // ── Format filter ─────────────────────────────────────────
                    if (!"All Formats".equals(fmt)) {
                        String name = f.getName().toLowerCase();
                        boolean ok = false;
                        switch (fmt) {
                            case "JPEG":  ok = name.endsWith(".jpg") || name.endsWith(".jpeg"); break;
                            case "PNG":   ok = name.endsWith(".png");  break;
                            case "GIF":   ok = name.endsWith(".gif");  break;
                            case "BMP":   ok = name.endsWith(".bmp");  break;
                            case "TIFF":  ok = name.endsWith(".tif")  || name.endsWith(".tiff"); break;
                            case "WebP":  ok = name.endsWith(".webp"); break;
                            case "PSD":   ok = name.endsWith(".psd");  break;
                            case "HEIC":  ok = name.endsWith(".heic") || name.endsWith(".heif"); break;
                            case "RAW":   ok = name.endsWith(".cr2")  || name.endsWith(".cr3")
                                          || name.endsWith(".nef")    || name.endsWith(".arw")
                                          || name.endsWith(".dng")    || name.endsWith(".raf"); break;
                            default: ok = true;
                        }
                        if (!ok) continue;
                    }

                    // ── Keyword filter ────────────────────────────────────────
                    if (!keyword.isEmpty()) {
                        String path = f.getAbsolutePath().toLowerCase();
                        if (!path.contains(keyword)) continue;
                    }

                    // ── Tag filter ────────────────────────────────────────────
                    if (!selTags.isEmpty()) {
                        Set<String> fileTags = tm.getTags(f);
                        if (matchAll) {
                            if (!fileTags.containsAll(selTags)) continue;
                        } else {
                            boolean anyMatch = selTags.stream().anyMatch(fileTags::contains);
                            if (!anyMatch) continue;
                        }
                    }

                    // ── Date range filter ─────────────────────────────────────
                    if (dfrom != null || dto != null) {
                        Date fileDate = new Date(f.lastModified());
                        if (dfrom != null && fileDate.before(dfrom)) continue;
                        if (dto   != null && fileDate.after(dto))    continue;
                    }

                    // ── Camera / EXIF filter ──────────────────────────────────
                    if (!camera.isEmpty()) {
                        try {
                            String camInfo = MetadataUtil.getCameraModel(f);
                            if (camInfo == null || !camInfo.toLowerCase().contains(camera)) continue;
                        } catch (Exception ignored) { continue; }
                    }

                    matches.add(f);
                }
                return matches;
            }

            @Override
            protected void done() {
                try {
                    List<File> results = get();
                    showResults(results);
                    lblStatus.setText(results.size() + " result" +
                        (results.size() == 1 ? "" : "s") + " found" +
                        (results.size() == 500 ? " (showing first 500)" : ""));
                } catch (Exception e) {
                    lblStatus.setText("Search error: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void showResults(List<File> files) {
        resultsPanel.removeAll();
        resultCards.clear();

        if (files.isEmpty()) {
            JLabel none = new JLabel("No images match your criteria.");
            none.setForeground(new Color(120,120,120));
            none.setFont(none.getFont().deriveFont(Font.ITALIC));
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            resultsPanel.add(none);
            resultsPanel.revalidate(); resultsPanel.repaint();
            return;
        }

        // Limit to 500 to keep UI responsive
        List<File> limited = files.size() > 500 ? files.subList(0, 500) : files;

        for (File f : limited) {
            ResultCard card = new ResultCard(f);
            card.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2)
                        openListeners.forEach(l -> l.openRequested(f));
                    else {
                        // single click: highlight
                        resultCards.forEach(c -> c.setHighlighted(false));
                        card.setHighlighted(true);
                    }
                }
            });
            resultCards.add(card);
            resultsPanel.add(card);
            // Async thumbnail load
            thumbPool.submit(() -> {
                BufferedImage img = thumbCache.computeIfAbsent(f,
                    k -> ImageLoader.generateThumbnail(k, 80));
                SwingUtilities.invokeLater(() -> card.setThumbnail(img));
            });
        }
        resultsPanel.revalidate();
        resultsPanel.repaint();
    }

    private void clearResults() {
        resultsPanel.removeAll();
        resultCards.clear();
        resultsPanel.revalidate();
        resultsPanel.repaint();
    }

    private void clearAll() {
        tfKeyword.setText("");
        tfCamera.setText("");
        tfDateFrom.setText("");
        tfDateTo.setText("");
        cbFormat.setSelectedIndex(0);
        cbTagMode.setSelectedIndex(0);
        tagList.clearSelection();
        clearResults();
        lblStatus.setText("Cleared – enter new criteria and search.");
    }

    private void refreshTagList() {
        List<String> allTags = TagManager.getInstance().getAllTags();
        // Also collect tags from searchRoots
        SwingUtilities.invokeLater(() -> {
            List<String> selected = tagList.getSelectedValuesList();
            tagListModel.clear();
            for (String t : allTags) tagListModel.addElement(t);
            // Restore selection
            for (String s : selected) {
                int idx = tagListModel.indexOf(s);
                if (idx >= 0) tagList.addSelectionInterval(idx, idx);
            }
        });
    }

    private static Date parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return DATE_FMT.parse(s.trim()); } catch (Exception e) { return null; }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ResultCard – single row in the results list
    // ═════════════════════════════════════════════════════════════════════════
    private static class ResultCard extends JPanel {
        private final JLabel thumbLabel = new JLabel();
        private final JLabel nameLabel  = new JLabel();
        private final JLabel pathLabel  = new JLabel();
        private final JLabel tagLabel   = new JLabel();
        private final JLabel infoLabel  = new JLabel();
        private boolean highlighted     = false;

        private static final Color BG_NORMAL  = new Color(42, 42, 42);
        private static final Color BG_HOVER   = new Color(55, 55, 55);
        private static final Color BG_SELECT  = new Color(30, 80, 140);

        ResultCard(File file) {
            setLayout(new BorderLayout(8, 0));
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,0,1,0, new Color(60,60,60)),
                BorderFactory.createEmptyBorder(6,8,6,8)));
            setBackground(BG_NORMAL);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            // Thumbnail placeholder
            thumbLabel.setPreferredSize(new Dimension(70, 60));
            thumbLabel.setBorder(BorderFactory.createLineBorder(new Color(70,70,70)));
            thumbLabel.setHorizontalAlignment(SwingConstants.CENTER);
            thumbLabel.setText("…");

            // Text block
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
            nameLabel.setText(file.getName());
            nameLabel.setForeground(new Color(220,220,220));

            pathLabel.setFont(pathLabel.getFont().deriveFont(10f));
            pathLabel.setForeground(new Color(130,130,130));
            String parentPath = file.getParent() == null ? "" : file.getParent();
            pathLabel.setText(parentPath);

            // Tags
            Set<String> tags = TagManager.getInstance().getTags(file);
            tagLabel.setFont(tagLabel.getFont().deriveFont(Font.ITALIC, 11f));
            tagLabel.setForeground(new Color(100,170,100));
            tagLabel.setText(tags.isEmpty() ? "(no tags)" :
                tags.stream().collect(Collectors.joining("  •  ")));

            // File info
            long kb = file.length() / 1024;
            String dateStr = DATE_FMT.format(new Date(file.lastModified()));
            infoLabel.setFont(infoLabel.getFont().deriveFont(10f));
            infoLabel.setForeground(new Color(100,100,100));
            infoLabel.setText(kb + " KB  |  " + dateStr);

            JPanel textBlock = new JPanel();
            textBlock.setOpaque(false);
            textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
            textBlock.add(nameLabel);
            textBlock.add(Box.createVerticalStrut(2));
            textBlock.add(pathLabel);
            textBlock.add(Box.createVerticalStrut(2));
            textBlock.add(tagLabel);
            textBlock.add(Box.createVerticalStrut(1));
            textBlock.add(infoLabel);

            add(thumbLabel,  BorderLayout.WEST);
            add(textBlock,   BorderLayout.CENTER);

            // Hover effect
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (!highlighted) setBackground(BG_HOVER);
                }
                @Override public void mouseExited(MouseEvent e) {
                    if (!highlighted) setBackground(BG_NORMAL);
                }
            });
        }

        void setThumbnail(BufferedImage img) {
            if (img == null) return;
            int tw = 70, th = 60;
            double scale = Math.min(tw/(double)img.getWidth(), th/(double)img.getHeight());
            int nw = (int)(img.getWidth()*scale), nh = (int)(img.getHeight()*scale);
            Image scaled = img.getScaledInstance(nw, nh, Image.SCALE_SMOOTH);
            thumbLabel.setIcon(new ImageIcon(scaled));
            thumbLabel.setText("");
        }

        void setHighlighted(boolean h) {
            highlighted = h;
            setBackground(h ? BG_SELECT : BG_NORMAL);
            repaint();
        }
    }
}

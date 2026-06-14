package com.imageviewer.ui;

import com.imageviewer.core.MetadataUtil;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Tabbed metadata viewer.
 * FIX: switch expression replaced with plain if/else (Java 11 compat).
 */
public class MetadataPanel extends JPanel {

    private final JTabbedPane tabs;
    private final Map<String, MetaTable> tableMap = new LinkedHashMap<>();

    public MetadataPanel() {
        super(new BorderLayout());
        tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setFont(tabs.getFont().deriveFont(11f));
        add(tabs, BorderLayout.CENTER);
        setPreferredSize(new Dimension(320, 0));
    }

    public void loadFile(File file) {
        tabs.removeAll();
        tableMap.clear();
        if (file == null) return;

        SwingWorker<Map<String, List<String[]>>, Void> worker =
                new SwingWorker<Map<String, List<String[]>>, Void>() {
            @Override protected Map<String, List<String[]>> doInBackground() {
                return MetadataUtil.extractAll(file);
            }
            @Override protected void done() {
                try {
                    Map<String, List<String[]>> meta = get();
                    String[] order = {MetadataUtil.DIR_BASIC, MetadataUtil.DIR_EXIF,
                            MetadataUtil.DIR_GPS, MetadataUtil.DIR_IPTC,
                            MetadataUtil.DIR_XMP, MetadataUtil.DIR_OTHER};
                    for (String key : order) {
                        List<String[]> rows = meta.get(key);
                        if (rows != null && !rows.isEmpty()) addTab(key, rows);
                    }
                    meta.forEach((k, v) -> { if (!tableMap.containsKey(k)) addTab(k, v); });
                    if (tabs.getTabCount() > 0) tabs.setSelectedIndex(0);
                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }

    private void addTab(String name, List<String[]> rows) {
        MetaTable mt = new MetaTable(rows);
        tableMap.put(name, mt);

        JPanel wrap = new JPanel(new BorderLayout(0, 4));
        wrap.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search...");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { mt.filter(search.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { mt.filter(search.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { mt.filter(search.getText()); }
        });
        wrap.add(search, BorderLayout.NORTH);
        wrap.add(new JScrollPane(mt.table()), BorderLayout.CENTER);

        // FIX: plain if/else replaces Java 14+ switch expression
        String icon;
        if      (MetadataUtil.DIR_BASIC.equals(name)) icon = "[i]";
        else if (MetadataUtil.DIR_EXIF.equals(name))  icon = "[E]";
        else if (MetadataUtil.DIR_GPS.equals(name))   icon = "[G]";
        else if (MetadataUtil.DIR_IPTC.equals(name))  icon = "[IP]";
        else if (MetadataUtil.DIR_XMP.equals(name))   icon = "[X]";
        else                                           icon = "[*]";

        tabs.addTab(icon + " " + name, wrap);
    }

    private static class MetaTable {
        private final List<String[]>    allRows;
        private final DefaultTableModel model;
        private final JTable            table;

        MetaTable(List<String[]> rows) {
            this.allRows = new ArrayList<>(rows);
            model = new DefaultTableModel(new Object[]{"Property", "Value"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            table = new JTable(model);
            table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            table.setRowHeight(20);
            table.getColumnModel().getColumn(0).setPreferredWidth(160);
            table.getColumnModel().getColumn(1).setPreferredWidth(250);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
            table.setShowGrid(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v,
                        boolean sel, boolean foc, int r, int c) {
                    super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                    if (!sel) setBackground(r % 2 == 0 ? new Color(38, 38, 38) : new Color(44, 44, 44));
                    setForeground(c == 0 ? new Color(150, 190, 255) : new Color(220, 220, 220));
                    setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
                    return this;
                }
            });
            populate(allRows);
        }

        void filter(String text) {
            String q = text.toLowerCase().trim();
            if (q.isEmpty()) { populate(allRows); return; }
            List<String[]> f = new ArrayList<>();
            for (String[] row : allRows)
                if (row[0].toLowerCase().contains(q) || row[1].toLowerCase().contains(q)) f.add(row);
            populate(f);
        }

        private void populate(List<String[]> rows) {
            model.setRowCount(0);
            for (String[] r : rows) model.addRow(r);
        }

        JTable table() { return table; }
    }
}

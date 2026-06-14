package com.imageviewer.ui;

import com.imageviewer.core.TagManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class TagPanel extends JPanel implements TagManager.Listener {

    private File currentFile;
    private final DefaultListModel<String> currentTagModel = new DefaultListModel<>();
    private final DefaultListModel<String> allTagModel     = new DefaultListModel<>();
    private final JList<String>            currentTagList;
    private final JList<String>            allTagList;
    private final JTextField               addTagField;
    private final List<Consumer<String>>   filterListeners = new ArrayList<>();

    public TagPanel() {
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        TagManager.getInstance().addListener(this);

        // -- Current image tags --
        JPanel topPanel = new JPanel(new BorderLayout(0, 4));
        topPanel.setBorder(BorderFactory.createTitledBorder("Image Tags"));

        currentTagList = new JList<>(currentTagModel);
        currentTagList.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        JScrollPane topScroll = new JScrollPane(currentTagList);
        topScroll.setPreferredSize(new Dimension(0, 100));
        topPanel.add(topScroll, BorderLayout.CENTER);

        addTagField = new JTextField();
        addTagField.putClientProperty("JTextField.placeholderText", "New tag...");
        JButton addBtn = new JButton("+");
        addBtn.setToolTipText("Add tag");
        addBtn.addActionListener(e -> addTagToCurrentFile());
        addTagField.addActionListener(e -> addTagToCurrentFile());

        JPanel addRow = new JPanel(new BorderLayout(4, 0));
        addRow.add(addTagField, BorderLayout.CENTER);
        addRow.add(addBtn,      BorderLayout.EAST);

        JButton removeBtn = new JButton("Remove Selected Tag");
        removeBtn.addActionListener(e -> {
            if (currentFile == null) return;
            String sel = currentTagList.getSelectedValue();
            if (sel != null) TagManager.getInstance().removeTag(currentFile, sel);
        });

        JPanel btns = new JPanel(new BorderLayout(0, 2));
        btns.add(addRow,    BorderLayout.NORTH);
        btns.add(removeBtn, BorderLayout.SOUTH);
        topPanel.add(btns, BorderLayout.SOUTH);

        // -- All tags library --
        JPanel botPanel = new JPanel(new BorderLayout(0, 4));
        botPanel.setBorder(BorderFactory.createTitledBorder("All Tags (click to filter)"));

        allTagList = new JList<>(allTagModel);
        allTagList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        allTagList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean sel, boolean foc) {
                super.getListCellRendererComponent(list, value, index, sel, foc);
                String tag = (String) value;
                long count = TagManager.getInstance().getTagCount(tag);
                setText(tag + "  (" + count + ")");
                return this;
            }
        });
        allTagList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                String sel = allTagList.getSelectedValue();
                if (sel != null) filterListeners.forEach(l -> l.accept(sel));
            }
        });

        JButton clearFilter = new JButton("Clear Filter");
        clearFilter.addActionListener(e -> filterListeners.forEach(l -> l.accept(null)));

        botPanel.add(new JScrollPane(allTagList), BorderLayout.CENTER);
        botPanel.add(clearFilter, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, botPanel);
        split.setResizeWeight(0.4);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);

        refreshAllTags();
    }

    public void setCurrentFile(File file) { this.currentFile = file; refreshCurrentTags(); }
    public void addFilterListener(Consumer<String> l) { filterListeners.add(l); }

    @Override public void tagsChanged() {
        SwingUtilities.invokeLater(() -> { refreshCurrentTags(); refreshAllTags(); });
    }

    private void addTagToCurrentFile() {
        String tag = addTagField.getText().trim();
        if (tag.isEmpty() || currentFile == null) return;
        TagManager.getInstance().addTag(currentFile, tag);
        addTagField.setText("");
    }

    private void refreshCurrentTags() {
        currentTagModel.clear();
        if (currentFile == null) return;
        TagManager.getInstance().getTags(currentFile).forEach(currentTagModel::addElement);
    }

    private void refreshAllTags() {
        allTagModel.clear();
        TagManager.getInstance().getAllTags().forEach(allTagModel::addElement);
    }
}

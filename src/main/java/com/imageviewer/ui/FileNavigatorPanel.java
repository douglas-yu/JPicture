package com.imageviewer.ui;

import com.imageviewer.core.ImageLoader;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.filechooser.FileSystemView;
import javax.swing.tree.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileNavigatorPanel extends JPanel {

    public interface DirSelectedListener { void directorySelected(File dir); }

    private final JTree                 tree;
    private final DefaultTreeModel      treeModel;
    private final List<DirSelectedListener> listeners = new ArrayList<>();
    private final FileSystemView        fsv = FileSystemView.getFileSystemView();

    public FileNavigatorPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Computer");
        treeModel = new DefaultTreeModel(root);
        for (File r : File.listRoots()) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(r);
            root.add(node);
            node.add(new DefaultMutableTreeNode("Loading…"));
        }

        tree = new JTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new FileTreeCellRenderer());
        tree.setToggleClickCount(1);

        tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            public void treeExpanded(javax.swing.event.TreeExpansionEvent e) {
                DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) e.getPath().getLastPathComponent();
                expandNode(node);
            }
            public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) {}
        });

        tree.addTreeSelectionListener((TreeSelectionEvent e) -> {
            DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null) return;
            Object obj = node.getUserObject();
            // FIX: plain instanceof check then explicit cast (no pattern-match)
            if (obj instanceof File) {
                File dir = (File) obj;
                if (dir.isDirectory()) listeners.forEach(l -> l.directorySelected(dir));
            }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        JButton homeBtn = new JButton("⌂ Home");
        homeBtn.setFocusPainted(false);
        homeBtn.addActionListener(e -> navigateTo(new File(System.getProperty("user.home"))));
        add(homeBtn, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(220, 0));
    }

    private void expandNode(DefaultMutableTreeNode node) {
        Object uo = node.getUserObject();
        // FIX: plain instanceof + cast
        if (!(uo instanceof File)) return;
        File dir = (File) uo;
        // Skip if already expanded (first child is not the placeholder)
        if (node.getChildCount() > 0) {
            Object firstChild = ((DefaultMutableTreeNode) node.getFirstChild()).getUserObject();
            if (!(firstChild instanceof String)) return;
        }
        node.removeAllChildren();
        File[] children = dir.listFiles(f -> f.isDirectory() && !f.isHidden());
        if (children != null) {
            java.util.Arrays.sort(children,
                (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                DefaultMutableTreeNode cn = new DefaultMutableTreeNode(child);
                node.add(cn);
                cn.add(new DefaultMutableTreeNode("Loading…"));
            }
        }
        treeModel.reload(node);
    }

    public void navigateTo(File dir) {
        File[] parts = getPathParts(dir);
        if (parts == null || parts.length == 0) return;
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        DefaultMutableTreeNode current = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            if (parts[0].equals(child.getUserObject())) { current = child; break; }
        }
        if (current == null) return;
        for (int i = 1; i < parts.length; i++) {
            expandNode(current);
            boolean found = false;
            for (int j = 0; j < current.getChildCount(); j++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) current.getChildAt(j);
                if (parts[i].equals(child.getUserObject())) { current = child; found = true; break; }
            }
            if (!found) break;
        }
        TreePath tp = new TreePath(current.getPath());
        tree.setSelectionPath(tp);
        tree.scrollPathToVisible(tp);
    }

    private File[] getPathParts(File dir) {
        List<File> parts = new ArrayList<>();
        File f = dir;
        while (f != null) { parts.add(0, f); f = f.getParentFile(); }
        return parts.toArray(new File[0]);
    }

    public void addDirSelectedListener(DirSelectedListener l) { listeners.add(l); }

    private class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            // FIX: two separate plain instanceof + cast (no pattern-matching)
            if (value instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                if (node.getUserObject() instanceof File) {
                    File f = (File) node.getUserObject();
                    String display = fsv.getSystemDisplayName(f);
                    setText(display == null || display.isEmpty() ? f.getAbsolutePath() : display);
                    setIcon(fsv.getSystemIcon(f));
                    File[] imgs = f.listFiles(fi -> fi.isFile() && ImageLoader.isSupported(fi));
                    if (imgs != null && imgs.length > 0)
                        setFont(getFont().deriveFont(Font.BOLD));
                }
            }
            return this;
        }
    }
}

package com.imageviewer;

import com.formdev.flatlaf.FlatDarkLaf;
import com.imageviewer.ui.MainWindow;

import javax.swing.*;
import java.awt.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { FlatDarkLaf.setup(); }
            catch (Exception e) {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception ignored) {}
            }

            // ── Bigger menu fonts ──────────────────────────────────────────────
            Font menuFont     = new Font(Font.SANS_SERIF, Font.PLAIN,  15);
            Font menuItemFont = new Font(Font.SANS_SERIF, Font.PLAIN,  14);
            UIManager.put("MenuBar.font",           menuFont);
            UIManager.put("Menu.font",              menuFont);
            UIManager.put("MenuItem.font",          menuItemFont);
            UIManager.put("CheckBoxMenuItem.font",  menuItemFont);
            UIManager.put("RadioButtonMenuItem.font", menuItemFont);
            UIManager.put("PopupMenu.font",         menuItemFont);

            // ── General UI tweaks ──────────────────────────────────────────────
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("ScrollBar.thumbArc", 999);

            new MainWindow().setVisible(true);
        });
    }
}

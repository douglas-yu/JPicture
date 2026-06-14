package com.imageviewer.ui;

import com.imageviewer.core.ImageLoader;
import com.imageviewer.core.MetadataUtil;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class StatusBar extends JPanel {

    private final JLabel fileLabel, dimsLabel, sizeLabel, zoomLabel, cameraLabel, gpsLabel;

    public StatusBar() {
        super(new FlowLayout(FlowLayout.LEFT, 12, 3));
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));
        fileLabel   = label("No file selected");
        dimsLabel   = label(""); sizeLabel  = label("");
        zoomLabel   = label(""); cameraLabel = label(""); gpsLabel = label("");
        add(fileLabel);
        add(sep()); add(dimsLabel);
        add(sep()); add(sizeLabel);
        add(sep()); add(zoomLabel);
        add(sep()); add(cameraLabel);
        add(sep()); add(gpsLabel);
    }

    public void update(File file, int w, int h, double zoom) {
        if (file == null) { fileLabel.setText("No file selected"); dimsLabel.setText(""); sizeLabel.setText(""); zoomLabel.setText(""); cameraLabel.setText(""); gpsLabel.setText(""); return; }
        fileLabel.setText(file.getName());
        dimsLabel.setText(w > 0 ? w + " x " + h + " px" : "");
        sizeLabel.setText(ImageLoader.formatFileSize(file.length()));
        zoomLabel.setText(zoom > 0 ? String.format("%.0f%%", zoom * 100) : "");
        new Thread(() -> {
            String cam = MetadataUtil.getCameraModel(file);
            String gps = MetadataUtil.getGpsString(file);
            SwingUtilities.invokeLater(() -> {
                cameraLabel.setText(cam != null ? cam : "");
                gpsLabel.setText(gps != null ? "GPS: " + gps : "");
            });
        }, "status-meta").start();
    }

    public void setZoom(double zoom) { zoomLabel.setText(zoom > 0 ? String.format("%.0f%%", zoom * 100) : ""); }

    private JLabel label(String t) {
        JLabel l = new JLabel(t);
        l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        l.setForeground(new Color(180, 180, 180));
        return l;
    }
    private JSeparator sep() { JSeparator s = new JSeparator(SwingConstants.VERTICAL); s.setPreferredSize(new Dimension(1, 14)); return s; }
}

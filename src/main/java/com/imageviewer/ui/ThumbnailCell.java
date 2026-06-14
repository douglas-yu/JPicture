package com.imageviewer.ui;

import com.imageviewer.core.TagManager;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Set;

public class ThumbnailCell extends JPanel {

    private static final Color SEL_BORDER = new Color(0, 120, 215);
    private static final Color TAG_DOT    = new Color(70, 190, 100);
    private static final int   LABEL_H    = 36;

    private final File    file;
    private BufferedImage thumbnail;
    private boolean       selected;
    private int           thumbSize;

    public ThumbnailCell(File file, int thumbSize) {
        this.file      = file;
        this.thumbSize = thumbSize;
        setOpaque(true);
        setBackground(new Color(45, 45, 45));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(file.getName());
        setPreferredSize(new Dimension(thumbSize + 16, thumbSize + LABEL_H + 16));
    }

    public void setThumbnail(BufferedImage img) { this.thumbnail = img; repaint(); }
    public void setSelected(boolean sel)        { this.selected  = sel; repaint(); }
    public boolean isSelected()                 { return selected; }
    public File    getFile()                    { return file; }

    public void updateThumbSize(int size) {
        this.thumbSize = size;
        setPreferredSize(new Dimension(size + 16, size + LABEL_H + 16));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        int cx = 8, cy = 8, iw = thumbSize, ih = thumbSize;

        g2.setColor(new Color(0, 0, 0, 60));
        g2.fillRoundRect(cx+2, cy+2, iw, ih, 8, 8);
        g2.setColor(new Color(30, 30, 30));
        g2.fillRoundRect(cx, cy, iw, ih, 8, 8);

        if (thumbnail != null) {
            g2.setClip(new java.awt.geom.RoundRectangle2D.Float(cx, cy, iw, ih, 8, 8));
            g2.drawImage(thumbnail, cx, cy, iw, ih, null);
            g2.setClip(null);
        }

        if (selected) {
            g2.setColor(SEL_BORDER);
            g2.setStroke(new BasicStroke(3f));
            g2.drawRoundRect(cx, cy, iw, ih, 8, 8);
        }

        String name = file.getName();
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics fm = g2.getFontMetrics();
        while (fm.stringWidth(name) > iw - 4 && name.length() > 4)
            name = name.substring(0, name.length() - 4) + "…";
        g2.setColor(new Color(200, 200, 200));
        g2.drawString(name, cx + (iw - fm.stringWidth(name)) / 2, cy + ih + 4 + fm.getAscent());

        Set<String> tags = TagManager.getInstance().getTags(file);
        if (!tags.isEmpty()) {
            int dotX = cx + iw - 10, dotY = cy + 4;
            g2.setColor(TAG_DOT);
            g2.fillOval(dotX, dotY, 8, 8);
            if (tags.size() > 1) {
                g2.setColor(new Color(200, 200, 200));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
                g2.drawString(String.valueOf(tags.size()), dotX+1, dotY+7);
            }
        }
        g2.dispose();
    }
}

package com.imageviewer.core;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

public final class ImageLoader {

    public static final Set<String> SUPPORTED_EXTENSIONS = new LinkedHashSet<>(Arrays.asList(
        "jpg","jpeg","png","gif","bmp","wbmp",
        "tif","tiff","webp","psd","hdr","icns","ico",
        "pnm","pbm","pgm","ppm","pcx"
    ));
    public static final Set<String> RAW_EXTENSIONS = new LinkedHashSet<>(Arrays.asList(
        "cr2","cr3","nef","arw","raf","dng","orf","rw2","srw"
    ));

    private ImageLoader() {}

    public static BufferedImage loadImage(File file) {
        try { BufferedImage img = ImageIO.read(file); if (img != null) return img; }
        catch (Exception ignored) {}
        return null;
    }

    public static BufferedImage generateThumbnail(File file, int size) {
        BufferedImage src = loadImage(file);
        if (src == null) return createErrorThumbnail(size);
        return scaleThumbnail(src, size);
    }

    public static BufferedImage scaleThumbnail(BufferedImage src, int size) {
        int w = src.getWidth(), h = src.getHeight();
        double scale = Math.min((double) size / w, (double) size / h);
        int sw = Math.max(1, (int)(w * scale)), sh = Math.max(1, (int)(h * scale));
        BufferedImage thumb = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = thumb.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setColor(new Color(40, 40, 40));
        g2.fillRect(0, 0, size, size);
        g2.drawImage(src, (size-sw)/2, (size-sh)/2, sw, sh, null);
        g2.dispose();
        return thumb;
    }

    private static BufferedImage createErrorThumbnail(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(60,60,60)); g.fillRect(0,0,size,size);
        g.setColor(new Color(180,60,60)); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,11));
        FontMetrics fm = g.getFontMetrics(); String txt = "No Preview";
        g.drawString(txt,(size-fm.stringWidth(txt))/2, size/2+fm.getAscent()/2);
        g.dispose(); return img;
    }

    public static List<File> getImageFiles(File dir, boolean includeRaw) {
        File[] files = dir.listFiles();
        if (files == null) return Collections.emptyList();
        List<File> result = new ArrayList<>();
        for (File f : files) {
            if (!f.isFile()) continue;
            String ext = getExtension(f);
            if (SUPPORTED_EXTENSIONS.contains(ext) || (includeRaw && RAW_EXTENSIONS.contains(ext)))
                result.add(f);
        }
        result.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public static String getExtension(File f) {
        String n = f.getName(); int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i+1).toLowerCase() : "";
    }

    public static boolean isSupported(File f) {
        String ext = getExtension(f);
        return SUPPORTED_EXTENSIONS.contains(ext) || RAW_EXTENSIONS.contains(ext);
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024*1024) return String.format("%.1f KB", bytes/1024.0);
        if (bytes < 1024L*1024*1024) return String.format("%.1f MB", bytes/(1024.0*1024));
        return String.format("%.2f GB", bytes/(1024.0*1024*1024));
    }
}

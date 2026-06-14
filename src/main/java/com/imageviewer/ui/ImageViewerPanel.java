package com.imageviewer.ui;

import com.imageviewer.core.ImageLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Zoomable / pannable single-image viewer.
 * Zoom: scroll-wheel. Pan: left-click drag. Rotate: R/E keys.
 */
public class ImageViewerPanel extends JPanel {

    public interface NavListener { void navigate(int delta); }

    private BufferedImage image;
    private File          currentFile;
    private List<File>    fileList = new ArrayList<>();

    private double  zoom     = 1.0;
    private double  panX     = 0, panY = 0;
    private int     rotation = 0;
    private boolean dragging = false;
    private int     dragX, dragY;

    private final JLabel noImageLabel;
    private final List<NavListener> navListeners = new ArrayList<>();

    public ImageViewerPanel() {
        super(null);
        setBackground(new Color(20, 20, 20));
        setFocusable(true);

        noImageLabel = new JLabel("Double-click a thumbnail to view", SwingConstants.CENTER);
        noImageLabel.setForeground(new Color(100, 100, 100));
        noImageLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 16));
        setLayout(new BorderLayout());
        add(noImageLabel, BorderLayout.CENTER);

        setupMouse();
        setupKeyboard();
    }

    public void loadImage(File file) {
        this.currentFile = file;
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() { return ImageLoader.loadImage(file); }
            @Override protected void done() {
                try {
                    image = get(); rotation = 0; fitToWindow();
                    noImageLabel.setVisible(image == null); repaint();
                } catch (Exception e) { noImageLabel.setVisible(true); }
            }
        };
        worker.execute();
    }

    public void setFileList(List<File> files) { this.fileList = new ArrayList<>(files); }

    public void fitToWindow() {
        if (image == null) return;
        int iw = rotW(), ih = rotH(), pw = getWidth(), ph = getHeight();
        if (pw <= 0 || ph <= 0) { zoom = 1; panX = panY = 0; return; }
        zoom = Math.min((double) pw / iw, (double) ph / ih) * 0.95;
        panX = (pw - iw * zoom) / 2.0;
        panY = (ph - ih * zoom) / 2.0;
    }

    public void actualSize() {
        if (image == null) return;
        zoom = 1.0;
        panX = (getWidth()  - image.getWidth())  / 2.0;
        panY = (getHeight() - image.getHeight()) / 2.0;
        repaint();
    }

    public void zoomIn()  { applyZoom(1.25, getWidth() / 2.0, getHeight() / 2.0); }
    public void zoomOut() { applyZoom(0.8,  getWidth() / 2.0, getHeight() / 2.0); }

    public void rotate(int delta) { rotation = ((rotation + delta) % 360 + 360) % 360; fitToWindow(); repaint(); }

    public double getZoom()     { return zoom; }
    public File   getCurrentFile() { return currentFile; }

    public void addNavListener(NavListener l) { navListeners.add(l); }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image == null) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            zoom >= 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                      : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        AffineTransform at = new AffineTransform();
        at.translate(panX, panY);
        at.scale(zoom, zoom);
        if (rotation != 0) {
            at.translate(image.getWidth() / 2.0, image.getHeight() / 2.0);
            at.rotate(Math.toRadians(rotation));
            at.translate(-image.getWidth() / 2.0, -image.getHeight() / 2.0);
        }
        g2.drawRenderedImage(image, at);
        g2.dispose();
        paintOverlay(g);
    }

    private void paintOverlay(Graphics g) {
        if (image == null) return;
        String info = String.format("%.0f%%  %dx%d", zoom * 100, image.getWidth(), image.getHeight());
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(info);
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(8, getHeight() - 30, tw + 12, 22, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString(info, 14, getHeight() - 14);
    }

    private void setupMouse() {
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragging = true; dragX = e.getX(); dragY = e.getY();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }
            @Override public void mouseReleased(MouseEvent e) { dragging = false; setCursor(Cursor.getDefaultCursor()); }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging) { panX += e.getX()-dragX; panY += e.getY()-dragY; dragX=e.getX(); dragY=e.getY(); repaint(); }
            }
        });
        addMouseWheelListener(e -> {
            double factor = e.getPreciseWheelRotation() < 0 ? 1.1 : 1.0/1.1;
            applyZoom(factor, e.getX(), e.getY());
        });
    }

    private void applyZoom(double factor, double cx, double cy) {
        double nz = Math.max(0.02, Math.min(zoom * factor, 32.0));
        double r = nz / zoom;
        panX = cx - r * (cx - panX); panY = cy - r * (cy - panY); zoom = nz; repaint();
    }

    private int rotW() { return (rotation == 90 || rotation == 270) ? image.getHeight() : image.getWidth(); }
    private int rotH() { return (rotation == 90 || rotation == 270) ? image.getWidth()  : image.getHeight(); }

    private void setupKeyboard() {
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();
        bind(im, am, "zoomIn",  KeyStroke.getKeyStroke('+'), e -> zoomIn());
        bind(im, am, "zoomIn2", KeyStroke.getKeyStroke('='), e -> zoomIn());
        bind(im, am, "zoomOut", KeyStroke.getKeyStroke('-'), e -> zoomOut());
        bind(im, am, "fit",     KeyStroke.getKeyStroke('f'), e -> { fitToWindow(); repaint(); });
        bind(im, am, "actual",  KeyStroke.getKeyStroke('1'), e -> actualSize());
        bind(im, am, "rotCW",   KeyStroke.getKeyStroke('r'), e -> rotate(90));
        bind(im, am, "rotCCW",  KeyStroke.getKeyStroke('e'), e -> rotate(-90));
        bind(im, am, "prev",    KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),  e -> navListeners.forEach(l -> l.navigate(-1)));
        bind(im, am, "next",    KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), e -> navListeners.forEach(l -> l.navigate(+1)));
    }

    private void bind(InputMap im, ActionMap am, String key, KeyStroke ks, java.util.function.Consumer<ActionEvent> action) {
        im.put(ks, key);
        am.put(key, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { action.accept(e); } });
    }

    @Override public void addNotify()  { super.addNotify(); fitToWindow(); }
    @Override public void setBounds(int x, int y, int w, int h) { super.setBounds(x,y,w,h); if (image != null) fitToWindow(); }
}

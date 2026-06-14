# Java Image Viewer

A feature-rich, cross-platform desktop image viewer built with Java Swing,
TwelveMonkeys ImageIO, Drew Noakes' metadata-extractor, FlatLaf, and Gson.

---

## Features

| Feature | Details |
|---|---|
| **Image Formats** | JPEG, PNG, GIF, BMP, WBMP, TIFF, WebP, PSD, HDR, ICNS, PNM/PBM/PGM/PPM, PCX |
| **RAW Formats** | Metadata read for CR2, CR3, NEF, ARW, RAF, DNG, ORF, RW2, SRW |
| **Thumbnail Browser** | Async-loaded grid, adjustable 80–280 px, name/date/size sort |
| **Image Viewer** | Smooth zoom (mouse-wheel), pan (drag), keyboard nav, rotate 90° |
| **EXIF / Metadata** | EXIF, GPS, IPTC, XMP – each in a searchable, filterable table |
| **Tag System** | Per-image tags; persisted to `~/.imageviewer/tags.json`; filter thumbnails by tag |
| **Slideshow** | Auto-advances at configurable interval (1–60 sec) |
| **Themes** | FlatLaf Dark (default) / Light – switchable at runtime |

---

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 11 or later (Java 17 LTS recommended) |
| Maven | 3.8+ |

---

## Build & Run

```bash
# 1 – clone / unzip the project
cd java-image-viewer

# 2 – build fat JAR (all dependencies bundled)
mvn clean package -q

# 3 – run
java -jar target/java-image-viewer-1.0.0.jar
```

The JAR is self-contained; no installation required.

---

## Project Layout

```
java-image-viewer/
├── pom.xml
└── src/main/java/com/imageviewer/
    ├── Main.java                      # Entry point
    ├── core/
    │   ├── ImageLoader.java           # Multi-format image / thumbnail loading
    │   ├── MetadataUtil.java          # EXIF/IPTC/XMP extraction (metadata-extractor)
    │   └── TagManager.java            # Tag CRUD + JSON persistence (Gson)
    └── ui/
        ├── MainWindow.java            # JFrame, menus, toolbar, layout orchestration
        ├── FileNavigatorPanel.java    # Directory tree (lazy expand, bold = has images)
        ├── ThumbnailBrowserPanel.java # Async thumbnail grid, sort/filter, context menu
        ├── ThumbnailCell.java         # Custom thumbnail card with tag indicator
        ├── ImageViewerPanel.java      # Zoom/pan/rotate canvas (AffineTransform)
        ├── MetadataPanel.java         # Tabbed metadata viewer with search
        ├── TagPanel.java              # Add/remove/filter tags
        └── StatusBar.java             # File info + zoom + camera + GPS
```

---

## Keyboard Shortcuts

| Key | Action |
|---|---|
| `+` / `=` | Zoom in |
| `-` | Zoom out |
| `F` | Fit to window |
| `1` | Actual size (100%) |
| `R` | Rotate 90° CW |
| `E` | Rotate 90° CCW |
| `←` / `→` | Previous / Next image |
| `Ctrl+O` | Open directory |

---

## Tag Storage

Tags are persisted in `~/.imageviewer/tags.json` (Linux/macOS) or
`C:\Users\<user>\.imageviewer\tags.json` (Windows):

```json
{
  "/home/user/photos/sunset.jpg": ["vacation", "landscape"],
  "/home/user/photos/cat.png":    ["pets"]
}
```

---

## Dependencies

| Library | Purpose |
|---|---|
| TwelveMonkeys ImageIO 3.10.1 | TIFF, WebP, PSD, HDR, ICNS, PCX, PNM support |
| metadata-extractor 2.19.0 | EXIF, GPS, IPTC, XMP parsing |
| FlatLaf 3.4 | Modern flat look-and-feel (dark/light) |
| Gson 2.10.1 | JSON tag persistence |

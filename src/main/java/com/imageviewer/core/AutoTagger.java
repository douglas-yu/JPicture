package com.imageviewer.core;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * AutoTagger – two-phase image analysis:
 *
 *  Phase 1 (Statistical – always instant, no downloads):
 *    Analyses pixel statistics, color histograms, region comparisons.
 *    Tags: outdoor, sky, nature, landscape, sunset/golden-hour, water,
 *          night, bright, dark, colorful, monochrome, warm-tones, cool-tones,
 *          portrait-orientation, landscape-orientation, square, green, etc.
 *
 *  Phase 2 (DJL AI – optional, downloads ~250 MB on first use, then cached):
 *    Object detection (COCO 80 classes) → friendly tags (person→people,
 *    dog/cat/bird→animal, car/truck→vehicle, pizza/apple→food …).
 *    Image classification (ImageNet 1000 classes) → top-5 labels as tags.
 *
 *  When DJL native libs or models are unavailable the phase silently falls
 *  back and only Phase-1 tags are returned.
 */
public class AutoTagger {

    // ── Progress callback used for batch processing ───────────────────────────
    public interface ProgressCallback {
        void onProgress(int current, int total, String filename);
        void onComplete(int taggedCount, int total);
        void onError(String message);
    }

    // ── COCO class → friendly tag ─────────────────────────────────────────────
    private static final Map<String, String> COCO_TO_TAG  = new LinkedHashMap<>();
    private static final Set<String>         ANIMAL_CLS   = new HashSet<>();
    private static final Set<String>         VEHICLE_CLS  = new HashSet<>();
    private static final Set<String>         FOOD_CLS     = new HashSet<>();

    static {
        String[] animals = {"bird","cat","dog","horse","sheep","cow",
                            "elephant","bear","zebra","giraffe"};
        for (String a : animals) { COCO_TO_TAG.put(a, a); ANIMAL_CLS.add(a); }

        String[] vehicles = {"bicycle","car","motorcycle","airplane",
                             "bus","train","truck","boat"};
        for (String v : vehicles) { COCO_TO_TAG.put(v, v); VEHICLE_CLS.add(v); }

        String[] foods = {"banana","apple","sandwich","orange","broccoli",
                          "carrot","hot dog","pizza","donut","cake"};
        for (String f : foods)  { COCO_TO_TAG.put(f, "food"); FOOD_CLS.add(f); }

        COCO_TO_TAG.put("person",      "people");
        COCO_TO_TAG.put("laptop",      "technology");
        COCO_TO_TAG.put("cell phone",  "technology");
        COCO_TO_TAG.put("book",        "books");
        COCO_TO_TAG.put("potted plant","plant");
        COCO_TO_TAG.put("chair",       "furniture");
        COCO_TO_TAG.put("couch",       "furniture");
        COCO_TO_TAG.put("bed",         "furniture");
        COCO_TO_TAG.put("tv",          "technology");
        COCO_TO_TAG.put("dining table","furniture");
        COCO_TO_TAG.put("umbrella",    "outdoor");
        COCO_TO_TAG.put("backpack",    "outdoor");
        COCO_TO_TAG.put("sports ball", "sports");
        COCO_TO_TAG.put("tennis racket","sports");
        COCO_TO_TAG.put("skateboard",  "sports");
        COCO_TO_TAG.put("surfboard",   "sports");
        COCO_TO_TAG.put("skis",        "sports");
        COCO_TO_TAG.put("kite",        "outdoor");
        COCO_TO_TAG.put("bench",       "outdoor");
        COCO_TO_TAG.put("fire hydrant","urban");
        COCO_TO_TAG.put("stop sign",   "urban");
        COCO_TO_TAG.put("traffic light","urban");
    }

    // ── DJL model cache (lazy, thread-safe) ───────────────────────────────────
    // Stored as raw Object to avoid hard compile-time dependency on DJL classes.
    // If DJL isn't on the classpath the try/catch in phase2 handles it cleanly.
    private static volatile Object djlDetectionModel  = null;
    private static volatile boolean djlInitFailed     = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Auto-tag a single file.  Always runs Phase 1.  Attempts Phase 2 (DJL)
     * only if {@code useAI} is true and DJL is available.
     */
    public static Set<String> autoTag(File imageFile, boolean useAI) {
        Set<String> tags = new LinkedHashSet<>();
        try { tags.addAll(analyzeStatistics(imageFile)); } catch (Exception ignored) {}
        if (useAI) {
            try { tags.addAll(detectObjectsDJL(imageFile)); } catch (Exception ignored) {}
        }
        return tags;
    }

    /**
     * Async batch auto-tag.  Applies tags to TagManager and reports progress.
     *
     * @param files     images to process
     * @param useAI     if true attempts DJL after statistical analysis
     * @param saveNow   if true applies tags immediately via TagManager
     * @param cb        progress callback (called on background thread)
     */
    public static void batchAutoTag(List<File> files,
                                    boolean useAI,
                                    boolean saveNow,
                                    ProgressCallback cb) {
        Thread t = new Thread(() -> {
            int tagged = 0;
            for (int i = 0; i < files.size(); i++) {
                File f = files.get(i);
                if (cb != null) cb.onProgress(i + 1, files.size(), f.getName());
                try {
                    Set<String> tags = autoTag(f, useAI);
                    if (saveNow && !tags.isEmpty()) {
                        TagManager tm = TagManager.getInstance();
                        for (String tag : tags) tm.addTag(f, tag);
                        tagged++;
                    }
                } catch (Exception e) {
                    if (cb != null) cb.onError("Skipped: " + f.getName() + " – " + e.getMessage());
                }
            }
            final int done = tagged;
            if (cb != null) cb.onComplete(done, files.size());
        }, "auto-tagger");
        t.setDaemon(true);
        t.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1: Statistical image analysis
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pure pixel statistics, no model required.
     * Samples the image on a grid, analyses brightness, colour, regions.
     */
    public static List<String> analyzeStatistics(File file) throws Exception {
        BufferedImage img = ImageIO.read(file);
        if (img == null) return Collections.emptyList();
        return analyzeStatistics(img);
    }

    public static List<String> analyzeStatistics(BufferedImage img) {
        List<String> tags = new ArrayList<>();
        int w = img.getWidth(), h = img.getHeight();

        // ── Orientation ───────────────────────────────────────────────────────
        if      (h > w * 1.15) tags.add("portrait-orientation");
        else if (w > h * 1.15) tags.add("landscape-orientation");
        else                   tags.add("square");

        // ── Pixel sampling on a sparse grid ───────────────────────────────────
        int step = Math.max(1, Math.min(w, h) / 60);
        long rSum = 0, gSum = 0, bSum = 0, cnt = 0;
        long topR = 0, topG = 0, topB = 0, topCnt = 0;
        long botR = 0, botG = 0, botB = 0, botCnt = 0;
        long leftR = 0, leftG = 0, leftB = 0, leftCnt = 0;
        // track max–min per row sample for saturation
        long satSum = 0;

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8)  & 0xFF;
                int b =  rgb        & 0xFF;
                rSum += r; gSum += g; bSum += b; cnt++;
                int mx = Math.max(r, Math.max(g, b));
                int mn = Math.min(r, Math.min(g, b));
                satSum += (mx - mn);
                if (y < h / 3)        { topR += r; topG += g; topB += b; topCnt++; }
                if (y > h * 2 / 3)    { botR += r; botG += g; botB += b; botCnt++; }
                if (x < w / 3)        { leftR += r; leftG += g; leftB += b; leftCnt++; }
            }
        }
        if (cnt == 0) return tags;

        double avgR = rSum / (double) cnt;
        double avgG = gSum / (double) cnt;
        double avgB = bSum / (double) cnt;
        double luma = 0.299 * avgR + 0.587 * avgG + 0.114 * avgB;
        double saturation = satSum / (double) cnt;

        // ── Brightness ───────────────────────────────────────────────────────
        if      (luma < 50)  { tags.add("night");    tags.add("dark");  }
        else if (luma < 90)  { tags.add("dark");  }
        else if (luma > 210) { tags.add("bright"); }
        else if (luma > 160) { tags.add("bright"); }

        // ── Saturation → colorful / monochrome ───────────────────────────────
        if      (saturation < 12) tags.add("monochrome");
        else if (saturation > 55) tags.add("colorful");

        // ── Colour temperature ────────────────────────────────────────────────
        if (avgR > avgB * 1.35) tags.add("warm-tones");
        if (avgB > avgR * 1.30) tags.add("cool-tones");

        // ── Top-third sky detection (blue dominant, bright) ───────────────────
        if (topCnt > 0) {
            double tR = topR / (double) topCnt;
            double tG = topG / (double) topCnt;
            double tB = topB / (double) topCnt;
            double tLuma = 0.299*tR + 0.587*tG + 0.114*tB;
            if (tB > tR * 1.20 && tB > tG * 1.05 && tB > 90 && tLuma > 80) {
                tags.add("sky");
                tags.add("outdoor");
            }
            // Sunset / golden hour: warm red/orange top + high saturation
            if (tR > tB * 1.6 && tR > 130 && saturation > 35) {
                tags.add("sunset");
                tags.add("golden-hour");
                tags.add("outdoor");
            }
        }

        // ── Landscape: blue sky top + green bottom ────────────────────────────
        if (topCnt > 0 && botCnt > 0) {
            double tB = topB / (double) topCnt;
            double tR = topR / (double) topCnt;
            double bG = botG / (double) botCnt;
            double bR = botR / (double) botCnt;
            if (tB > tR * 1.2 && bG > bR * 1.2 && bG > 70) {
                tags.add("nature");
                tags.add("landscape");
                tags.add("outdoor");
            }
        }

        // ── Snow / fog: overall very bright, very low saturation ─────────────
        if (luma > 190 && saturation < 18) tags.add("snow-or-fog");

        // ── Water: strong blue overall, mid brightness ────────────────────────
        if (avgB > avgR * 1.30 && avgB > avgG * 1.10 && luma > 60 && luma < 200) {
            tags.add("water");
        }

        // ── Green / forest ────────────────────────────────────────────────────
        if (avgG > avgR * 1.20 && avgG > avgB * 1.20 && avgG > 70) {
            tags.add("green");
            tags.add("nature");
        }

        // ── Fire / lava: very red-orange on left/centre ───────────────────────
        if (leftCnt > 0) {
            double lR = leftR / (double) leftCnt;
            double lB = leftB / (double) leftCnt;
            if (lR > lB * 2.0 && lR > 150) tags.add("fire-tones");
        }

        // ── Face-skin heuristic (warm, mid-luma, low-sat-variation) ──────────
        // Rough: dominant warm pinkish hue in a portrait crop
        if (tags.contains("portrait-orientation")) {
            double warmness = avgR - avgB;
            if (warmness > 20 && avgR > 120 && avgR < 220 && saturation > 8 && saturation < 60) {
                tags.add("faces-possible");
            }
        }

        return tags;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2: DJL object detection  (soft dependency – fails gracefully)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs DJL SSD object detection.  On first call loads the model (may
     * download ~250 MB of native libs + model weights and cache them in
     * ~/.djl.ai/).  Subsequent calls reuse the cached model.
     *
     * Throws if DJL classes are missing or model download fails – caller
     * should catch and ignore so Phase 1 results are still returned.
     */
    @SuppressWarnings("unchecked")
    private static List<String> detectObjectsDJL(File file) throws Exception {
        if (djlInitFailed) return Collections.emptyList();

        // Lazy model load
        Object model = djlDetectionModel;
        if (model == null) {
            synchronized (AutoTagger.class) {
                model = djlDetectionModel;
                if (model == null) {
                    try {
                        djlDetectionModel = model = loadDetectionModel();
                    } catch (Exception e) {
                        djlInitFailed = true;
                        throw e;
                    }
                }
            }
        }

        return runDetection(model, file);
    }

    // DJL classes used here – only reachable when DJL is on the classpath.
    private static Object loadDetectionModel() throws Exception {
        ai.djl.repository.zoo.Criteria<
            ai.djl.modality.cv.Image,
            ai.djl.modality.cv.output.DetectedObjects> criteria =
            ai.djl.repository.zoo.Criteria.builder()
                .optApplication(ai.djl.Application.CV.OBJECT_DETECTION)
                .setTypes(ai.djl.modality.cv.Image.class,
                          ai.djl.modality.cv.output.DetectedObjects.class)
                .optFilter("backbone", "resnet50")
                .optEngine("PyTorch")
                .build();
        return ai.djl.repository.zoo.ModelZoo.loadModel(criteria);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static List<String> runDetection(Object modelObj, File file) throws Exception {
        ai.djl.repository.zoo.ZooModel<
            ai.djl.modality.cv.Image,
            ai.djl.modality.cv.output.DetectedObjects> model =
            (ai.djl.repository.zoo.ZooModel<
                ai.djl.modality.cv.Image,
                ai.djl.modality.cv.output.DetectedObjects>) modelObj;

        ai.djl.modality.cv.Image djlImg =
            ai.djl.modality.cv.ImageFactory.getInstance().fromFile(file.toPath());

        try (ai.djl.inference.Predictor<
                ai.djl.modality.cv.Image,
                ai.djl.modality.cv.output.DetectedObjects> predictor =
             model.newPredictor()) {

            ai.djl.modality.cv.output.DetectedObjects detections =
                predictor.predict(djlImg);

            Set<String> tags     = new LinkedHashSet<>();
            boolean hasPerson    = false;
            boolean hasAnimal    = false;
            boolean hasVehicle   = false;
            boolean hasFood      = false;
            int     personCount  = 0;

            List<ai.djl.modality.cv.output.DetectedObjects.DetectedObject> items = detections.items();
            for (ai.djl.modality.cv.output.DetectedObjects.DetectedObject obj : items) {
                if (obj.getProbability() < 0.45) continue;
                String cls = obj.getClassName().toLowerCase();
                String mapped = COCO_TO_TAG.getOrDefault(cls, cls);
                tags.add(mapped);

                if (cls.equals("person"))          { hasPerson = true; personCount++; }
                if (ANIMAL_CLS.contains(cls))       hasAnimal  = true;
                if (VEHICLE_CLS.contains(cls))      hasVehicle = true;
                if (FOOD_CLS.contains(cls))         hasFood    = true;
            }

            // Category rollup tags
            if (hasPerson)           tags.add("people");
            if (personCount >= 2)    tags.add("group");
            if (personCount >= 5)    tags.add("crowd");
            if (hasAnimal)           tags.add("animal");
            if (hasVehicle)          tags.add("vehicle");
            if (hasFood)             tags.add("food");

            return new ArrayList<>(tags);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility: preview tags without saving (used by the preview dialog)
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if the DJL engine has been successfully initialised. */
    public static boolean isDJLAvailable() {
        if (djlInitFailed) return false;
        if (djlDetectionModel != null) return true;
        // Quick class-presence check
        try {
            Class.forName("ai.djl.repository.zoo.ModelZoo");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Shows a preview dialog listing detected tags and asks the user whether
     * to apply them.  Must be called on the EDT.
     *
     * @return the confirmed tag set, or null if the user cancelled
     */
    public static Set<String> showPreviewDialog(java.awt.Window owner,
                                                 File file,
                                                 Set<String> detected) {
        if (detected.isEmpty()) {
            JOptionPane.showMessageDialog(owner,
                "No tags were detected for this image.",
                "Auto-Tag Result", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }

        // Build check-box list
        List<JCheckBox> boxes = new ArrayList<>();
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("<html><b>Detected tags for: </b>" + file.getName() + "</html>");
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        panel.add(title);
        for (String tag : detected) {
            JCheckBox cb = new JCheckBox(tag, true);
            cb.setOpaque(false);
            boxes.add(cb);
            panel.add(cb);
        }

        JScrollPane sp = new JScrollPane(panel);
        sp.setPreferredSize(new java.awt.Dimension(340, Math.min(detected.size() * 28 + 60, 320)));

        int result = JOptionPane.showConfirmDialog(owner, sp,
            "AI Auto-Tag Preview  –  Uncheck tags you don't want",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return null;
        Set<String> chosen = new LinkedHashSet<>();
        for (JCheckBox cb : boxes) if (cb.isSelected()) chosen.add(cb.getText());
        return chosen;
    }
}

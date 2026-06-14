package com.imageviewer.core;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.*;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;

import java.io.File;
import java.util.*;

/**
 * Wraps Drew Noakes' metadata-extractor.
 *
 * KEY FIX: Directory.getErrors() returns Iterable<String> (NOT Collection),
 * so we cannot call .isEmpty() on it. We use iterator().hasNext() instead.
 */
public final class MetadataUtil {

    private MetadataUtil() {}

    public static final String DIR_BASIC = "Basic Info";
    public static final String DIR_EXIF  = "EXIF";
    public static final String DIR_GPS   = "GPS";
    public static final String DIR_IPTC  = "IPTC";
    public static final String DIR_XMP   = "XMP";
    public static final String DIR_OTHER = "Other";

    public static Map<String, List<String[]>> extractAll(File file) {
        Map<String, List<String[]>> result = new LinkedHashMap<>();

        List<String[]> basic = new ArrayList<>();
        basic.add(new String[]{"File Name",     file.getName()});
        basic.add(new String[]{"Full Path",      file.getAbsolutePath()});
        basic.add(new String[]{"File Size",      ImageLoader.formatFileSize(file.length())});
        basic.add(new String[]{"Last Modified",  new Date(file.lastModified()).toString()});
        basic.add(new String[]{"Extension",      ImageLoader.getExtension(file).toUpperCase()});
        result.put(DIR_BASIC, basic);

        try {
            Metadata meta = ImageMetadataReader.readMetadata(file);

            List<String[]> exifList  = new ArrayList<>();
            List<String[]> gpsList   = new ArrayList<>();
            List<String[]> iptcList  = new ArrayList<>();
            List<String[]> xmpList   = new ArrayList<>();
            List<String[]> otherList = new ArrayList<>();

            for (Directory dir : meta.getDirectories()) {
                List<String[]> target;

                if (dir instanceof ExifSubIFDDirectory
                        || dir instanceof ExifIFD0Directory
                        || dir instanceof ExifThumbnailDirectory
                        || dir instanceof JpegDirectory
                        || dir instanceof PngDirectory) {
                    target = exifList;
                    if (dir instanceof ExifSubIFDDirectory) {
                        ExifSubIFDDirectory exif = (ExifSubIFDDirectory) dir;
                        Integer imgW = exif.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH);
                        Integer imgH = exif.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
                        if (imgW != null && imgH != null)
                            basic.add(new String[]{"Dimensions", imgW + " x " + imgH + " px"});
                    }
                } else if (dir instanceof GpsDirectory) {
                    target = gpsList;
                } else if (dir instanceof IptcDirectory) {
                    target = iptcList;
                } else if (dir instanceof XmpDirectory) {
                    target = xmpList;
                } else {
                    target = otherList;
                }

                for (Tag tag : dir.getTags()) {
                    String val;
                    try   { val = tag.getDescription(); }
                    catch (Exception e) { val = "(unreadable)"; }
                    if (val == null) val = "";
                    target.add(new String[]{dir.getName() + " / " + tag.getTagName(), val});
                }

                // FIX: getErrors() returns Iterable<String>, NOT Collection.
                // .isEmpty() does not exist on Iterable – use iterator().hasNext() instead.
                Iterable<String> errors = dir.getErrors();
                if (errors.iterator().hasNext()) {
                    for (String err : errors)
                        target.add(new String[]{"⚠ Error", err});
                }
            }

            if (!exifList.isEmpty())  result.put(DIR_EXIF,  exifList);
            if (!gpsList.isEmpty())   result.put(DIR_GPS,   gpsList);
            if (!iptcList.isEmpty())  result.put(DIR_IPTC,  iptcList);
            if (!xmpList.isEmpty())   result.put(DIR_XMP,   xmpList);
            if (!otherList.isEmpty()) result.put(DIR_OTHER, otherList);

        } catch (Exception e) {
            List<String[]> err = new ArrayList<>();
            err.add(new String[]{"Error", e.getMessage() != null ? e.getMessage() : e.toString()});
            result.put("Error", err);
        }
        return result;
    }

    public static String getCameraModel(File file) {
        try {
            Metadata meta = ImageMetadataReader.readMetadata(file);
            ExifIFD0Directory dir = meta.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null) {
                String make  = dir.getDescription(ExifIFD0Directory.TAG_MAKE);
                String model = dir.getDescription(ExifIFD0Directory.TAG_MODEL);
                if (make != null && model != null) return make.trim() + " " + model.trim();
                if (model != null) return model.trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String getGpsString(File file) {
        try {
            Metadata meta = ImageMetadataReader.readMetadata(file);
            GpsDirectory gps = meta.getFirstDirectoryOfType(GpsDirectory.class);
            if (gps != null) {
                com.drew.lang.GeoLocation loc = gps.getGeoLocation();
                if (loc != null)
                    return String.format("%.6f, %.6f", loc.getLatitude(), loc.getLongitude());
            }
        } catch (Exception ignored) {}
        return null;
    }
}

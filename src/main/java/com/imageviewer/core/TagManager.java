package com.imageviewer.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

public class TagManager {

    private static TagManager instance;
    private final File tagFile;
    private final Gson gson;
    private Map<String, Set<String>> data;
    private final List<Listener> listeners = new ArrayList<>();

    public interface Listener { void tagsChanged(); }

    private TagManager() {
        File dir = new File(System.getProperty("user.home"), ".imageviewer");
        dir.mkdirs();
        tagFile = new File(dir, "tags.json");
        gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    public static synchronized TagManager getInstance() {
        if (instance == null) instance = new TagManager();
        return instance;
    }

    public synchronized Set<String> getTags(File file) {
        return new LinkedHashSet<>(data.getOrDefault(key(file), Collections.emptySet()));
    }

    public synchronized void addTag(File file, String tag) {
        if (tag == null || tag.isBlank()) return;
        data.computeIfAbsent(key(file), k -> new LinkedHashSet<>()).add(tag.trim().toLowerCase());
        save(); fire();
    }

    public synchronized void removeTag(File file, String tag) {
        Set<String> tags = data.get(key(file));
        if (tags != null) { tags.remove(tag); if (tags.isEmpty()) data.remove(key(file)); }
        save(); fire();
    }

    public synchronized void setTags(File file, Collection<String> tags) {
        if (tags == null || tags.isEmpty()) data.remove(key(file));
        else data.put(key(file), new LinkedHashSet<>(tags));
        save(); fire();
    }

    public synchronized List<String> getAllTags() {
        return data.values().stream().flatMap(Collection::stream).distinct().sorted().collect(Collectors.toList());
    }

    public synchronized long getTagCount(String tag) {
        return data.values().stream().filter(s -> s.contains(tag)).count();
    }

    public synchronized List<File> getFilesWithTag(String tag) {
        return data.entrySet().stream()
            .filter(e -> e.getValue().contains(tag))
            .map(e -> new File(e.getKey()))
            .collect(Collectors.toList());
    }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private String key(File f) { return f.getAbsolutePath(); }

    private void load() {
        if (!tagFile.exists()) { data = new LinkedHashMap<>(); return; }
        try (Reader r = new FileReader(tagFile)) {
            Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
            Map<String, List<String>> raw = gson.fromJson(r, type);
            data = new LinkedHashMap<>();
            if (raw != null) raw.forEach((k, v) -> data.put(k, new LinkedHashSet<>(v)));
        } catch (Exception e) { data = new LinkedHashMap<>(); }
    }

    private void save() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        data.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        try (Writer w = new FileWriter(tagFile)) { gson.toJson(out, w); }
        catch (Exception ignored) {}
    }

    private void fire() { listeners.forEach(Listener::tagsChanged); }
}

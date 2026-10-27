package com.nous.buildkit;

import org.bukkit.block.data.BlockData;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Библиотека структур plugins/BuildKit/structures/*.bk. */
public final class StructureStore {

    private final File dir;
    private final Map<String, Structure> cache = new HashMap<>();

    public StructureStore(File dir) {
        this.dir = dir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public File dir() {
        return dir;
    }

    public File file(String name) {
        return new File(dir, name + ".bk");
    }

    public boolean exists(String name) {
        return file(name).isFile();
    }

    public Structure load(String name) {
        Structure cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        File f = file(name);
        if (!f.isFile()) {
            throw new CommandError("структура '" + name + "' не найдена в " + dir.getAbsolutePath());
        }
        try {
            Structure s = BkIO.readStructure(f);
            cache.put(name, s);
            return s;
        } catch (IOException e) {
            throw new CommandError("не удалось прочитать структуру '" + name + "': " + e.getMessage());
        }
    }

    public void save(Structure s) throws IOException {
        BkIO.writeStructure(file(s.name), s);
        cache.put(s.name, s);
    }

    public PlacementTemplate template(String name, Transform.Rot rot, Transform.Mir mir) {
        return new PlacementTemplate(load(name), rot, mir);
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".bk") && f.isFile()) {
                out.add(n.substring(0, n.length() - 3));
            }
        }
        return out;
    }

    public void invalidate() {
        cache.clear();
    }
}

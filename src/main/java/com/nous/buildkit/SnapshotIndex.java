package com.nous.buildkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Индекс автоснимков snapshots/index.tsv и запись файлов .bk. */
public final class SnapshotIndex {

    public static final class Entry {
        public final int seq;
        public final String file;
        public final String label;
        public final String world;
        public final int blocks;
        public final long timestamp;

        Entry(int seq, String file, String label, String world, int blocks, long timestamp) {
            this.seq = seq;
            this.file = file;
            this.label = label;
            this.world = world;
            this.blocks = blocks;
            this.timestamp = timestamp;
        }

        public File resolve(File dir) {
            return new File(dir, file);
        }
    }

    private static final java.util.regex.Pattern AUTO_NAME =
            java.util.regex.Pattern.compile("auto-(\\d+)-.*");
    private final File dir;
    private final File indexFile;

    public SnapshotIndex(File dir) {
        this.dir = dir;
        this.indexFile = new File(dir, "index.tsv");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public File dir() {
        return dir;
    }

    public synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        if (!indexFile.isFile()) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(indexFile.toPath(), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f.length < 6) {
                    continue;
                }
                out.add(new Entry(Integer.parseInt(f[0].trim()), f[1], f[2], f[3],
                        Integer.parseInt(f[4].trim()), Long.parseLong(f[5].trim())));
            }
        } catch (Exception e) {
            // Битый индекс не должен ронять сервер: считаем, что снимков нет.
            return new ArrayList<>();
        }
        return out;
    }

    /** Сохранить changeset и добавить строку в индекс. */
    public synchronized Entry save(ChangeSet cs) throws IOException {
        int seq = 1;
        for (Entry e : entries()) {
            if (e.seq >= seq) {
                seq = e.seq + 1;
            }
        }
        // undo убирает записи из индекса, но файлы остаются: номер не должен переиспользоваться
        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                java.util.regex.Matcher m = AUTO_NAME.matcher(f.getName());
                if (m.matches()) {
                    try {
                        seq = Math.max(seq, Integer.parseInt(m.group(1)) + 1);
                    } catch (NumberFormatException ignored) {
                        // не наш файл
                    }
                }
            }
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(cs.createdAt));
        String file = String.format(Locale.ROOT, "auto-%04d-%s-%s.bk", seq, stamp, sanitize(cs.label));
        File target = new File(dir, file);
        BkIO.writeChangeSet(target, cs);
        String line = seq + "\t" + file + "\t" + sanitize(cs.label) + "\t" + cs.worldName
                + "\t" + cs.n + "\t" + cs.createdAt + "\n";
        Files.write(indexFile.toPath(), line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return new Entry(seq, file, cs.label, cs.worldName, cs.n, cs.createdAt);
    }

    /** Убрать записи из индекса (файлы остаются на диске). */
    public synchronized void drop(List<Entry> drop) {
        List<Entry> all = entries();
        all.removeIf(e -> drop.stream().anyMatch(d -> d.seq == e.seq && d.file.equals(e.file)));
        StringBuilder sb = new StringBuilder();
        for (Entry e : all) {
            sb.append(e.seq).append('\t').append(e.file).append('\t').append(e.label).append('\t')
                    .append(e.world).append('\t').append(e.blocks).append('\t').append(e.timestamp).append('\n');
        }
        try {
            Files.write(indexFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // не критично: индекс перечитается в следующий раз
        }
    }

    private static String sanitize(String s) {
        String out = s.replaceAll("[^A-Za-z0-9_.-]", "_");
        while (out.endsWith(".bk")) {
            out = out.substring(0, out.length() - 3);
        }
        return out;
    }
}

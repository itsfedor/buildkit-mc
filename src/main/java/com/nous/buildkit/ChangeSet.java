package com.nous.buildkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Набор изменений "как было до": позиция + прежний blockstate. */
public final class ChangeSet {

    public final String label;
    public final String worldName;
    public final long createdAt;

    public int n;
    public int[] xs;
    public int[] ys;
    public int[] zs;
    public short[] pi;

    public final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    public boolean truncated;

    public ChangeSet(String label, String worldName) {
        this(label, worldName, System.currentTimeMillis());
    }

    public ChangeSet(String label, String worldName, long createdAt) {
        this.label = label;
        this.worldName = worldName;
        this.createdAt = createdAt;
        this.xs = new int[1024];
        this.ys = new int[1024];
        this.zs = new int[1024];
        this.pi = new short[1024];
    }

    public void ensure(int cap) {
        if (cap <= xs.length) {
            return;
        }
        int size = Math.max(cap, xs.length + (xs.length >> 1));
        xs = java.util.Arrays.copyOf(xs, size);
        ys = java.util.Arrays.copyOf(ys, size);
        zs = java.util.Arrays.copyOf(zs, size);
        pi = java.util.Arrays.copyOf(pi, size);
    }

    /** Записать прежнее состояние блока. Порядок записи важен: откат идёт с конца. */
    public void record(int x, int y, int z, String state) {
        if (truncated) {
            return;
        }
        if (n >= Util.MAX_CHANGES) {
            truncated = true;
            return;
        }
        Integer p = paletteIndex.get(state);
        if (p == null) {
            p = palette.size();
            if (p > 65000) {
                truncated = true;
                return;
            }
            palette.add(state);
            paletteIndex.put(state, p);
        }
        ensure(n + 1);
        xs[n] = x;
        ys[n] = y;
        zs[n] = z;
        pi[n] = (short) (int) p;
        n++;
    }

    public String state(int i) {
        return palette.get(pi[i] & 0xffff);
    }

    public long volume() {
        return n;
    }
}

package com.nous.buildkit;

import java.util.ArrayList;
import java.util.List;

/** Плотная структура: палитра blockstates + индексы (порядок y, z, x). */
public final class Structure {

    public final String name;
    public final int sx;
    public final int sy;
    public final int sz;
    public final String[] palette;
    public final int[] indices;
    private final boolean[] air;

    public Structure(String name, int sx, int sy, int sz, String[] palette, int[] indices) {
        this.name = name;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.palette = palette;
        this.indices = indices;
        this.air = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            air[i] = isAirState(palette[i]);
        }
        long expected = Util.volume(sx, sy, sz);
        if (indices.length != expected) {
            throw new IllegalStateException("структура " + name + ": ожидалось " + expected
                    + " индексов, записано " + indices.length);
        }
    }

    public static boolean isAirState(String state) {
        return state.startsWith("minecraft:air")
                || state.startsWith("minecraft:cave_air")
                || state.startsWith("minecraft:void_air");
    }

    public long volume() {
        return Util.volume(sx, sy, sz);
    }

    public boolean isAir(int paletteIndex) {
        return air[paletteIndex];
    }

    public int nonAirBlocks() {
        int n = 0;
        for (int idx : indices) {
            if (!air[idx]) {
                n++;
            }
        }
        return n;
    }

    /** Уникальные состояния палитры без воздуха. */
    public List<String> states() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < palette.length; i++) {
            if (!air[i]) {
                out.add(palette[i]);
            }
        }
        return out;
    }
}

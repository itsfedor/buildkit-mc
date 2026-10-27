package com.nous.buildkit;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

/** Палитра структуры, уже повёрнутая/отражённая: шаблон для вставки в разные точки. */
public final class PlacementTemplate {

    public final Structure structure;
    public final Transform.Rot rot;
    public final Transform.Mir mir;
    public final int sx;
    public final int sy;
    public final int sz;

    private final BlockData[] palette;

    public PlacementTemplate(Structure s, Transform.Rot rot, Transform.Mir mir) {
        this.structure = s;
        this.rot = rot;
        this.mir = mir;
        int[] d = Transform.dims(s.sx, s.sy, s.sz, rot);
        this.sx = d[0];
        this.sy = d[1];
        this.sz = d[2];
        this.palette = new BlockData[s.palette.length];
        for (int i = 0; i < s.palette.length; i++) {
            try {
                BlockData base = Bukkit.createBlockData(s.palette[i]);
                palette[i] = Transform.apply(base, rot, mir);
            } catch (IllegalArgumentException e) {
                throw new CommandError("структура " + s.name + ": неизвестный blockstate '"
                        + s.palette[i] + "'");
            }
        }
    }

    public Placement at(int ox, int oy, int oz) {
        return new Placement(this, ox, oy, oz);
    }

    BlockData data(int paletteIndex) {
        return palette[paletteIndex];
    }
}

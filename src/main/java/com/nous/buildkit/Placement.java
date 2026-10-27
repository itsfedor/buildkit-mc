package com.nous.buildkit;

/** Поток блоков структуры с поворотом/отражением. Точка вставки — min-corner. */
public final class Placement {

    public final PlacementTemplate template;
    public final int ox;
    public final int oy;
    public final int oz;

    private final int total;
    private int cursor;
    private final Structure structure;
    private final Transform.Rot rot;
    private final Transform.Mir mir;

    Placement(PlacementTemplate template, int ox, int oy, int oz) {
        this.template = template;
        this.structure = template.structure;
        this.rot = template.rot;
        this.mir = template.mir;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.total = structure.indices.length;
    }

    public int total() {
        return total;
    }

    public int cursor() {
        return cursor;
    }

    public boolean advance(Util.BlockOp op) {
        if (cursor >= total) {
            return false;
        }
        int i = cursor++;
        int lx = i % structure.sx;
        int rem = i / structure.sx;
        int lz = rem % structure.sz;
        int ly = rem / structure.sz;
        int p = structure.indices[i];
        long m = Transform.map(lx, lz, structure.sx, structure.sz, mir, rot);
        op.x = ox + (int) (m >>> 32);
        op.y = oy + ly;
        op.z = oz + (int) m;
        op.data = template.data(p);
        return true;
    }
}

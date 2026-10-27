package com.nous.buildkit;

import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Тиражирование структур по сетке nx × nz с чередованием вариантов.
 * cycle: вариант = (i + j) % n. seeded: детерминированный хеш ячейки, соседи не совпадают.
 */
public final class GridJob extends WorldJob {

    private final List<Structure> variants;
    private final int ox;
    private final int oy;
    private final int oz;
    private final int dx;
    private final int dz;
    private final int nx;
    private final int nz;
    private final int[] chosen;
    private final long seed;
    private final Map<Integer, PlacementTemplate> templates = new HashMap<>();

    private int cell;
    private Placement current;
    private int cellBlocks;
    private int totalBlocks;

    public GridJob(BuildKitPlugin plugin, CommandSender sender, World world, List<Structure> variants,
                   int ox, int oy, int oz, int dx, int dz, int nx, int nz, boolean seeded, int budget) {
        super(plugin, sender, world, budget, "grid " + nx + "x" + nz);
        this.variants = variants;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.dx = dx;
        this.dz = dz;
        this.nx = nx;
        this.nz = nz;
        this.seed = seedOf(ox, oy, oz, dx, dz, nx, nz, variants.get(0).name);
        this.chosen = new int[nx * nz];
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                int v;
                if (seeded) {
                    long h = mix(seed, i, j);
                    v = (int) Math.floorMod(h, (long) variants.size());
                    if (i > 0 && v == chosen[idx - 1]) {
                        v = (v + 1) % variants.size();
                    }
                    if (j > 0 && v == chosen[idx - nx]) {
                        v = (v + 1) % variants.size();
                    }
                } else {
                    v = (i + j) % variants.size();
                }
                chosen[idx] = v;
                totalBlocks += variants.get(v).indices.length;
            }
        }
    }

    private static long seedOf(int ox, int oy, int oz, int dx, int dz, int nx, int nz, String name) {
        long h = 0x9E3779B97F4A7C15L;
        h = h * 31 + ox;
        h = h * 31 + oy;
        h = h * 31 + oz;
        h = h * 31 + dx;
        h = h * 31 + dz;
        h = h * 31 + nx;
        h = h * 31 + nz;
        h = h * 31 + name.hashCode();
        return h;
    }

    private static long mix(long seed, int i, int j) {
        long h = seed ^ (i * 0x9E3779B97F4A7C15L) ^ (j * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }

    public int totalBlocks() {
        return totalBlocks;
    }

    private PlacementTemplate template(int variant) {
        return templates.computeIfAbsent(variant, v -> new PlacementTemplate(
                variants.get(v), Transform.Rot.NONE, Transform.Mir.NONE));
    }

    @Override
    protected void step() {
        Util.BlockOp op = new Util.BlockOp();
        int used = 0;
        while (used < budget) {
            if (current == null) {
                if (cell >= chosen.length) {
                    return;
                }
                int i = cell % nx;
                int j = cell / nx;
                current = template(chosen[cell]).at(ox + i * dx, oy, oz + j * dz);
                cellBlocks = 0;
            }
            if (!current.advance(op)) {
                cell++;
                current = null;
                continue;
            }
            placeOp(op);
            cellBlocks++;
            used++;
        }
    }

    @Override
    public boolean isDone() {
        return cell >= chosen.length && current == null;
    }

    @Override
    public String progressLine() {
        int done = cell * 100 / Math.max(1, chosen.length);
        return "ячеек " + Math.min(cell, chosen.length) + "/" + chosen.length
                + " (" + done + "%), блоков " + blocksSet + "/" + totalBlocks;
    }
}

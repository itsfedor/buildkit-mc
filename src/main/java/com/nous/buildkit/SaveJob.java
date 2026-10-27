package com.nous.buildkit;

import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Снятие области в .bk файл, порциями по тикам (не блокирует сервер). */
public final class SaveJob extends TickJob {

    private final World world;
    private final String name;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final int sx;
    private final int sy;
    private final int sz;
    private final int total;
    private final File target;
    private final boolean library;

    private final Util.BlockAccess acc;
    private final HashMap<String, Integer> paletteMap = new HashMap<>();
    private final List<String> palette = new ArrayList<>();
    private final int[] indices;

    private int cursor;
    private Structure result;

    public SaveJob(BuildKitPlugin plugin, CommandSender sender, World world, String name,
                   int x1, int y1, int z1, int x2, int y2, int z2, File target, boolean library,
                   int budget) {
        super(plugin, sender, budget, "save " + name);
        this.world = world;
        this.name = name;
        this.minX = Math.min(x1, x2);
        this.maxX = Math.max(x1, x2);
        this.minY = Math.min(y1, y2);
        this.maxY = Math.max(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxZ = Math.max(z1, z2);

        this.sx = maxX - minX + 1;
        this.sy = maxY - minY + 1;
        this.sz = maxZ - minZ + 1;
        if (sx > Util.MAX_SX || sy > Util.MAX_SY || sz > Util.MAX_SZ) {
            throw new CommandError("область " + sx + "×" + sy + "×" + sz
                    + " больше лимита структуры 64×256×64");
        }
        if (!Util.inBuildHeight(minY) || !Util.inBuildHeight(maxY)) {
            throw new CommandError("Y вне допустимого диапазона " + Util.MIN_Y + ".." + Util.MAX_Y);
        }
        this.total = (int) Util.volume(sx, sy, sz);
        this.indices = new int[total];
        this.target = target;
        this.library = library;
        this.acc = new Util.BlockAccess(world);
    }

    @Override
    protected void step() {
        int used = 0;
        while (used < budget && cursor < total) {
            int i = cursor++;
            int lx = i % sx;
            int rem = i / sx;
            int lz = rem % sz;
            int ly = rem / sz;
            String state = acc.get(minX + lx, minY + ly, minZ + lz).getAsString();
            Integer p = paletteMap.get(state);
            if (p == null) {
                p = palette.size();
                paletteMap.put(state, p);
                palette.add(state);
            }
            indices[i] = p;
            used++;
        }
    }

    @Override
    public boolean isDone() {
        return cursor >= total;
    }

    @Override
    public String progressLine() {
        return "снято " + cursor + "/" + total + " блоков";
    }

    @Override
    protected void onFinish(boolean success) {
        if (!success) {
            return;
        }
        result = new Structure(name, sx, sy, sz, palette.toArray(new String[0]), indices);
        try {
            if (library) {
                plugin.structures().save(result);
            } else {
                BkIO.writeStructure(target, result);
            }
            plugin.getLogger().info("[" + label + "] записано " + target.getAbsolutePath()
                    + " (" + result.sx + "×" + result.sy + "×" + result.sz
                    + ", палитра " + result.palette.length + ")");
        } catch (IOException e) {
            throw new CommandError("не удалось записать " + target + ": " + e.getMessage());
        }
    }

    @Override
    protected String finishSummary() {
        if (result == null) {
            return "save " + name + ": не записано";
        }
        return "save " + name + ": " + result.sx + "×" + result.sy + "×" + result.sz
                + " = " + Util.fmt(result.volume()) + " блоков, палитра " + result.palette.length
                + ", не-воздух " + Util.fmt(result.nonAirBlocks())
                + " -> " + target.getAbsolutePath();
    }
}

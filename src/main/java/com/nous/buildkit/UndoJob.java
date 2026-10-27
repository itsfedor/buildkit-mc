package com.nous.buildkit;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/** Откат последних автоснимков: блоки и blockstates возвращаются как были. */
public final class UndoJob extends TickJob {

    private final List<SnapshotIndex.Entry> entries;
    private final java.io.File dir;
    private final Util.BlockAccess acc;
    private final World world;

    private int entryIndex;
    private ChangeSet set;
    private BlockData[] cache;
    private int pos;
    private int restored;
    private int files;
    private final List<SnapshotIndex.Entry> used = new ArrayList<>();

    public UndoJob(BuildKitPlugin plugin, CommandSender sender, World world,
                   List<SnapshotIndex.Entry> entries, int budget) {
        super(plugin, sender, budget, "undo " + entries.size());
        this.world = world;
        this.entries = entries;
        this.dir = plugin.snapshots().dir();
        this.acc = new Util.BlockAccess(world);
        this.entryIndex = entries.size() - 1;
    }

    @Override
    protected void step() {
        int usedBlocks = 0;
        while (usedBlocks < budget) {
            if (set == null) {
                if (entryIndex < 0) {
                    return;
                }
                SnapshotIndex.Entry e = entries.get(entryIndex--);
                ChangeSet cs;
                try {
                    cs = BkIO.readChangeSet(e.resolve(dir));
                } catch (Exception ex) {
                    throw new CommandError("не удалось прочитать снимок " + e.file + ": " + ex.getMessage());
                }
                if (cs.truncated) {
                    plugin.getLogger().warning("[undo] снимок " + e.file
                            + " обрезан при записи — пропущен, откат неполный");
                    continue;
                }
                if (cs.worldName != null && !cs.worldName.isEmpty()
                        && !cs.worldName.equals(world.getName())) {
                    plugin.getLogger().warning("[undo] снимок " + e.file + " из мира " + cs.worldName
                            + ", а откат в " + world.getName() + " — пропущен");
                    continue;
                }
                set = cs;
                cache = new BlockData[cs.palette.size()];
                pos = cs.n - 1;
                used.add(e);
                files++;
            }
            while (pos >= 0 && usedBlocks < budget) {
                restore(pos);
                pos--;
                usedBlocks++;
            }
            if (pos < 0) {
                set = null;
            }
        }
    }

    private void restore(int i) {
        String state = set.state(i);
        int p = set.pi[i] & 0xffff;
        BlockData data = cache[p];
        if (data == null) {
            data = org.bukkit.Bukkit.createBlockData(state);
            cache[p] = data;
        }
        int x = set.xs[i];
        int y = set.ys[i];
        int z = set.zs[i];
        if (!Util.inBuildHeight(y)) {
            return;
        }
        acc.set(x, y, z, data);
        restored++;
    }

    @Override
    public boolean isDone() {
        return entryIndex < 0 && set == null;
    }

    @Override
    public String progressLine() {
        return "восстановлено " + restored + " блоков (снимков " + files + ")";
    }

    @Override
    protected void onFinish(boolean success) {
        if (success) {
            plugin.snapshots().drop(used);
        }
    }

    @Override
    protected String finishSummary() {
        return "undo: восстановлено " + restored + " блоков из " + files + " снимков";
    }
}

package com.nous.buildkit;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;

import java.io.IOException;

/** Базовая задача, меняющая мир: пишет прежние состояния и сохраняет автоснимок. */
public abstract class WorldJob extends TickJob {

    protected final World world;
    protected final Util.BlockAccess acc;
    protected final ChangeSet changes;
    protected int blocksSet;
    protected int outOfRange;
    protected SnapshotIndex.Entry snapshot;
    private String snapshotError;

    protected WorldJob(BuildKitPlugin plugin, CommandSender sender, World world, int budget, String label) {
        super(plugin, sender, budget, label);
        this.world = world;
        this.acc = new Util.BlockAccess(world);
        this.changes = new ChangeSet(label, world.getName());
    }

    /** Записать старое состояние и поставить блок. */
    protected void placeOp(Util.BlockOp op) {
        if (!Util.inBuildHeight(op.y)) {
            outOfRange++;
            return;
        }
        Block block = acc.block(op.x, op.y, op.z);
        BlockData current = block.getBlockData();
        if (op.data.getMaterial().isAir() && current.getMaterial().isAir()) {
            return;
        }
        changes.record(op.x, op.y, op.z, current.getAsString());
        block.setBlockData(op.data, false);
        blocksSet++;
    }

    /** Поставить один блок с учётом режима replace/keep/destroy. */
    protected void setBlock(int x, int y, int z, BlockData data, int mode) {
        if (!Util.inBuildHeight(y)) {
            outOfRange++;
            return;
        }
        Block block = acc.block(x, y, z);
        if (mode == RunJob.MODE_KEEP && !block.isReplaceable()) {
            return;
        }
        BlockData current = block.getBlockData();
        if (data.getMaterial().isAir() && current.getMaterial().isAir()) {
            return;
        }
        changes.record(x, y, z, current.getAsString());
        block.setBlockData(data, false);
        blocksSet++;
    }

    @Override
    protected void onFinish(boolean success) {
        if (success && changes.n > 0 && !changes.truncated) {
            try {
                snapshot = plugin.snapshots().save(changes);
                plugin.getLogger().info("[" + label + "] автоснимок " + snapshot.file
                        + " (" + changes.n + " блоков, мир " + world.getName() + ")");
            } catch (IOException e) {
                snapshotError = e.getMessage();
                plugin.getLogger().severe("[" + label + "] не удалось записать автоснимок: " + e);
            }
        }
        if (success && changes.truncated) {
            plugin.getLogger().warning("[" + label + "] снимок обрезан: изменено больше "
                    + Util.MAX_CHANGES + " блоков, undo этого шага недоступен");
        }
    }

    protected String snapshotNote() {
        if (snapshot != null) {
            return "снимок: snapshots/" + snapshot.file + " (" + changes.n + " блоков), /bk undo 1 вернёт как было";
        }
        if (changes.truncated) {
            return "снимок обрезан (> " + Util.MAX_CHANGES + " блоков)";
        }
        if (snapshotError != null) {
            return "снимок НЕ записан: " + snapshotError;
        }
        return "изменений нет, снимок не нужен";
    }

    @Override
    protected String finishSummary() {
        return progressLine() + ", блоков изменено " + blocksSet
                + (outOfRange > 0 ? ", вне высоты мира " + outOfRange : "")
                + "; " + snapshotNote();
    }
}

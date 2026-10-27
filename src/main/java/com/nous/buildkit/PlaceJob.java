package com.nous.buildkit;

import org.bukkit.World;
import org.bukkit.command.CommandSender;

/** Вставка одной структуры. */
public final class PlaceJob extends WorldJob {

    private final Placement placement;
    private final String structureName;

    public PlaceJob(BuildKitPlugin plugin, CommandSender sender, World world, Placement placement,
                    int budget, String structureName) {
        super(plugin, sender, world, budget, "place " + structureName);
        this.placement = placement;
        this.structureName = structureName;
    }

    @Override
    protected void step() {
        Util.BlockOp op = new Util.BlockOp();
        int used = 0;
        while (used < budget && placement.advance(op)) {
            placeOp(op);
            used++;
        }
    }

    @Override
    public boolean isDone() {
        return placement.cursor() >= placement.total();
    }

    @Override
    public String progressLine() {
        return "выполнено " + placement.cursor() + "/" + placement.total()
                + " блоков структуры " + structureName;
    }
}

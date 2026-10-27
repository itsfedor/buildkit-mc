package com.nous.buildkit;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Чтение мира без скачивания регионов: столбец и послойный дамп с RLE. */
public final class Dump {

    private static final int PROBE_LINES = 60;
    private static final int BOX_LINES = 200;
    private static final long BOX_MAX_VOLUME = 64_000L;

    private Dump() {
    }

    public static List<String> probe(World world, int x, int y, int z) {
        Util.BlockAccess acc = new Util.BlockAccess(world);
        List<String> out = new ArrayList<>();
        out.add("probe (" + x + "," + y + "," + z + ") мир " + world.getName() + ", Y " + Util.MIN_Y + ".." + Util.MAX_Y);
        String prev = null;
        int startY = 0;
        int endY = 0;
        for (int yy = Util.MAX_Y; yy >= Util.MIN_Y; yy--) {
            String state = acc.get(x, yy, z).getAsString();
            if (prev == null) {
                prev = state;
                startY = yy;
                endY = yy;
            } else if (state.equals(prev)) {
                endY = yy;
            } else {
                out.add(columnLine(startY, endY, prev));
                if (out.size() >= PROBE_LINES) {
                    out.add("... обрезано (лимит " + PROBE_LINES + " строк)");
                    return out;
                }
                prev = state;
                startY = yy;
                endY = yy;
            }
        }
        out.add(columnLine(startY, endY, prev));
        return out;
    }

    private static String columnLine(int startY, int endY, String state) {
        if (startY == endY) {
            return "y=" + startY + ": " + state;
        }
        return "y=" + startY + ".." + endY + ": " + state + "  (×" + (startY - endY + 1) + ")";
    }

    public static List<String> box(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        long volume = Util.volume(sx, sy, sz);
        if (volume > BOX_MAX_VOLUME) {
            throw new CommandError("box: объём " + Util.fmt(volume) + " больше " + Util.fmt(BOX_MAX_VOLUME)
                    + " — уменьшите область (дамп синхронный, в один тик)");
        }
        if (!Util.inBuildHeight(minY) || !Util.inBuildHeight(maxY)) {
            throw new CommandError("box: Y вне диапазона " + Util.MIN_Y + ".." + Util.MAX_Y);
        }

        Util.BlockAccess acc = new Util.BlockAccess(world);

        // палитра со счётчиками
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (int y = maxY; y >= minY; y--) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    String s = acc.get(x, y, z).getAsString();
                    palette.merge(s, 1, Integer::sum);
                }
            }
        }

        List<String> out = new ArrayList<>();
        out.add("box (" + minX + "," + minY + "," + minZ + ")..(" + maxX + "," + maxY + "," + maxZ + ")"
                + " = " + sx + "×" + sy + "×" + sz + " = " + Util.fmt(volume) + " блоков, палитра " + palette.size());
        int idx = 0;
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : palette.entrySet()) {
            paletteIndex.put(e.getKey(), idx);
            out.add(String.format(java.util.Locale.ROOT, "[%2d] %-70s ×%d", idx, e.getKey(), e.getValue()));
            idx++;
        }
        out.add("--- послойно (сверху вниз), RLE по X ---");

        for (int y = maxY; y >= minY; y--) {
            for (int z = minZ; z <= maxZ; z++) {
                StringBuilder sb = new StringBuilder();
                sb.append("y=").append(y).append(" z=").append(z).append(": ");
                int run = 0;
                int runIdx = -1;
                for (int x = minX; x <= maxX; x++) {
                    int p = paletteIndex.get(acc.get(x, y, z).getAsString());
                    if (run == 0) {
                        run = 1;
                        runIdx = p;
                    } else if (p == runIdx) {
                        run++;
                    } else {
                        sb.append('[').append(runIdx).append("]×").append(run).append(' ');
                        run = 1;
                        runIdx = p;
                    }
                }
                if (run > 0) {
                    sb.append('[').append(runIdx).append("]×").append(run);
                }
                out.add(sb.toString());
                if (out.size() >= BOX_LINES) {
                    out.add("... обрезано (лимит " + BOX_LINES + " строк)");
                    return out;
                }
            }
        }
        return out;
    }
}

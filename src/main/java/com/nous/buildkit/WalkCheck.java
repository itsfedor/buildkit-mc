package com.nous.buildkit;

import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * BFS по колонкам: клетка проходима, если на y и y+1 нет коллизии (и это не жидкость),
 * а под ногами твёрдый блок. Отвечает: проходимо ли, длина маршрута, где ширина 1 блок,
 * а если нет — где именно упирается.
 */
public final class WalkCheck {

    public static final class Result {
        public boolean passable;
        public int y;
        public int steps = -1;
        public int visited;
        public final List<int[]> path = new ArrayList<>();
        public final List<int[]> narrow = new ArrayList<>();
        public int[] front;
        public final List<int[]> blocked = new ArrayList<>();
        public String goalProblem;
        public boolean goalOutside;
    }

    private WalkCheck() {
    }

    public static Result run(World world, int x1, int z1, int x2, int z2, int y) {
        if (!Util.inBuildHeight(y) || !Util.inBuildHeight(y + 1)) {
            throw new CommandError("Y=" + y + " вне диапазона " + Util.MIN_Y + ".." + Util.MAX_Y);
        }

        int margin = 8;
        int minX = Math.min(x1, x2) - margin;
        int maxX = Math.max(x1, x2) + margin;
        int minZ = Math.min(z1, z2) - margin;
        int maxZ = Math.max(z1, z2) + margin;
        int w = maxX - minX + 1;
        int h = maxZ - minZ + 1;
        long cells = (long) w * h;
        if (cells > 400_000L) {
            throw new CommandError("область поиска " + w + "×" + h + " = " + Util.fmt(cells)
                    + " клеток, максимум 400000 — уменьшите прямоугольник");
        }

        Util.BlockAccess acc = new Util.BlockAccess(world);
        byte[] pass = new byte[w * h];
        Result r = new Result();
        r.y = y;

        int startIdx = (z1 - minZ) * w + (x1 - minX);
        int goalIdx = (z2 - minZ) * w + (x2 - minX);
        if (!passable(acc, pass, minX, minZ, w, x1, z1, y)) {
            r.passable = false;
            r.goalProblem = "стартовая клетка (" + x1 + "," + y + "," + z1 + ") непроходима: "
                    + describeColumn(acc, x1, y, z1);
            return r;
        }

        boolean[] seen = new boolean[w * h];
        int[] parent = new int[w * h];
        java.util.Arrays.fill(parent, -1);
        int[] queue = new int[w * h];
        int head = 0;
        int tail = 0;
        queue[tail++] = startIdx;
        seen[startIdx] = true;

        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};

        while (head < tail) {
            int cur = queue[head++];
            int cx = minX + cur % w;
            int cz = minZ + cur / w;
            for (int d = 0; d < 4; d++) {
                int nx = cx + dx[d];
                int nz = cz + dz[d];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                int ni = (nz - minZ) * w + (nx - minX);
                if (seen[ni] || !passable(acc, pass, minX, minZ, w, nx, nz, y)) {
                    continue;
                }
                seen[ni] = true;
                parent[ni] = cur;
                queue[tail++] = ni;
            }
        }
        r.visited = tail;

        if (seen[goalIdx]) {
            r.passable = true;
            List<Integer> chain = new ArrayList<>();
            int cur = goalIdx;
            while (cur != -1) {
                chain.add(cur);
                if (cur == startIdx) {
                    break;
                }
                cur = parent[cur];
            }
            for (int i = chain.size() - 1; i >= 0; i--) {
                int idx = chain.get(i);
                r.path.add(new int[]{minX + idx % w, y, minZ + idx / w});
            }
            r.steps = r.path.size() - 1;
            collectNarrow(acc, pass, minX, minZ, w, maxX, maxZ, r);
            return r;
        }

        // Непроходимо: ищем достигнутую клетку, ближайшую к цели.
        r.passable = false;
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < w * h; i++) {
            if (!seen[i]) {
                continue;
            }
            int cx = minX + i % w;
            int cz = minZ + i / w;
            int dist = Math.abs(cx - x2) + Math.abs(cz - z2);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        if (best >= 0) {
            int fx = minX + best % w;
            int fz = minZ + best / w;
            r.front = new int[]{fx, y, fz};
            for (int d = 0; d < 4; d++) {
                int nx = fx + dx[d];
                int nz = fz + dz[d];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                if (!passable(acc, pass, minX, minZ, w, nx, nz, y)) {
                    r.blocked.add(new int[]{nx, y, nz});
                }
            }
        }
        if (!passable(acc, pass, minX, minZ, w, x2, z2, y)) {
            r.goalProblem = "целевая клетка (" + x2 + "," + y + "," + z2 + ") непроходима: "
                    + describeColumn(acc, x2, y, z2);
        }
        return r;
    }

    private static void collectNarrow(Util.BlockAccess acc, byte[] pass, int minX, int minZ, int w,
                                      int maxX, int maxZ, Result r) {
        for (int i = 1; i < r.path.size(); i++) {
            int[] prev = r.path.get(i - 1);
            int[] cell = r.path.get(i);
            int ddx = cell[0] - prev[0];
            int ddz = cell[2] - prev[2];
            int width;
            if (ddx != 0) {
                width = corridor(acc, pass, minX, minZ, w, cell[0], cell[2], r.y, 0, 1);
            } else {
                width = corridor(acc, pass, minX, minZ, w, cell[0], cell[2], r.y, 1, 0);
            }
            if (width == 1) {
                boolean duplicate = !r.narrow.isEmpty()
                        && r.narrow.get(r.narrow.size() - 1)[0] == cell[0]
                        && r.narrow.get(r.narrow.size() - 1)[2] == cell[2];
                if (!duplicate) {
                    r.narrow.add(cell);
                }
            }
        }
    }

    /** Ширина коридора в клетках в направлении (ax,az) через точку, максимум 33. */
    private static int corridor(Util.BlockAccess acc, byte[] pass, int minX, int minZ, int w,
                                int x, int z, int y, int ax, int az) {
        int count = 1;
        for (int sign = -1; sign <= 1; sign += 2) {
            for (int k = 1; k <= 16; k++) {
                int nx = x + ax * k * sign;
                int nz = z + az * k * sign;
                if (!passable(acc, pass, minX, minZ, w, nx, nz, y)) {
                    break;
                }
                count++;
            }
        }
        return count;
    }

    private static boolean passable(Util.BlockAccess acc, byte[] cache, int minX, int minZ, int w,
                                    int x, int z, int y) {
        int ix = x - minX;
        int iz = z - minZ;
        if (ix < 0 || iz < 0) {
            return false;
        }
        int idx = iz * w + ix;
        if (idx < 0 || idx >= cache.length) {
            return false;
        }
        if (cache[idx] != 0) {
            return cache[idx] == 1;
        }
        boolean ok = computePassable(acc, x, y, z);
        cache[idx] = (byte) (ok ? 1 : 2);
        return ok;
    }

    private static boolean computePassable(Util.BlockAccess acc, int x, int y, int z) {
        Block feet = acc.block(x, y, z);
        if (!feet.isPassable() || feet.isLiquid()) {
            return false;
        }
        Block head = acc.block(x, y + 1, z);
        if (!head.isPassable() || head.isLiquid()) {
            return false;
        }
        Block floor = acc.block(x, y - 1, z);
        return floor.getType().isSolid() && !floor.isPassable();
    }

    private static String describeColumn(Util.BlockAccess acc, int x, int y, int z) {
        Block feet = acc.block(x, y, z);
        Block head = acc.block(x, y + 1, z);
        Block floor = acc.block(x, y - 1, z);
        StringBuilder sb = new StringBuilder();
        sb.append("ноги ").append(feet.getBlockData().getAsString());
        if (feet.isPassable() && !feet.isLiquid()) {
            sb.append(", голова ").append(head.getBlockData().getAsString());
            sb.append(", под ногами ").append(floor.getBlockData().getAsString());
        }
        return sb.toString();
    }

    public static List<String> format(Result r, int x1, int z1, int x2, int z2) {
        List<String> out = new ArrayList<>();
        out.add("walkcheck y=" + r.y + ": (" + x1 + "," + z1 + ") -> (" + x2 + "," + z2 + ")");
        out.add("проходимо: " + (r.passable ? "да" : "нет"));
        if (r.passable) {
            out.add("длина маршрута: " + r.steps + " шагов (" + r.path.size() + " клеток)");
            if (r.narrow.isEmpty()) {
                out.add("мест шириной 1 блок: нет");
            } else {
                StringBuilder sb = new StringBuilder("мест шириной 1 блок: " + r.narrow.size() + " —");
                int shown = 0;
                for (int[] c : r.narrow) {
                    if (shown++ >= 12) {
                        sb.append(" ...");
                        break;
                    }
                    sb.append(" (").append(c[0]).append(',').append(c[2]).append(')');
                }
                out.add(sb.toString());
            }
        } else {
            if (r.goalProblem != null) {
                out.add(r.goalProblem);
            }
            if (r.front != null) {
                out.add("достигнуто: (" + r.front[0] + "," + r.front[1] + "," + r.front[2]
                        + "), пройдено клеток: " + r.visited);
            }
            if (!r.blocked.isEmpty()) {
                StringBuilder sb = new StringBuilder("узкое место: блокирует");
                int shown = 0;
                for (int[] b : r.blocked) {
                    if (shown++ >= 8) {
                        sb.append(" ...");
                        break;
                    }
                    sb.append(" (").append(b[0]).append(',').append(b[1]).append(',').append(b[2]).append(')');
                }
                out.add(sb.toString());
            }
        }
        return out;
    }
}

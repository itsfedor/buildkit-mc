package com.nous.buildkit;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/** Общие константы, парсинг аргументов и кэширующий доступ к блокам. */
public final class Util {

    /** Максимальный объём одной операции с миром. */
    public static final int MAX_OP_VOLUME = 200_000;
    /** С этого объёма операция выдаёт предупреждение. */
    public static final int WARN_VOLUME = 50_000;
    /** Максимальные размеры структуры. */
    public static final int MAX_SX = 64;
    public static final int MAX_SY = 256;
    public static final int MAX_SZ = 64;
    /** Предел записываемых изменений (страховка от переполнения памяти). */
    public static final int MAX_CHANGES = 600_000;
    /** Границы мира 1.21+ (в этой версии API World не отдаёт min/max height). */
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;

    private Util() {
    }

    public static long volume(int sx, int sy, int sz) {
        return (long) sx * (long) sy * (long) sz;
    }

    public static String fmt(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', ' ');
    }

    public static int parseInt(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new CommandError("не число: '" + token + "'");
        }
    }

    public static String structureName(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new CommandError("недопустимое имя '" + raw
                    + "' (разрешены A-Z a-z 0-9 _ . -, до 64 символов)");
        }
        return raw;
    }

    /** Имя файла скрипта: без каталогов и выхода за пределы scripts/. */
    public static String scriptName(String raw) {
        if (raw == null || raw.isEmpty() || raw.contains("/") || raw.contains("\\")
                || raw.contains("..") || raw.length() > 128) {
            throw new CommandError("недопустимое имя файла скрипта '" + raw + "'");
        }
        return raw;
    }

    public static World world(CommandSender sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getWorld();
        }
        return Bukkit.getWorlds().get(0);
    }

    public static World worldByName(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) {
            throw new CommandError("мир не найден: " + name);
        }
        return w;
    }

    public static boolean inBuildHeight(int y) {
        return y >= MIN_Y && y <= MAX_Y;
    }

    /** Координаты из args[from..from+n-1]; поддерживает и "x y z", и "x,y,z". */
    public static int[] coords(String[] args, int from, int n) {
        java.util.List<String> flat = new java.util.ArrayList<>();
        for (int i = from; i < args.length; i++) {
            if (args[i].indexOf(',') >= 0) {
                for (String part : args[i].split(",")) {
                    if (!part.isEmpty()) {
                        flat.add(part);
                    }
                }
            } else {
                flat.add(args[i]);
            }
        }
        if (flat.size() < n) {
            throw new CommandError("нужно " + n + " координат(ы), получено " + flat.size());
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = parseInt(flat.get(i));
        }
        return out;
    }

    /** Доступ к блокам с кэшем последнего чанка: в разы быстрее world.getBlockAt на каждый блок. */
    public static final class BlockAccess {
        private final World world;
        private Chunk chunk;
        private int cx = Integer.MIN_VALUE;
        private int cz = Integer.MIN_VALUE;

        public BlockAccess(World world) {
            this.world = world;
        }

        public World world() {
            return world;
        }

        public Block block(int x, int y, int z) {
            int ncx = x >> 4;
            int ncz = z >> 4;
            if (chunk == null || ncx != cx || ncz != cz) {
                chunk = world.getChunkAt(ncx, ncz);
                cx = ncx;
                cz = ncz;
            }
            return chunk.getBlock(x & 15, y, z & 15);
        }

        public BlockData get(int x, int y, int z) {
            return block(x, y, z).getBlockData();
        }

        public void set(int x, int y, int z, BlockData data) {
            block(x, y, z).setBlockData(data, false);
        }
    }

    /** Одна операция установки блока, переиспользуется в потоках. */
    public static final class BlockOp {
        public int x;
        public int y;
        public int z;
        public BlockData data;
    }
}

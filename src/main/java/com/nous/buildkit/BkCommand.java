package com.nous.buildkit;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Реализация /bk. */
public final class BkCommand implements CommandExecutor, TabCompleter {

    private static final int DEFAULT_BUDGET = 20_000;

    private final BuildKitPlugin plugin;

    public BkCommand(BuildKitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("buildkit.use")) {
            sender.sendMessage("§c[BuildKit] отказ: нет права buildkit.use");
            plugin.getLogger().warning("[BuildKit] отказ в доступе: " + sender.getName() + " без buildkit.use");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            Flags f = Flags.parse(args);
            switch (sub) {
                case "list":
                    return cmdList(sender);
                case "info":
                    return cmdInfo(sender, f);
                case "save":
                    return cmdSave(sender, f);
                case "place":
                    return cmdPlace(sender, f);
                case "grid":
                    return cmdGrid(sender, f);
                case "run":
                    return cmdRun(sender, f);
                case "walkcheck":
                    return cmdWalkCheck(sender, f);
                case "probe":
                    return cmdProbe(sender, f);
                case "box":
                    return cmdBox(sender, f);
                case "snapshot":
                    return cmdSnapshot(sender, f);
                case "undo":
                    return cmdUndo(sender, f);
                case "export":
                    return cmdExport(sender, f);
                case "permmock":
                    return permMock(sender);
                default:
                    sender.sendMessage("§c[BuildKit] неизвестная подкоманда: " + sub);
                    help(sender);
                    return true;
            }
        } catch (CommandError e) {
            sender.sendMessage("§c[BuildKit] " + e.getMessage());
            plugin.getLogger().warning("[bk " + sub + "] " + e.getMessage());
            return true;
        } catch (Throwable t) {
            sender.sendMessage("§c[BuildKit] внутренняя ошибка: " + t);
            plugin.getLogger().severe("[bk " + sub + "] " + t);
            t.printStackTrace();
            return true;
        }
    }

    // ------------------------------------------------------------------ команды

    private boolean cmdList(CommandSender sender) {
        List<String> names = plugin.structures().list();
        if (names.isEmpty()) {
            sender.sendMessage("[BuildKit] библиотека пуста: " + plugin.structures().dir().getAbsolutePath());
            return true;
        }
        sender.sendMessage("[BuildKit] структур: " + names.size() + " (" + plugin.structures().dir().getAbsolutePath() + ")");
        for (String n : names) {
            try {
                Structure s = plugin.structures().load(n);
                long size = plugin.structures().file(n).length();
                sender.sendMessage("  " + n + ": " + s.sx + "×" + s.sy + "×" + s.sz
                        + " = " + Util.fmt(s.volume()) + " блоков, палитра " + s.palette.length
                        + ", не-воздух " + Util.fmt(s.nonAirBlocks()) + ", " + Util.fmt(size) + " Б");
            } catch (CommandError e) {
                sender.sendMessage("  " + n + ": ОШИБКА ЧТЕНИЯ — " + e.getMessage());
            }
        }
        return true;
    }

    private boolean cmdInfo(CommandSender sender, Flags f) {
        if (f.pos.isEmpty()) {
            throw new CommandError("формат: /bk info <имя>");
        }
        String name = Util.structureName(f.pos.get(0));
        Structure s = plugin.structures().load(name);
        sender.sendMessage("[BuildKit] " + name + ": " + s.sx + "×" + s.sy + "×" + s.sz
                + " = " + Util.fmt(s.volume()) + " блоков, палитра " + s.palette.length
                + ", не-воздух " + Util.fmt(s.nonAirBlocks()));
        sender.sendMessage("  файл: " + plugin.structures().file(name).getAbsolutePath()
                + " (" + Util.fmt(plugin.structures().file(name).length()) + " Б)");
        List<String> states = s.states();
        int shown = 0;
        StringBuilder sb = new StringBuilder("  палитра: ");
        for (String st : states) {
            if (shown++ >= 12) {
                sb.append("... ещё ").append(states.size() - 12);
                break;
            }
            if (shown > 1) {
                sb.append(", ");
            }
            sb.append(st);
        }
        sender.sendMessage(sb.toString());
        return true;
    }

    private boolean cmdSave(CommandSender sender, Flags f) {
        requireIdle();
        if (f.pos.size() < 7) {
            throw new CommandError("формат: /bk save <имя> <x1 y1 z1> <x2 y2 z2>");
        }
        String name = Util.structureName(f.pos.get(0));
        int[] a = ints(f.pos, 1);
        int[] b = ints(f.pos, 4);
        World world = Util.world(sender);
        SaveJob job = new SaveJob(plugin, sender, world, name, a[0], a[1], a[2], b[0], b[1], b[2],
                plugin.structures().file(name), true, f.budget);
        plugin.jobs().submit(job);
        sender.sendMessage("§7[BuildKit] save " + name + " запущен, пишу "
                + plugin.structures().file(name).getAbsolutePath());
        return true;
    }

    private boolean cmdPlace(CommandSender sender, Flags f) {
        requirePerm(sender, "buildkit.place");
        requireIdle();
        if (f.pos.size() < 4) {
            throw new CommandError("формат: /bk place <имя> <x y z> [rot=0|90|180|270] [mirror=none|x|z]");
        }
        String name = Util.structureName(f.pos.get(0));
        int[] p = ints(f.pos, 1);
        RotMir rm = rotMir(f.pos, 4);
        World world = Util.world(sender);
        Structure s = plugin.structures().load(name);
        PlacementTemplate tpl = new PlacementTemplate(s, rm.rot, rm.mir);
        long volume = Util.volume(tpl.sx, tpl.sy, tpl.sz);
        checkVolume(volume, "place " + name);
        int maxX = p[0] + tpl.sx - 1;
        int maxY = p[1] + tpl.sy - 1;
        int maxZ = p[2] + tpl.sz - 1;
        if (!Util.inBuildHeight(p[1]) || !Util.inBuildHeight(maxY)) {
            throw new CommandError("Y выходит за " + Util.MIN_Y + ".." + Util.MAX_Y);
        }
        guard(sender, world, p[0], p[1], p[2], maxX, maxY, maxZ, f.force);
        warnVolume(sender, volume);
        writeNotice(sender);
        plugin.jobs().submit(new PlaceJob(plugin, sender, world, tpl.at(p[0], p[1], p[2]),
                f.budget, name));
        sender.sendMessage("§7[BuildKit] place " + name + " rot=" + rm.rot.deg + " mirror=" + rm.mir.name().toLowerCase(Locale.ROOT)
                + " в (" + p[0] + "," + p[1] + "," + p[2] + "), bbox " + tpl.sx + "×" + tpl.sy + "×" + tpl.sz
                + " = " + Util.fmt(volume) + " блоков (min-corner)");
        return true;
    }

    private boolean cmdGrid(CommandSender sender, Flags f) {
        requirePerm(sender, "buildkit.place");
        requireIdle();
        if (f.pos.size() < 8) {
            throw new CommandError("формат: /bk grid <имена через запятую> <x y z> <dx dz> <nx nz> [cycle|seeded]");
        }
        String[] nameList = f.pos.get(0).split(",");
        List<Structure> variants = new ArrayList<>();
        for (String n : nameList) {
            if (n.trim().isEmpty()) {
                continue;
            }
            variants.add(plugin.structures().load(Util.structureName(n.trim())));
        }
        if (variants.isEmpty()) {
            throw new CommandError("не указано ни одного имени структуры");
        }
        int[] o = ints(f.pos, 1);
        int[] d = intsN(f.pos, 4, 2);
        int[] n = intsN(f.pos, 6, 2);
        if (n[0] < 1 || n[1] < 1) {
            throw new CommandError("nx и nz должны быть >= 1");
        }
        if ((long) n[0] * n[1] > 10_000L) {
            throw new CommandError("слишком много ячеек: " + n[0] + "×" + n[1]);
        }
        if (d[0] == 0 || d[1] == 0) {
            throw new CommandError("dx и dz не могут быть нулём");
        }
        boolean seeded = false;
        if (f.pos.size() > 8) {
            String mode = f.pos.get(8).toLowerCase(Locale.ROOT);
            if (mode.equals("seeded")) {
                seeded = true;
            } else if (!mode.equals("cycle")) {
                throw new CommandError("режим должен быть cycle или seeded (получено '" + f.pos.get(8) + "')");
            }
        }
        if (f.pos.size() > 9) {
            throw new CommandError("лишние аргументы: " + f.pos.subList(9, f.pos.size()));
        }
        World world = Util.world(sender);
        GridJob job = new GridJob(plugin, sender, world, variants, o[0], o[1], o[2], d[0], d[1],
                n[0], n[1], seeded, f.budget);
        long total = job.totalBlocks();
        checkVolume(total, "grid " + n[0] + "×" + n[1]);
        warnVolume(sender, total);

        int maxSx = 0;
        int maxSy = 0;
        int maxSz = 0;
        for (Structure v : variants) {
            maxSx = Math.max(maxSx, v.sx);
            maxSy = Math.max(maxSy, v.sy);
            maxSz = Math.max(maxSz, v.sz);
        }
        int x1 = Math.min(o[0], o[0] + (n[0] - 1) * d[0]);
        int z1 = Math.min(o[2], o[2] + (n[1] - 1) * d[1]);
        int x2 = Math.max(o[0], o[0] + (n[0] - 1) * d[0]) + maxSx - 1;
        int z2 = Math.max(o[2], o[2] + (n[1] - 1) * d[1]) + maxSz - 1;
        int y2 = o[1] + maxSy - 1;
        if (!Util.inBuildHeight(o[1]) || !Util.inBuildHeight(y2)) {
            throw new CommandError("Y выходит за " + Util.MIN_Y + ".." + Util.MAX_Y);
        }
        guard(sender, world, x1, o[1], z1, x2, y2, z2, f.force);
        writeNotice(sender);
        plugin.jobs().submit(job);
        sender.sendMessage("§7[BuildKit] grid " + n[0] + "×" + n[1] + " (" + variants.size()
                + " вариантов, " + (seeded ? "seeded" : "cycle") + "), суммарный объём "
                + Util.fmt(total) + " блоков, bbox (" + x1 + "," + o[1] + "," + z1 + ")..("
                + x2 + "," + y2 + "," + z2 + ")");
        return true;
    }

    private boolean cmdRun(CommandSender sender, Flags f) {
        requirePerm(sender, "buildkit.run");
        requireIdle();
        if (f.pos.isEmpty()) {
            throw new CommandError("формат: /bk run <файл> [--tick-budget=N]");
        }
        String fileName = Util.scriptName(f.pos.get(0));
        File file = new File(plugin.scripts(), fileName);
        if (!file.isFile()) {
            throw new CommandError("скрипт не найден: " + file.getAbsolutePath());
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CommandError("не удалось прочитать " + file + ": " + e.getMessage());
        }
        List<RunJob.Cmd> cmds = RunJob.parse(lines, plugin.structures());
        if (cmds.isEmpty()) {
            throw new CommandError("скрипт " + fileName + " не содержит команд");
        }
        World world = Util.world(sender);

        long planned = 0;
        int x1 = Integer.MAX_VALUE;
        int y1 = Integer.MAX_VALUE;
        int z1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE;
        int y2 = Integer.MIN_VALUE;
        int z2 = Integer.MIN_VALUE;
        for (RunJob.Cmd c : cmds) {
            if (c.kind == RunJob.WAIT) {
                continue;
            }
            int cx2 = c.x1;
            int cy2 = c.y1;
            int cz2 = c.z1;
            if (c.kind == RunJob.PLACE) {
                Structure s = plugin.structures().load(c.structureName);
                int[] dims = Transform.dims(s.sx, s.sy, s.sz, c.rot);
                cx2 = c.x1 + dims[0] - 1;
                cy2 = c.y1 + dims[1] - 1;
                cz2 = c.z1 + dims[2] - 1;
                planned += Util.volume(dims[0], dims[1], dims[2]);
            } else if (c.kind == RunJob.FILL) {
                planned += Util.volume(c.x2 - c.x1 + 1, c.y2 - c.y1 + 1, c.z2 - c.z1 + 1);
            } else {
                planned++;
            }
            x1 = Math.min(x1, c.x1);
            y1 = Math.min(y1, c.y1);
            z1 = Math.min(z1, c.z1);
            x2 = Math.max(x2, cx2);
            y2 = Math.max(y2, cy2);
            z2 = Math.max(z2, cz2);
        }
        if (planned > Util.MAX_OP_VOLUME) {
            throw new CommandError("скрипт меняет " + Util.fmt(planned) + " блоков, лимит "
                    + Util.fmt(Util.MAX_OP_VOLUME) + " — разбейте на части");
        }
        if (x1 != Integer.MAX_VALUE) {
            guard(sender, world, x1, y1, z1, x2, y2, z2, f.force);
        }
        warnVolume(sender, planned);
        writeNotice(sender);
        plugin.jobs().submit(new RunJob(plugin, sender, world, cmds, f.budget, fileName));
        sender.sendMessage("§7[BuildKit] run " + fileName + ": " + cmds.size() + " команд, "
                + Util.fmt(planned) + " блоков, бюджет " + f.budget + " блоков/тик");
        return true;
    }

    private boolean cmdWalkCheck(CommandSender sender, Flags f) {
        if (f.pos.size() < 4) {
            throw new CommandError("формат: /bk walkcheck <x1 z1> <x2 z2> [y]");
        }
        int[] c = intsN(f.pos, 0, 4);
        World world = Util.world(sender);
        int y = f.pos.size() >= 5 ? Util.parseInt(f.pos.get(4))
                : world.getHighestBlockYAt(c[0], c[1]) + 1;
        WalkCheck.Result r = WalkCheck.run(world, c[0], c[1], c[2], c[3], y);
        for (String line : WalkCheck.format(r, c[0], c[1], c[2], c[3])) {
            sender.sendMessage("[BuildKit] " + line);
        }
        return true;
    }

    private boolean cmdProbe(CommandSender sender, Flags f) {
        if (f.pos.size() < 3) {
            throw new CommandError("формат: /bk probe <x y z>");
        }
        int[] p = ints(f.pos, 0);
        for (String line : Dump.probe(Util.world(sender), p[0], p[1], p[2])) {
            sender.sendMessage(line);
        }
        return true;
    }

    private boolean cmdBox(CommandSender sender, Flags f) {
        if (f.pos.size() < 6) {
            throw new CommandError("формат: /bk box <x1 y1 z1> <x2 y2 z2>");
        }
        int[] a = ints(f.pos, 0);
        int[] b = ints(f.pos, 3);
        for (String line : Dump.box(Util.world(sender), a[0], a[1], a[2], b[0], b[1], b[2])) {
            sender.sendMessage(line);
        }
        return true;
    }

    private boolean cmdSnapshot(CommandSender sender, Flags f) {
        requireIdle();
        if (f.pos.size() < 7) {
            throw new CommandError("формат: /bk snapshot <имя> <x1 y1 z1> <x2 y2 z2>");
        }
        String name = Util.structureName(f.pos.get(0));
        int[] a = ints(f.pos, 1);
        int[] b = ints(f.pos, 4);
        File target = new File(plugin.snapshots().dir(), name + ".bk");
        SaveJob job = new SaveJob(plugin, sender, Util.world(sender), name, a[0], a[1], a[2],
                b[0], b[1], b[2], target, false, f.budget);
        plugin.jobs().submit(job);
        sender.sendMessage("§7[BuildKit] snapshot " + name + " пишу в " + target.getAbsolutePath());
        return true;
    }

    private boolean cmdUndo(CommandSender sender, Flags f) {
        requirePerm(sender, "buildkit.undo");
        requireIdle();
        int n = f.pos.isEmpty() ? 1 : Util.parseInt(f.pos.get(0));
        if (n < 1) {
            throw new CommandError("n должно быть >= 1");
        }
        List<SnapshotIndex.Entry> all = plugin.snapshots().entries();
        if (all.isEmpty()) {
            throw new CommandError("автоснимков нет: " + plugin.snapshots().dir().getAbsolutePath());
        }
        n = Math.min(n, all.size());
        List<SnapshotIndex.Entry> take = new ArrayList<>(all.subList(all.size() - n, all.size()));
        for (SnapshotIndex.Entry e : take) {
            if (!e.resolve(plugin.snapshots().dir()).isFile()) {
                throw new CommandError("файл снимка отсутствует: " + e.file
                        + " — откат невозможен, проверьте " + plugin.snapshots().dir().getAbsolutePath());
            }
        }
        writeNotice(sender);
        plugin.jobs().submit(new UndoJob(plugin, sender, Util.world(sender), take, f.budget));
        sender.sendMessage("§7[BuildKit] undo " + n + ": " + take.stream().mapToInt(e -> e.blocks).sum()
                + " блоков из снимков " + take.get(0).file + (n > 1 ? " ... " + take.get(n - 1).file : ""));
        return true;
    }

    private boolean cmdExport(CommandSender sender, Flags f) {
        if (f.pos.size() < 5) {
            throw new CommandError("формат: /bk export <имя> <x y z> <файл> [rot=..] [mirror=..]");
        }
        String name = Util.structureName(f.pos.get(0));
        int[] p = ints(f.pos, 1);
        String fileName = Util.scriptName(f.pos.get(4));
        RotMir rm = rotMir(f.pos, 5);
        Structure s = plugin.structures().load(name);
        PlacementTemplate tpl = new PlacementTemplate(s, rm.rot, rm.mir);
        Placement placement = tpl.at(p[0], p[1], p[2]);
        Util.BlockOp op = new Util.BlockOp();
        List<String> lines = new ArrayList<>();
        lines.add("# BuildKit export: " + name + " -> " + fileName
                + " (rot=" + rm.rot.deg + ", mirror=" + rm.mir.name().toLowerCase(Locale.ROOT)
                + ", min-corner " + p[0] + " " + p[1] + " " + p[2] + ")");
        int blocks = 0;
        while (placement.advance(op)) {
            if (op.data.getMaterial().isAir()) {
                continue;
            }
            lines.add("setblock " + op.x + " " + op.y + " " + op.z + " " + op.data.getAsString());
            blocks++;
        }
        File out = new File(plugin.scripts(), fileName);
        try {
            Files.write(out.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CommandError("не удалось записать " + out + ": " + e.getMessage());
        }
        sender.sendMessage("§a[BuildKit] export " + name + " -> " + out.getAbsolutePath()
                + ": " + blocks + " setblock-команд (воздух пропущен, " + tpl.sx + "×" + tpl.sy + "×" + tpl.sz + ")");
        plugin.getLogger().info("[bk export] " + name + " -> " + out.getName() + ", " + blocks + " команд");
        return true;
    }

    /** Самопроверка прав: команда от не-op отправителя должна быть отклонена. */
    private boolean permMock(CommandSender sender) {
        if (!(sender instanceof ConsoleCommandSender)) {
            throw new CommandError("permmock доступен только из консоли");
        }
        final List<String> seen = new ArrayList<>();
        CommandSender mock = (CommandSender) Proxy.newProxyInstance(
                BkCommand.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "hasPermission":
                            return false;
                        case "isOp":
                            return false;
                        case "getName":
                        case "name":
                            return "PermMock";
                        case "sendMessage":
                            if (margs != null) {
                                for (Object o : margs) {
                                    seen.add(String.valueOf(o));
                                }
                            }
                            return null;
                        case "toString":
                            return "PermMock";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return margs != null && margs.length > 0 && proxy == margs[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        onCommand(mock, plugin.getCommand("bk"), "bk", new String[]{"list"});
        boolean refused = seen.stream().anyMatch(s -> s.contains("buildkit.use"));
        plugin.getLogger().info("[BuildKit] permmock: не-op получил " + seen + "; отказ=" + refused);
        sender.sendMessage("[BuildKit] permmock: не-op получил " + seen + "; отказ=" + refused);
        return true;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    // ------------------------------------------------------------------ помощники

    private void help(CommandSender sender) {
        sender.sendMessage("BuildKit — headless-постройки. Команды:");
        sender.sendMessage("  /bk save <имя> <x1 y1 z1> <x2 y2 z2> — снять область в structures/<имя>.bk");
        sender.sendMessage("  /bk place <имя> <x y z> [rot=0|90|180|270] [mirror=none|x|z] — вставка, точка = min-corner");
        sender.sendMessage("  /bk grid <имена> <x y z> <dx dz> <nx nz> [cycle|seeded] — сетка с чередованием");
        sender.sendMessage("  /bk run <файл> [--tick-budget=N] — скрипт из scripts/ (setblock/fill/place/wait/#)");
        sender.sendMessage("  /bk walkcheck <x1 z1> <x2 z2> [y] — проходимость по колонкам (BFS)");
        sender.sendMessage("  /bk probe <x y z> — столбец; /bk box <x1 y1 z1> <x2 y2 z2> — послойный RLE-дамп");
        sender.sendMessage("  /bk snapshot <имя> <x1 y1 z1> <x2 y2 z2> — снимок в snapshots/");
        sender.sendMessage("  /bk undo [n] — откат последних n операций по автоснимкам");
        sender.sendMessage("  /bk list | /bk info <имя> | /bk export <имя> <x y z> <файл> [rot=..] [mirror=..]");
        sender.sendMessage("  Флаг --force обходит проверку чужих регионов (пишется в лог).");
    }

    private void requirePerm(CommandSender sender, String node) {
        if (!sender.hasPermission(node)) {
            throw new CommandError("отказ: нет права " + node);
        }
    }

    private void requireIdle() {
        if (plugin.jobs().busy()) {
            throw new CommandError("уже выполняется '" + plugin.jobs().current().label()
                    + "' — дождитесь окончания");
        }
    }

    private void checkVolume(long volume, String what) {
        if (volume > Util.MAX_OP_VOLUME) {
            throw new CommandError(what + ": объём " + Util.fmt(volume) + " блоков больше лимита "
                    + Util.fmt(Util.MAX_OP_VOLUME));
        }
    }

    private void warnVolume(CommandSender sender, long volume) {
        if (volume > Util.WARN_VOLUME) {
            String text = "[BuildKit] объём " + Util.fmt(volume) + " > "
                    + Util.fmt(Util.WARN_VOLUME) + ": операция пойдёт порциями по "
                    + Util.fmt(DEFAULT_BUDGET) + " блоков/тик, следите за TPS";
            sender.sendMessage("§e" + text);
            plugin.getLogger().warning(text);
        }
    }

    private void writeNotice(CommandSender sender) {
        sender.sendMessage("§7[BuildKit] правки попадут в лог CoreProtect; автоснимок для /bk undo будет создан автоматически");
    }

    private void guard(CommandSender sender, World world, int x1, int y1, int z1,
                       int x2, int y2, int z2, boolean force) {
        UUID actor = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        List<Guard.Hit> hits = Guard.check(world, x1, y1, z1, x2, y2, z2, actor);
        if (hits.isEmpty()) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (Guard.Hit h : hits) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(h.describe());
        }
        if (!force) {
            throw new CommandError("в bbox попадают чужие регионы: " + names
                    + " — операция прервана до первого блока; проверьте вручную и повторите с --force");
        }
        String warn = "[BuildKit] --force: игнорирую регионы " + names + " (оператор " + sender.getName()
                + "), bbox (" + x1 + "," + y1 + "," + z1 + ")..(" + x2 + "," + y2 + "," + z2 + ")";
        plugin.getLogger().warning(warn);
        sender.sendMessage("§e" + warn);
    }

    private static int[] ints(List<String> pos, int from) {
        return intsN(pos, from, 3);
    }

    private static int[] intsN(List<String> pos, int from, int n) {
        if (pos.size() < from + n) {
            throw new CommandError("нужно " + n + " чисел, получено " + Math.max(0, pos.size() - from));
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = Util.parseInt(pos.get(from + i));
        }
        return out;
    }

    private static final class RotMir {
        Transform.Rot rot = Transform.Rot.NONE;
        Transform.Mir mir = Transform.Mir.NONE;
    }

    private static RotMir rotMir(List<String> pos, int from) {
        RotMir rm = new RotMir();
        for (int i = from; i < pos.size(); i++) {
            String raw = pos.get(i);
            String t = raw.toLowerCase(Locale.ROOT);
            if (t.startsWith("rot=") || t.matches("\\d+")) {
                rm.rot = Transform.Rot.ofToken(raw);
            } else if (t.startsWith("mirror=") || t.equals("x") || t.equals("z") || t.equals("none")) {
                rm.mir = Transform.Mir.of(raw);
            } else {
                throw new CommandError("лишний аргумент: " + raw);
            }
        }
        return rm;
    }

    private static final class Flags {
        boolean force;
        int budget = DEFAULT_BUDGET;
        final List<String> pos = new ArrayList<>();

        static Flags parse(String[] args) {
            Flags f = new Flags();
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--force")) {
                    f.force = true;
                } else if (a.startsWith("--tick-budget=")) {
                    f.budget = Util.parseInt(a.substring("--tick-budget=".length()));
                } else if (a.equals("--tick-budget")) {
                    if (i + 1 >= args.length) {
                        throw new CommandError("--tick-budget требует число");
                    }
                    f.budget = Util.parseInt(args[++i]);
                } else {
                    f.pos.add(a);
                }
            }
            if (f.budget < 1) {
                throw new CommandError("--tick-budget должен быть >= 1");
            }
            if (f.budget > 200_000) {
                f.budget = 200_000;
            }
            return f;
        }
    }

    // ------------------------------------------------------------------ tab-complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("buildkit.use")) {
            return Collections.emptyList();
        }
        List<String> subs = List.of("help", "list", "info", "save", "place", "grid", "run",
                "walkcheck", "probe", "box", "snapshot", "undo", "export");
        if (args.length == 1) {
            return filter(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("place") || sub.equals("info") || sub.equals("export")) {
                return filter(plugin.structures().list(), args[1]);
            }
            if (sub.equals("run")) {
                List<String> files = new ArrayList<>();
                File[] list = plugin.scripts().listFiles();
                if (list != null) {
                    for (File file : list) {
                        if (file.isFile()) {
                            files.add(file.getName());
                        }
                    }
                }
                return filter(files, args[1]);
            }
        }
        if (args.length == 6 && sub.equals("export")) {
            return filter(List.of("0", "90", "180", "270"), args[5]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(o);
            }
        }
        return out;
    }
}

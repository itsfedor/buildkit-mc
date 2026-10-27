package com.nous.buildkit;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Выполнение скрипта BuildKit построчно, порциями по тикам. */
public final class RunJob extends WorldJob {

    public static final int MODE_REPLACE = 0;
    public static final int MODE_KEEP = 1;
    public static final int MODE_DESTROY = 2;

    static final int SETBLOCK = 0;
    static final int FILL = 1;
    static final int PLACE = 2;
    static final int WAIT = 3;

    /** Одна команда скрипта (координаты уже нормализованы). */
    public static final class Cmd {
        public final int line;
        public final int kind;
        public final String text;

        int x1;
        int y1;
        int z1;
        int x2;
        int y2;
        int z2;
        int mode = MODE_REPLACE;
        BlockData data;
        String structureName;
        Transform.Rot rot = Transform.Rot.NONE;
        Transform.Mir mir = Transform.Mir.NONE;
        PlacementTemplate template;
        Placement placement;
        int waitTicks;

        Cmd(int line, int kind, String text) {
            this.line = line;
            this.kind = kind;
            this.text = text;
        }
    }

    private final List<Cmd> cmds;
    private final String fileName;

    private int idx;
    private int doneCommands;
    private int waitLeft;

    private boolean fillActive;
    private int fx;
    private int fy;
    private int fz;

    public RunJob(BuildKitPlugin plugin, CommandSender sender, World world, List<Cmd> cmds,
                  int budget, String fileName) {
        super(plugin, sender, world, budget, "run " + fileName);
        this.cmds = cmds;
        this.fileName = fileName;
    }

    public int totalCommands() {
        return cmds.size();
    }

    @Override
    protected void step() {
        int used = 0;
        while (true) {
            if (idx >= cmds.size()) {
                return;
            }
            if (waitLeft > 0) {
                waitLeft--;
                return;
            }
            Cmd c = cmds.get(idx);
            int[] counter = new int[1];
            boolean complete;
            switch (c.kind) {
                case SETBLOCK:
                    setBlock(c.x1, c.y1, c.z1, c.data, c.mode);
                    used++;
                    complete = true;
                    break;
                case FILL:
                    complete = stepFill(c, budget - used, counter);
                    used += counter[0];
                    break;
                case PLACE:
                    complete = stepPlace(c, budget - used, counter);
                    used += counter[0];
                    break;
                case WAIT:
                    if (c.waitTicks <= 0) {
                        complete = true;
                    } else {
                        // команда считается выполненной сразу, дальше идёт пауза на N тиков
                        waitLeft = c.waitTicks;
                        idx++;
                        doneCommands++;
                        return;
                    }
                    break;
                default:
                    complete = true;
                    break;
            }
            if (complete) {
                idx++;
                doneCommands++;
            } else {
                return;
            }
            if (used >= budget) {
                return;
            }
        }
    }

    private boolean stepFill(Cmd c, int limit, int[] counter) {
        if (!fillActive) {
            fillActive = true;
            fx = c.x1;
            fy = c.y1;
            fz = c.z1;
        }
        int n = 0;
        while (n < limit) {
            setBlock(fx, fy, fz, c.data, c.mode);
            n++;
            fx++;
            if (fx > c.x2) {
                fx = c.x1;
                fz++;
                if (fz > c.z2) {
                    fz = c.z1;
                    fy++;
                    if (fy > c.y2) {
                        fillActive = false;
                        counter[0] = n;
                        return true;
                    }
                }
            }
        }
        counter[0] = n;
        return false;
    }

    private boolean stepPlace(Cmd c, int limit, int[] counter) {
        if (c.placement == null) {
            if (c.template == null) {
                c.template = plugin.structures().template(c.structureName, c.rot, c.mir);
            }
            c.placement = c.template.at(c.x1, c.y1, c.z1);
        }
        Util.BlockOp op = new Util.BlockOp();
        int n = 0;
        while (n < limit && c.placement.advance(op)) {
            placeOp(op);
            n++;
        }
        counter[0] = n;
        return c.placement.cursor() >= c.placement.total();
    }

    @Override
    public boolean isDone() {
        return idx >= cmds.size() && waitLeft == 0;
    }

    @Override
    public String progressLine() {
        int line = idx < cmds.size() ? cmds.get(idx).line
                : (cmds.isEmpty() ? 0 : cmds.get(cmds.size() - 1).line);
        return "выполнено " + doneCommands + "/" + cmds.size() + " команд, блоков " + blocksSet
                + ", строка " + line + " (" + fileName + ")";
    }

    // ------------------------------------------------------------------ парсер

    /** Разобрать скрипт целиком; любая ошибка — CommandError с номером строки и текстом. */
    public static List<Cmd> parse(List<String> lines, StructureStore store) {
        List<Cmd> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String raw = lines.get(i);
            String text = raw.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String[] tok = text.split("\\s+");
            String head = tok[0].toLowerCase(Locale.ROOT);
            switch (head) {
                case "setblock": {
                    require(tok.length >= 5, lineNo, text, "setblock <x> <y> <z> <block[states]> [replace|keep|destroy]");
                    Cmd c = new Cmd(lineNo, SETBLOCK, text);
                    c.x1 = num(tok[1], lineNo, text);
                    c.y1 = num(tok[2], lineNo, text);
                    c.z1 = num(tok[3], lineNo, text);
                    c.data = block(tok[4], lineNo, text);
                    if (tok.length >= 6) {
                        c.mode = mode(tok[5], lineNo, text);
                    }
                    out.add(c);
                    break;
                }
                case "fill": {
                    require(tok.length >= 8, lineNo, text,
                            "fill <x1> <y1> <z1> <x2> <y2> <z2> <block[states]> [replace|keep|destroy]");
                    Cmd c = new Cmd(lineNo, FILL, text);
                    int ax = num(tok[1], lineNo, text);
                    int ay = num(tok[2], lineNo, text);
                    int az = num(tok[3], lineNo, text);
                    int bx = num(tok[4], lineNo, text);
                    int by = num(tok[5], lineNo, text);
                    int bz = num(tok[6], lineNo, text);
                    c.x1 = Math.min(ax, bx);
                    c.y1 = Math.min(ay, by);
                    c.z1 = Math.min(az, bz);
                    c.x2 = Math.max(ax, bx);
                    c.y2 = Math.max(ay, by);
                    c.z2 = Math.max(az, bz);
                    c.data = block(tok[7], lineNo, text);
                    if (tok.length >= 9) {
                        c.mode = mode(tok[8], lineNo, text);
                    }
                    out.add(c);
                    break;
                }
                case "place": {
                    require(tok.length >= 5, lineNo, text,
                            "place <имя> <x> <y> <z> [rot=0|90|180|270] [mirror=none|x|z]");
                    Cmd c = new Cmd(lineNo, PLACE, text);
                    c.structureName = Util.structureName(tok[1]);
                    c.x1 = num(tok[2], lineNo, text);
                    c.y1 = num(tok[3], lineNo, text);
                    c.z1 = num(tok[4], lineNo, text);
                    for (int k = 5; k < tok.length; k++) {
                        String t = tok[k].toLowerCase(Locale.ROOT);
                        if (t.startsWith("rot=") || t.matches("\\d+")) {
                            c.rot = Transform.Rot.ofToken(tok[k]);
                        } else if (t.startsWith("mirror=") || t.equals("x") || t.equals("z")
                                || t.equals("none")) {
                            c.mir = Transform.Mir.of(tok[k]);
                        } else {
                            throw new CommandError("строка " + lineNo + ": лишний аргумент '" + tok[k]
                                    + "' — " + text);
                        }
                    }
                    // структура должна существовать и читаться уже на этапе разбора
                    store.load(c.structureName);
                    out.add(c);
                    break;
                }
                case "wait": {
                    require(tok.length == 2, lineNo, text, "wait <тики>");
                    Cmd c = new Cmd(lineNo, WAIT, text);
                    c.waitTicks = num(tok[1], lineNo, text);
                    if (c.waitTicks < 0) {
                        throw new CommandError("строка " + lineNo + ": wait не может быть отрицательным — " + text);
                    }
                    out.add(c);
                    break;
                }
                default:
                    throw new CommandError("строка " + lineNo + ": неизвестная команда '" + tok[0]
                            + "' — " + text);
            }
        }
        return out;
    }

    private static void require(boolean ok, int line, String text, String usage) {
        if (!ok) {
            throw new CommandError("строка " + line + ": неверный формат, нужно: " + usage + " — " + text);
        }
    }

    private static int num(String token, int line, String text) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new CommandError("строка " + line + ": не число '" + token + "' — " + text);
        }
    }

    private static int mode(String token, int line, String text) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "replace":
                return MODE_REPLACE;
            case "keep":
                return MODE_KEEP;
            case "destroy":
                return MODE_DESTROY;
            default:
                throw new CommandError("строка " + line + ": неизвестный режим '" + token
                        + "' (replace|keep|destroy) — " + text);
        }
    }

    private static BlockData block(String token, int line, String text) {
        try {
            return Bukkit.createBlockData(token);
        } catch (IllegalArgumentException e) {
            throw new CommandError("строка " + line + ": неизвестный blockstate '" + token + "' — " + text);
        }
    }
}

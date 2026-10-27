package com.nous.buildkit;

import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

/**
 * Поворот и отражение структур.
 *
 * <p>Состояния блоков трансформируются штатным {@link BlockData#mirror(Mirror)} /
 * {@link BlockData#rotate(StructureRotation)} (ванильная семантика: facing, axis, half, shape,
 * hinge, open, rotation, соединения стен/заборов, рельсы и т.д.). Сначала отражение, потом
 * поворот — как в StructureTemplate.
 *
 * <p>Координаты: структура хранится от min-corner, точка вставки в команде тоже min-corner.
 * Поворот идёт по часовой стрелке, если смотреть сверху (север → восток).
 * mirror=x отражает по оси X (x → -x, восток ↔ запад), mirror=z — по оси Z (z → -z, север ↔ юг).
 */
public final class Transform {

    public enum Rot {
        NONE(0), CW90(90), CW180(180), CW270(270);
        public final int deg;

        Rot(int deg) {
            this.deg = deg;
        }

        public static Rot of(int deg) {
            switch (deg) {
                case 0:
                    return NONE;
                case 90:
                    return CW90;
                case 180:
                    return CW180;
                case 270:
                    return CW270;
                default:
                    throw new CommandError("rot должен быть 0, 90, 180 или 270 (получено " + deg + ")");
            }
        }

        public static Rot ofToken(String token) {
            String t = token.toLowerCase(java.util.Locale.ROOT);
            if (t.startsWith("rot=")) {
                t = t.substring(4);
            }
            return of(Util.parseInt(t));
        }
    }

    public enum Mir {
        NONE, X, Z;

        public static Mir of(String token) {
            String t = token.toLowerCase(java.util.Locale.ROOT);
            if (t.startsWith("mirror=")) {
                t = t.substring(7);
            }
            switch (t) {
                case "none":
                case "0":
                    return NONE;
                case "x":
                    return X;
                case "z":
                    return Z;
                default:
                    throw new CommandError("mirror должен быть none, x или z (получено '" + token + "')");
            }
        }
    }

    private Transform() {
    }

    public static StructureRotation bukkitRot(Rot r) {
        switch (r) {
            case CW90:
                return StructureRotation.CLOCKWISE_90;
            case CW180:
                return StructureRotation.CLOCKWISE_180;
            case CW270:
                return StructureRotation.COUNTERCLOCKWISE_90;
            default:
                return StructureRotation.NONE;
        }
    }

    /**
     * Mirror.X — отражение по X (ванильный FRONT_BACK = INVERT_X, восток ↔ запад).
     * Mirror.Z — отражение по Z (ванильный LEFT_RIGHT = INVERT_Z, север ↔ юг).
     */
    public static Mirror bukkitMir(Mir m) {
        switch (m) {
            case X:
                return Mirror.FRONT_BACK;
            case Z:
                return Mirror.LEFT_RIGHT;
            default:
                return Mirror.NONE;
        }
    }

    public static BlockData apply(BlockData source, Rot rot, Mir mir) {
        BlockData data = source.clone();
        if (mir != Mir.NONE) {
            data.mirror(bukkitMir(mir));
        }
        if (rot != Rot.NONE) {
            data.rotate(bukkitRot(rot));
        }
        return data;
    }

    /** Размеры после поворота: 90/270 меняют местами X и Z. */
    public static int[] dims(int sx, int sy, int sz, Rot rot) {
        if (rot == Rot.CW90 || rot == Rot.CW270) {
            return new int[]{sz, sy, sx};
        }
        return new int[]{sx, sy, sz};
    }

    /**
     * Пересчёт локальных координат (x,z) внутри bbox sx×sz.
     * Возвращает упакованную пару: старшие 32 бита — новый x, младшие — новый z.
     */
    public static long map(int x, int z, int sx, int sz, Mir mir, Rot rot) {
        int nx = x;
        int nz = z;
        int w = sx;
        int h = sz;

        if (mir == Mir.X) {
            nx = w - 1 - nx;
        } else if (mir == Mir.Z) {
            nz = h - 1 - nz;
        }

        switch (rot) {
            case CW90: {
                int tx = h - 1 - nz;
                int tz = nx;
                nx = tx;
                nz = tz;
                break;
            }
            case CW180: {
                nx = w - 1 - nx;
                nz = h - 1 - nz;
                break;
            }
            case CW270: {
                int tx = nz;
                int tz = w - 1 - nx;
                nx = tx;
                nz = tz;
                break;
            }
            default:
                break;
        }

        return ((long) nx << 32) | (nz & 0xffffffffL);
    }
}

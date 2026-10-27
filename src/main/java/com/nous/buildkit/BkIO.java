package com.nous.buildkit;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Бинарный формат .bk (gzip).
 *
 * <pre>
 * int   magic 0x424B5331 ("BKS1")
 * byte  version = 1
 * byte  type: 1 = структура, 2 = changeset (снимок "до")
 * UTF   label
 * UTF   world
 * long  createdAt (epoch millis)
 * int   sx, sy, sz (для changeset = 0)
 * int   paletteSize; UTF[paletteSize]
 * type 1: int count = sx*sy*sz; int[count] индексы
 * type 2: int count; {int x, int y, int z, short paletteIndex}[count]; boolean truncated
 * </pre>
 */
public final class BkIO {

    public static final int MAGIC = 0x424B5331;
    public static final byte TYPE_STRUCTURE = 1;
    public static final byte TYPE_CHANGESET = 2;
    private static final byte VERSION = 1;

    private BkIO() {
    }

    public static void writeStructure(File file, Structure s) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(TYPE_STRUCTURE);
            out.writeUTF(s.name);
            out.writeUTF("");
            out.writeLong(System.currentTimeMillis());
            out.writeInt(s.sx);
            out.writeInt(s.sy);
            out.writeInt(s.sz);
            out.writeInt(s.palette.length);
            for (String p : s.palette) {
                out.writeUTF(p);
            }
            out.writeInt(s.indices.length);
            for (int idx : s.indices) {
                out.writeInt(idx);
            }
        }
    }

    public static Structure readStructure(File file) throws IOException {
        try (DataInputStream in = open(file)) {
            Header h = readHeader(in);
            if (h.type != TYPE_STRUCTURE) {
                throw new IOException("файл " + file.getName() + " — не структура (type=" + h.type + ")");
            }
            int count = in.readInt();
            long expected = Util.volume(h.sx, h.sy, h.sz);
            if (count != expected) {
                throw new IOException("файл " + file.getName() + ": ожидалось " + expected
                        + " индексов, в файле " + count);
            }
            int[] indices = new int[count];
            for (int i = 0; i < count; i++) {
                indices[i] = in.readInt();
            }
            return new Structure(h.label, h.sx, h.sy, h.sz, h.palette.toArray(new String[0]), indices);
        }
    }

    public static void writeChangeSet(File file, ChangeSet cs) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(TYPE_CHANGESET);
            out.writeUTF(cs.label);
            out.writeUTF(cs.worldName == null ? "" : cs.worldName);
            out.writeLong(cs.createdAt);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(cs.palette.size());
            for (String p : cs.palette) {
                out.writeUTF(p);
            }
            out.writeInt(cs.n);
            for (int i = 0; i < cs.n; i++) {
                out.writeInt(cs.xs[i]);
                out.writeInt(cs.ys[i]);
                out.writeInt(cs.zs[i]);
                out.writeShort(cs.pi[i]);
            }
            out.writeBoolean(cs.truncated);
        }
    }

    public static ChangeSet readChangeSet(File file) throws IOException {
        try (DataInputStream in = open(file)) {
            Header h = readHeader(in);
            if (h.type != TYPE_CHANGESET) {
                throw new IOException("файл " + file.getName() + " — не снимок (type=" + h.type + ")");
            }
            int n = in.readInt();
            if (n < 0 || n > Util.MAX_CHANGES * 2) {
                throw new IOException("файл " + file.getName() + ": подозрительное число блоков " + n);
            }
            ChangeSet cs = new ChangeSet(h.label, h.world, h.createdAt);
            cs.palette.addAll(h.palette);
            cs.ensure(n);
            for (int i = 0; i < n; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                int p = in.readUnsignedShort();
                if (p >= cs.palette.size()) {
                    throw new IOException("файл " + file.getName() + ": индекс палитры " + p + " вне диапазона");
                }
                cs.xs[i] = x;
                cs.ys[i] = y;
                cs.zs[i] = z;
                cs.pi[i] = (short) p;
            }
            cs.n = n;
            cs.truncated = in.readBoolean();
            return cs;
        }
    }

    private static DataInputStream open(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("файл не найден: " + file.getAbsolutePath());
        }
        return new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))));
    }

    private static Header readHeader(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("не .bk файл (magic=0x" + Integer.toHexString(magic) + ")");
        }
        byte version = in.readByte();
        if (version != VERSION) {
            throw new IOException("неподдерживаемая версия формата " + version);
        }
        Header h = new Header();
        h.type = in.readByte();
        h.label = in.readUTF();
        h.world = in.readUTF();
        h.createdAt = in.readLong();
        h.sx = in.readInt();
        h.sy = in.readInt();
        h.sz = in.readInt();
        int paletteSize = in.readInt();
        if (paletteSize < 0 || paletteSize > 65536) {
            throw new IOException("подозрительный размер палитры " + paletteSize);
        }
        h.palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            h.palette.add(in.readUTF());
        }
        return h;
    }

    private static final class Header {
        byte type;
        String label;
        String world;
        long createdAt;
        int sx;
        int sy;
        int sz;
        List<String> palette;
    }
}

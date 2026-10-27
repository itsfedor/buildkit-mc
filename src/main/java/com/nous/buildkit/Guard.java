package com.nous.buildkit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Проверка чужих регионов перед операцией. WorldGuard и GriefPrevention опрашиваются
 * через рефлексию, чтобы не тянуть их API в compile-time и не падать, если плагина нет.
 * Любая ошибка проверки — отказ от операции, а не молчаливое продолжение.
 */
public final class Guard {

    public static final class Hit {
        public final String kind;
        public final String id;
        public final String owner;

        Hit(String kind, String id, String owner) {
            this.kind = kind;
            this.id = id;
            this.owner = owner;
        }

        public String describe() {
            return kind + ":" + id + (owner == null || owner.isEmpty() ? "" : " (" + owner + ")");
        }
    }

    private Guard() {
    }

    public static List<Hit> check(World world, int x1, int y1, int z1, int x2, int y2, int z2, UUID actor) {
        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.max(y1, y2);
        int maxZ = Math.max(z1, z2);

        List<Hit> hits = new ArrayList<>();
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            checkWorldGuard(world, minX, minY, minZ, maxX, maxY, maxZ, actor, hits);
        }
        if (Bukkit.getPluginManager().getPlugin("GriefPrevention") != null) {
            checkGriefPrevention(world, minX, minY, minZ, maxX, maxY, maxZ, actor, hits);
        }
        return hits;
    }

    private static void checkWorldGuard(World world, int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ, UUID actor, List<Hit> hits) {
        try {
            Class<?> cWg = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = cWg.getMethod("getInstance").invoke(null);
            Object platform = cWg.getMethod("getPlatform").invoke(wg);
            Class<?> cPlatform = Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform");
            Object container = cPlatform.getMethod("getRegionContainer").invoke(platform);
            if (container == null) {
                return;
            }
            Class<?> cWeWorld = Class.forName("com.sk89q.worldedit.world.World");
            Object adapted = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                    .getMethod("adapt", World.class).invoke(null, world);
            Class<?> cContainer = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer");
            Object manager = cContainer.getMethod("get", cWeWorld).invoke(container, adapted);
            if (manager == null) {
                return;
            }
            Class<?> cManager = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager");
            Class<?> cRegion = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion");
            Class<?> cVector = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Class<?> cDomain = Class.forName("com.sk89q.worldguard.domains.DefaultDomain");

            Map<?, ?> regions = (Map<?, ?>) cManager.getMethod("getRegions").invoke(manager);
            for (Object region : regions.values()) {
                String id = (String) cRegion.getMethod("getId").invoke(region);
                if ("__global__".equals(id)) {
                    continue;
                }
                Object min = cRegion.getMethod("getMinimumPoint").invoke(region);
                Object max = cRegion.getMethod("getMaximumPoint").invoke(region);
                int rMinX = ((Number) cVector.getMethod("getX").invoke(min)).intValue();
                int rMinY = ((Number) cVector.getMethod("getY").invoke(min)).intValue();
                int rMinZ = ((Number) cVector.getMethod("getZ").invoke(min)).intValue();
                int rMaxX = ((Number) cVector.getMethod("getX").invoke(max)).intValue();
                int rMaxY = ((Number) cVector.getMethod("getY").invoke(max)).intValue();
                int rMaxZ = ((Number) cVector.getMethod("getZ").invoke(max)).intValue();
                if (!intersects(minX, minY, minZ, maxX, maxY, maxZ, rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ)) {
                    continue;
                }
                boolean ours = false;
                if (actor != null) {
                    Object owners = cRegion.getMethod("getOwners").invoke(region);
                    Object members = cRegion.getMethod("getMembers").invoke(region);
                    Method contains = cDomain.getMethod("contains", UUID.class);
                    ours = Boolean.TRUE.equals(contains.invoke(owners, actor))
                            || Boolean.TRUE.equals(contains.invoke(members, actor));
                }
                if (!ours) {
                    hits.add(new Hit("WorldGuard", id, domainLabel(cDomain, cRegion, region)));
                }
            }
        } catch (CommandError e) {
            throw e;
        } catch (Throwable t) {
            throw new CommandError("проверка регионов WorldGuard не удалась (" + t
                    + ") — операция отменена; проверьте регион вручную и повторите с --force");
        }
    }

    private static void checkGriefPrevention(World world, int minX, int minY, int minZ,
                                            int maxX, int maxY, int maxZ, UUID actor, List<Hit> hits) {
        try {
            Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
            Object ds = gp.getClass().getField("dataStore").get(gp);
            if (ds == null) {
                return;
            }
            Collection<?> claims = (Collection<?>) ds.getClass().getMethod("getClaims").invoke(ds);
            for (Object claim : claims) {
                Location less = (Location) claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
                Location greater = (Location) claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);
                if (less == null || greater == null || less.getWorld() == null) {
                    continue;
                }
                if (!less.getWorld().equals(world)) {
                    continue;
                }
                if (!intersects(minX, minY, minZ, maxX, maxY, maxZ,
                        Math.min(less.getBlockX(), greater.getBlockX()),
                        Math.min(less.getBlockY(), greater.getBlockY()),
                        Math.min(less.getBlockZ(), greater.getBlockZ()),
                        Math.max(less.getBlockX(), greater.getBlockX()),
                        Math.max(less.getBlockY(), greater.getBlockY()),
                        Math.max(less.getBlockZ(), greater.getBlockZ()))) {
                    continue;
                }
                UUID owner = (UUID) claim.getClass().getMethod("getOwnerID").invoke(claim);
                boolean admin = (Boolean) claim.getClass().getMethod("isAdminClaim").invoke(claim);
                boolean ours = !admin && actor != null && actor.equals(owner);
                if (!ours) {
                    Object id = claim.getClass().getMethod("getID").invoke(claim);
                    String ownerText = admin ? "admin-claim" : String.valueOf(owner);
                    hits.add(new Hit("GriefPrevention", String.valueOf(id), ownerText));
                }
            }
        } catch (CommandError e) {
            throw e;
        } catch (Throwable t) {
            throw new CommandError("проверка клаймов GriefPrevention не удалась (" + t
                    + ") — операция отменена; проверьте территорию вручную и повторите с --force");
        }
    }

    private static String domainLabel(Class<?> cDomain, Class<?> cRegion, Object region) {
        try {
            Object owners = cRegion.getMethod("getOwners").invoke(region);
            Set<?> names = (Set<?>) cDomain.getMethod("getPlayers").invoke(owners);
            Set<?> ids = (Set<?>) cDomain.getMethod("getUniqueIds").invoke(owners);
            StringBuilder sb = new StringBuilder();
            for (Object n : names) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(n);
            }
            if (sb.length() == 0) {
                for (Object id : ids) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(id);
                }
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean intersects(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                      int rMinX, int rMinY, int rMinZ, int rMaxX, int rMaxY, int rMaxZ) {
        return minX <= rMaxX && maxX >= rMinX
                && minY <= rMaxY && maxY >= rMinY
                && minZ <= rMaxZ && maxZ >= rMinZ;
    }
}

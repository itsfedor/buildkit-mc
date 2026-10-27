package com.nous.buildkit;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

/**
 * Задача, которая делает часть работы каждый тик в main thread.
 * Начало/конец/прогресс пишутся в лог сервера — по ним же снимается приёмка.
 */
public abstract class TickJob implements Runnable {

    protected final BuildKitPlugin plugin;
    protected final CommandSender sender;
    protected final int budget;
    protected final String label;

    private BukkitTask task;
    protected boolean finished;
    protected long startedAt;
    protected double minTps = 20.0;
    private long lastLogAt;

    protected TickJob(BuildKitPlugin plugin, CommandSender sender, int budget, String label) {
        this.plugin = plugin;
        this.sender = sender;
        this.budget = Math.max(1, budget);
        this.label = label;
    }

    public String label() {
        return label;
    }

    public void start() {
        startedAt = System.currentTimeMillis();
        lastLogAt = startedAt;
        plugin.getLogger().info("[" + label + "] старт");
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    public void cancel() {
        if (finished) {
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public final void run() {
        if (finished) {
            return;
        }
        try {
            step();
            double tps = Bukkit.getTPS()[0];
            if (tps < minTps) {
                minTps = tps;
            }
            long now = System.currentTimeMillis();
            if (!isDone() && now - lastLogAt >= 2000L) {
                lastLogAt = now;
                String p = progressLine();
                if (p != null) {
                    plugin.getLogger().info("[" + label + "] " + p);
                }
            }
            if (isDone()) {
                complete();
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    protected abstract void step();

    public abstract boolean isDone();

    public abstract String progressLine();

    protected void onFinish(boolean success) {
    }

    protected String finishSummary() {
        return progressLine();
    }

    private void complete() {
        finished = true;
        if (task != null) {
            task.cancel();
        }
        boolean ok = true;
        try {
            onFinish(true);
        } catch (Throwable t) {
            ok = false;
            plugin.getLogger().severe("[" + label + "] ошибка при завершении: " + t);
            t.printStackTrace();
        }
        double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
        String summary = finishSummary();
        plugin.getLogger().info("[" + label + "] готово: " + summary
                + String.format(java.util.Locale.ROOT, ", %.1f с, TPS min %.2f", seconds, minTps));
        if (ok) {
            send("§a[BuildKit] " + summary);
        } else {
            send("§c[BuildKit] операция завершилась с ошибкой при фиксации результата, см. лог");
        }
        plugin.jobs().finished(this);
    }

    protected void fail(Throwable t) {
        finished = true;
        if (task != null) {
            task.cancel();
        }
        String msg = t.getMessage() == null ? t.toString() : t.getMessage();
        plugin.getLogger().severe("[" + label + "] ошибка: " + msg);
        t.printStackTrace();
        try {
            onFinish(false);
        } catch (Throwable ignored) {
            // уже падаем
        }
        send("§c[BuildKit] " + label + ": ошибка — " + msg);
        plugin.jobs().finished(this);
    }

    protected void send(String message) {
        try {
            sender.sendMessage(message);
        } catch (Throwable ignored) {
            // отправитель мог отключиться
        }
    }
}

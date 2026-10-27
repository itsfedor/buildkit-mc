package com.nous.buildkit;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * BuildKit — пакетные правки мира без Gradle и без WorldEdit:
 * структуры с поворотом/отражением, скрипты, сетка, walkcheck, снимки и откат.
 *
 * <p>При включении плагин только создаёт каталоги и регистрирует команду.
 * Никаких действий с миром при загрузке нет.
 */
public final class BuildKitPlugin extends JavaPlugin {

    private StructureStore structures;
    private SnapshotIndex snapshots;
    private File scriptsDir;
    private JobManager jobs;

    @Override
    public void onEnable() {
        File data = getDataFolder();
        if (!data.exists() && !data.mkdirs()) {
            getLogger().severe("не удалось создать " + data.getAbsolutePath());
        }
        structures = new StructureStore(new File(data, "structures"));
        snapshots = new SnapshotIndex(new File(data, "snapshots"));
        scriptsDir = new File(data, "scripts");
        if (!scriptsDir.exists() && !scriptsDir.mkdirs()) {
            getLogger().severe("не удалось создать " + scriptsDir.getAbsolutePath());
        }
        jobs = new JobManager(this);

        PluginCommand command = getCommand("bk");
        if (command == null) {
            getLogger().severe("команда /bk не объявлена в plugin.yml — плагин неработоспособен");
            return;
        }
        BkCommand executor = new BkCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        getLogger().info("Enabled — structures=" + structures.dir().getAbsolutePath()
                + " snapshots=" + snapshots.dir().getAbsolutePath()
                + " scripts=" + scriptsDir.getAbsolutePath());
    }

    @Override
    public void onDisable() {
        if (jobs != null) {
            jobs.cancelAll();
        }
        getLogger().info("Disabled");
    }

    public StructureStore structures() {
        return structures;
    }

    public SnapshotIndex snapshots() {
        return snapshots;
    }

    public File scripts() {
        return scriptsDir;
    }

    public JobManager jobs() {
        return jobs;
    }
}

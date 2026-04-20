package de.gilde.statsimporter.importer;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class ImportScheduler {

    private final Plugin plugin;
    private final ImportCoordinator coordinator;
    private ScheduledTask task;

    public ImportScheduler(Plugin plugin, ImportCoordinator coordinator) {
        this.plugin = plugin;
        this.coordinator = coordinator;
    }

    public synchronized void start(long intervalSeconds, boolean ignoreHashOnTimer) {
        stop();
        task = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                scheduledTask -> coordinator.triggerImport("timer", ignoreHashOnTimer),
                0L,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}


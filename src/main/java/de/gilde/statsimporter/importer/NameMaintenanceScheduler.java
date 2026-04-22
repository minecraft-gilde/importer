package de.gilde.statsimporter.importer;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class NameMaintenanceScheduler {

    private final Plugin plugin;
    private final ImportCoordinator coordinator;
    private ScheduledTask task;

    public NameMaintenanceScheduler(Plugin plugin, ImportCoordinator coordinator) {
        this.plugin = plugin;
        this.coordinator = coordinator;
    }

    public synchronized void start(long intervalSeconds, int maxPerRun) {
        stop();
        task = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                scheduledTask -> coordinator.triggerNameResolve("timer:maintenance", maxPerRun),
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

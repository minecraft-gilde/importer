package de.gilde.statsimporter;

import de.gilde.statsimporter.command.StatsImportCommand;
import de.gilde.statsimporter.config.ConfigLoader;
import de.gilde.statsimporter.config.PluginSettings;
import de.gilde.statsimporter.db.DatabaseManager;
import de.gilde.statsimporter.db.SchemaBootstrapper;
import de.gilde.statsimporter.importer.ImportCoordinator;
import de.gilde.statsimporter.importer.ImportScheduler;
import de.gilde.statsimporter.importer.NameMaintenanceScheduler;
import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class ImporterPlugin extends JavaPlugin {

    private PluginSettings settings;
    private DatabaseManager databaseManager;
    private ImportCoordinator importCoordinator;
    private ImportScheduler importScheduler;
    private NameMaintenanceScheduler nameMaintenanceScheduler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            bootstrapRuntime();
            registerCommands();
            startSchedulerIfConfigured();
            startNameMaintenanceIfConfigured();
            getLogger().info("StatsImporter enabled.");
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Could not start plugin runtime", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        shutdownRuntime();
    }

    public synchronized String reloadRuntime() {
        if (importCoordinator != null && importCoordinator.isRunning()) {
            return "Import läuft gerade. Reload erst nach Abschluss möglich.";
        }

        try {
            shutdownRuntime();
            reloadConfig();
            bootstrapRuntime();
            startSchedulerIfConfigured();
            startNameMaintenanceIfConfigured();
            return "Konfiguration neu geladen.";
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Reload failed", ex);
            return "Reload fehlgeschlagen: " + ex.getMessage();
        }
    }

    public ImportCoordinator importCoordinator() {
        return importCoordinator;
    }

    public PluginSettings settings() {
        return settings;
    }

    private void bootstrapRuntime() {
        saveDefaultConfig();
        File seedFile = new File(getDataFolder(), "metric-seeds.yml");
        if (!seedFile.exists()) {
            saveResource("metric-seeds.yml", false);
        }
        settings = ConfigLoader.load(getConfig());
        databaseManager = new DatabaseManager(settings.databaseSettings());
        SchemaBootstrapper bootstrapper = new SchemaBootstrapper(
                this,
                databaseManager.dataSource(),
                settings.bootstrapSettings(),
                Set.of(settings.importSettings().kingMetricId())
        );
        try {
            bootstrapper.ensureReady();
        } catch (Exception ex) {
            throw new IllegalStateException("Database bootstrap failed: " + ex.getMessage(), ex);
        }
        importCoordinator = new ImportCoordinator(this, settings, databaseManager.dataSource());
        importScheduler = new ImportScheduler(this, importCoordinator);
        nameMaintenanceScheduler = new NameMaintenanceScheduler(this, importCoordinator);
    }

    private void startSchedulerIfConfigured() {
        if (settings.importSettings().enabled()) {
            importScheduler.start(settings.importSettings().intervalSeconds(), settings.importSettings().ignoreHashOnTimer());
            getLogger().info("Import scheduler started. interval=" + settings.importSettings().intervalSeconds() + "s");
        }
    }

    private void startNameMaintenanceIfConfigured() {
        if (!settings.importSettings().nameResolverEnabled()) {
            return;
        }
        if (!settings.importSettings().nameResolverMaintenanceEnabled()) {
            return;
        }
        nameMaintenanceScheduler.start(
                settings.importSettings().nameResolverMaintenanceIntervalSeconds(),
                settings.importSettings().nameResolverMaintenanceMaxPerRun()
        );
        getLogger().info("Name maintenance scheduler started. interval="
                + settings.importSettings().nameResolverMaintenanceIntervalSeconds()
                + "s, max=" + settings.importSettings().nameResolverMaintenanceMaxPerRun());
    }

    private void shutdownRuntime() {
        if (nameMaintenanceScheduler != null) {
            nameMaintenanceScheduler.stop();
            nameMaintenanceScheduler = null;
        }
        if (importScheduler != null) {
            importScheduler.stop();
            importScheduler = null;
        }
        if (importCoordinator != null) {
            importCoordinator.close();
            importCoordinator = null;
        }
        if (databaseManager != null) {
            databaseManager.close();
            databaseManager = null;
        }
    }

    private void registerCommands() {
        StatsImportCommand command = new StatsImportCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                "statsimport",
                "Run and manage the stats import pipeline",
                List.of(),
                command
        ));
    }
}

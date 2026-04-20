package de.gilde.statsimporter;

import de.gilde.statsimporter.command.StatsImportCommand;
import de.gilde.statsimporter.config.ConfigLoader;
import de.gilde.statsimporter.config.PluginSettings;
import de.gilde.statsimporter.db.DatabaseManager;
import de.gilde.statsimporter.db.SchemaBootstrapper;
import de.gilde.statsimporter.importer.ImportCoordinator;
import de.gilde.statsimporter.importer.ImportScheduler;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ImporterPlugin extends JavaPlugin {

    private PluginSettings settings;
    private DatabaseManager databaseManager;
    private ImportCoordinator importCoordinator;
    private ImportScheduler importScheduler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            bootstrapRuntime();
            registerCommands();
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
            return "Import laeuft gerade. Reload erst nach Abschluss moeglich.";
        }

        try {
            shutdownRuntime();
            reloadConfig();
            bootstrapRuntime();
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
        saveResource("metric-seeds.yml", false);
        settings = ConfigLoader.load(getConfig());
        databaseManager = new DatabaseManager(settings.databaseSettings());
        SchemaBootstrapper bootstrapper = new SchemaBootstrapper(this, databaseManager.dataSource(), settings.bootstrapSettings());
        try {
            bootstrapper.ensureReady();
        } catch (Exception ex) {
            throw new IllegalStateException("Database bootstrap failed: " + ex.getMessage(), ex);
        }
        importCoordinator = new ImportCoordinator(this, settings, databaseManager.dataSource());
        importScheduler = new ImportScheduler(this, importCoordinator);
        if (settings.importSettings().enabled()) {
            importScheduler.start(settings.importSettings().intervalSeconds(), settings.importSettings().ignoreHashOnTimer());
        }
    }

    private void shutdownRuntime() {
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
        PluginCommand command = getCommand("statsimport");
        if (command == null) {
            throw new IllegalStateException("Command 'statsimport' missing in plugin.yml");
        }
        StatsImportCommand executor = new StatsImportCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}

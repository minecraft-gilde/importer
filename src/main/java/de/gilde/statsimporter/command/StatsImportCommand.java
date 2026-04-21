package de.gilde.statsimporter.command;

import de.gilde.statsimporter.ImporterPlugin;
import de.gilde.statsimporter.model.ImportSummary;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

public final class StatsImportCommand implements BasicCommand {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.GERMAN)
            .withZone(ZoneId.systemDefault());

    private final ImporterPlugin plugin;

    public StatsImportCommand(ImporterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack commandSourceStack, String[] args) {
        CommandSender sender = commandSourceStack.getSender();
        if (args.length == 0) {
            sender.sendMessage("Nutze: /statsimport <run|status|reload|resolve [max]>");
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "run" -> handleRun(sender, args);
            case "status" -> handleStatus(sender);
            case "reload" -> sender.sendMessage(plugin.reloadRuntime());
            case "resolve" -> handleResolve(sender, args);
            default -> sender.sendMessage("Unbekannter Subcommand. Nutze: run, status, reload oder resolve [max].");
        }
    }

    private void handleRun(CommandSender sender, String[] args) {
        boolean ignoreHash = false;
        boolean dryRun = false;
        for (int i = 1; i < args.length; i++) {
            String option = args[i].toLowerCase(Locale.ROOT);
            switch (option) {
                case "ignorehash" -> ignoreHash = true;
                case "dryrun" -> dryRun = true;
                default -> {
                    sender.sendMessage("Unbekannte Option: " + args[i]);
                    sender.sendMessage("Nutze: /statsimport run [ignorehash] [dryrun]");
                    return;
                }
            }
        }

        boolean started = plugin.importCoordinator().triggerImport("manual:" + sender.getName(), ignoreHash, dryRun);
        if (!started) {
            sender.sendMessage("Import/Resolver laeuft bereits.");
            return;
        }
        sender.sendMessage("Import gestartet."
                + (ignoreHash ? " (ignorehash aktiv)" : "")
                + (dryRun ? " (dryrun aktiv)" : ""));
    }

    private void handleResolve(CommandSender sender, String[] args) {
        Integer maxOverride = null;
        if (args.length > 2) {
            sender.sendMessage("Nutze: /statsimport resolve [max]");
            return;
        }
        if (args.length == 2) {
            try {
                int parsed = Integer.parseInt(args[1]);
                if (parsed < 1) {
                    sender.sendMessage("max muss >= 1 sein.");
                    return;
                }
                maxOverride = parsed;
            } catch (NumberFormatException ex) {
                sender.sendMessage("max muss eine ganze Zahl sein.");
                return;
            }
        }

        boolean started = plugin.importCoordinator().triggerNameResolve(
                "manual:resolve:" + sender.getName(),
                maxOverride
        );
        if (!started) {
            sender.sendMessage("Import/Resolver laeuft bereits.");
            return;
        }
        sender.sendMessage("Namensaufloesung gestartet."
                + (maxOverride != null ? " (max=" + maxOverride + ")" : ""));
    }

    private void handleStatus(CommandSender sender) {
        boolean running = plugin.importCoordinator().isRunning();
        ImportSummary summary = plugin.importCoordinator().lastSummary();

        sender.sendMessage("Import running: " + running);
        sender.sendMessage("Last run: " + summary.reason() + " | success=" + summary.success());
        sender.sendMessage("Started: " + DATE_FORMAT.format(summary.startedAt()));
        sender.sendMessage("Finished: " + DATE_FORMAT.format(summary.finishedAt()));
        sender.sendMessage("Processed=" + summary.processedFiles()
                + ", kept=" + summary.keptPlayers()
                + ", changed=" + summary.changedPlayers()
                + ", duration=" + summary.durationSeconds() + "s");
        sender.sendMessage("Message: " + summary.message());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            addIfMatches(completions, args[0], "run");
            addIfMatches(completions, args[0], "status");
            addIfMatches(completions, args[0], "reload");
            addIfMatches(completions, args[0], "resolve");
        } else if (args.length >= 2 && "run".equalsIgnoreCase(args[0])) {
            String current = args[args.length - 1];
            addIfMatches(completions, current, "ignorehash");
            addIfMatches(completions, current, "dryrun");
        } else if (args.length == 2 && "resolve".equalsIgnoreCase(args[0])) {
            String configured = String.valueOf(plugin.settings().importSettings().nameResolverMaxPerRun());
            addIfMatches(completions, args[1], configured);
        }
        return completions;
    }

    @Override
    public String permission() {
        return "statsimporter.admin";
    }

    private void addIfMatches(List<String> completions, String input, String candidate) {
        if (candidate.startsWith(input.toLowerCase(Locale.ROOT))) {
            completions.add(candidate);
        }
    }
}

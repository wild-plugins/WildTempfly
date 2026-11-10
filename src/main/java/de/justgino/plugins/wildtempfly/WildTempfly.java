package de.justgino.plugins.wildtempfly;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings("UnstableApiUsage")
public final class WildTempfly extends JavaPlugin {

    private interface Change {
        long apply(UUID uuid, long seconds) throws Exception;
    }

    private Texts texts;
    private Database database;
    private FlyService fly;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String name : new String[]{"lang.yml", "database.yml"}) {
            if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
        }
        texts = new Texts(this, "lang.yml");
        texts.load();
        database = new Database(this);
        try {
            database.connect();
        } catch (Exception e) {
            getLogger().severe("Could not connect to the database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        fly = new FlyService(this);
        getServer().getPluginManager().registerEvents(fly, this);
        fly.start();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new TempflyPlaceholder(this).register();
        List<String> flyAliases = getConfig().getStringList("commands.fly-aliases");
        List<String> adminAliases = getConfig().getStringList("commands.admin-aliases");
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            registrar.register(Commands.literal("fly")
                    .requires(source -> source.getSender().hasPermission("wildtempfly.fly"))
                    .executes(ctx -> {
                        Player player = player(ctx);
                        if (player != null) fly.toggle(player);
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.literal("time").executes(ctx -> {
                        Player player = player(ctx);
                        if (player != null) {
                            FlyService.Data data = fly.data(player.getUniqueId());
                            texts.send(player, "fly.time", "time", fly.timeText(player, data == null ? 0 : data.seconds()));
                        }
                        return Command.SINGLE_SUCCESS;
                    })).build(), "Turns fly on or off", flyAliases);
            registrar.register(admin(), "Manage fly time", adminAliases);
        });
    }

    @Override
    public void onDisable() {
        if (fly != null) fly.stop();
        if (database != null) database.close();
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> admin() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tempfly")
                .requires(source -> {
                    CommandSender s = source.getSender();
                    return s.hasPermission("wildtempfly.admin.add") || s.hasPermission("wildtempfly.admin.remove")
                            || s.hasPermission("wildtempfly.admin.set") || s.hasPermission("wildtempfly.admin.check")
                            || s.hasPermission("wildtempfly.admin.reload");
                })
                .executes(ctx -> {
                    texts.sendLines(ctx.getSource().getSender(), "admin.help", "version", getPluginMeta().getVersion());
                    return Command.SINGLE_SUCCESS;
                });
        root.then(timed("add", "wildtempfly.admin.add", "admin.added", (uuid, s) -> database.add(uuid, s)));
        root.then(timed("remove", "wildtempfly.admin.remove", "admin.removed", (uuid, s) -> database.add(uuid, -s)));
        root.then(timed("set", "wildtempfly.admin.set", "admin.set", (uuid, s) -> database.set(uuid, s)));
        root.then(Commands.literal("reset").requires(source -> source.getSender().hasPermission("wildtempfly.admin.set"))
                .then(playerArgument().executes(ctx -> {
                    change(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), 0, "admin.reset", (uuid, s) -> database.set(uuid, 0));
                    return Command.SINGLE_SUCCESS;
                })));
        root.then(Commands.literal("check").requires(source -> source.getSender().hasPermission("wildtempfly.admin.check"))
                .then(playerArgument().executes(ctx -> {
                    change(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), 0, "admin.check", (uuid, s) -> database.seconds(uuid));
                    return Command.SINGLE_SUCCESS;
                })));
        root.then(Commands.literal("reload").requires(source -> source.getSender().hasPermission("wildtempfly.admin.reload"))
                .executes(ctx -> {
                    reloadConfig();
                    texts.load();
                    texts.send(ctx.getSource().getSender(), "messages.reloaded");
                    return Command.SINGLE_SUCCESS;
                }));
        return root.build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> timed(String name, String permission, String message, Change change) {
        return Commands.literal(name).requires(source -> source.getSender().hasPermission(permission))
                .then(playerArgument().then(Commands.argument("time", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String example : List.of("30m", "1h", "1h30m", "1d")) builder.suggest(example);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            long seconds = FlyService.parseTime(StringArgumentType.getString(ctx, "time"));
                            if (seconds < 0) {
                                texts.send(sender, "messages.invalid-time");
                            } else {
                                change(sender, StringArgumentType.getString(ctx, "player"), seconds, message, change);
                            }
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> playerArgument() {
        return Commands.argument("player", StringArgumentType.word()).suggests((ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(online.getName());
            }
            return builder.buildFuture();
        });
    }

    private void change(CommandSender sender, String name, long seconds, String message, Change change) {
        Player online = Bukkit.getPlayerExact(name);
        CompletableFuture<Map.Entry<UUID, String>> lookup = online != null
                ? database.run(() -> {
                    database.join(online.getUniqueId(), online.getName());
                    return Map.entry(online.getUniqueId(), online.getName());
                })
                : database.run(() -> database.find(name));
        lookup.thenCompose(found -> found == null ? CompletableFuture.completedFuture(null)
                        : database.run(() -> Map.entry(found, change.apply(found.getKey(), seconds))))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        texts.send(sender, "messages.error");
                    } else if (result == null) {
                        texts.send(sender, "messages.player-not-found", "player", name);
                    } else {
                        fly.refresh(result.getKey().getKey(), result.getValue());
                        texts.send(sender, message, "player", result.getKey().getValue(),
                                "time", texts.duration(seconds), "total", total(message, result.getKey().getKey(), result.getValue()));
                    }
                });
    }

    private String total(String message, UUID uuid, long seconds) {
        Player target = Bukkit.getPlayer(uuid);
        if (message.equals("admin.check") && target != null) return fly.timeText(target, seconds);
        return texts.duration(seconds);
    }

    private Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player) return player;
        texts.send(ctx.getSource().getSender(), "messages.players-only");
        return null;
    }

    public Texts texts() {
        return texts;
    }

    public Database database() {
        return database;
    }

    public FlyService fly() {
        return fly;
    }
}

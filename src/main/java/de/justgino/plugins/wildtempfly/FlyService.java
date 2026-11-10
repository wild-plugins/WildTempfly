package de.justgino.plugins.wildtempfly;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class FlyService implements Listener {

    public static final class Data {
        private final AtomicLong seconds = new AtomicLong();
        private final AtomicLong used = new AtomicLong();
        private volatile boolean enabled;
        private volatile ScheduledTask timer;
        private volatile long noFallUntil;

        public long seconds() {
            return seconds.get();
        }

        public boolean enabled() {
            return enabled;
        }
    }

    private final WildTempfly plugin;
    private final Map<UUID, Data> online = new ConcurrentHashMap<>();
    private ScheduledTask sync;

    public FlyService(WildTempfly plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long every = plugin.database().syncInterval();
        sync = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> syncAll(), every, every, TimeUnit.SECONDS);
        for (Player player : Bukkit.getOnlinePlayers()) load(player);
    }

    public void stop() {
        if (sync != null) sync.cancel();
        for (Map.Entry<UUID, Data> entry : online.entrySet()) {
            Data data = entry.getValue();
            if (data.timer != null) data.timer.cancel();
            long used = data.used.getAndSet(0);
            if (used > 0) {
                try {
                    plugin.database().add(entry.getKey(), -used);
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not save fly time: " + e.getMessage());
                }
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && data.enabled) resetFlight(player);
        }
        online.clear();
    }

    public Data data(UUID player) {
        return online.get(player);
    }

    private void load(Player player) {
        UUID uuid = player.getUniqueId();
        Data data = new Data();
        online.put(uuid, data);
        plugin.database().run(() -> plugin.database().join(uuid, player.getName()))
                .thenAccept(value -> data.seconds.set(Math.max(0, value - data.used.get())));
    }

    private void syncAll() {
        for (Map.Entry<UUID, Data> entry : online.entrySet()) {
            UUID uuid = entry.getKey();
            Data data = entry.getValue();
            plugin.database().run(() -> {
                long used = data.used.getAndSet(0);
                long value = used > 0 ? plugin.database().add(uuid, -used) : plugin.database().seconds(uuid);
                data.seconds.set(Math.max(0, value - data.used.get()));
                return value;
            });
        }
    }

    public void refresh(UUID uuid, long value) {
        Data data = online.get(uuid);
        if (data != null) data.seconds.set(Math.max(0, value - data.used.get()));
    }

    public boolean worldAllowed(Player player) {
        if (player.hasPermission("wildtempfly.bypass.world")) return true;
        String world = player.getWorld().getName().toLowerCase(Locale.ROOT);
        for (String allowed : plugin.getConfig().getStringList("worlds")) {
            if (allowed.equalsIgnoreCase(world)) return true;
        }
        return false;
    }

    private boolean unlimited(Player player) {
        return player.hasPermission("wildtempfly.bypass.time");
    }

    public void toggle(Player player) {
        Data data = online.get(player.getUniqueId());
        if (data == null) {
            plugin.texts().send(player, "fly.loading");
            return;
        }
        if (data.enabled) {
            disable(player, data);
            plugin.texts().send(player, "fly.off", "time", plugin.texts().duration(data.seconds()));
            sound(player, "disable");
            return;
        }
        if (!worldAllowed(player)) {
            plugin.texts().send(player, "fly.world");
            return;
        }
        if (!unlimited(player) && data.seconds() <= 0) {
            plugin.texts().send(player, "fly.no-time");
            return;
        }
        data.enabled = true;
        player.setAllowFlight(true);
        data.timer = player.getScheduler().runAtFixedRate(plugin, task -> tick(player, data), null, 20L, 20L);
        plugin.texts().send(player, "fly.on", "time", unlimited(player) ? "∞" : plugin.texts().duration(data.seconds()));
        sound(player, "enable");
    }

    private void tick(Player player, Data data) {
        if (!data.enabled) {
            if (data.timer != null) data.timer.cancel();
            return;
        }
        if (!worldAllowed(player)) {
            disable(player, data);
            plugin.texts().send(player, "fly.world-left");
            sound(player, "disable");
            return;
        }
        if (unlimited(player)) {
            if (plugin.getConfig().getBoolean("actionbar", true) && plugin.getConfig().getBoolean("actionbar-unlimited", false)) {
                player.sendActionBar(plugin.texts().get("fly.actionbar", "time", "∞"));
            }
            return;
        }
        boolean counts = !plugin.getConfig().getBoolean("count-only-while-flying", true) || player.isFlying();
        if (counts) {
            long left = Math.max(0, data.seconds.decrementAndGet());
            data.seconds.set(left);
            data.used.incrementAndGet();
            if (plugin.getConfig().getLongList("warnings").contains(left) && left > 0) {
                plugin.texts().send(player, "fly.warning", "time", plugin.texts().duration(left));
                sound(player, "warning");
            }
        }
        if (data.seconds() <= 0) {
            disable(player, data);
            plugin.texts().send(player, "fly.expired");
            sound(player, "disable");
            return;
        }
        if (plugin.getConfig().getBoolean("actionbar", true)) {
            player.sendActionBar(plugin.texts().get("fly.actionbar", "time", plugin.texts().duration(data.seconds())));
        }
    }

    private void disable(Player player, Data data) {
        data.enabled = false;
        if (data.timer != null) data.timer.cancel();
        data.timer = null;
        if (plugin.getConfig().getBoolean("fall-protection", true) && !player.isOnGround()) {
            data.noFallUntil = System.currentTimeMillis() + 30_000L;
        }
        resetFlight(player);
    }

    private void resetFlight(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Data data = online.remove(uuid);
        if (data == null) return;
        if (data.timer != null) data.timer.cancel();
        if (data.enabled) resetFlight(event.getPlayer());
        long used = data.used.getAndSet(0);
        if (used > 0) plugin.database().run(() -> plugin.database().add(uuid, -used));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Data data = online.get(player.getUniqueId());
        if (data == null || !data.enabled || worldAllowed(player)) return;
        disable(player, data);
        plugin.texts().send(player, "fly.world-left");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) return;
        Data data = online.get(player.getUniqueId());
        if (data == null || data.noFallUntil == 0) return;
        if (System.currentTimeMillis() < data.noFallUntil) event.setCancelled(true);
        data.noFallUntil = 0;
    }

    private void sound(Player player, String key) {
        String name = plugin.getConfig().getString("sounds." + key, "");
        if (name == null || name.isBlank()) return;
        try {
            player.playSound(Sound.sound(Key.key(name.trim().toLowerCase(Locale.ROOT)), Sound.Source.MASTER, 1f, 1f));
        } catch (Exception e) {
            plugin.getLogger().warning("Unknown sound in config.yml: " + name);
        }
    }

    public static long parseTime(String input) {
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return -1;
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        long total = 0;
        long number = -1;
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) {
                number = (number < 0 ? 0 : number * 10) + (c - '0');
                continue;
            }
            if (number < 0) return -1;
            long unit = switch (c) {
                case 'd' -> 86400;
                case 'h' -> 3600;
                case 'm' -> 60;
                case 's' -> 1;
                default -> -1;
            };
            if (unit < 0) return -1;
            total += number * unit;
            number = -1;
        }
        return number >= 0 ? -1 : total;
    }
}

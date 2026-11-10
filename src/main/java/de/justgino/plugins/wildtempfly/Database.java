package de.justgino.plugins.wildtempfly;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.core.config.Configurator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class Database {

    private final WildTempfly plugin;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("WildTempfly-Database").daemon(true).factory());
    private HikariDataSource source;
    private boolean mariadb;
    private String table;
    private int syncInterval;

    public Database(WildTempfly plugin) {
        this.plugin = plugin;
    }

    public boolean isMariaDb() {
        return mariadb;
    }

    public int syncInterval() {
        return syncInterval;
    }

    public void connect() throws SQLException {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "database.yml"));
        String type = cfg.getString("type", "sqlite").trim().toLowerCase(Locale.ROOT);
        mariadb = type.equals("mariadb") || type.equals("mysql");
        table = cfg.getString("table-prefix", "wildtempfly_").replaceAll("[^A-Za-z0-9_]", "") + "flytime";
        syncInterval = Math.max(2, cfg.getInt("sync-interval", 10));
        try {
            Configurator.setLevel("de.justgino.plugins.wildtempfly.libs.hikari", org.apache.logging.log4j.Level.WARN);
        } catch (Throwable ignored) {
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("WildTempfly");
        hikari.setConnectionTimeout(10_000);
        if (mariadb) {
            ConfigurationSection db = cfg.getConfigurationSection("mariadb");
            if (db == null) db = cfg.createSection("mariadb");
            Map<String, String> params = new LinkedHashMap<>();
            params.put("sslMode", "disable");
            params.put("allowPublicKeyRetrieval", "true");
            params.put("connectTimeout", "5000");
            params.put("socketTimeout", "30000");
            ConfigurationSection extra = db.getConfigurationSection("properties");
            if (extra != null) for (String key : extra.getKeys(false)) params.put(key, String.valueOf(extra.get(key)));
            String query = params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
            hikari.setDriverClassName("org.mariadb.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mariadb://" + db.getString("host", "localhost") + ":" + port(db.get("port"))
                    + "/" + db.getString("database", "wildtempfly") + "?" + query);
            hikari.setUsername(db.getString("username", "root"));
            hikari.setPassword(db.getString("password", ""));
            hikari.setMaximumPoolSize(Math.max(1, db.getInt("pool-size", 4)));
            hikari.setMinimumIdle(1);
            hikari.setMaxLifetime(600_000);
        } else {
            File file = new File(plugin.getDataFolder(), cfg.getString("sqlite.file", "flytime.db"));
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath() + "?journal_mode=DELETE&synchronous=FULL&busy_timeout=5000");
            hikari.setMaximumPoolSize(1);
        }
        source = new HikariDataSource(hikari);
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "name VARCHAR(32) NOT NULL, name_lower VARCHAR(32) NOT NULL, seconds BIGINT NOT NULL)"
                    + (mariadb ? " DEFAULT CHARSET=utf8mb4" : ""));
        }
        plugin.getLogger().info("Connected to " + (mariadb ? "MariaDB" : "SQLite") + ".");
    }

    private static int port(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 3306;
        }
    }

    public <T> CompletableFuture<T> run(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Database task failed", t);
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (source != null) source.close();
    }

    public long join(UUID uuid, String name) throws SQLException {
        try (Connection c = source.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET name = ?, name_lower = ? WHERE uuid = ?")) {
                ps.setString(1, name);
                ps.setString(2, name.toLowerCase(Locale.ROOT));
                ps.setString(3, uuid.toString());
                if (ps.executeUpdate() == 0) {
                    try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + table + " (uuid, name, name_lower, seconds) VALUES (?, ?, ?, 0)")) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, name);
                        insert.setString(3, name.toLowerCase(Locale.ROOT));
                        insert.executeUpdate();
                    }
                }
            }
        }
        return seconds(uuid);
    }

    public long seconds(UUID uuid) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT seconds FROM " + table + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0, rs.getLong(1)) : 0;
            }
        }
    }

    public long add(UUID uuid, long delta) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + table
                     + " SET seconds = CASE WHEN seconds + ? < 0 THEN 0 ELSE seconds + ? END WHERE uuid = ?")) {
            ps.setLong(1, delta);
            ps.setLong(2, delta);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
        return seconds(uuid);
    }

    public long set(UUID uuid, long value) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET seconds = ? WHERE uuid = ?")) {
            ps.setLong(1, Math.max(0, value));
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
        return seconds(uuid);
    }

    public @Nullable Map.Entry<UUID, String> find(String name) throws SQLException {
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name FROM " + table + " WHERE name_lower = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Map.entry(UUID.fromString(rs.getString(1)), rs.getString(2)) : null;
            }
        }
    }
}

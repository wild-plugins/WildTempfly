package de.justgino.plugins.wildtempfly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Texts {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final String file;
    private YamlConfiguration texts = new YamlConfiguration();

    public Texts(JavaPlugin plugin, String file) {
        this.plugin = plugin;
        this.file = file;
    }

    public void load() {
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), file));
        try (InputStream in = plugin.getResource(file)) {
            if (in != null) loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
        }
        texts = loaded;
    }

    public String raw(String path) {
        return texts.getString(path, "");
    }

    public Component get(String path, Object... placeholders) {
        return parse(raw(path), placeholders);
    }

    public void send(CommandSender to, String path, Object... placeholders) {
        String text = raw(path);
        if (!text.isEmpty()) to.sendMessage(parse(raw("prefix") + text, placeholders));
    }

    public void sendLines(CommandSender to, String path, Object... placeholders) {
        List<String> lines = texts.isList(path) ? texts.getStringList(path) : List.of(raw(path));
        for (String line : lines) to.sendMessage(parse(line, placeholders));
    }

    public String duration(long totalSeconds) {
        long seconds = Math.max(0, totalSeconds);
        long[] parts = {seconds / 86400, seconds % 86400 / 3600, seconds % 3600 / 60, seconds % 60};
        String[] keys = {"time.days", "time.hours", "time.minutes", "time.seconds"};
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] > 0 || (out.isEmpty() && i == parts.length - 1)) out.add(raw(keys[i]).replace("{n}", String.valueOf(parts[i])));
        }
        return String.join(" ", out);
    }

    public static Component parse(String raw, Object... placeholders) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        String text = raw;
        TagResolver.Builder tags = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String key = String.valueOf(placeholders[i]);
            String token = "{" + key + "}";
            if (!text.contains(token)) continue;
            if (placeholders[i + 1] instanceof Component component) {
                String tag = "ph_" + key.replaceAll("[^a-z0-9_]", "");
                tags.resolver(Placeholder.component(tag, component));
                text = text.replace(token, "<" + tag + ">");
            } else {
                text = text.replace(token, MINI.escapeTags(String.valueOf(placeholders[i + 1])));
            }
        }
        return MINI.deserialize(text, tags.build());
    }
}

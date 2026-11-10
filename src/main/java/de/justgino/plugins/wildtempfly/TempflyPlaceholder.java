package de.justgino.plugins.wildtempfly;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class TempflyPlaceholder extends PlaceholderExpansion {

    private final WildTempfly plugin;

    public TempflyPlaceholder(WildTempfly plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "wildtempfly";
    }

    @Override
    public @NotNull String getAuthor() {
        return "JustGino";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        FlyService.Data data = plugin.fly().data(player.getUniqueId());
        long seconds = data == null ? 0 : data.seconds();
        return switch (params) {
            case "time" -> plugin.texts().duration(seconds);
            case "seconds" -> String.valueOf(seconds);
            case "enabled" -> String.valueOf(data != null && data.enabled());
            default -> null;
        };
    }
}

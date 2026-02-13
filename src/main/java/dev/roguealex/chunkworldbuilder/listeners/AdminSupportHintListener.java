package dev.roguealex.chunkworldbuilder.listeners;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class AdminSupportHintListener implements Listener {

    private final JavaPlugin plugin;
    private final boolean enabled;
    private final String messageTemplate;
    private final String linkTextTemplate;
    private final String linkUrl;
    private final boolean useMiniMessage;
    private final String fontKey;
    private final int delayTicks;
    private final Set<UUID> shownThisRuntime;
    private final MiniMessage miniMessage;

    public AdminSupportHintListener(
            JavaPlugin plugin,
            boolean enabled,
            String messageTemplate,
            String linkTextTemplate,
            String linkUrl,
            boolean useMiniMessage,
            String fontKey,
            int delayTicks
    ) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.messageTemplate = messageTemplate;
        this.linkTextTemplate = linkTextTemplate;
        this.linkUrl = linkUrl;
        this.useMiniMessage = useMiniMessage;
        this.fontKey = (fontKey == null || fontKey.isBlank()) ? null : fontKey.trim();
        this.delayTicks = Math.max(0, delayTicks);
        this.shownThisRuntime = ConcurrentHashMap.newKeySet();
        this.miniMessage = MiniMessage.miniMessage();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isOp() && !player.hasPermission("chunkworldbuilder.admin")) {
            return;
        }
        if (!shownThisRuntime.add(player.getUniqueId())) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            sendSupportMessage(player);
        }, delayTicks);
    }

    private void sendSupportMessage(Player player) {
        Component message = deserialize(messageTemplate);
        Component link = deserialize(linkTextTemplate);
        if (linkUrl != null && !linkUrl.isBlank()) {
            link = link.clickEvent(ClickEvent.openUrl(linkUrl))
                    .hoverEvent(HoverEvent.showText(Component.text(linkUrl)));
        }

        message = applyFontIfPresent(message);
        link = applyFontIfPresent(link);

        if (!message.equals(Component.empty())) {
            player.sendMessage(message);
        }
        if (!link.equals(Component.empty())) {
            player.sendMessage(link);
        }
    }

    private Component deserialize(String value) {
        if (value == null || value.isBlank()) {
            return Component.empty();
        }
        if (useMiniMessage) {
            return miniMessage.deserialize(value);
        }
        return Component.text(value);
    }

    private Component applyFontIfPresent(Component component) {
        if (fontKey == null) {
            return component;
        }
        return component.font(Key.key(fontKey));
    }
}

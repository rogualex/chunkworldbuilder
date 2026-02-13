package dev.roguealex.chunkworldbuilder.listeners;

import dev.roguealex.chunkworldbuilder.service.EndPortalProgressionService;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class AdvancementListener implements Listener {

    private final JavaPlugin plugin;
    private final World targetWorld;
    private final EndPortalProgressionService progressionService;
    private final String triggerAdvancementKey;

    public AdvancementListener(
            JavaPlugin plugin,
            World targetWorld,
            EndPortalProgressionService progressionService,
            String triggerAdvancementKey
    ) {
        this.plugin = plugin;
        this.targetWorld = targetWorld;
        this.progressionService = progressionService;
        this.triggerAdvancementKey = triggerAdvancementKey;
    }

    @EventHandler
    public void onPlayerAdvancementDone(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!player.getWorld().equals(targetWorld)) {
            return;
        }

        NamespacedKey key = event.getAdvancement().getKey();
        if (!triggerAdvancementKey.equals(key.asString())) {
            return;
        }

        try {
            Location portal = progressionService.ensurePortalForPlayerDirection(player);
            player.sendMessage("End portal prepared at X=" + portal.getBlockX()
                    + " Y=" + portal.getBlockY()
                    + " Z=" + portal.getBlockZ());
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Failed to create end portal progression patch: " + ex.getMessage());
        }
    }
}

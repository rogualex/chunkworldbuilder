package dev.roguealex.chunkworldbuilder.listeners;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import dev.roguealex.chunkworldbuilder.patch.PatchStateRegistry;
import dev.roguealex.chunkworldbuilder.patch.PatchStatus;
import dev.roguealex.chunkworldbuilder.service.WorldExpansionService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class TeleportSafetyListener implements Listener {

    private final JavaPlugin plugin;
    private final World targetWorld;
    private final PatchStateRegistry patchStateRegistry;
    private final WorldExpansionService worldExpansionService;
    private final int patchWidth;
    private final int patchLength;
    private final int pregenRadiusPatches;
    private final long timeoutMillis;
    private final Map<UUID, PendingTeleport> pendingTeleports;
    private final Set<UUID> bypassOnce;
    private int taskId;

    public TeleportSafetyListener(
            JavaPlugin plugin,
            World targetWorld,
            PatchStateRegistry patchStateRegistry,
            WorldExpansionService worldExpansionService,
            int patchWidth,
            int patchLength,
            int pregenRadiusPatches,
            int timeoutSeconds
    ) {
        this.plugin = plugin;
        this.targetWorld = targetWorld;
        this.patchStateRegistry = patchStateRegistry;
        this.worldExpansionService = worldExpansionService;
        this.patchWidth = patchWidth;
        this.patchLength = patchLength;
        this.pregenRadiusPatches = Math.max(0, pregenRadiusPatches);
        this.timeoutMillis = Math.max(1, timeoutSeconds) * 1000L;
        this.pendingTeleports = new HashMap<>();
        this.bypassOnce = new HashSet<>();
        this.taskId = -1;
    }

    public void start() {
        if (taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        pendingTeleports.clear();
        bypassOnce.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || !to.getWorld().equals(targetWorld)) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        if (bypassOnce.remove(playerId)) {
            return;
        }

        PatchCoord targetPatch = PatchCoord.fromBlock(
                to.getBlockX(),
                to.getBlockZ(),
                patchWidth,
                patchLength
        );

        if (patchStateRegistry.getStatus(targetPatch) == PatchStatus.DONE) {
            return;
        }

        event.setCancelled(true);
        worldExpansionService.queuePatchUrgent(targetPatch);
        worldExpansionService.queueAroundUrgent(targetPatch, pregenRadiusPatches);
        pendingTeleports.put(playerId, new PendingTeleport(to.clone(), System.currentTimeMillis()));

        event.getPlayer().sendMessage("Preparing destination area, teleport will continue in a moment...");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingTeleport>> iterator = pendingTeleports.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingTeleport> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }

            PendingTeleport pending = entry.getValue();
            PatchCoord targetPatch = PatchCoord.fromBlock(
                    pending.target.getBlockX(),
                    pending.target.getBlockZ(),
                    patchWidth,
                    patchLength
            );

            if (patchStateRegistry.getStatus(targetPatch) == PatchStatus.DONE) {
                bypassOnce.add(player.getUniqueId());
                player.teleport(pending.target);
                player.sendMessage("Destination is ready.");
                iterator.remove();
                continue;
            }

            if ((now - pending.createdAtMillis) > timeoutMillis) {
                player.sendMessage("Teleport cancelled: destination generation timed out.");
                iterator.remove();
            }
        }
    }

    private record PendingTeleport(Location target, long createdAtMillis) {
    }
}

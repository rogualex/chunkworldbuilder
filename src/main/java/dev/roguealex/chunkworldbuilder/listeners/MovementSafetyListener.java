package dev.roguealex.chunkworldbuilder.listeners;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import dev.roguealex.chunkworldbuilder.patch.PatchStateRegistry;
import dev.roguealex.chunkworldbuilder.patch.PatchStatus;
import dev.roguealex.chunkworldbuilder.service.WorldExpansionService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class MovementSafetyListener implements Listener {

    private final World targetWorld;
    private final PatchStateRegistry patchStateRegistry;
    private final WorldExpansionService worldExpansionService;
    private final int patchWidth;
    private final int patchLength;
    private final long messageCooldownMillis;
    private final Map<UUID, Long> lastMessageTime;

    public MovementSafetyListener(
            World targetWorld,
            PatchStateRegistry patchStateRegistry,
            WorldExpansionService worldExpansionService,
            int patchWidth,
            int patchLength,
            int messageCooldownSeconds
    ) {
        this.targetWorld = targetWorld;
        this.patchStateRegistry = patchStateRegistry;
        this.worldExpansionService = worldExpansionService;
        this.patchWidth = patchWidth;
        this.patchLength = patchLength;
        this.messageCooldownMillis = Math.max(1, messageCooldownSeconds) * 1000L;
        this.lastMessageTime = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        if (!event.getTo().getWorld().equals(targetWorld)) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        PatchCoord fromPatch = PatchCoord.fromBlock(
                event.getFrom().getBlockX(),
                event.getFrom().getBlockZ(),
                patchWidth,
                patchLength
        );
        PatchCoord toPatch = PatchCoord.fromBlock(
                event.getTo().getBlockX(),
                event.getTo().getBlockZ(),
                patchWidth,
                patchLength
        );

        if (fromPatch.equals(toPatch)) {
            return;
        }
        if (patchStateRegistry.getStatus(toPatch) == PatchStatus.DONE) {
            return;
        }

        worldExpansionService.queuePatchUrgent(toPatch);
        event.setTo(event.getFrom());
        maybeNotify(event.getPlayer());
    }

    private void maybeNotify(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMessageTime.get(player.getUniqueId());
        if (last != null && (now - last) < messageCooldownMillis) {
            return;
        }
        lastMessageTime.put(player.getUniqueId(), now);
        player.sendMessage("This area is still generating. Please wait a moment.");
    }
}

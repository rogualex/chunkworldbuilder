package dev.roguealex.chunkworldbuilder.listeners;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import dev.roguealex.chunkworldbuilder.service.WorldExpansionService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class PlayerMoveWatcher implements Listener {

    private final World targetWorld;
    private final WorldExpansionService worldExpansionService;
    private final int patchWidth;
    private final int patchLength;
    private final int edgeTriggerDistanceBlocks;
    private final long checkIntervalMillis;
    private final Map<UUID, Long> lastCheckTimeMillis;

    public PlayerMoveWatcher(
            World targetWorld,
            WorldExpansionService worldExpansionService,
            int patchWidth,
            int patchLength,
            int edgeTriggerDistanceBlocks,
            int checkIntervalTicks
    ) {
        this.targetWorld = targetWorld;
        this.worldExpansionService = worldExpansionService;
        this.patchWidth = patchWidth;
        this.patchLength = patchLength;
        this.edgeTriggerDistanceBlocks = Math.max(1, edgeTriggerDistanceBlocks);
        this.checkIntervalMillis = Math.max(1, checkIntervalTicks) * 50L;
        this.lastCheckTimeMillis = new ConcurrentHashMap<>();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld() == null || event.getTo().getWorld() == null) {
            return;
        }
        if (!event.getTo().getWorld().equals(targetWorld)) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastCheckTimeMillis.get(event.getPlayer().getUniqueId());
        if (last != null && (now - last) < checkIntervalMillis) {
            return;
        }
        lastCheckTimeMillis.put(event.getPlayer().getUniqueId(), now);

        maybeQueueExpansion(
                event.getFrom().getBlockX(),
                event.getFrom().getBlockZ(),
                event.getTo().getBlockX(),
                event.getTo().getBlockZ()
        );
    }

    private void maybeQueueExpansion(int fromX, int fromZ, int toX, int toZ) {
        PatchCoord currentPatch = PatchCoord.fromBlock(
                toX,
                toZ,
                patchWidth,
                patchLength
        );

        int localX = Math.floorMod(toX, patchWidth);
        int localZ = Math.floorMod(toZ, patchLength);
        Edge edge = pickApproachedEdge(localX, localZ, fromX, fromZ, toX, toZ);
        if (edge == null) {
            return;
        }

        worldExpansionService.queuePatch(neighborPatch(currentPatch, edge));
    }

    private enum Edge {
        WEST, EAST, NORTH, SOUTH
    }

    private Edge pickApproachedEdge(int localX, int localZ, int fromX, int fromZ, int toX, int toZ) {
        int moveX = toX - fromX;
        int moveZ = toZ - fromZ;
        int absX = Math.abs(moveX);
        int absZ = Math.abs(moveZ);

        if (absX == 0 && absZ == 0) {
            return null;
        }

        if (absX >= absZ) {
            if (moveX < 0 && localX <= edgeTriggerDistanceBlocks) {
                return Edge.WEST;
            }
            if (moveX > 0 && localX >= (patchWidth - 1 - edgeTriggerDistanceBlocks)) {
                return Edge.EAST;
            }
        }

        if (absZ >= absX) {
            if (moveZ < 0 && localZ <= edgeTriggerDistanceBlocks) {
                return Edge.NORTH;
            }
            if (moveZ > 0 && localZ >= (patchLength - 1 - edgeTriggerDistanceBlocks)) {
                return Edge.SOUTH;
            }
        }

        return null;
    }

    private PatchCoord neighborPatch(PatchCoord currentPatch, Edge edge) {
        return switch (edge) {
            case WEST -> new PatchCoord(currentPatch.patchX() - 1, currentPatch.patchZ());
            case EAST -> new PatchCoord(currentPatch.patchX() + 1, currentPatch.patchZ());
            case NORTH -> new PatchCoord(currentPatch.patchX(), currentPatch.patchZ() - 1);
            case SOUTH -> new PatchCoord(currentPatch.patchX(), currentPatch.patchZ() + 1);
        };
    }
}

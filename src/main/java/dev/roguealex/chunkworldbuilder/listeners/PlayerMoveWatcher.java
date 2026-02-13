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
        if (event.getFrom().distanceSquared(event.getTo()) < 1.0e-6) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastCheckTimeMillis.get(event.getPlayer().getUniqueId());
        if (last != null && (now - last) < checkIntervalMillis) {
            return;
        }
        lastCheckTimeMillis.put(event.getPlayer().getUniqueId(), now);

        maybeQueueExpansion(
                event.getFrom().getX(),
                event.getFrom().getZ(),
                event.getTo().getBlockX(),
                event.getTo().getBlockZ(),
                event.getTo().getX(),
                event.getTo().getZ(),
                event.getTo().getDirection().getX(),
                event.getTo().getDirection().getZ()
        );
    }

    private void maybeQueueExpansion(
            double fromX,
            double fromZ,
            int toBlockX,
            int toBlockZ,
            double toX,
            double toZ,
            double lookX,
            double lookZ
    ) {
        PatchCoord currentPatch = PatchCoord.fromBlock(
                toBlockX,
                toBlockZ,
                patchWidth,
                patchLength
        );

        int localX = Math.floorMod(toBlockX, patchWidth);
        int localZ = Math.floorMod(toBlockZ, patchLength);
        double moveX = toX - fromX;
        double moveZ = toZ - fromZ;

        Edge edge = pickApproachedEdge(localX, localZ, moveX, moveZ);
        if (edge == null) {
            edge = pickApproachedEdge(localX, localZ, lookX, lookZ);
        }
        if (edge == null) {
            return;
        }

        worldExpansionService.queuePatch(neighborPatch(currentPatch, edge));
    }

    private enum Edge {
        WEST, EAST, NORTH, SOUTH
    }

    private Edge pickApproachedEdge(int localX, int localZ, double moveX, double moveZ) {
        double absX = Math.abs(moveX);
        double absZ = Math.abs(moveZ);

        if (absX < 1.0e-6 && absZ < 1.0e-6) {
            return null;
        }

        if (absX >= absZ) {
            if (moveX < 0.0 && localX <= edgeTriggerDistanceBlocks) {
                return Edge.WEST;
            }
            if (moveX > 0.0 && localX >= (patchWidth - 1 - edgeTriggerDistanceBlocks)) {
                return Edge.EAST;
            }
        }

        if (absZ >= absX) {
            if (moveZ < 0.0 && localZ <= edgeTriggerDistanceBlocks) {
                return Edge.NORTH;
            }
            if (moveZ > 0.0 && localZ >= (patchLength - 1 - edgeTriggerDistanceBlocks)) {
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

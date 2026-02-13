package dev.roguealex.chunkworldbuilder.service;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import dev.roguealex.chunkworldbuilder.patch.PatchStateRegistry;
import dev.roguealex.chunkworldbuilder.patch.PatchStatus;
import dev.roguealex.chunkworldbuilder.storage.GeneratedPatchStorage;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class EndPortalProgressionService {

    private final World targetWorld;
    private final PatchCopyService patchCopyService;
    private final PatchStateRegistry patchStateRegistry;
    private final GeneratedPatchStorage storage;
    private final int patchWidth;
    private final int patchLength;
    private final int edgeOffsetPatches;

    public EndPortalProgressionService(
            World targetWorld,
            PatchCopyService patchCopyService,
            PatchStateRegistry patchStateRegistry,
            GeneratedPatchStorage storage,
            int patchWidth,
            int patchLength,
            int edgeOffsetPatches
    ) {
        this.targetWorld = targetWorld;
        this.patchCopyService = patchCopyService;
        this.patchStateRegistry = patchStateRegistry;
        this.storage = storage;
        this.patchWidth = patchWidth;
        this.patchLength = patchLength;
        this.edgeOffsetPatches = Math.max(1, edgeOffsetPatches);
    }

    public synchronized Location ensurePortalForPlayerDirection(Player player) {
        Location existing = storage.getEndPortalLocation();
        if (existing != null) {
            return existing;
        }

        PatchCoord playerPatch = PatchCoord.fromBlock(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ(),
                patchWidth,
                patchLength
        );

        PatchCoord targetPatch = selectEdgePatch(playerPatch, player.getLocation().getDirection());
        ensurePatchDone(targetPatch);

        Location portalLocation = buildPortalRoom(targetPatch);
        storage.markEndPortalSpawned(portalLocation);
        return portalLocation;
    }

    private void ensurePatchDone(PatchCoord patchCoord) {
        PatchStatus status = patchStateRegistry.getStatus(patchCoord);
        if (status == PatchStatus.DONE) {
            return;
        }

        if (!patchStateRegistry.tryQueue(patchCoord)) {
            throw new IllegalStateException("Patch is not available for generation: " + patchCoord.asKey());
        }

        if (!patchStateRegistry.tryStartGenerating(patchCoord)) {
            patchStateRegistry.resetToNew(patchCoord);
            throw new IllegalStateException("Could not start generation for patch: " + patchCoord.asKey());
        }

        try {
            patchCopyService.copyPatchFromRandomDonor(patchCoord);
            patchStateRegistry.markDone(patchCoord);
        } catch (RuntimeException ex) {
            patchStateRegistry.resetToNew(patchCoord);
            throw ex;
        }
    }

    private PatchCoord selectEdgePatch(PatchCoord playerPatch, Vector lookVector) {
        Set<PatchCoord> done = new HashSet<>(storage.getGeneratedPatches());
        done.add(playerPatch);

        int minX = playerPatch.patchX();
        int maxX = playerPatch.patchX();
        int minZ = playerPatch.patchZ();
        int maxZ = playerPatch.patchZ();

        for (PatchCoord patch : done) {
            if (patch.patchX() < minX) {
                minX = patch.patchX();
            }
            if (patch.patchX() > maxX) {
                maxX = patch.patchX();
            }
            if (patch.patchZ() < minZ) {
                minZ = patch.patchZ();
            }
            if (patch.patchZ() > maxZ) {
                maxZ = patch.patchZ();
            }
        }

        double absX = Math.abs(lookVector.getX());
        double absZ = Math.abs(lookVector.getZ());
        if (absX >= absZ) {
            if (lookVector.getX() >= 0) {
                return new PatchCoord(maxX + edgeOffsetPatches, clamp(playerPatch.patchZ(), minZ, maxZ));
            }
            return new PatchCoord(minX - edgeOffsetPatches, clamp(playerPatch.patchZ(), minZ, maxZ));
        }

        if (lookVector.getZ() >= 0) {
            return new PatchCoord(clamp(playerPatch.patchX(), minX, maxX), maxZ + edgeOffsetPatches);
        }
        return new PatchCoord(clamp(playerPatch.patchX(), minX, maxX), minZ - edgeOffsetPatches);
    }

    private Location buildPortalRoom(PatchCoord patchCoord) {
        int minX = patchCoord.minBlockX(patchWidth);
        int minZ = patchCoord.minBlockZ(patchLength);

        int centerX = minX + (patchWidth / 2);
        int centerZ = minZ + (patchLength / 2);
        int y = Math.max(targetWorld.getMinHeight() + 5, targetWorld.getHighestBlockYAt(centerX, centerZ) + 1);

        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                setType(centerX + dx, y - 1, centerZ + dz, Material.STONE_BRICKS);
                for (int dy = 0; dy <= 5; dy++) {
                    setType(centerX + dx, y + dy, centerZ + dz, Material.AIR);
                }
            }
        }

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 && Math.abs(dz) <= 1) {
                    placeFrame(centerX + dx, y, centerZ + dz, dx < 0 ? "east" : "west");
                } else if (Math.abs(dz) == 2 && Math.abs(dx) <= 1) {
                    placeFrame(centerX + dx, y, centerZ + dz, dz < 0 ? "south" : "north");
                } else if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    setType(centerX + dx, y, centerZ + dz, Material.END_PORTAL);
                }
            }
        }

        return new Location(targetWorld, centerX + 0.5, y + 1, centerZ + 0.5);
    }

    private void placeFrame(int x, int y, int z, String facing) {
        Block block = targetWorld.getBlockAt(x, y, z);
        BlockData data = Material.END_PORTAL_FRAME.createBlockData("[eye=true,facing=" + facing + "]");
        block.setBlockData(data, false);
    }

    private void setType(int x, int y, int z, Material material) {
        targetWorld.getBlockAt(x, y, z).setType(material, false);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}

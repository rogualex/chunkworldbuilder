package dev.roguealex.chunkworldbuilder.service;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

public final class PatchGenerationTask {

    private final PatchCoord targetPatch;
    private final PatchCoord donorPatch;
    private final PatchCopyService patchCopyService;
    private final World targetWorld;
    private final World donorWorld;
    private final int patchWidth;
    private final int patchLength;
    private final boolean copyBiomes;
    private final int minY;
    private final int maxY;
    private final int targetMinX;
    private final int targetMinZ;
    private final int donorMinX;
    private final int donorMinZ;
    private final WorldEditPatchCopyEngine worldEditEngine;
    private final int targetMaxX;
    private final int targetMaxZ;
    private final int donorMaxX;
    private final int donorMaxZ;
    private int dx;
    private int dz;
    private int y;
    private boolean complete;
    private boolean copiedWithWorldEdit;
    private boolean prepared;

    public PatchGenerationTask(
            PatchCoord targetPatch,
            PatchCoord donorPatch,
            PatchCopyService patchCopyService,
            WorldEditPatchCopyEngine worldEditEngine
    ) {
        this.targetPatch = targetPatch;
        this.donorPatch = donorPatch;
        this.patchCopyService = patchCopyService;
        this.worldEditEngine = worldEditEngine;
        this.targetWorld = patchCopyService.getTargetWorld();
        this.donorWorld = patchCopyService.getDonorWorld();
        this.patchWidth = patchCopyService.getPatchWidth();
        this.patchLength = patchCopyService.getPatchLength();
        this.copyBiomes = patchCopyService.isCopyBiomesEnabled();

        minY = Math.max(targetWorld.getMinHeight(), donorWorld.getMinHeight());
        maxY = Math.min(targetWorld.getMaxHeight(), donorWorld.getMaxHeight()) - 1;

        targetMinX = targetPatch.minBlockX(patchWidth);
        targetMinZ = targetPatch.minBlockZ(patchLength);
        targetMaxX = targetMinX + patchWidth - 1;
        targetMaxZ = targetMinZ + patchLength - 1;
        donorMinX = donorPatch.minBlockX(patchWidth);
        donorMinZ = donorPatch.minBlockZ(patchLength);
        donorMaxX = donorMinX + patchWidth - 1;
        donorMaxZ = donorMinZ + patchLength - 1;

        dx = 0;
        dz = 0;
        y = minY;
        complete = maxY < minY;
        copiedWithWorldEdit = false;
        prepared = false;
    }

    public int process(int maxBlocks) {
        if (!prepared) {
            prepareChunks();
            prepared = true;
        }

        if (!complete && !copiedWithWorldEdit && worldEditEngine != null) {
            worldEditEngine.copyPatch(targetPatch, donorPatch);
            copiedWithWorldEdit = true;
            complete = true;
            return Math.max(1, maxBlocks);
        }

        int processed = 0;
        while (!complete && processed < maxBlocks) {
            copyCurrentBlock();
            processed++;
            advanceCursor();
        }
        return processed;
    }

    public boolean isComplete() {
        return complete;
    }

    public PatchCoord targetPatch() {
        return targetPatch;
    }

    private void prepareChunks() {
        loadChunkRange(targetWorld, targetMinX, targetMaxX, targetMinZ, targetMaxZ);
        loadChunkRange(donorWorld, donorMinX, donorMaxX, donorMinZ, donorMaxZ);
    }

    private void loadChunkRange(World world, int minX, int maxX, int minZ, int maxZ) {
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                world.loadChunk(chunkX, chunkZ, true);
            }
        }
    }

    private void copyCurrentBlock() {
        int targetX = targetMinX + dx;
        int targetZ = targetMinZ + dz;
        int donorX = donorMinX + dx;
        int donorZ = donorMinZ + dz;

        Block donorBlock = donorWorld.getBlockAt(donorX, y, donorZ);
        Block targetBlock = targetWorld.getBlockAt(targetX, y, targetZ);

        BlockData donorData = donorBlock.getBlockData();
        targetBlock.setBlockData(donorData, false);

        if (copyBiomes) {
            Biome biome = donorWorld.getBiome(donorX, y, donorZ);
            targetWorld.setBiome(targetX, y, targetZ, biome);
        }
    }

    private void advanceCursor() {
        y++;
        if (y <= maxY) {
            return;
        }

        y = minY;
        dz++;
        if (dz < patchLength) {
            return;
        }

        dz = 0;
        dx++;
        if (dx >= patchWidth) {
            complete = true;
        }
    }
}

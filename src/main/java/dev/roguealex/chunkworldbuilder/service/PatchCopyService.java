package dev.roguealex.chunkworldbuilder.service;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

public final class PatchCopyService {

    private final World targetWorld;
    private final World donorWorld;
    private final int patchWidth;
    private final int patchLength;
    private final int donorMinPatchX;
    private final int donorMaxPatchX;
    private final int donorMinPatchZ;
    private final int donorMaxPatchZ;
    private final boolean copyBiomes;
    private final boolean copyTileEntities;

    public PatchCopyService(
            World targetWorld,
            World donorWorld,
            int patchWidth,
            int patchLength,
            int donorRangeMinX,
            int donorRangeMaxX,
            int donorRangeMinZ,
            int donorRangeMaxZ,
            boolean copyBiomes,
            boolean copyTileEntities
    ) {
        this.targetWorld = targetWorld;
        this.donorWorld = donorWorld;
        this.patchWidth = patchWidth;
        this.patchLength = patchLength;
        this.copyBiomes = copyBiomes;
        this.copyTileEntities = copyTileEntities;

        int maxStartX = donorRangeMaxX - patchWidth + 1;
        int maxStartZ = donorRangeMaxZ - patchLength + 1;
        donorMinPatchX = ceilDiv(donorRangeMinX, patchWidth);
        donorMaxPatchX = Math.floorDiv(maxStartX, patchWidth);
        donorMinPatchZ = ceilDiv(donorRangeMinZ, patchLength);
        donorMaxPatchZ = Math.floorDiv(maxStartZ, patchLength);

        if (donorMinPatchX > donorMaxPatchX || donorMinPatchZ > donorMaxPatchZ) {
            throw new IllegalArgumentException(
                    "Donor range is too small for configured patch size: "
                            + "patch=" + patchWidth + "x" + patchLength
                            + ", rangeX=[" + donorRangeMinX + ".." + donorRangeMaxX + "]"
                            + ", rangeZ=[" + donorRangeMinZ + ".." + donorRangeMaxZ + "]"
            );
        }
    }

    public CopyResult copyPatchFromRandomDonor(PatchCoord targetPatch) {
        PatchCoord donorPatch = selectRandomDonorPatch();
        copyPatch(targetPatch, donorPatch);
        return new CopyResult(targetPatch, donorPatch);
    }

    public void copyPatch(PatchCoord targetPatch, PatchCoord donorPatch) {
        int targetMinX = targetPatch.minBlockX(patchWidth);
        int targetMinZ = targetPatch.minBlockZ(patchLength);
        int donorMinX = donorPatch.minBlockX(patchWidth);
        int donorMinZ = donorPatch.minBlockZ(patchLength);

        int minY = Math.max(targetWorld.getMinHeight(), donorWorld.getMinHeight());
        int maxY = Math.min(targetWorld.getMaxHeight(), donorWorld.getMaxHeight()) - 1;

        for (int dx = 0; dx < patchWidth; dx++) {
            for (int dz = 0; dz < patchLength; dz++) {
                int targetX = targetMinX + dx;
                int targetZ = targetMinZ + dz;
                int donorX = donorMinX + dx;
                int donorZ = donorMinZ + dz;

                for (int y = minY; y <= maxY; y++) {
                    Block donorBlock = donorWorld.getBlockAt(donorX, y, donorZ);
                    Block targetBlock = targetWorld.getBlockAt(targetX, y, targetZ);

                    BlockData donorData = donorBlock.getBlockData();
                    targetBlock.setBlockData(donorData, false);

                    if (copyBiomes) {
                        Biome biome = donorWorld.getBiome(donorX, y, donorZ);
                        targetWorld.setBiome(targetX, y, targetZ, biome);
                    }
                }
            }
        }
    }

    public boolean isCopyTileEntitiesEnabled() {
        return copyTileEntities;
    }

    public boolean isCopyBiomesEnabled() {
        return copyBiomes;
    }

    public World getTargetWorld() {
        return targetWorld;
    }

    public World getDonorWorld() {
        return donorWorld;
    }

    public int getPatchWidth() {
        return patchWidth;
    }

    public int getPatchLength() {
        return patchLength;
    }

    public PatchCoord selectRandomDonorPatch() {
        int x = ThreadLocalRandom.current().nextInt(donorMinPatchX, donorMaxPatchX + 1);
        int z = ThreadLocalRandom.current().nextInt(donorMinPatchZ, donorMaxPatchZ + 1);
        return new PatchCoord(x, z);
    }

    public PatchCoord selectRandomDonorPatchPreferLand(int maxAttempts) {
        int attempts = Math.max(1, maxAttempts);
        PatchCoord fallback = selectRandomDonorPatch();
        for (int i = 0; i < attempts; i++) {
            PatchCoord candidate = selectRandomDonorPatch();
            if (isLikelyLandPatch(candidate)) {
                return candidate;
            }
            fallback = candidate;
        }
        return fallback;
    }

    private boolean isLikelyLandPatch(PatchCoord patch) {
        int minX = patch.minBlockX(patchWidth);
        int minZ = patch.minBlockZ(patchLength);
        int centerX = minX + (patchWidth / 2);
        int centerZ = minZ + (patchLength / 2);

        int oceanSamples = 0;
        int totalSamples = 0;
        int[] sampleOffsetsX = new int[]{0, -(patchWidth / 3), patchWidth / 3};
        int[] sampleOffsetsZ = new int[]{0, -(patchLength / 3), patchLength / 3};

        for (int ox : sampleOffsetsX) {
            for (int oz : sampleOffsetsZ) {
                int sampleX = centerX + ox;
                int sampleZ = centerZ + oz;
                int sampleY = Math.max(donorWorld.getMinHeight(), donorWorld.getHighestBlockYAt(sampleX, sampleZ));
                Biome biome = donorWorld.getBiome(sampleX, sampleY, sampleZ);
                if (isOceanBiome(biome)) {
                    oceanSamples++;
                }
                totalSamples++;
            }
        }

        return oceanSamples <= (totalSamples / 3);
    }

    private boolean isOceanBiome(Biome biome) {
        String biomeName = biome.name().toUpperCase(Locale.ROOT);
        return biomeName.contains("OCEAN");
    }

    private static int ceilDiv(int value, int divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    public record CopyResult(PatchCoord targetPatch, PatchCoord donorPatch) {
    }
}

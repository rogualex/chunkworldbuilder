package dev.roguealex.chunkworldbuilder.patch;

import java.util.Objects;

public record PatchCoord(int patchX, int patchZ) {

    public static PatchCoord fromBlock(int blockX, int blockZ, int patchWidth, int patchLength) {
        validatePatchSize(patchWidth, patchLength);
        int x = Math.floorDiv(blockX, patchWidth);
        int z = Math.floorDiv(blockZ, patchLength);
        return new PatchCoord(x, z);
    }

    public int minBlockX(int patchWidth) {
        if (patchWidth <= 0) {
            throw new IllegalArgumentException("patchWidth must be > 0");
        }
        return patchX * patchWidth;
    }

    public int minBlockZ(int patchLength) {
        if (patchLength <= 0) {
            throw new IllegalArgumentException("patchLength must be > 0");
        }
        return patchZ * patchLength;
    }

    public String asKey() {
        return patchX + "," + patchZ;
    }

    public static PatchCoord fromKey(String key) {
        Objects.requireNonNull(key, "key");
        String[] parts = key.split(",", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid patch key: " + key);
        }

        try {
            return new PatchCoord(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid patch key: " + key, ex);
        }
    }

    private static void validatePatchSize(int patchWidth, int patchLength) {
        if (patchWidth <= 0 || patchLength <= 0) {
            throw new IllegalArgumentException("Patch size must be > 0");
        }
    }
}

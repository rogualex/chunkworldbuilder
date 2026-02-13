package dev.roguealex.chunkworldbuilder.storage;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class GeneratedPatchStorage {

    private static final String GENERATED_PATH = "generated";
    private static final String META_TARGET_WORLD_UUID_PATH = "meta.target-world-uuid";
    private static final String META_PATCH_WIDTH_PATH = "meta.patch-width";
    private static final String META_PATCH_LENGTH_PATH = "meta.patch-length";
    private static final String END_PORTAL_SPAWNED_PATH = "end-portal.spawned";
    private static final String END_PORTAL_WORLD_PATH = "end-portal.world";
    private static final String END_PORTAL_X_PATH = "end-portal.x";
    private static final String END_PORTAL_Y_PATH = "end-portal.y";
    private static final String END_PORTAL_Z_PATH = "end-portal.z";

    private final JavaPlugin plugin;
    private final File file;
    private final Set<PatchCoord> generatedPatches;
    private String storedTargetWorldUuid;
    private int storedPatchWidth;
    private int storedPatchLength;
    private boolean endPortalSpawned;
    private String endPortalWorld;
    private int endPortalX;
    private int endPortalY;
    private int endPortalZ;

    public GeneratedPatchStorage(JavaPlugin plugin, String targetWorldName) {
        this.plugin = plugin;
        this.file = new File(
                new File(plugin.getDataFolder(), "data"),
                "generated-patches-" + sanitizeFilePart(targetWorldName) + ".yml"
        );
        this.generatedPatches = new HashSet<>();
    }

    public void load() {
        generatedPatches.clear();
        storedTargetWorldUuid = null;
        storedPatchWidth = 0;
        storedPatchLength = 0;
        endPortalSpawned = false;
        endPortalWorld = null;
        endPortalX = 0;
        endPortalY = 0;
        endPortalZ = 0;

        if (!file.exists()) {
            ensureParentDirectory();
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        storedTargetWorldUuid = yaml.getString(META_TARGET_WORLD_UUID_PATH);
        storedPatchWidth = yaml.getInt(META_PATCH_WIDTH_PATH, 0);
        storedPatchLength = yaml.getInt(META_PATCH_LENGTH_PATH, 0);
        List<String> entries = yaml.getStringList(GENERATED_PATH);
        for (String key : entries) {
            try {
                generatedPatches.add(PatchCoord.fromKey(key));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid patch entry in storage: " + key);
            }
        }

        endPortalSpawned = yaml.getBoolean(END_PORTAL_SPAWNED_PATH, false);
        endPortalWorld = yaml.getString(END_PORTAL_WORLD_PATH);
        endPortalX = yaml.getInt(END_PORTAL_X_PATH, 0);
        endPortalY = yaml.getInt(END_PORTAL_Y_PATH, 0);
        endPortalZ = yaml.getInt(END_PORTAL_Z_PATH, 0);
    }

    public synchronized boolean isGenerated(PatchCoord coord) {
        return generatedPatches.contains(coord);
    }

    public synchronized void markGenerated(PatchCoord coord) {
        if (generatedPatches.add(coord)) {
            save();
        }
    }

    public synchronized Set<PatchCoord> getGeneratedPatches() {
        return Set.copyOf(generatedPatches);
    }

    public synchronized boolean hasStoredPatchSize() {
        return storedPatchWidth > 0 && storedPatchLength > 0;
    }

    public synchronized int getStoredPatchWidth() {
        return storedPatchWidth;
    }

    public synchronized int getStoredPatchLength() {
        return storedPatchLength;
    }

    public synchronized MigrationResult migratePatchGridIfNeeded(int newPatchWidth, int newPatchLength) {
        if (newPatchWidth <= 0 || newPatchLength <= 0) {
            throw new IllegalArgumentException("new patch size must be > 0");
        }

        if (!hasStoredPatchSize()) {
            storedPatchWidth = newPatchWidth;
            storedPatchLength = newPatchLength;
            save();
            return new MigrationResult(false, 0, generatedPatches.size(), newPatchWidth, newPatchLength);
        }

        if (storedPatchWidth == newPatchWidth && storedPatchLength == newPatchLength) {
            return new MigrationResult(false, generatedPatches.size(), generatedPatches.size(), newPatchWidth, newPatchLength);
        }

        int oldWidth = storedPatchWidth;
        int oldLength = storedPatchLength;
        Set<PatchCoord> migrated = new HashSet<>();

        for (PatchCoord oldPatch : generatedPatches) {
            int oldMinX = oldPatch.patchX() * oldWidth;
            int oldMinZ = oldPatch.patchZ() * oldLength;
            int oldMaxX = oldMinX + oldWidth - 1;
            int oldMaxZ = oldMinZ + oldLength - 1;

            int minNewPatchX = Math.floorDiv(oldMinX, newPatchWidth);
            int maxNewPatchX = Math.floorDiv(oldMaxX, newPatchWidth);
            int minNewPatchZ = Math.floorDiv(oldMinZ, newPatchLength);
            int maxNewPatchZ = Math.floorDiv(oldMaxZ, newPatchLength);

            for (int px = minNewPatchX; px <= maxNewPatchX; px++) {
                for (int pz = minNewPatchZ; pz <= maxNewPatchZ; pz++) {
                    migrated.add(new PatchCoord(px, pz));
                }
            }
        }

        int oldCount = generatedPatches.size();
        generatedPatches.clear();
        generatedPatches.addAll(migrated);
        storedPatchWidth = newPatchWidth;
        storedPatchLength = newPatchLength;
        save();

        return new MigrationResult(true, oldCount, generatedPatches.size(), newPatchWidth, newPatchLength);
    }

    public synchronized WorldResetResult resetIfTargetWorldChanged(UUID targetWorldUuid) {
        String newUuid = targetWorldUuid.toString();
        if (storedTargetWorldUuid == null || storedTargetWorldUuid.isBlank()) {
            storedTargetWorldUuid = newUuid;
            save();
            return new WorldResetResult(false, 0, generatedPatches.size());
        }

        if (storedTargetWorldUuid.equals(newUuid)) {
            return new WorldResetResult(false, generatedPatches.size(), generatedPatches.size());
        }

        int oldCount = generatedPatches.size();
        generatedPatches.clear();
        storedPatchWidth = 0;
        storedPatchLength = 0;
        endPortalSpawned = false;
        endPortalWorld = null;
        endPortalX = 0;
        endPortalY = 0;
        endPortalZ = 0;
        storedTargetWorldUuid = newUuid;
        save();

        return new WorldResetResult(true, oldCount, generatedPatches.size());
    }

    public synchronized boolean isEndPortalSpawned() {
        return endPortalSpawned;
    }

    public synchronized Location getEndPortalLocation() {
        if (!endPortalSpawned || endPortalWorld == null) {
            return null;
        }
        World world = Bukkit.getWorld(endPortalWorld);
        if (world == null) {
            return null;
        }
        return new Location(world, endPortalX + 0.5, endPortalY, endPortalZ + 0.5);
    }

    public synchronized void markEndPortalSpawned(Location location) {
        endPortalSpawned = true;
        endPortalWorld = location.getWorld() != null ? location.getWorld().getName() : null;
        endPortalX = location.getBlockX();
        endPortalY = location.getBlockY();
        endPortalZ = location.getBlockZ();
        save();
    }

    private void save() {
        ensureParentDirectory();

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(META_TARGET_WORLD_UUID_PATH, storedTargetWorldUuid);
        yaml.set(META_PATCH_WIDTH_PATH, storedPatchWidth);
        yaml.set(META_PATCH_LENGTH_PATH, storedPatchLength);
        yaml.set(GENERATED_PATH, serialize(generatedPatches));
        yaml.set(END_PORTAL_SPAWNED_PATH, endPortalSpawned);
        yaml.set(END_PORTAL_WORLD_PATH, endPortalWorld);
        yaml.set(END_PORTAL_X_PATH, endPortalX);
        yaml.set(END_PORTAL_Y_PATH, endPortalY);
        yaml.set(END_PORTAL_Z_PATH, endPortalZ);

        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save generated patch storage: " + ex.getMessage());
        }
    }

    private void ensureParentDirectory() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create storage directory: " + parent.getAbsolutePath());
        }
    }

    private static Collection<String> serialize(Set<PatchCoord> patches) {
        List<String> result = new ArrayList<>(patches.size());
        for (PatchCoord patch : patches) {
            result.add(patch.asKey());
        }
        return result;
    }

    private static String sanitizeFilePart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public record MigrationResult(
            boolean migrated,
            int oldGeneratedCount,
            int newGeneratedCount,
            int patchWidth,
            int patchLength
    ) {
    }

    public record WorldResetResult(
            boolean reset,
            int oldGeneratedCount,
            int newGeneratedCount
    ) {
    }
}

package dev.roguealex.chunkworldbuilder;

import dev.roguealex.chunkworldbuilder.listeners.AdvancementListener;
import dev.roguealex.chunkworldbuilder.listeners.AdminSupportHintListener;
import dev.roguealex.chunkworldbuilder.listeners.MovementSafetyListener;
import dev.roguealex.chunkworldbuilder.listeners.PlayerMoveWatcher;
import dev.roguealex.chunkworldbuilder.listeners.SpawnWorldRoutingListener;
import dev.roguealex.chunkworldbuilder.listeners.TeleportSafetyListener;
import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import dev.roguealex.chunkworldbuilder.patch.PatchStateRegistry;
import dev.roguealex.chunkworldbuilder.service.BoundaryService;
import dev.roguealex.chunkworldbuilder.service.CopyEngineMode;
import dev.roguealex.chunkworldbuilder.service.EndPortalProgressionService;
import dev.roguealex.chunkworldbuilder.service.PatchCopyService;
import dev.roguealex.chunkworldbuilder.service.WorldEditPatchCopyEngine;
import dev.roguealex.chunkworldbuilder.service.WorldExpansionService;
import dev.roguealex.chunkworldbuilder.storage.GeneratedPatchStorage;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkWorldBuilderPlugin extends JavaPlugin {

    private World targetWorld;
    private World donorWorld;
    private int patchWidth;
    private int patchLength;
    private GeneratedPatchStorage generatedPatchStorage;
    private PatchStateRegistry patchStateRegistry;
    private PatchCopyService patchCopyService;
    private WorldEditPatchCopyEngine worldEditPatchCopyEngine;
    private WorldExpansionService worldExpansionService;
    private BoundaryService boundaryService;
    private EndPortalProgressionService endPortalProgressionService;
    private TeleportSafetyListener teleportSafetyListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        targetWorld = loadWorld(
                "worlds.target",
                getConfig().getBoolean("worlds.target.use-void-generator")
        );

        donorWorld = loadWorld("worlds.donor", false);

        if (targetWorld == null || donorWorld == null) {
            getLogger().severe("Failed to initialize required worlds. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        patchWidth = getConfig().getInt("generation.patch-width");
        patchLength = getConfig().getInt("generation.patch-length");
        if (patchWidth <= 0 || patchLength <= 0) {
            getLogger().severe("Invalid patch size in config. patch-width and patch-length must be > 0.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        generatedPatchStorage = new GeneratedPatchStorage(this, targetWorld.getName());
        generatedPatchStorage.load();
        GeneratedPatchStorage.WorldResetResult worldResetResult =
                generatedPatchStorage.resetIfTargetWorldChanged(targetWorld.getUID());
        if (worldResetResult.reset()) {
            getLogger().warning("Detected target world recreation (UUID changed). Cleared generated patch storage ("
                    + worldResetResult.oldGeneratedCount() + " -> " + worldResetResult.newGeneratedCount() + ").");
        }
        GeneratedPatchStorage.MigrationResult migrationResult =
                generatedPatchStorage.migratePatchGridIfNeeded(patchWidth, patchLength);
        if (migrationResult.migrated()) {
            getLogger().warning("Patch grid size changed. Migrated generated patches to "
                    + migrationResult.patchWidth() + "x" + migrationResult.patchLength()
                    + " (" + migrationResult.oldGeneratedCount() + " -> "
                    + migrationResult.newGeneratedCount() + ").");
            getLogger().warning("Recommendation: recreate the target world after changing patch size to avoid possible layout issues.");
        }
        patchStateRegistry = new PatchStateRegistry(generatedPatchStorage);

        try {
            patchCopyService = new PatchCopyService(
                    targetWorld,
                    donorWorld,
                    patchWidth,
                    patchLength,
                    getConfig().getInt("generation.donor-range-min-x"),
                    getConfig().getInt("generation.donor-range-max-x"),
                    getConfig().getInt("generation.donor-range-min-z"),
                    getConfig().getInt("generation.donor-range-max-z"),
                    getConfig().getBoolean("generation.copy-biomes"),
                    getConfig().getBoolean("generation.copy-tile-entities")
            );
        } catch (IllegalArgumentException ex) {
            getLogger().severe("Invalid donor range settings: " + ex.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (patchCopyService.isCopyTileEntitiesEnabled()) {
            getLogger().warning("copy-tile-entities=true is accepted, but NBT copy is not implemented yet.");
        }

        worldEditPatchCopyEngine = resolveWorldEditEngine();

        worldExpansionService = new WorldExpansionService(
                this,
                patchStateRegistry,
                patchCopyService,
                worldEditPatchCopyEngine,
                getConfig().getInt("performance.max-blocks-per-tick"),
                getConfig().getInt("performance.max-patches-queued")
        );
        worldExpansionService.start();

        PatchCoord spawnPatch = toPatchCoord(
                targetWorld.getSpawnLocation().getBlockX(),
                targetWorld.getSpawnLocation().getBlockZ()
        );
        worldExpansionService.queuePatchPreferLand(spawnPatch, 96);
        int startupPregenRadius = Math.max(0, getConfig().getInt("generation.pregen-radius-patches"));
        if (startupPregenRadius > 0) {
            worldExpansionService.queueAround(spawnPatch, startupPregenRadius);
        }

        Bukkit.getPluginManager().registerEvents(
                new PlayerMoveWatcher(
                        targetWorld,
                        worldExpansionService,
                        patchWidth,
                        patchLength,
                        getConfig().getInt("generation.edge-trigger-distance-blocks"),
                        getConfig().getInt("performance.player-move-check-interval-ticks")
                ),
                this
        );

        if (getConfig().getBoolean("movement-safety.enabled")) {
            Bukkit.getPluginManager().registerEvents(
                    new MovementSafetyListener(
                            targetWorld,
                            patchStateRegistry,
                            worldExpansionService,
                            patchWidth,
                            patchLength,
                            getConfig().getInt("movement-safety.message-cooldown-seconds")
                    ),
                    this
            );
        }

        if (getConfig().getBoolean("teleport-safety.enabled")) {
            teleportSafetyListener = new TeleportSafetyListener(
                    this,
                    targetWorld,
                    patchStateRegistry,
                    worldExpansionService,
                    patchWidth,
                    patchLength,
                    getConfig().getInt("teleport-safety.pregen-radius-patches"),
                    getConfig().getInt("teleport-safety.timeout-seconds")
            );
            teleportSafetyListener.start();
            Bukkit.getPluginManager().registerEvents(teleportSafetyListener, this);
        }

        if (getConfig().getBoolean("boundary.enabled")) {
            boundaryService = new BoundaryService(
                    this,
                    targetWorld,
                    patchStateRegistry,
                    patchWidth,
                    patchLength,
                    getConfig().getInt("boundary.min-y"),
                    getConfig().getInt("boundary.max-y"),
                    getConfig().getBoolean("boundary.invisible-barrier"),
                    getConfig().getLong("boundary.update-interval-ticks")
            );
            boundaryService.start();
        }

        endPortalProgressionService = new EndPortalProgressionService(
                targetWorld,
                patchCopyService,
                patchStateRegistry,
                generatedPatchStorage,
                patchWidth,
                patchLength,
                getConfig().getInt("progression.portal-edge-offset-patches")
        );

        if (getConfig().getBoolean("progression.end-portal-on-advancement.enabled")) {
            String advancementKey = getConfig().getString(
                    "progression.end-portal-on-advancement.trigger-advancement"
            );
            Bukkit.getPluginManager().registerEvents(
                    new AdvancementListener(this, targetWorld, endPortalProgressionService, advancementKey),
                    this
            );
        }

        if (getConfig().getBoolean("spawn-routing.enabled")) {
            Bukkit.getPluginManager().registerEvents(
                    new SpawnWorldRoutingListener(
                            this,
                            targetWorld,
                            getConfig().getBoolean("spawn-routing.route-on-join"),
                            getConfig().getBoolean("spawn-routing.route-on-respawn"),
                            getConfig().getInt("spawn-routing.join-teleport-delay-ticks")
                    ),
                    this
            );
        }

        if (getConfig().isConfigurationSection("support-message")) {
            Bukkit.getPluginManager().registerEvents(
                    new AdminSupportHintListener(
                            this,
                            getConfig().getBoolean("support-message.enabled", false),
                            getConfig().getString("support-message.text"),
                            getConfig().getString("support-message.link-text"),
                            getConfig().getString("support-message.link"),
                            getConfig().getBoolean("support-message.use-minimessage"),
                            getConfig().getString("support-message.font"),
                            getConfig().getInt("support-message.delay-ticks")
                    ),
                    this
            );
        }

        getLogger().info("ChunkWorldBuilder enabled. target=" + targetWorld.getName()
                + ", donor=" + donorWorld.getName()
                + ", generatedPatches=" + patchStateRegistry.getDoneCount());
    }

    @Override
    public void onDisable() {
        if (teleportSafetyListener != null) {
            teleportSafetyListener.stop();
        }
        if (boundaryService != null) {
            boundaryService.stop();
        }
        if (worldExpansionService != null) {
            worldExpansionService.stop();
        }
        getLogger().info("ChunkWorldBuilder disabled.");
    }

    private World loadWorld(String root, boolean useVoidGenerator) {
        String name = getConfig().getString(root + ".name");
        if (name == null || name.isBlank()) {
            getLogger().severe("Missing world name in config at " + root + ".name");
            return null;
        }

        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            return existing;
        }

        boolean createIfMissing = getConfig().getBoolean(root + ".create-if-missing", false);
        if (!createIfMissing) {
            getLogger().severe("World '" + name + "' is missing and create-if-missing=false (" + root + ")");
            return null;
        }

        World.Environment environment = parseEnvironment(getConfig().getString(root + ".environment"));

        WorldCreator creator = new WorldCreator(name);
        creator.environment(environment);
        creator.type(parseWorldType(getConfig().getString(root + ".type")));

        String seedRaw = getConfig().getString(root + ".seed");
        if (seedRaw != null && !seedRaw.isBlank() && !"null".equalsIgnoreCase(seedRaw)) {
            try {
                creator.seed(Long.parseLong(seedRaw.trim()));
            } catch (NumberFormatException ex) {
                getLogger().warning("Invalid seed '" + seedRaw + "' for " + root + ".seed, ignoring.");
            }
        }

        creator.generateStructures(getConfig().getBoolean(root + ".generate-structures"));

        String generatorSettings = getConfig().getString(root + ".generator-settings");
        if (generatorSettings != null && !generatorSettings.isBlank()
                && !"null".equalsIgnoreCase(generatorSettings)) {
            creator.generatorSettings(generatorSettings);
        }

        if (useVoidGenerator) {
            creator.generator(new VoidChunkGenerator());
        }

        World created = creator.createWorld();
        if (created == null) {
            getLogger().severe("Could not create world '" + name + "'");
            return null;
        }

        return created;
    }

    private World.Environment parseEnvironment(String rawValue) {
        if (rawValue == null) {
            return World.Environment.NORMAL;
        }

        try {
            return World.Environment.valueOf(rawValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Unknown environment '" + rawValue + "', using NORMAL.");
            return World.Environment.NORMAL;
        }
    }

    private WorldType parseWorldType(String rawValue) {
        if (rawValue == null) {
            return WorldType.NORMAL;
        }

        try {
            return WorldType.valueOf(rawValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Unknown world type '" + rawValue + "', using NORMAL.");
            return WorldType.NORMAL;
        }
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

    public GeneratedPatchStorage getGeneratedPatchStorage() {
        return generatedPatchStorage;
    }

    public PatchStateRegistry getPatchStateRegistry() {
        return patchStateRegistry;
    }

    public PatchCoord toPatchCoord(int blockX, int blockZ) {
        return PatchCoord.fromBlock(blockX, blockZ, patchWidth, patchLength);
    }

    public PatchCopyService getPatchCopyService() {
        return patchCopyService;
    }

    public EndPortalProgressionService getEndPortalProgressionService() {
        return endPortalProgressionService;
    }

    public WorldExpansionService getWorldExpansionService() {
        return worldExpansionService;
    }

    private WorldEditPatchCopyEngine resolveWorldEditEngine() {
        CopyEngineMode mode = CopyEngineMode.fromConfig(getConfig().getString("generation.copy-engine"));
        Plugin worldEdit = Bukkit.getPluginManager().getPlugin("WorldEdit");
        boolean worldEditInstalled = worldEdit != null && worldEdit.isEnabled();

        if (mode == CopyEngineMode.BUKKIT) {
            logInfoAqua("Copy engine: BUKKIT (forced by config).");
            return null;
        }

        if (!worldEditInstalled) {
            if (mode == CopyEngineMode.WORLDEDIT) {
                getLogger().warning("Copy engine WORLDEDIT requested, but WorldEdit is not installed. Falling back to BUKKIT.");
            } else {
                logInfoAqua("Copy engine: BUKKIT (WorldEdit not installed).");
            }
            if (getConfig().getBoolean("performance.worldedit-recommendation.enabled")) {
                logInfoAqua("Recommendation: install WorldEdit for better performance on large patch operations.");
            }
            return null;
        }

        try {
            WorldEditPatchCopyEngine engine = new WorldEditPatchCopyEngine(patchCopyService);
            if (mode == CopyEngineMode.WORLDEDIT) {
                logInfoAqua("Copy engine: WORLDEDIT (forced by config).");
            } else {
                logInfoAqua("Copy engine: WORLDEDIT (AUTO mode, plugin detected).");
            }
            return engine;
        } catch (Throwable ex) {
            getLogger().warning("Could not initialize WorldEdit copy engine: " + ex.getMessage());
            getLogger().warning("Falling back to BUKKIT copy engine.");
            return null;
        }
    }

    private void logInfoAqua(String message) {
        Bukkit.getConsoleSender().sendMessage(
                Component.text("[ChunkWorldBuilder] " + message, NamedTextColor.AQUA)
        );
    }
}

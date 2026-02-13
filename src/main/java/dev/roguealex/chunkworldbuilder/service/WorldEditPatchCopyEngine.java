package dev.roguealex.chunkworldbuilder.service;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import dev.roguealex.chunkworldbuilder.patch.PatchCoord;

public final class WorldEditPatchCopyEngine {

    private final PatchCopyService patchCopyService;

    public WorldEditPatchCopyEngine(PatchCopyService patchCopyService) {
        this.patchCopyService = patchCopyService;
    }

    public void copyPatch(PatchCoord targetPatch, PatchCoord donorPatch) {
        org.bukkit.World donorWorld = patchCopyService.getDonorWorld();
        org.bukkit.World targetWorld = patchCopyService.getTargetWorld();

        int patchWidth = patchCopyService.getPatchWidth();
        int patchLength = patchCopyService.getPatchLength();
        int minY = Math.max(targetWorld.getMinHeight(), donorWorld.getMinHeight());
        int maxY = Math.min(targetWorld.getMaxHeight(), donorWorld.getMaxHeight()) - 1;

        int donorMinX = donorPatch.minBlockX(patchWidth);
        int donorMinZ = donorPatch.minBlockZ(patchLength);
        int donorMaxX = donorMinX + patchWidth - 1;
        int donorMaxZ = donorMinZ + patchLength - 1;

        int targetMinX = targetPatch.minBlockX(patchWidth);
        int targetMinZ = targetPatch.minBlockZ(patchLength);

        World weDonorWorld = BukkitAdapter.adapt(donorWorld);
        World weTargetWorld = BukkitAdapter.adapt(targetWorld);

        BlockVector3 regionMin = BlockVector3.at(donorMinX, minY, donorMinZ);
        BlockVector3 regionMax = BlockVector3.at(donorMaxX, maxY, donorMaxZ);
        BlockVector3 targetOrigin = BlockVector3.at(targetMinX, minY, targetMinZ);

        Region region = new CuboidRegion(weDonorWorld, regionMin, regionMax);

        try (com.sk89q.worldedit.EditSession editSession = WorldEdit.getInstance().newEditSession(weTargetWorld)) {
            ForwardExtentCopy copy = new ForwardExtentCopy(weDonorWorld, region, regionMin, editSession, targetOrigin);
            copy.setCopyingEntities(false);
            copy.setCopyingBiomes(patchCopyService.isCopyBiomesEnabled());
            Operations.complete(copy);
            editSession.flushSession();
        } catch (Exception ex) {
            throw new RuntimeException("WorldEdit patch copy failed", ex);
        }
    }
}

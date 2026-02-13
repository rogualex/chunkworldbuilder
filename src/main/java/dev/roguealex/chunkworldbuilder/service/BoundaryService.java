package dev.roguealex.chunkworldbuilder.service;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import dev.roguealex.chunkworldbuilder.patch.PatchStateRegistry;
import dev.roguealex.chunkworldbuilder.patch.PatchStatus;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

public final class BoundaryService {

    private final JavaPlugin plugin;
    private final World targetWorld;
    private final PatchStateRegistry patchStateRegistry;
    private final int patchWidth;
    private final int patchLength;
    private final int minY;
    private final int maxY;
    private final Material boundaryMaterial;
    private final long updateIntervalTicks;
    private int taskId;
    private Set<Column> lastColumns;

    public BoundaryService(
            JavaPlugin plugin,
            World targetWorld,
            PatchStateRegistry patchStateRegistry,
            int patchWidth,
            int patchLength,
            int minY,
            int maxY,
            boolean invisibleBarrier,
            long updateIntervalTicks
    ) {
        this.plugin = plugin;
        this.targetWorld = targetWorld;
        this.patchStateRegistry = patchStateRegistry;
        this.patchWidth = patchWidth;
        this.patchLength = patchLength;
        this.minY = Math.max(targetWorld.getMinHeight(), minY);
        this.maxY = Math.min(targetWorld.getMaxHeight() - 1, maxY);
        this.boundaryMaterial = invisibleBarrier ? Material.BARRIER : Material.GLASS;
        this.updateIntervalTicks = Math.max(1L, updateIntervalTicks);
        this.taskId = -1;
        this.lastColumns = Set.of();
    }

    public void start() {
        if (taskId != -1 || maxY < minY) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::refresh, 20L, updateIntervalTicks);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }

        if (!lastColumns.isEmpty()) {
            drawColumns(lastColumns, true);
            lastColumns = Set.of();
        }
    }

    private void refresh() {
        Set<Column> currentColumns = computePerimeterColumns();
        if (currentColumns.equals(lastColumns)) {
            return;
        }

        Set<Column> toClear = new HashSet<>(lastColumns);
        toClear.removeAll(currentColumns);

        Set<Column> toAdd = new HashSet<>(currentColumns);
        toAdd.removeAll(lastColumns);

        drawColumns(toClear, true);
        drawColumns(toAdd, false);
        lastColumns = currentColumns;
    }

    private Set<Column> computePerimeterColumns() {
        Set<PatchCoord> done = patchStateRegistry.getPatchesWithStatuses(PatchStatus.DONE);
        if (done.isEmpty()) {
            return Set.of();
        }

        Set<Column> columns = new HashSet<>();

        for (PatchCoord patch : done) {
            int minX = patch.patchX() * patchWidth;
            int maxX = minX + patchWidth - 1;
            int minZ = patch.patchZ() * patchLength;
            int maxZ = minZ + patchLength - 1;

            PatchCoord west = new PatchCoord(patch.patchX() - 1, patch.patchZ());
            PatchCoord east = new PatchCoord(patch.patchX() + 1, patch.patchZ());
            PatchCoord north = new PatchCoord(patch.patchX(), patch.patchZ() - 1);
            PatchCoord south = new PatchCoord(patch.patchX(), patch.patchZ() + 1);

            if (!done.contains(west)) {
                int wallX = minX - 1;
                for (int z = minZ; z <= maxZ; z++) {
                    columns.add(new Column(wallX, z));
                }
            }
            if (!done.contains(east)) {
                int wallX = maxX + 1;
                for (int z = minZ; z <= maxZ; z++) {
                    columns.add(new Column(wallX, z));
                }
            }
            if (!done.contains(north)) {
                int wallZ = minZ - 1;
                for (int x = minX; x <= maxX; x++) {
                    columns.add(new Column(x, wallZ));
                }
            }
            if (!done.contains(south)) {
                int wallZ = maxZ + 1;
                for (int x = minX; x <= maxX; x++) {
                    columns.add(new Column(x, wallZ));
                }
            }
        }

        return columns;
    }

    private void drawColumns(Set<Column> columns, boolean clear) {
        if (columns.isEmpty()) {
            return;
        }
        for (Column column : columns) {
            for (int y = minY; y <= maxY; y++) {
                setBoundaryBlock(column.x, y, column.z, clear);
            }
        }
    }

    private void setBoundaryBlock(int x, int y, int z, boolean clear) {
        Block block = targetWorld.getBlockAt(x, y, z);
        if (clear) {
            if (block.getType() == boundaryMaterial) {
                block.setType(Material.AIR, false);
            }
            return;
        }
        block.setType(boundaryMaterial, false);
    }

    private record Column(int x, int z) {
    }
}

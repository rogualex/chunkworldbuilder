package dev.roguealex.chunkworldbuilder.service;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import dev.roguealex.chunkworldbuilder.patch.PatchStateRegistry;
import java.util.ArrayDeque;
import java.util.Queue;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldExpansionService {

    private final JavaPlugin plugin;
    private final PatchStateRegistry patchStateRegistry;
    private final PatchCopyService patchCopyService;
    private final WorldEditPatchCopyEngine worldEditEngine;
    private final int maxBlocksPerTick;
    private final int maxPatchesQueued;
    private final Queue<PatchGenerationRequest> normalQueue;
    private final Queue<PatchGenerationRequest> urgentQueue;
    private int taskId;
    private PatchGenerationTask activeTask;

    public WorldExpansionService(
            JavaPlugin plugin,
            PatchStateRegistry patchStateRegistry,
            PatchCopyService patchCopyService,
            WorldEditPatchCopyEngine worldEditEngine,
            int maxBlocksPerTick,
            int maxPatchesQueued
    ) {
        this.plugin = plugin;
        this.patchStateRegistry = patchStateRegistry;
        this.patchCopyService = patchCopyService;
        this.worldEditEngine = worldEditEngine;
        this.maxBlocksPerTick = Math.max(1, maxBlocksPerTick);
        this.maxPatchesQueued = Math.max(1, maxPatchesQueued);
        this.normalQueue = new ArrayDeque<>();
        this.urgentQueue = new ArrayDeque<>();
        this.taskId = -1;
    }

    public void start() {
        if (taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, 1L);
        if (taskId == -1) {
            throw new IllegalStateException("Could not start WorldExpansionService scheduler task.");
        }
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }

        if (activeTask != null) {
            patchStateRegistry.resetToNew(activeTask.targetPatch());
            activeTask = null;
        }

        while (!urgentQueue.isEmpty()) {
            PatchGenerationRequest queued = urgentQueue.poll();
            if (queued != null) {
                patchStateRegistry.resetToNew(queued.targetPatch());
            }
        }
        while (!normalQueue.isEmpty()) {
            PatchGenerationRequest queued = normalQueue.poll();
            if (queued != null) {
                patchStateRegistry.resetToNew(queued.targetPatch());
            }
        }
    }

    public synchronized boolean queuePatch(PatchCoord targetPatch) {
        return queuePatch(targetPatch, patchCopyService.selectRandomDonorPatch(), false);
    }

    public synchronized boolean queuePatchUrgent(PatchCoord targetPatch) {
        return queuePatch(targetPatch, patchCopyService.selectRandomDonorPatch(), true);
    }

    public synchronized boolean queuePatchPreferLand(PatchCoord targetPatch, int maxAttempts) {
        return queuePatch(targetPatch, patchCopyService.selectRandomDonorPatchPreferLand(maxAttempts), false);
    }

    private boolean queuePatch(PatchCoord targetPatch, PatchCoord donorPatch, boolean urgent) {
        if ((urgentQueue.size() + normalQueue.size()) >= maxPatchesQueued) {
            return false;
        }

        if (!patchStateRegistry.tryQueue(targetPatch)) {
            return false;
        }

        if (urgent) {
            urgentQueue.offer(new PatchGenerationRequest(targetPatch, donorPatch));
        } else {
            normalQueue.offer(new PatchGenerationRequest(targetPatch, donorPatch));
        }
        return true;
    }

    public synchronized int queueAround(PatchCoord center, int radiusPatches) {
        return queueAroundInternal(center, radiusPatches, false);
    }

    public synchronized int queueAroundUrgent(PatchCoord center, int radiusPatches) {
        return queueAroundInternal(center, radiusPatches, true);
    }

    private int queueAroundInternal(PatchCoord center, int radiusPatches, boolean urgent) {
        int added = 0;
        for (int dx = -radiusPatches; dx <= radiusPatches; dx++) {
            for (int dz = -radiusPatches; dz <= radiusPatches; dz++) {
                if (queuePatch(new PatchCoord(center.patchX() + dx, center.patchZ() + dz),
                        patchCopyService.selectRandomDonorPatch(),
                        urgent)) {
                    added++;
                }
            }
        }
        return added;
    }

    public synchronized int getQueuedCount() {
        return urgentQueue.size() + normalQueue.size() + (activeTask == null ? 0 : 1);
    }

    private void tick() {
        try {
            if (activeTask == null) {
                if (!startNextTaskIfAvailable()) {
                    return;
                }
            }

            activeTask.process(maxBlocksPerTick);
            if (!activeTask.isComplete()) {
                return;
            }

            patchStateRegistry.markDone(activeTask.targetPatch());
            activeTask = null;
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Patch generation tick failed: " + ex.getMessage());
            if (activeTask != null) {
                patchStateRegistry.resetToNew(activeTask.targetPatch());
                activeTask = null;
            }
        }
    }

    private boolean startNextTaskIfAvailable() {
        PatchGenerationRequest next;
        synchronized (this) {
            next = urgentQueue.poll();
            if (next == null) {
                next = normalQueue.poll();
            }
        }

        if (next == null) {
            return false;
        }

        if (!patchStateRegistry.tryStartGenerating(next.targetPatch())) {
            patchStateRegistry.resetToNew(next.targetPatch());
            return false;
        }

        activeTask = new PatchGenerationTask(next.targetPatch(), next.donorPatch(), patchCopyService, worldEditEngine);
        return true;
    }

    private record PatchGenerationRequest(PatchCoord targetPatch, PatchCoord donorPatch) {
    }
}

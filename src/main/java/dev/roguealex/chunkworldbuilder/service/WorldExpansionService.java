package dev.roguealex.chunkworldbuilder.service;

import dev.roguealex.chunkworldbuilder.patch.PatchCoord;
import dev.roguealex.chunkworldbuilder.patch.PatchStateRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldExpansionService {

    private final JavaPlugin plugin;
    private final PatchStateRegistry patchStateRegistry;
    private final PatchCopyService patchCopyService;
    private final WorldEditPatchCopyEngine worldEditEngine;
    private final int maxBlocksPerTick;
    private final int maxPatchesQueued;
    private final PriorityQueue<PatchGenerationRequest> normalQueue;
    private final Queue<PatchGenerationRequest> urgentQueue;
    private final World targetWorld;
    private final int patchWidth;
    private final int patchLength;
    private int taskId;
    private long sequenceCounter;
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
        this.targetWorld = patchCopyService.getTargetWorld();
        this.patchWidth = patchCopyService.getPatchWidth();
        this.patchLength = patchCopyService.getPatchLength();
        this.normalQueue = new PriorityQueue<>(Comparator
                .comparingInt(PatchGenerationRequest::priority)
                .thenComparingLong(PatchGenerationRequest::sequence));
        this.urgentQueue = new ArrayDeque<>();
        this.taskId = -1;
        this.sequenceCounter = 0L;
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
            urgentQueue.offer(new PatchGenerationRequest(targetPatch, donorPatch, 0, nextSequence()));
        } else {
            int priority = computePlayerDistancePriority(targetPatch);
            normalQueue.offer(new PatchGenerationRequest(targetPatch, donorPatch, priority, nextSequence()));
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
        if (radiusPatches <= 0) {
            return queuePatch(center, patchCopyService.selectRandomDonorPatch(), urgent) ? 1 : 0;
        }

        List<Offset> offsets = new ArrayList<>();
        for (int dx = -radiusPatches; dx <= radiusPatches; dx++) {
            for (int dz = -radiusPatches; dz <= radiusPatches; dz++) {
                offsets.add(new Offset(dx, dz));
            }
        }
        offsets.sort(Comparator
                .comparingInt(Offset::ringDistance)
                .thenComparingInt(Offset::manhattanDistance));

        int added = 0;
        for (Offset offset : offsets) {
            if (queuePatch(
                    new PatchCoord(center.patchX() + offset.dx(), center.patchZ() + offset.dz()),
                    patchCopyService.selectRandomDonorPatch(),
                    urgent
            )) {
                added++;
            }
        }
        return added;
    }

    public synchronized int getQueuedCount() {
        return urgentQueue.size() + normalQueue.size() + (activeTask == null ? 0 : 1);
    }

    private void tick() {
        if (Bukkit.isStopping()) {
            stop();
            return;
        }

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

    private int computePlayerDistancePriority(PatchCoord targetPatch) {
        int best = Integer.MAX_VALUE;
        for (Player player : targetWorld.getPlayers()) {
            if (!player.getWorld().equals(targetWorld)) {
                continue;
            }

            PatchCoord playerPatch = PatchCoord.fromBlock(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ(),
                    patchWidth,
                    patchLength
            );

            int distance = Math.abs(targetPatch.patchX() - playerPatch.patchX())
                    + Math.abs(targetPatch.patchZ() - playerPatch.patchZ());
            if (distance < best) {
                best = distance;
            }
        }

        if (best == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE / 2;
        }
        return best;
    }

    private long nextSequence() {
        return sequenceCounter++;
    }

    private record PatchGenerationRequest(PatchCoord targetPatch, PatchCoord donorPatch, int priority, long sequence) {
    }

    private record Offset(int dx, int dz) {
        int ringDistance() {
            return Math.max(Math.abs(dx), Math.abs(dz));
        }

        int manhattanDistance() {
            return Math.abs(dx) + Math.abs(dz);
        }
    }
}

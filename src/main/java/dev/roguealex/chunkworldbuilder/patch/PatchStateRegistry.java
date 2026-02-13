package dev.roguealex.chunkworldbuilder.patch;

import dev.roguealex.chunkworldbuilder.storage.GeneratedPatchStorage;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public final class PatchStateRegistry {

    private final GeneratedPatchStorage storage;
    private final Map<PatchCoord, PatchStatus> statuses;

    public PatchStateRegistry(GeneratedPatchStorage storage) {
        this.storage = storage;
        this.statuses = new HashMap<>();

        Set<PatchCoord> donePatches = storage.getGeneratedPatches();
        for (PatchCoord coord : donePatches) {
            statuses.put(coord, PatchStatus.DONE);
        }
    }

    public synchronized PatchStatus getStatus(PatchCoord coord) {
        return statuses.getOrDefault(coord, PatchStatus.NEW);
    }

    public synchronized boolean tryQueue(PatchCoord coord) {
        PatchStatus current = getStatus(coord);
        if (current != PatchStatus.NEW) {
            return false;
        }
        statuses.put(coord, PatchStatus.QUEUED);
        return true;
    }

    public synchronized boolean tryStartGenerating(PatchCoord coord) {
        if (getStatus(coord) != PatchStatus.QUEUED) {
            return false;
        }
        statuses.put(coord, PatchStatus.GENERATING);
        return true;
    }

    public synchronized void markDone(PatchCoord coord) {
        statuses.put(coord, PatchStatus.DONE);
        storage.markGenerated(coord);
    }

    public synchronized void resetToNew(PatchCoord coord) {
        statuses.remove(coord);
    }

    public synchronized int getDoneCount() {
        int count = 0;
        for (PatchStatus status : statuses.values()) {
            if (status == PatchStatus.DONE) {
                count++;
            }
        }
        return count;
    }

    public synchronized Set<PatchCoord> getPatchesWithStatuses(PatchStatus... targetStatuses) {
        EnumSet<PatchStatus> filter = EnumSet.noneOf(PatchStatus.class);
        for (PatchStatus status : targetStatuses) {
            filter.add(status);
        }

        Set<PatchCoord> result = new HashSet<>();
        for (Map.Entry<PatchCoord, PatchStatus> entry : statuses.entrySet()) {
            if (filter.contains(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
}

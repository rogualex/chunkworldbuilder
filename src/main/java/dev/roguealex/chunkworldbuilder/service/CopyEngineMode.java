package dev.roguealex.chunkworldbuilder.service;

import java.util.Locale;

public enum CopyEngineMode {
    AUTO,
    BUKKIT,
    WORLDEDIT;

    public static CopyEngineMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }

        try {
            return CopyEngineMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return AUTO;
        }
    }
}

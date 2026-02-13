package dev.roguealex.chunkworldbuilder.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpawnWorldRoutingListener implements Listener {

    private final JavaPlugin plugin;
    private final World targetWorld;
    private final boolean routeOnJoin;
    private final boolean routeOnRespawn;
    private final int joinTeleportDelayTicks;

    public SpawnWorldRoutingListener(
            JavaPlugin plugin,
            World targetWorld,
            boolean routeOnJoin,
            boolean routeOnRespawn,
            int joinTeleportDelayTicks
    ) {
        this.plugin = plugin;
        this.targetWorld = targetWorld;
        this.routeOnJoin = routeOnJoin;
        this.routeOnRespawn = routeOnRespawn;
        this.joinTeleportDelayTicks = Math.max(0, joinTeleportDelayTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!routeOnJoin) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getWorld().equals(targetWorld)) {
            return;
        }

        Location spawn = safeSpawn(targetWorld);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!player.getWorld().equals(targetWorld)) {
                player.teleport(spawn);
            }
        }, joinTeleportDelayTicks);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!routeOnRespawn) {
            return;
        }
        if (event.getRespawnLocation().getWorld() != null
                && event.getRespawnLocation().getWorld().equals(targetWorld)) {
            return;
        }
        event.setRespawnLocation(safeSpawn(targetWorld));
    }

    private static Location safeSpawn(World world) {
        Location spawn = world.getSpawnLocation().clone();
        int x = spawn.getBlockX();
        int z = spawn.getBlockZ();

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 2;
        int startY = Math.max(minY + 1, world.getHighestBlockYAt(x, z) + 1);
        int safeY = findSafeY(world, x, z, startY, minY + 1, maxY);

        return new Location(world, x + 0.5, safeY, z + 0.5, spawn.getYaw(), spawn.getPitch());
    }

    private static int findSafeY(World world, int x, int z, int startY, int minY, int maxY) {
        int clampedStart = Math.max(minY, Math.min(startY, maxY));

        for (int y = clampedStart; y <= maxY; y++) {
            if (isSafeAt(world, x, y, z)) {
                return y;
            }
        }

        for (int y = clampedStart - 1; y >= minY; y--) {
            if (isSafeAt(world, x, y, z)) {
                return y;
            }
        }

        return clampedStart;
    }

    private static boolean isSafeAt(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block below = world.getBlockAt(x, y - 1, z);

        return feet.isPassable() && head.isPassable() && !below.isPassable();
    }
}

package net.donutsmp.spawners.storage;

import net.donutsmp.spawners.DonutSpawners;
import net.donutsmp.spawners.mob.SpawnerType;
import net.donutsmp.spawners.tasks.ProductionTask;
import net.donutsmp.spawners.tasks.HopperTransferTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.donutsmp.spawners.util.SchedulerAdapter; //
import net.donutsmp.spawners.util.BukkitSchedulerAdapter; //

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnerManager {
    private final DonutSpawners plugin;
    private final Map<Location, SpawnerData> spawners = new ConcurrentHashMap<>();
    private final File spawnersFile;
    private ProductionTask productionTask;

    private final SchedulerAdapter scheduler; //
    private Object productionHandle;  //
    private Object hopperHandle;  // 

    public SpawnerManager(DonutSpawners plugin) {
        this.plugin = plugin;
        this.spawnersFile = new File(plugin.getDataFolder(), "spawners.yml");
        this.scheduler = new BukkitSchedulerAdapter(plugin); //
        long autoSave = plugin.getConfig().getLong("settings.auto_save_interval", 6000L);
        scheduler.runRepeatingAsync(() -> saveSpawners(false), autoSave, autoSave); //
    }

    public void loadSpawners() {
        if (spawnersFile.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(spawnersFile);
            for (String key : config.getKeys(false)) {
                String[] parts = key.split(",");
                if (parts.length != 4) continue;
                if (Bukkit.getWorld(parts[0]) == null) {
                    plugin.getLogger().warning("World " + parts[0] + " not found for spawner " + key + ". Skipping.");
                    continue;
                }
                Location loc = new Location(Bukkit.getWorld(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));

                UUID owner = UUID.fromString(config.getString(key + ".owner"));
                SpawnerType type = SpawnerType.fromString(config.getString(key + ".type"));
                int stack = config.getInt(key + ".stack", 1);
                long xp = config.getLong(key + ".xp", 0);
                Map<Material, Long> drops = new HashMap<>();
                if (config.contains(key + ".drops")) {
                    for (String mat : config.getConfigurationSection(key + ".drops").getKeys(false)) {
                        drops.put(Material.valueOf(mat), config.getLong(key + ".drops." + mat));
                    }
                }
                SpawnerData data = new SpawnerData(loc, owner, type);
                data.setStackSize(stack);
                data.setAccumulatedXP(xp);
                data.setAccumulatedDrops(drops);
                spawners.put(loc, data);
            }
        }

        productionTask = new ProductionTask(this);
        long prodInterval = plugin.getConfig().getLong("settings.production_interval", 600L);
        productionHandle = scheduler.runRepeatingSync(productionTask::run, 0L, prodInterval);

        if (plugin.getConfig().getBoolean("hopper.enabled", false)) {
            long checkDelayTicks = parseDelayToTicks(plugin.getConfig().getString("hopper.check_delay", "3s"));
            int stacksPerTransfer = plugin.getConfig().getInt("hopper.stack_per_transfer", 5);
            HopperTransferTask hopperTaskRunnable = new HopperTransferTask(this, stacksPerTransfer);
            hopperHandle = scheduler.runRepeatingSync(hopperTaskRunnable::run, 0L, checkDelayTicks);
        }
    }

    public void saveSpawners() {
        saveSpawners(true);
    }

    public void saveSpawners(boolean stopTask) {
        if (stopTask) {
            if (productionHandle != null) { scheduler.cancel(productionHandle); productionHandle = null; } if (hopperHandle != null) { scheduler.cancel(hopperHandle); hopperHandle = null; } //
        }

        if (!spawnersFile.getParentFile().exists()) {
            spawnersFile.getParentFile().mkdirs();
        }

        FileConfiguration config = new YamlConfiguration();
        for (Map.Entry<Location, SpawnerData> entry : new HashMap<>(spawners).entrySet()) {
            Location loc = entry.getKey();
            SpawnerData data = entry.getValue();
            String key = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
            config.set(key + ".owner", data.getOwner().toString());
            config.set(key + ".type", data.getType().name());
            config.set(key + ".stack", data.getStackSize());
            config.set(key + ".xp", data.getAccumulatedXP());
            for (Map.Entry<Material, Long> drop : data.getAccumulatedDrops().entrySet()) {
                config.set(key + ".drops." + drop.getKey().name(), drop.getValue());
            }
        }
        try {
            config.save(spawnersFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save spawners.yml: " + e.getMessage());
        }
    }

    private long parseDelayToTicks(String raw) {
        if (raw == null || raw.isEmpty()) return 60L; raw = raw.trim().toLowerCase();
        try {
            if (raw.endsWith("s")) {
                long sec = Long.parseLong(raw.substring(0, raw.length()-1)); return Math.max(1, sec * 20L);
            } else if (raw.endsWith("m")) {
                long min = Long.parseLong(raw.substring(0, raw.length()-1)); return Math.max(1, min * 60L * 20L);
            } else if (raw.endsWith("t")) {
                long t = Long.parseLong(raw.substring(0, raw.length()-1)); return Math.max(1, t);
            } else { return Math.max(1, Long.parseLong(raw)); }
        } catch (NumberFormatException e) { return 60L; }
    }

    public SpawnerData getSpawner(Location loc) {
        return spawners.get(loc);
    }

    public void addSpawner(SpawnerData data) {
        spawners.put(data.getLocation(), data);
    }

    public void removeSpawner(Location loc) {
        spawners.remove(loc);
    }

    public Map<Location, SpawnerData> getSpawners() {
        return spawners;
    }

    public boolean isIsolated(Location loc) {
        int radius = plugin.getConfig().getInt("settings.isolation_radius", 5);
        for (Location other : spawners.keySet()) {
            if (!other.equals(loc) && other.distance(loc) <= radius) {
                return false;
            }
        }
        return true;
    }

    public void onChunkLoad(Chunk chunk) {
    }

    public void onChunkUnload(Chunk chunk) {
    }

    public DonutSpawners getPlugin() {
        return plugin;
    }

    public long getXPAmount() {
        return plugin.getConfig().getLong("xp.amount_per_cycle", 5);
    }
}

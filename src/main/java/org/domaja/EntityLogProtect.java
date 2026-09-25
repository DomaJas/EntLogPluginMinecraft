package org.domaja;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.domaja.entitylogprotect.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class EntityLogProtect extends JavaPlugin {

    private LogManager logManager;

    public final Set<UUID> inspectionPlayers = new HashSet<>();

    public boolean isInspectionEnabled(Player player) {
        return inspectionPlayers.contains(player.getUniqueId());
    }

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        saveDefaultConfig();
        reloadConfig();

        int memoryDays = getConfig().getInt("memory-days", 14);
        int pageSize = getConfig().getInt("check-page-size", 10);
        double maxRadius = getConfig().getDouble("max-near-radius", 200);
        int pruneIntervalMinutes = getConfig().getInt("prune-interval-minutes", 60);

        this.logManager = new LogManager(this);
        this.logManager.setMemoryDays(memoryDays);
        this.logManager.load();

        getServer().getPluginManager().registerEvents(new InventoryLogListener(this, logManager), this);
        getServer().getPluginManager().registerEvents(new InspectionListener(this, pageSize),  this);
        getServer().getPluginManager().registerEvents(new EntityLogListener(logManager),  this);

        ElCommand elCommand = new ElCommand(this, logManager, pageSize, maxRadius);
        getCommand("el").setExecutor(elCommand);
        getCommand("el").setTabCompleter(elCommand);

        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                elCommand.removePlayer(e.getPlayer().getName());
            }
        }, this);

        long periodTicks = pruneIntervalMinutes * 60L * 20L;
        getServer().getScheduler().runTaskTimer(this, elCommand::purgeExpiredCache, 20L * 60, 20L * 60);
        getServer().getScheduler().runTaskTimer(this, logManager::pruneOldEntries, periodTicks, periodTicks);

        getLogger().info("[Entity Log Protect] включён. Записей в памяти: " + logManager.size());
    }

    @Override
    public void onDisable() {
        if (logManager != null) {
            logManager.flush();
        }
    }

    public LogManager getLogManager() {
        return logManager;
    }
}
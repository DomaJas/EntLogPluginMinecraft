package org.domaja.entitylogprotect;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EntityLogListener implements Listener {

    private final LogManager logManager;
    private final Map<UUID, Long> recentEggUse = new ConcurrentHashMap<>();

    public EntityLogListener(LogManager logManager) {
        this.logManager = logManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player) return;

        Player killer = null;
        Entity damagerEntity = null;
        EntityDamageEvent damageEvent = entity.getLastDamageCause();
        EntityDamageEvent.DamageCause cause = damageEvent != null ? damageEvent.getCause() : null;

        if (damageEvent instanceof EntityDamageByEntityEvent byEntity) {
            damagerEntity = byEntity.getDamager();
            if (damagerEntity instanceof Player player) {
                killer = player;
            } else if (damagerEntity instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player shooterPlayer) {
                killer = shooterPlayer;
            }
        }

        logManager.logEntityDeath(entity, killer, cause, damagerEntity);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Entity vehicle = event.getVehicle();
        Entity attackerEntity = event.getAttacker();
        Player attackerPlayer = null;

        if (attackerEntity instanceof Player player) {
            attackerPlayer = player;
        } else if (attackerEntity instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooterPlayer) {
            attackerPlayer = shooterPlayer;
        }

        logManager.logEntityDeath(vehicle, attackerPlayer, null, attackerEntity);

        if (vehicle instanceof InventoryHolder holder) {
            Inventory inv = holder.getInventory();
            for (ItemStack item : inv.getContents()) {
                if (item == null || item.getType().isAir()) continue;
                logManager.logInventoryLoss(vehicle, attackerPlayer, attackerEntity,
                        item.getType().name(), item.getAmount());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityPlace(EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        Player player = event.getPlayer();
        if (player == null) return;

        logManager.logEntityPlace(entity, player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;

        ItemStack item = event.getItem();
        if (item == null || !item.getType().name().endsWith("_SPAWN_EGG")) return;

        recentEggUse.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return;

        Entity entity = event.getEntity();
        Location loc = entity.getLocation();
        long now = System.currentTimeMillis();

        Player placer = null;
        double bestDistSquared = Double.MAX_VALUE;

        for (Map.Entry<UUID, Long> e : recentEggUse.entrySet()) {
            if (now - e.getValue() > 3000) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.getWorld().equals(entity.getWorld())) continue;

            double distSquared = p.getLocation().distanceSquared(loc);
            if (distSquared <= 25.0 && distSquared < bestDistSquared) {
                bestDistSquared = distSquared;
                placer = p;
            }
        }

        if (placer != null) {
            recentEggUse.remove(placer.getUniqueId());
        }

        logManager.logEntityPlace(entity, placer);
    }
}
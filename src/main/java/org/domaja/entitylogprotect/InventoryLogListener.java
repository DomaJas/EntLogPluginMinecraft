package org.domaja.entitylogprotect;

import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Strider;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;


public class InventoryLogListener implements Listener {

    private final JavaPlugin plugin;
    private final LogManager logManager;

    private final Map<Inventory, ItemStack[]> openSnapshots = new IdentityHashMap<>();
    private final Map<Inventory, String> openedBy = new IdentityHashMap<>();
    private final Map<Inventory, Entity> openedEntity = new IdentityHashMap<>();

    public InventoryLogListener(JavaPlugin plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    private Entity resolveEntity(InventoryHolder holder) {
        if (holder instanceof Entity && !(holder instanceof Player)) {
            return (Entity) holder;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        Entity entity = resolveEntity(event.getInventory().getHolder());
        if (entity == null) return;
        if (!(event.getPlayer() instanceof Player)) return;

        Inventory inv = event.getInventory();
        openSnapshots.put(inv, cloneContents(inv));
        openedBy.put(inv, event.getPlayer().getName());
        openedEntity.put(inv, entity);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        ItemStack[] before = openSnapshots.remove(inv);
        String playerName = openedBy.remove(inv);
        Entity entity = openedEntity.remove(inv);
        if (before == null || entity == null) return;
        if (!entity.isValid() && !entity.isDead()) {

        }

        ItemStack[] after = cloneContents(inv);
        diffAndLog(playerName, entity, before, after);
    }

    private ItemStack[] cloneContents(Inventory inv) {
        ItemStack[] src = inv.getContents();
        ItemStack[] copy = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            copy[i] = src[i] == null ? null : src[i].clone();
        }
        return copy;
    }

    private void diffAndLog(String playerName, Entity entity, ItemStack[] before, ItemStack[] after) {
        Map<String, Integer> beforeCounts = countByMaterial(before);
        Map<String, Integer> afterCounts = countByMaterial(after);

        Map<String, Integer> allKeys = new HashMap<>(beforeCounts);
        allKeys.putAll(afterCounts);

        for (String material : allKeys.keySet()) {
            int b = beforeCounts.getOrDefault(material, 0);
            int a = afterCounts.getOrDefault(material, 0);
            int delta = a - b;
            if (delta == 0) continue;
            if (delta > 0) {
                logManager.log(LogEntry.create(playerName, LogEntry.Action.ADD, entity, material, delta));
            } else {
                logManager.log(LogEntry.create(playerName, LogEntry.Action.REMOVE, entity, material, -delta));
            }
        }
    }

    private Map<String, Integer> countByMaterial(ItemStack[] items) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            counts.merge(item.getType().name(), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) return;
        ItemFrame frame = (ItemFrame) event.getRightClicked();
        Player player = event.getPlayer();

        boolean hadItemBefore = frame.getItem() != null && !frame.getItem().getType().isAir();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!frame.isValid()) return;
            ItemStack now = frame.getItem();
            boolean hasItemAfter = now != null && !now.getType().isAir();

            if (!hadItemBefore && hasItemAfter) {
                logManager.log(LogEntry.create(player.getName(), LogEntry.Action.ADD, frame, now.getType().name(), 1));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSaddleInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        Player player = event.getPlayer();

        boolean hadSaddleBefore;
        if (clicked instanceof AbstractHorse) {
            hadSaddleBefore = ((AbstractHorse) clicked).getInventory().getSaddle() != null;
        } else if (clicked instanceof Pig) {
            hadSaddleBefore = ((Pig) clicked).hasSaddle();
        } else if (clicked instanceof Strider) {
            hadSaddleBefore = ((Strider) clicked).hasSaddle();
        } else {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!clicked.isValid()) return;

            boolean hasSaddleAfter;
            if (clicked instanceof AbstractHorse) {
                hasSaddleAfter = ((AbstractHorse) clicked).getInventory().getSaddle() != null;
            } else if (clicked instanceof Pig) {
                hasSaddleAfter = ((Pig) clicked).hasSaddle();
            } else {
                hasSaddleAfter = ((Strider) clicked).hasSaddle();
            }

            if (!hadSaddleBefore && hasSaddleAfter) {
                logManager.log(LogEntry.create(player.getName(), LogEntry.Action.ADD, clicked, "SADDLE", 1));
            } else if (hadSaddleBefore && !hasSaddleAfter) {
                logManager.log(LogEntry.create(player.getName(), LogEntry.Action.REMOVE, clicked, "SADDLE", 1));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameBreak(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) return;
        if (!(event.getDamager() instanceof Player)) return;
        ItemFrame frame = (ItemFrame) event.getEntity();
        Player player = (Player) event.getDamager();

        ItemStack current = frame.getItem();
        if (current == null || current.getType().isAir()) return;

        logManager.log(LogEntry.create(player.getName(), LogEntry.Action.REMOVE, frame, current.getType().name(), 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        ItemStack standItem = event.getArmorStandItem();
        ItemStack playerItem = event.getPlayerItem();

        boolean standHadItem = standItem != null && !standItem.getType().isAir();
        boolean playerHasItem = playerItem != null && !playerItem.getType().isAir();

        if (!standHadItem && playerHasItem) {
            logManager.log(LogEntry.create(player.getName(), LogEntry.Action.ADD, event.getRightClicked(),
                    playerItem.getType().name(), 1));
        } else if (standHadItem && !playerHasItem) {
            logManager.log(LogEntry.create(player.getName(), LogEntry.Action.REMOVE, event.getRightClicked(),
                    standItem.getType().name(), 1));
        } else if (standHadItem && playerHasItem) {
            logManager.log(LogEntry.create(player.getName(), LogEntry.Action.REMOVE, event.getRightClicked(),
                    standItem.getType().name(), 1));
            logManager.log(LogEntry.create(player.getName(), LogEntry.Action.ADD, event.getRightClicked(),
                    playerItem.getType().name(), 1));
        }
    }
}
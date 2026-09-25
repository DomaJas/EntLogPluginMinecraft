package org.domaja.entitylogprotect;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.domaja.EntityLogProtect;

import java.util.*;

public class InspectionListener implements Listener {

    private final EntityLogProtect plugin;
    private final int pageSize;

    private final Set<UUID> rightClickHeld = new HashSet<>();
    private final Map<UUID, Long> lastInteract = new HashMap<>();

    public InspectionListener(EntityLogProtect plugin, int pageSize) {
        this.plugin = plugin;
        this.pageSize = pageSize;
    }

    private TeleportMode getTeleportMode() {
        return TeleportMode.fromConfig(plugin.getConfig().getString("teleportMode", "alwaysTeleport"));
    }

    private boolean canInspect(Player player) {

        UUID uuid = player.getUniqueId();

        if (rightClickHeld.contains(uuid)) {
            return false;
        }

        rightClickHeld.add(uuid);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            rightClickHeld.remove(uuid);
        }, 2L);

        return true;
    }

    @EventHandler
    public void onBlockClick(PlayerInteractEvent event) {

        Player player = event.getPlayer();

        if (!plugin.isInspectionEnabled(player)) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) { return; }

        event.setCancelled(true);

        if (!canInspect(player)) {
            return;
        }

        Block block = event.getClickedBlock();

        if (block == null) {
            return;
        }

        LogManager.Page page = plugin.getLogManager().findBlock(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                null,
                null,
                null,
                null,
                1,
                pageSize
        );

        player.sendMessage("§b[Entity Log Protect] §6Блок: §f" + block.getType());
        player.sendMessage("§b[Entity Log Protect] §7Координаты: §f" +
                block.getX() + " " + block.getY() + " " + block.getZ());

        if (page.totalMatches == 0) {
            player.sendMessage("§b[Entity Log Protect] §cДействий не найдено.");
            return;
        }

        for (LogEntry entry : page.items) {
            player.sendMessage(entry.formatComponent(player, getTeleportMode()));
        }

        if (page.totalPages > 1) {
            String base = "/el block " + block.getWorld().getName() + " " + block.getX() + " " + block.getY() + " " + block.getZ();
            Component prev = page.page > 1
                    ? Component.text("«", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(base + " page:" + (page.page - 1)))
                    : Component.text("«", NamedTextColor.DARK_GRAY);
            Component next = page.page < page.totalPages
                    ? Component.text("»", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(base + " page:" + (page.page + 1)))
                    : Component.text("»", NamedTextColor.DARK_GRAY);

            player.sendMessage(Component.text("[Entity Log Protect] ", NamedTextColor.AQUA)
                    .append(prev)
                    .append(Component.text(" " + page.page + "/" + page.totalPages + " ", NamedTextColor.GRAY))
                    .append(next));
        }
    }

    @EventHandler
    public void onEntityClick(PlayerInteractEntityEvent event) {

        Player player = event.getPlayer();

        if (!plugin.isInspectionEnabled(player)) {
            return;
        }

        event.setCancelled(true);

        if (!canInspect(player)) {
            return;
        }

        Entity entity = event.getRightClicked();

        UUID entityUuid = entity.getUniqueId();

        LogManager.Page page = plugin.getLogManager().findEntityByUuid(
                entityUuid,
                1,
                pageSize
        );

        player.sendMessage(
                "§b[Entity Log Protect] §6Сущность: §f" + entity.getType()
        );

        if (page.totalMatches == 0) {
            player.sendMessage("§b[Entity Log Protect] §cДействий не найдено.");
            return;
        }

        for (LogEntry entry : page.items) {
            player.sendMessage(entry.formatComponent(player, getTeleportMode()));
        }

        if (page.totalPages > 1) {
            String base = "/el entity " + entityUuid;
            Component prev = page.page > 1
                    ? Component.text("«", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(base + " page:" + (page.page - 1)))
                    : Component.text("«", NamedTextColor.DARK_GRAY);
            Component next = page.page < page.totalPages
                    ? Component.text("»", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(base + " page:" + (page.page + 1)))
                    : Component.text("»", NamedTextColor.DARK_GRAY);

            player.sendMessage(Component.text("[Entity Log Protect] ", NamedTextColor.AQUA)
                    .append(prev)
                    .append(Component.text(" " + page.page + "/" + page.totalPages + " ", NamedTextColor.GRAY))
                    .append(next));
        }
    }
}
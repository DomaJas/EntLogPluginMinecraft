package org.domaja.entitylogprotect;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class LogEntry {

    public enum Action {
        ADD,
        REMOVE,
        DAMAGE,
        DEATH,
        PLACE //, DESTROY
    }

    private final long time;
    private final String playerName;
    private final Action action;
    private final String entityType;
    private final UUID entityUuid;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final String material;
    private final int amount;

    public LogEntry(long time, String playerName, Action action, String entityType, UUID entityUuid,
                    String world, int x, int y, int z, String material, int amount) {
        this.time = time;
        this.playerName = playerName;
        this.action = action;
        this.entityType = entityType;
        this.entityUuid = entityUuid;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.material = material;
        this.amount = amount;
    }

    public static LogEntry create(String playerName, Action action, org.bukkit.entity.Entity entity,
                                  String material, int amount) {
        Location loc = entity.getLocation();
        return new LogEntry(
                System.currentTimeMillis(),
                playerName,
                action,
                entity.getType().name(),
                entity.getUniqueId(),
                loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                material,
                amount
        );
    }

    public long getTime() { return time; }
    public String getPlayerName() { return playerName; }
    public Action getAction() { return action; }
    public String getEntityType() { return entityType; }
    public UUID getEntityUuid() { return entityUuid; }
    public String getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public String getMaterial() { return material; }
    public int getAmount() { return amount; }

    // time|player|action|entityType|entityUuid|world|x|y|z|material|amount
    public String serialize() {
        return time + "|" + playerName + "|" + action + "|" + entityType + "|" + entityUuid + "|"
                + world + "|" + x + "|" + y + "|" + z + "|" + material + "|" + amount;
    }

    public static LogEntry deserialize(String line) {
        try {
            int len = line.length();
            int[] sep = new int[10];
            int idx = 0;
            int pos = -1;
            for (int i = 0; i < 10; i++) {
                pos = line.indexOf('|', pos + 1);
                if (pos == -1) return null;
                sep[i] = pos;
            }

            String timeStr      = line.substring(0, sep[0]);
            String playerName   = line.substring(sep[0] + 1, sep[1]);
            String actionStr    = line.substring(sep[1] + 1, sep[2]);
            String entityType   = line.substring(sep[2] + 1, sep[3]);
            String entityUuidStr= line.substring(sep[3] + 1, sep[4]);
            String world        = line.substring(sep[4] + 1, sep[5]);
            String xStr         = line.substring(sep[5] + 1, sep[6]);
            String yStr         = line.substring(sep[6] + 1, sep[7]);
            String zStr         = line.substring(sep[7] + 1, sep[8]);
            String material     = line.substring(sep[8] + 1, sep[9]);
            String amountStr    = line.substring(sep[9] + 1);

            long time = Long.parseLong(timeStr);
            Action action = Action.valueOf(actionStr);
            UUID entityUuid = UUID.fromString(entityUuidStr);
            int x = Integer.parseInt(xStr);
            int y = Integer.parseInt(yStr);
            int z = Integer.parseInt(zStr);
            int amount = Integer.parseInt(amountStr);

            return new LogEntry(time, playerName, action, entityType, entityUuid, world, x, y, z, material, amount);
        } catch (Exception e) {
            return null;
        }
    }

    private String getSignPrefix() {
        switch (action) {
            case ADD:
                return "§a+";
            case REMOVE:
                return "§c-";
            default:
                return "";
        }
    }

    public String formatForChat() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd.MM HH:mm:ss");
        String actionText = action == Action.ADD ? "положил" : "забрал";
        return String.format("§7[%s] %s§f%s §7%s §e%dx %s §7%s (%s: %d %d %d)",
                sdf.format(new java.util.Date(time)), getSignPrefix(), playerName, actionText, amount, material,
                "в/из " + entityType, world, x, y, z);
    }

    private Component buildCoordsComponent(CommandSender viewer, String coords, TeleportMode teleportMode) {
        boolean isPlayer = viewer instanceof Player;
        boolean isSpectator = isPlayer && ((Player) viewer).getGameMode() == GameMode.SPECTATOR;

        boolean directTeleport;
        switch (teleportMode) {
            case ALWAYS_TELEPORT:
                directTeleport = isPlayer;
                break;
            case TELEPORT_CONFIRM:
                directTeleport = false;
                break;
            case BOTH:
            default:
                directTeleport = isSpectator;
                break;
        }

        if (directTeleport) {
            String hoverText = (teleportMode == TeleportMode.BOTH)
                    ? "Нажмите, чтобы телепортироваться сюда (только в режиме спектатора)"
                    : "Нажмите, чтобы телепортироваться сюда";
            return Component.text(world + " " + coords, NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand(
                            "/el tpcoords " + viewer.getName() + " " + world + " " + x + " " + y + " " + z))
                    .hoverEvent(HoverEvent.showText(Component.text(hoverText, NamedTextColor.GRAY)));
        }

        return Component.text(world + " " + coords, NamedTextColor.GRAY)
                .clickEvent(ClickEvent.suggestCommand("/tp @p " + coords))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Нажмите, чтобы телепортироваться сюда", NamedTextColor.GRAY)));
    }

    public Component formatComponent(CommandSender viewer, TeleportMode teleportMode) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd.MM HH:mm:ss");
        String coords = x + " " + y + " " + z;

        Component signComponent;
        switch (action) {
            case ADD:
                signComponent = Component.text("+ ", NamedTextColor.GREEN);
                break;
            case REMOVE:
                signComponent = Component.text("- ", NamedTextColor.RED);
                break;
            default:
                signComponent = Component.empty();
        }

        Component nameComponent = Component.text(playerName, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand("/el lookup user:" + playerName))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Нажмите, чтобы посмотреть действия игрока " + playerName, NamedTextColor.GRAY)));

        Component coordsComponent = buildCoordsComponent(viewer, coords, teleportMode);

        Component actionComponent;
        switch (action) {
            case ADD:
                actionComponent = Component.text(" положил ", NamedTextColor.GRAY)
                        .append(Component.text(amount + "x " + material, NamedTextColor.YELLOW))
                        .append(Component.text(" в " + entityType + " (", NamedTextColor.GRAY))
                        .append(coordsComponent)
                        .append(Component.text(")", NamedTextColor.GRAY));
                break;
            case REMOVE:
                actionComponent = Component.text(" забрал ", NamedTextColor.GRAY)
                        .append(Component.text(amount + "x " + material, NamedTextColor.YELLOW))
                        .append(Component.text(" из " + entityType + " (", NamedTextColor.GRAY))
                        .append(coordsComponent)
                        .append(Component.text(")", NamedTextColor.GRAY));
                break;
            case DAMAGE:
                actionComponent = Component.text(" нанёс урон ", NamedTextColor.GRAY)
                        .append(Component.text(entityType, NamedTextColor.YELLOW))
                        .append(Component.text(" (" + amount + " HP, " + material + ") (", NamedTextColor.GRAY))
                        .append(coordsComponent)
                        .append(Component.text(")", NamedTextColor.GRAY));
                break;
            case DEATH:
                actionComponent = Component.text(" убил ", NamedTextColor.GRAY)
                        .append(Component.text(entityType, NamedTextColor.YELLOW))
                        .append(Component.text(" (", NamedTextColor.GRAY))
                        .append(coordsComponent)
                        .append(Component.text(")", NamedTextColor.GRAY));
                break;
            case PLACE:
                actionComponent = Component.text(" разместил ", NamedTextColor.GRAY)
                        .append(Component.text(entityType, NamedTextColor.YELLOW))
                        .append(Component.text(" (", NamedTextColor.GRAY))
                        .append(coordsComponent)
                        .append(Component.text(")", NamedTextColor.GRAY));
                break;

            default:
                actionComponent = Component.text(" ?", NamedTextColor.GRAY);
        }

        return Component.text("[" + sdf.format(new java.util.Date(time)) + "] ", NamedTextColor.GRAY)
                .append(signComponent)
                .append(nameComponent)
                .append(actionComponent);
    }
}
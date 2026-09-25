package org.domaja.entitylogprotect;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.domaja.EntityLogProtect;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ElCommand implements CommandExecutor, TabCompleter {

    private final LogManager logManager;
    private final EntityLogProtect plugin;
    private final int pageSize;
    private final double maxRadius;

    private static final long CACHE_TTL_MILLIS = 60_000L;
    private final Map<String, CachedQuery> nearCache = new ConcurrentHashMap<>();
    private final Map<String, CachedQuery> lookupCache = new ConcurrentHashMap<>();
    private final Map<String, CachedQuery> blockCache = new ConcurrentHashMap<>();
    private final Map<String, CachedQuery> entityCache = new ConcurrentHashMap<>();

    private static final List<String> ATTRIBUTE_KEYS = Arrays.asList("time", "include", "exclude", "user", "target", "radius", "page", "action");
    private static final List<String> ACTIONS = Arrays.asList("DAMAGE", "DEATH", "ADD", "REMOVE", "PLACE");

    private static final List<String> ENTITY_TYPES_BASE = Arrays.asList(
            "HORSE", "DONKEY", "MULE", "LLAMA", "TRADER_LLAMA",
            "MINECART_CHEST", "ITEM_FRAME", "GLOW_ITEM_FRAME", "ARMOR_STAND");

    private static final List<String> ENTITY_TYPES = Stream.concat(
                    ENTITY_TYPES_BASE.stream(),
                    LogManager.ENTITY_CATEGORIES.keySet().stream())
            .distinct()
            .collect(Collectors.toList());

    private static final List<String> ITEM_MATERIALS = Arrays.stream(org.bukkit.Material.values())
            .filter(org.bukkit.Material::isItem)
            .map(Enum::name)
            .collect(Collectors.toList());

    private static final List<String> TIME_SUGGESTIONS = Arrays.asList("10s","30s", "5m","10m", "30m", "1h", "6h", "1d", "7d");
    private static final List<String> CACHE_TYPES = Arrays.asList("near", "lookup", "block", "entity", "all");

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([smhd])");

    private static class ParsedAttributes {
        String time;
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
        List<String> action = new ArrayList<>();
        String user;
        String radius;
        String target;
        int page = 1;
    }

    private static class CachedQuery {
        final String signature;
        final List<LogEntry> matches;
        final long createdAt;

        CachedQuery(String signature, List<LogEntry> matches) {
            this.signature = signature;
            this.matches = matches;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isValidFor(String signature) {
            return this.signature.equals(signature)
                    && (System.currentTimeMillis() - createdAt) < CACHE_TTL_MILLIS;
        }
    }

    private String buildSignature(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(p == null ? "null" : p.toString()).append('|');
        }
        return sb.toString();
    }

    private TeleportMode getTeleportMode() {
        return TeleportMode.fromConfig(plugin.getConfig().getString("teleportMode", "alwaysTeleport"));
    }

    public void purgeExpiredCache() {
        long now = System.currentTimeMillis();
        purgeMap(nearCache, now);
        purgeMap(lookupCache, now);
        purgeMap(blockCache, now);
        purgeMap(entityCache, now);
    }

    private void purgeMap(Map<String, CachedQuery> cache, long now) {
        cache.entrySet().removeIf(e -> now - e.getValue().createdAt > CACHE_TTL_MILLIS);
    }

    public void removePlayer(String name) {
        nearCache.remove(name);
        lookupCache.remove(name);
        blockCache.remove(name);
        entityCache.remove(name);
    }

    private ParsedAttributes parseAttributes(String[] args, int startIndex, CommandSender sender) {
        ParsedAttributes result = new ParsedAttributes();
        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            int colon = arg.indexOf(':');
            if (colon > 0) {
                String key = arg.substring(0, colon).toLowerCase();
                String value = arg.substring(colon + 1);
                switch (key) {
                    case "time":
                        result.time = value;
                        break;
                    case "include":
                        result.include.add(value.toUpperCase());
                        break;
                    case "exclude":
                        result.exclude.add(value.toUpperCase());
                        break;
                    case "user":
                        result.user = value;
                        break;
                    case "radius":
                        result.radius = value;
                        break;
                    case "page":
                        try {
                            result.page = Integer.parseInt(value);

                            if (result.page < 1) {
                                sender.sendMessage("§b[Entity Log Protect] §cСтраница должна быть больше 0.");
                                result.page = 1;
                            }
                        } catch (NumberFormatException e) {
                            sender.sendMessage(
                                    "§b[Entity Log Protect] §cНекорректный page: §f" + value
                            );
                        }
                        break;
                    case "action":
                        String actionValue = value.toUpperCase();
                        if (!ACTIONS.contains(actionValue)) {
                            sender.sendMessage("§b[Entity Log Protect] §cНеизвестное действие: §f" + value
                                    + " §7(Доступно: " + String.join(", ", ACTIONS) + ")");
                        } else {
                            result.action.add(actionValue);
                        }
                        break;
                    case "target":
                        result.target = value;
                        break;

                    default:
                        sender.sendMessage("§b[Entity Log Protect] §cНеизвестный атрибут: §f" + key);
                }
            } else {
                sender.sendMessage("§b[Entity Log Protect] §cНеизвестный аргумент: §f" + arg
                        + " §7(используйте time:, include:, exclude:, user:, page:)");
            }
        }

        return result;
    }

    private Long resolveSince(String time, CommandSender sender) {
        if (time == null) return null;
        Matcher m = DURATION_PATTERN.matcher(time.toLowerCase());
        if (!m.matches()) {
            sender.sendMessage("§b[Entity Log Protect] §cНеверный формат времени: §f" + time + " §7(пример: 1s, 10m, 1h, 7d)");
            return null;
        }
        long amount = Long.parseLong(m.group(1));
        long unitMillis;
        switch (m.group(2)) {
            case "s": unitMillis = 1000L; break;
            case "m": unitMillis = 60_000L; break;
            case "h": unitMillis = 3_600_000L; break;
            case "d": unitMillis = 86_400_000L; break;
            default: return null;
        }
        return System.currentTimeMillis() - amount * unitMillis;
    }

    public ElCommand(EntityLogProtect plugin, LogManager logManager, int pageSize, double maxRadius) {
        this.logManager = logManager;
        this.pageSize = pageSize;
        this.maxRadius = maxRadius;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("entitylogprotect.use")) {
            sender.sendMessage("§b[Entity Log Protect] §cУ вас нет прав на использование этой команды.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "near":
                if (!sender.hasPermission("entitylogprotect.near")) {
                    sender.sendMessage("§b[Entity Log Protect] §cУ вас нет прав на использование этой команды.");
                    return true;
                }

                return handleNear(sender, args);
            case "lookup":
                if (!sender.hasPermission("entitylogprotect.lookup")) {
                    sender.sendMessage("§b[Entity Log Protect] §cУ вас нет прав на использование этой команды.");
                    return true;
                }

                return handleLookup(sender, args);
            case "i":
                if (!sender.hasPermission("entitylogprotect.i")) {
                    sender.sendMessage("§b[Entity Log Protect] §cУ вас нет прав на использование этой команды.");
                    return true;
                }

                return handleInspection(sender, args);

            case "tpcoords":
                if (!sender.hasPermission("entitylogprotect.tpcoords")) {
                    sender.sendMessage("§b[Entity BE#tpcoords] §cУ вас нет прав на использование этой команды.");
                    return true;
                }
                return handleTpCoords(sender, args);
            case "entity":
                if (!sender.hasPermission("entitylogprotect.entity")) {
                    sender.sendMessage("§b[Entity BE#entity] §cУ вас нет прав на использование этой команды.");
                    return true;
                }
                return handleEntity(sender, args);
            case "block":
                if (!sender.hasPermission("entitylogprotect.block")) {
                    sender.sendMessage("§b[Entity Log Protect] §cУ вас нет прав на использование этой команды.");
                    return true;
                }
                return handleBlock(sender, args);
            case "cache":
                if (!sender.hasPermission("entitylogprotect.cache")) {
                    sender.sendMessage("§b[Entity Log Protect] §cУ вас нет прав на использование этой команды.");
                    return true;
                }
                return handleCache(sender, args);
            case "info":
                if (!sender.hasPermission("entitylogprotect.info")) {
                    sender.sendMessage("§b[Entity Log Protect] §cУ вас нет прав на использование этой команды.");
                    return true;
                }
                return handleInfo(sender, args);

            default:
                sendUsage(sender);
                return true;
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§b[Entity Log Protect] §6Entity Log Protect §7- использование:");
        sender.sendMessage("§b[Entity Log Protect] §e/el near [radius:] [time:] [include:] [exclude:] [user:] [target:] [page:]");
        sender.sendMessage("§b[Entity Log Protect] §e/el lookup [user:] [target:] [time:] [include:] [exclude:] [page:]");
        sender.sendMessage("§b[Entity Log Protect] §e/el i");
        sender.sendMessage("§b[Entity Log Protect] §e/el cache clear <near|lookup|block|entity|all> [player|*]");
        sender.sendMessage("§b[Entity Log Protect] §e/el info");
    }

    private String buildNextPageBase(String prefix, ParsedAttributes attrs) {
        StringBuilder sb = new StringBuilder(prefix);
        if (attrs.time != null) sb.append(" time:").append(attrs.time);
        for (String inc : attrs.include) sb.append(" include:").append(inc);
        for (String exc : attrs.exclude) sb.append(" exclude:").append(exc);
        for (String act : attrs.action) sb.append(" action:").append(act);
        if (attrs.user != null) sb.append(" user:").append(attrs.user);
        if (attrs.target != null) sb.append(" target:").append(attrs.target);
        if (attrs.radius != null) sb.append(" radius:").append(attrs.radius);
        return sb.toString();
    }

    private Component buildPaginationComponent(String baseCommand, ParsedAttributes attrs, int currentPage, int totalPages) {
        String base = buildNextPageBase(baseCommand, attrs);

        Component prevArrow;
        if (currentPage > 1) {
            String prevCommand = base + " page:" + (currentPage - 1);
            prevArrow = Component.text("«", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(prevCommand))
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Страница " + (currentPage - 1), NamedTextColor.GRAY)));
        } else {
            prevArrow = Component.text("«", NamedTextColor.DARK_GRAY);
        }

        Component nextArrow;
        if (currentPage < totalPages) {
            String nextCommand = base + " page:" + (currentPage + 1);
            nextArrow = Component.text("»", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(nextCommand))
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Страница " + (currentPage + 1), NamedTextColor.GRAY)));
        } else {
            nextArrow = Component.text("»", NamedTextColor.DARK_GRAY);
        }

        return prevArrow
                .append(Component.text(" " + currentPage + "/" + totalPages + " ", NamedTextColor.GRAY))
                .append(nextArrow);
    }

    public void toggleInspection(Player player) {
        UUID uuid = player.getUniqueId();

        if (plugin.inspectionPlayers.remove(uuid)) {
            player.sendMessage("§b[Entity Log Protect] §cInspection mode выключен.");
        } else {
            plugin.inspectionPlayers.add(uuid);
            player.sendMessage("§b[Entity Log Protect] §aInspection mode включён.");
            player.sendMessage("§7ПКМ по блоку или сущности, чтобы посмотреть действия.");
        }
    }

    // /el cache clear <near|lookup|block|entity|all> [player|*]
    private boolean handleCache(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("clear")) {
            sender.sendMessage("§b[Entity Log Protect] §cИспользование: /el cache clear <near|lookup|block|entity|all> [player|*]");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§b[Entity Log Protect] §cУкажите тип кеша: §f" + String.join(", ", CACHE_TYPES));
            return true;
        }

        String type = args[2].toLowerCase();
        if (!CACHE_TYPES.contains(type)) {
            sender.sendMessage("§b[Entity Log Protect] §cНеизвестный тип кеша: §f" + args[2]
                    + " §7(доступно: " + String.join(", ", CACHE_TYPES) + ")");
            return true;
        }

        String target = args.length >= 4 ? args[3] : sender.getName();
        boolean allPlayers = "*".equals(target);

        int cleared = 0;
        cleared += clearCache(nearCache, type, "near", target, allPlayers);
        cleared += clearCache(lookupCache, type, "lookup", target, allPlayers);
        cleared += clearCache(blockCache, type, "block", target, allPlayers);
        cleared += clearCache(entityCache, type, "entity", target, allPlayers);

        if (allPlayers) {
            sender.sendMessage("§b[Entity Log Protect] §aКеш (" + type + ") очищен для всех игроков §7(записей удалено: " + cleared + ")");
        } else {
            sender.sendMessage("§b[Entity Log Protect] §aКеш (" + type + ") очищен для §f" + target + " §7(записей удалено: " + cleared + ")");
        }

        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        sender.sendMessage("§b[Entity Log Protect] §6Информация о плагине:");
        sender.sendMessage("§b[Entity Log Protect] §7Записей в памяти: §f" + logManager.size());
        sender.sendMessage("§b[Entity Log Protect] §7Хранение логов: §f" + logManager.getMemoryDays() + " §7дней");
        sender.sendMessage("§b[Entity Log Protect] §7Размер страницы: §f" + pageSize);
        sender.sendMessage("§b[Entity Log Protect] §7Макс. радиус /el near: §f" + (int) maxRadius);
        sender.sendMessage("§b[Entity Log Protect] §7Игроков в inspection-режиме: §f" + plugin.inspectionPlayers.size());

        sender.sendMessage("§b[Entity Log Protect] §6Кеш (записей на игрока / всего закешированных логов):");
        sendCacheLine(sender, "near", nearCache);
        sendCacheLine(sender, "lookup", lookupCache);
        sendCacheLine(sender, "block", blockCache);
        sendCacheLine(sender, "entity", entityCache);

        int totalCachedQueries = nearCache.size() + lookupCache.size() + blockCache.size() + entityCache.size();
        int totalCachedMatches = sumCachedMatches(nearCache) + sumCachedMatches(lookupCache)
                + sumCachedMatches(blockCache) + sumCachedMatches(entityCache);

        sender.sendMessage("§b[Entity Log Protect] §7Итого кеш-запросов: §f" + totalCachedQueries
                + " §7| закешированных записей: §f" + totalCachedMatches);

        return true;
    }

    private void sendCacheLine(CommandSender sender, String name, Map<String, CachedQuery> cache) {
        sender.sendMessage("§b[Entity Log Protect] §7  " + name + ": §f" + cache.size()
                + " §7игроков, §f" + sumCachedMatches(cache) + " §7записей");
    }

    private int sumCachedMatches(Map<String, CachedQuery> cache) {
        int total = 0;
        for (CachedQuery q : cache.values()) {
            total += q.matches.size();
        }
        return total;
    }

    private int clearCache(Map<String, CachedQuery> cache, String requestedType, String cacheName,
                           String target, boolean allPlayers) {
        if (!requestedType.equals("all") && !requestedType.equals(cacheName)) return 0;

        if (allPlayers) {
            int size = cache.size();
            cache.clear();
            return size;
        } else {
            return cache.remove(target) != null ? 1 : 0;
        }
    }

    private boolean handleInspection(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b[Entity Log Protect] §cЭту команду можно использовать только находясь в игре.");
            return true;
        }

        Player player = (Player) sender;

        toggleInspection(player);

        return true;
    }

    private boolean handleEntity(CommandSender sender, String[] args) {

        if (!(sender instanceof Player)) {
            sender.sendMessage("§b[Entity Logger Entity] §cЭту команду можно использовать только находясь в игре.");
            return true;
        }

        if (args.length < 2) return false;

        UUID uuid;
        try {
            uuid = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§b[Entity Log Protect] §cНекорректный UUID.");
            return true;
        }

        int page = 1;
        for (int i = 2; i < args.length; i++) {
            if (args[i].toLowerCase().startsWith("page:")) {
                try {
                    page = Integer.parseInt(args[i].substring(5));
                } catch (NumberFormatException ignored) {}
            }
        }
        final int finalPage = page;

        String signature = buildSignature(uuid);
        String cacheKey = sender.getName();

        CachedQuery cached = entityCache.get(cacheKey);
        if (cached != null && cached.isValidFor(signature)) {
            LogManager.Page pageResult = logManager.paginate(cached.matches, finalPage, pageSize);
            sendEntityResult(sender, uuid, pageResult);
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<LogEntry> matches = logManager.matchesEntityByUuid(uuid);

            entityCache.put(cacheKey, new CachedQuery(signature, matches));

            Bukkit.getScheduler().runTask(plugin, () -> {
                LogManager.Page pageResult = logManager.paginate(matches, finalPage, pageSize);
                sendEntityResult(sender, uuid, pageResult);
            });
        });

        return true;
    }

    private void sendEntityResult(CommandSender sender, UUID uuid, LogManager.Page result) {
        if (result.totalMatches == 0) {
            sender.sendMessage("§b[Entity Log Protect] §cДействий не найдено.");
            return;
        }

        for (LogEntry entry : result.items) {
            sender.sendMessage(entry.formatComponent(sender, getTeleportMode()));
        }

        if (result.totalPages > 1) {
            String base = "/el entity " + uuid;
            Component prev = result.page > 1
                    ? Component.text("«", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(base + " page:" + (result.page - 1)))
                    : Component.text("«", NamedTextColor.DARK_GRAY);
            Component next = result.page < result.totalPages
                    ? Component.text("»", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(base + " page:" + (result.page + 1)))
                    : Component.text("»", NamedTextColor.DARK_GRAY);

            sender.sendMessage(Component.text("[Entity Log Protect] ", NamedTextColor.AQUA)
                    .append(prev)
                    .append(Component.text(" " + result.page + "/" + result.totalPages + " ", NamedTextColor.GRAY))
                    .append(next));
        }
    }

    private boolean handleBlock(CommandSender sender, String[] args) {
        if (args.length < 5) return false;

        String worldName = args[1];
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§b[Entity Log Protect] §cМир не найден: " + worldName);
            return true;
        }

        int x, y, z;
        try {
            x = Integer.parseInt(args[2]);
            y = Integer.parseInt(args[3]);
            z = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§b[Entity Log Protect] §cНекорректные координаты.");
            return true;
        }

        int page = 1;
        for (int i = 5; i < args.length; i++) {
            if (args[i].toLowerCase().startsWith("page:")) {
                try {
                    page = Integer.parseInt(args[i].substring(5));
                } catch (NumberFormatException ignored) {}
            }
        }
        final int finalPage = page;

        String signature = buildSignature(worldName, x, y, z);
        String cacheKey = sender.getName();

        CachedQuery cached = blockCache.get(cacheKey);
        if (cached != null && cached.isValidFor(signature)) {
            LogManager.Page pageResult = logManager.paginate(cached.matches, finalPage, pageSize);
            sendBlockResult(sender, worldName, x, y, z, pageResult);
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<LogEntry> matches = logManager.matchesBlock(worldName, x, y, z, null, null, null, null);

            blockCache.put(cacheKey, new CachedQuery(signature, matches));

            Bukkit.getScheduler().runTask(plugin, () -> {
                LogManager.Page pageResult = logManager.paginate(matches, finalPage, pageSize);
                sendBlockResult(sender, worldName, x, y, z, pageResult);
            });
        });

        return true;
    }

    private void sendBlockResult(CommandSender sender, String worldName, int x, int y, int z, LogManager.Page result) {
        sender.sendMessage("§b[Entity Log Protect] §6Блок §7- координаты: §f" + x + " " + y + " " + z + " §7(" + worldName + ")");

        if (result.totalMatches == 0) {
            sender.sendMessage("§b[Entity Log Protect] §cДействий не найдено.");
            return;
        }

        for (LogEntry entry : result.items) {
            sender.sendMessage(entry.formatComponent(sender, getTeleportMode()));
        }

        if (result.totalPages > 1) {
            String base = "/el block " + worldName + " " + x + " " + y + " " + z;
            Component prev = result.page > 1
                    ? Component.text("«", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(base + " page:" + (result.page - 1)))
                    : Component.text("«", NamedTextColor.DARK_GRAY);
            Component next = result.page < result.totalPages
                    ? Component.text("»", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(base + " page:" + (result.page + 1)))
                    : Component.text("»", NamedTextColor.DARK_GRAY);

            sender.sendMessage(Component.text("[Entity Log Protect] ", NamedTextColor.AQUA)
                    .append(prev)
                    .append(Component.text(" " + result.page + "/" + result.totalPages + " ", NamedTextColor.GRAY))
                    .append(next));
        }
    }

    private boolean handleTpCoords(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§b[Entity BE#tpcoords] §cЭту команду может выполнять только игрок.");
            return true;
        }

        if (args.length < 5) {
            return false;
        }

        String expectedName = args[1];
        if (!player.getName().equals(expectedName)) {
            player.sendMessage("§b[Entity BE#tpcoords] §cЭта ссылка предназначена не для вас.");
            return true;
        }

        TeleportMode mode = getTeleportMode();
        boolean isSpectator = player.getGameMode() == GameMode.SPECTATOR;

        if (mode == TeleportMode.TELEPORT_CONFIRM) {
            player.sendMessage("§b[Entity BE#tpcoords] §cПрямая телепортация отключена текущими настройками.");
            return true;
        }
        if (mode == TeleportMode.BOTH && !isSpectator) {
            player.sendMessage("§b[Entity BE#tpcoords] §cТелепортация по клику доступна только в режиме спектатора.");
            return true;
        }

        World world = Bukkit.getWorld(args[2]);
        if (world == null) {
            player.sendMessage("§b[Entity BE#tpcoords] §cМир не найден: " + args[2]);
            return true;
        }

        try {
            int x = Integer.parseInt(args[3]);
            int y = Integer.parseInt(args[4]);
            int z = Integer.parseInt(args[5]);

            Location oldLoc = player.getLocation();
            Location newLoc = new Location(world, x + 0.5, y, z + 0.5);

            player.teleport(newLoc);

            String oldCoordsStr = String.format("%d, %d, %d",
                    oldLoc.getBlockX(), oldLoc.getBlockY(), oldLoc.getBlockZ());
            String newCoordsStr = String.format("%d, %d, %d", x, y, z);

            String backCommand = "/el tpcoords " + player.getName() + " " + oldLoc.getWorld().getName()
                    + " " + oldLoc.getBlockX() + " " + oldLoc.getBlockY() + " " + oldLoc.getBlockZ();

            Component oldCoordsComponent = Component.text(oldCoordsStr, NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(backCommand))
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Телепортироваться обратно", NamedTextColor.GRAY)));

            Component message = Component.text("[Entity BE#tpcoords] ", NamedTextColor.AQUA)
                    .append(Component.text("Вы телепортированы с ", NamedTextColor.GREEN))
                    .append(oldCoordsComponent)
                    .append(Component.text(" на ", NamedTextColor.GREEN))
                    .append(Component.text(newCoordsStr, NamedTextColor.YELLOW));

            player.sendMessage(message);
        } catch (Exception e) {
            player.sendMessage("§b[Entity BE#tpcoords] §cОшибка координат.");
        }

        return true;
    }

    // /el near [radius:] [time:] [include:] [exclude:] [user:] [target:] [page:]
    private boolean handleNear(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b[Entity Log Protect] §cЭту команду можно использовать только находясь в игре.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§b[Entity Log Protect] §cИспользование: /el near [radius:] [time:] [include:] [exclude:] [user:] [target:] [page:]"
                    + " §7(include/exclude принимают тип сущности или предмет, напр. include:diamond, exclude:horse)");
            return true;
        }

        Player player = (Player) sender;
        ParsedAttributes attrs = parseAttributes(args, 1, sender);

        if (attrs.radius == null) {
            sender.sendMessage("§b[Entity Log Protect] §cУкажите радиус: §fradius:<число> §7например: §fradius:10");
            return true;
        }

        boolean isGlobal = "#global".equalsIgnoreCase(attrs.radius);
        double radius = 0;

        if (!isGlobal) {
            try {
                radius = Double.parseDouble(attrs.radius);
            } catch (NumberFormatException e) {
                sender.sendMessage("§b[Entity Log Protect] §cНекорректный radius: §f" + attrs.radius);
                return true;
            }
            if (radius <= 0 || radius > maxRadius) {
                sender.sendMessage("§b[Entity Log Protect] §cРадиус должен быть в пределах от 0 до " + (int) maxRadius + ".");
                return true;
            }
        }

        Long since = resolveSince(attrs.time, sender);
        if (attrs.time != null && since == null) return true;

        String world = player.getWorld().getName();
        double px = player.getLocation().getX();
        double py = player.getLocation().getY();
        double pz = player.getLocation().getZ();
        double finalRadius = radius;

        String signature = buildSignature(isGlobal, world, radius, since,
                attrs.include, attrs.exclude, attrs.action, attrs.user, attrs.target);
        String cacheKey = sender.getName();

        CachedQuery cached = nearCache.get(cacheKey);
        if (cached != null && cached.isValidFor(signature)) {
            LogManager.Page pageResult = logManager.paginate(cached.matches, attrs.page, pageSize);
            sendNearResult(sender, attrs, pageResult, isGlobal);
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<LogEntry> matches = isGlobal
                    ? logManager.matchesGlobal(world, attrs.include, attrs.exclude, attrs.action, since, attrs.user, attrs.target)
                    : logManager.matchesNear(world, px, py, pz, finalRadius, attrs.include, attrs.exclude, attrs.action, since, attrs.user, attrs.target);

            nearCache.put(cacheKey, new CachedQuery(signature, matches));

            Bukkit.getScheduler().runTask(plugin, () -> {
                LogManager.Page pageResult = logManager.paginate(matches, attrs.page, pageSize);
                sendNearResult(sender, attrs, pageResult, isGlobal);
            });
        });

        return true;
    }

    private void sendNearResult(CommandSender sender, ParsedAttributes attrs, LogManager.Page logPage, boolean isGlobal) {
        if (logPage.totalMatches == 0) {
            sender.sendMessage(isGlobal
                    ? "§b[Entity Log Protect] §7Действий в логах #Global не найдено."
                    : "§b[Entity Log Protect] §7Действий в логе рядом не найдено.");
            return;
        }

        sender.sendMessage(String.format("§b[Entity Log Protect] §6Действия в логе %s §7- страница §f%d§7/§f%d §7(всего: %d)",
                isGlobal ? "#Global" : "рядом", logPage.page, logPage.totalPages, logPage.totalMatches));

        for (LogEntry entry : logPage.items) {
            sender.sendMessage(entry.formatComponent(sender, getTeleportMode()));
        }

        if (logPage.totalPages > 1) {
            Component pagination = Component.text("[Entity Log Protect] ", NamedTextColor.AQUA)
                    .append(buildPaginationComponent("/el near", attrs, logPage.page, logPage.totalPages));
            sender.sendMessage(pagination);
        }
    }

    private boolean isTrackedEntity(Entity e) {
        if (e instanceof org.bukkit.inventory.InventoryHolder) return true;
        if (e instanceof org.bukkit.entity.ItemFrame) return true;
        if (e instanceof org.bukkit.entity.ArmorStand) return true;
        return false;
    }

    // /el lookup [user:] [target:] [time:] [include:] [exclude:] [page:]
    private boolean handleLookup(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§b[Entity Log Protect] §cИспользование: /el lookup [user:] [target:] [time:] [include:] [exclude:] [page:]"
                    + " §7(include/exclude принимают тип сущности или предмет, напр. include:diamond)");
            return true;
        }

        ParsedAttributes attrs = parseAttributes(args, 1, sender);

        if (attrs.user == null && attrs.target == null) {
            sender.sendMessage("§b[Entity Log Protect] §4Укажите user: и/или target: в /el lookup!");
            return true;
        }

        Long since = resolveSince(attrs.time, sender);
        if (attrs.time != null && since == null) return true;

        String label = attrs.user != null && attrs.target != null ? attrs.user + " / " + attrs.target
                : attrs.target != null ? attrs.target : attrs.user;

        String signature = buildSignature(attrs.user, attrs.target, attrs.include, attrs.exclude, attrs.action, since);
        String cacheKey = sender.getName();

        CachedQuery cached = lookupCache.get(cacheKey);
        if (cached != null && cached.isValidFor(signature)) {
            LogManager.Page pageResult = logManager.paginate(cached.matches, attrs.page, pageSize);
            sendLookupResult(sender, attrs, pageResult, label);
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<LogEntry> matches = logManager.matchesByUserAndTarget(
                    attrs.user, attrs.target, attrs.include, attrs.exclude, attrs.action, since);

            lookupCache.put(cacheKey, new CachedQuery(signature, matches));

            Bukkit.getScheduler().runTask(plugin, () -> {
                LogManager.Page pageResult = logManager.paginate(matches, attrs.page, pageSize);
                sendLookupResult(sender, attrs, pageResult, label);
            });
        });

        return true;
    }

    private void sendLookupResult(CommandSender sender, ParsedAttributes attrs, LogManager.Page result, String label) {
        if (result.totalMatches == 0) {
            sender.sendMessage("§b[Entity Log Protect] §7Записей не найдено для §f" + label + "§7.");
            return;
        }

        for (LogEntry entry : result.items) {
            sender.sendMessage(entry.formatComponent(sender, getTeleportMode()));
        }

        sender.sendMessage(String.format("§6Записи §f%s §7- страница §f%d§7/§f%d §7(всего: %d)",
                label, result.page, result.totalPages, result.totalMatches));

        if (result.totalPages > 1) {
            Component pagination = Component.text("[Entity Log Protect] ", NamedTextColor.AQUA)
                    .append(buildPaginationComponent("/el lookup", attrs, result.page, result.totalPages));
            sender.sendMessage(pagination);
        }
    }

    private boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    private List<String> completeAttribute(String[] args) {
        String current = args[args.length - 1];
        int colon = current.indexOf(':');
        boolean isLookup = args[0].equalsIgnoreCase("lookup");

        if (colon >= 0) {
            String key = current.substring(0, colon).toLowerCase();
            String valuePrefix = current.substring(colon + 1);
            List<String> values;
            switch (key) {
                case "include":
                case "exclude":
                    values = new ArrayList<>(ENTITY_TYPES);
                    values.addAll(ITEM_MATERIALS);
                    break;
                case "user":
                    values = org.bukkit.Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName).collect(Collectors.toList());
                    break;
                case "time":
                    values = TIME_SUGGESTIONS;
                    break;
                case "page":
                    values = Arrays.asList(
                            "1", "2", "3", "4", "5",
                            "6", "7", "8", "9", "10"
                    );
                    break;
                case "radius":
                    values = Arrays.asList("#global", "10", "20", "30", "50", "100");
                    break;
                case "action":
                    values = ACTIONS;
                    break;
                case "target":
                    values = new ArrayList<>(ENTITY_TYPES);
                    break;

                default:
                    return new ArrayList<>();
            }
            return filter(values, valuePrefix).stream()
                    .map(v -> key + ":" + v)
                    .collect(Collectors.toList());
        }

        List<String> usedSingleKeys = new ArrayList<>();
        for (int i = 2; i < args.length - 1; i++) {
            int c = args[i].indexOf(':');
            if (c > 0) {
                String key = args[i].substring(0, c).toLowerCase();
                if (key.equals("time") || key.equals("user")||  key.equals("radius") || key.equals("page") || key.equals("target")) {
                    usedSingleKeys.add(key);
                }
            }
        }

        List<String> suggestions = new ArrayList<>();
        for (String key : ATTRIBUTE_KEYS) {
            if (isLookup && key.equals("radius")) continue;
            if (!usedSingleKeys.contains(key)) {
                suggestions.add(key + ":");
            }
        }
        return filter(suggestions, current);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("near", "lookup", "i", "cache", "info"), args[0]);
        }

        if (args[0].equalsIgnoreCase("cache")) {
            return completeCache(sender, args);
        }

        if (args.length >= 2 && (args[0].equalsIgnoreCase("near") || args[0].equalsIgnoreCase("lookup"))) {
            return completeAttribute(args);
        }
        return new ArrayList<>();
    }

    private List<String> completeCache(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return filter(Collections.singletonList("clear"), args[1]);
        }
        if (args.length == 3) {
            return filter(CACHE_TYPES, args[2]);
        }
        if (args.length == 4) {
            List<String> values = new ArrayList<>();
            values.add("*");
            values.add(sender.getName());
            for (Player p : Bukkit.getOnlinePlayers()) {
                values.add(p.getName());
            }
            return filter(values, args[3]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(p)).collect(Collectors.toList());
    }
}
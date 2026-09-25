package org.domaja.entitylogprotect;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.BlockProjectileSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogManager {

    private static final Pattern DAILY_FILE_PATTERN = Pattern.compile("entitylogprotect-(\\d{4}-\\d{2}-\\d{2})\\.log");
    private static final SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    private final JavaPlugin plugin;
    private final File logsDir;

    private final Deque<LogEntry> entries = new ArrayDeque<>();

    private final Map<String, Deque<LogEntry>> byPlayer = new HashMap<>();
    private final Map<UUID, Deque<LogEntry>> byEntity = new HashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private int memoryDays = 14;

    private String cachedDay;
    private BufferedWriter cachedWriter;

    public LogManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logsDir = new File(plugin.getDataFolder(), "logs");
    }

    public int getMemoryDays() {
        return memoryDays;
    }

    public void setMemoryDays(int memoryDays) {
        this.memoryDays = Math.max(1, memoryDays);
    }

    private List<LogEntry> collectFilteredDesc(Deque<LogEntry> dq, Predicate<LogEntry> filter) {
        List<LogEntry> matches = new ArrayList<>();
        if (dq == null) return matches;
        Iterator<LogEntry> it = dq.descendingIterator();
        while (it.hasNext()) {
            LogEntry e = it.next();
            if (filter.test(e)) matches.add(e);
        }
        return matches;
    }

    public void load() {
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
        migrateLegacyFileIfPresent();

        File[] files = logsDir.listFiles((dir, name) -> DAILY_FILE_PATTERN.matcher(name).matches());
        if (files == null) return;

        long cutoff = System.currentTimeMillis() - (memoryDays * 24L * 60 * 60 * 1000);

        List<File> toLoad = new ArrayList<>();
        for (File f : files) {
            Matcher m = DAILY_FILE_PATTERN.matcher(f.getName());
            if (!m.matches()) continue;
            try {
                Date day = DAY_FORMAT.parse(m.group(1));
                if (day.getTime() >= cutoff - 24L * 60 * 60 * 1000) {
                    toLoad.add(f);
                }
            } catch (Exception ignored) {
            }
        }
        toLoad.sort(Comparator.comparing(File::getName));

        List<LogEntry> loaded = new ArrayList<>();

        for (File f : toLoad) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    LogEntry entry = LogEntry.deserialize(line);
                    if (entry != null && entry.getTime() >= cutoff) {
                        loaded.add(entry);
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось прочитать " + f.getName() + ": " + e.getMessage());
            }
        }

        if (!loaded.isEmpty()) {
            lock.writeLock().lock();
            try {
                for (LogEntry e : loaded) {
                    entries.addLast(e);
                    indexAdd(e);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    private void migrateLegacyFileIfPresent() {
        File legacy = new File(plugin.getDataFolder(), "entitylogprotect.log");
        if (!legacy.exists()) return;
        File target = new File(logsDir, "entitylogprotect-" + DAY_FORMAT.format(new Date(legacy.lastModified())) + ".log");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(legacy), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target, true), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                writer.write(line);
                writer.newLine();
            }
            legacy.delete();
            plugin.getLogger().info("Старый entitylogprotect.log перенесён в " + target.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось перенести старый entitylogprotect.log: " + e.getMessage());
        }
    }

    private void indexAdd(LogEntry e) {
        byPlayer.computeIfAbsent(e.getPlayerName().toLowerCase(Locale.ROOT), k -> new ArrayDeque<>()).addLast(e);
        byEntity.computeIfAbsent(e.getEntityUuid(), k -> new ArrayDeque<>()).addLast(e);
    }

    public void log(LogEntry entry) {
        lock.writeLock().lock();
        try {
            entries.addLast(entry);
            indexAdd(entry);
        } finally {
            lock.writeLock().unlock();
        }

        String day = DAY_FORMAT.format(new Date(entry.getTime()));
        try {
            ensureWriterForDay(day);
            cachedWriter.write(entry.serialize());
            cachedWriter.newLine();
            cachedWriter.flush();
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось записать лог: " + e.getMessage());
        }
    }

    private void ensureWriterForDay(String day) throws IOException {
        if (cachedWriter != null && day.equals(cachedDay)) return;
        if (cachedWriter != null) {
            try {
                cachedWriter.close();
            } catch (IOException ignored) {
            }
        }
        File file = new File(logsDir, "entitylogprotect-" + day + ".log");
        cachedWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
        cachedDay = day;
    }

    public void flush() {
        try {
            if (cachedWriter != null) cachedWriter.flush();
        } catch (IOException ignored) {
        }
    }

    public void pruneOldEntries() {
        long cutoff = System.currentTimeMillis() - (memoryDays * 24L * 60 * 60 * 1000);
        lock.writeLock().lock();
        try {
            while (!entries.isEmpty() && entries.peekFirst().getTime() < cutoff) {
                entries.pollFirst();
            }
            pruneIndex(byPlayer, cutoff);
            pruneIndex(byEntity, cutoff);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private <K> void pruneIndex(Map<K, Deque<LogEntry>> index, long cutoff) {
        Iterator<Map.Entry<K, Deque<LogEntry>>> it = index.entrySet().iterator();
        while (it.hasNext()) {
            Deque<LogEntry> dq = it.next().getValue();
            while (!dq.isEmpty() && dq.peekFirst().getTime() < cutoff) {
                dq.pollFirst();
            }
            if (dq.isEmpty()) it.remove();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public static class Page {
        public final List<LogEntry> items;
        public final int totalMatches;
        public final int totalPages;
        public final int page;

        public Page(List<LogEntry> items, int totalMatches, int totalPages, int page) {
            this.items = items;
            this.totalMatches = totalMatches;
            this.totalPages = totalPages;
            this.page = page;
        }
    }

    private List<LogEntry> playerSnapshotDesc(String nickname) {
        lock.readLock().lock();
        try {
            Deque<LogEntry> dq = byPlayer.get(nickname.toLowerCase(Locale.ROOT));
            if (dq == null || dq.isEmpty()) return Collections.emptyList();
            List<LogEntry> out = new ArrayList<>(dq);
            Collections.reverse(out);
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<LogEntry> entitySnapshotDesc(UUID entityUuid) {
        lock.readLock().lock();
        try {
            Deque<LogEntry> dq = byEntity.get(entityUuid);
            if (dq == null || dq.isEmpty()) return Collections.emptyList();
            List<LogEntry> out = new ArrayList<>(dq);
            Collections.reverse(out);
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<LogEntry> allSnapshotDesc() {
        lock.readLock().lock();
        try {
            List<LogEntry> out = new ArrayList<>(entries);
            Collections.reverse(out);
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Page findByPlayer(String nickname, List<String> include, List<String> exclude, List<String> actions,
                             Long sinceMillis, int page, int pageSize) {
        List<LogEntry> matches;
        lock.readLock().lock();
        try {
            Deque<LogEntry> dq = byPlayer.get(nickname.toLowerCase(Locale.ROOT));
            matches = collectFilteredDesc(dq, e ->
                    (sinceMillis == null || e.getTime() >= sinceMillis)
                            && matchesTypeFilters(e, include, exclude)
                            && matchesActionFilter(e, actions));
        } finally {
            lock.readLock().unlock();
        }
        return paginate(matches, page, pageSize);
    }

    public List<LogEntry> matchesNear(String world, double x, double y, double z, double radius,
                                      List<String> include, List<String> exclude, List<String> actions,
                                      Long sinceMillis, String userFilter, String targetFilter) {
        double radiusSquared = radius * radius;
        String normTarget = targetFilter != null ? normalizeEntityType(targetFilter) : null;

        Predicate<LogEntry> filter = e -> {
            if (!e.getWorld().equalsIgnoreCase(world)) return false;
            if (sinceMillis != null && e.getTime() < sinceMillis) return false;
            if (normTarget != null && !normalizeEntityType(e.getEntityType()).equalsIgnoreCase(normTarget)) return false;
            if (!matchesTypeFilters(e, include, exclude)) return false;
            if (!matchesActionFilter(e, actions)) return false;

            double dx = e.getX() - x;
            double dy = e.getY() - y;
            double dz = e.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= radiusSquared;
        };

        lock.readLock().lock();
        try {
            Deque<LogEntry> source = userFilter != null
                    ? byPlayer.get(userFilter.toLowerCase(Locale.ROOT))
                    : entries;
            return collectFilteredDesc(source, filter);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<LogEntry> matchesGlobal(String world, List<String> include, List<String> exclude,
                                        List<String> actions, Long sinceMillis,
                                        String userFilter, String targetFilter) {
        String normTarget = targetFilter != null ? normalizeEntityType(targetFilter) : null;

        lock.readLock().lock();
        try {
            Deque<LogEntry> source = userFilter != null
                    ? byPlayer.get(userFilter.toLowerCase(Locale.ROOT))
                    : entries;

            List<LogEntry> matches = new ArrayList<>();
            if (source == null) return matches;

            Iterator<LogEntry> it = source.descendingIterator();
            while (it.hasNext()) {
                LogEntry e = it.next();
                if (!e.getWorld().equalsIgnoreCase(world)) continue;
                if (sinceMillis != null && e.getTime() < sinceMillis) continue;
                if (normTarget != null && !normalizeEntityType(e.getEntityType()).equalsIgnoreCase(normTarget)) continue;
                if (!matchesTypeFilters(e, include, exclude)) continue;
                if (!matchesActionFilter(e, actions)) continue;
                matches.add(e);
            }
            return matches;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<LogEntry> matchesByUserAndTarget(String userFilter, String targetFilter,
                                                 List<String> include, List<String> exclude,
                                                 List<String> actions, Long sinceMillis) {
        String normTarget = targetFilter != null ? normalizeEntityType(targetFilter) : null;

        lock.readLock().lock();
        try {
            Deque<LogEntry> source = userFilter != null
                    ? byPlayer.get(userFilter.toLowerCase(Locale.ROOT))
                    : entries;

            List<LogEntry> matches = new ArrayList<>();
            if (source == null) return matches;

            Iterator<LogEntry> it = source.descendingIterator();
            while (it.hasNext()) {
                LogEntry e = it.next();
                if (normTarget != null && !normalizeEntityType(e.getEntityType()).equalsIgnoreCase(normTarget)) continue;
                if (sinceMillis != null && e.getTime() < sinceMillis) continue;
                if (!matchesTypeFilters(e, include, exclude)) continue;
                if (!matchesActionFilter(e, actions)) continue;
                matches.add(e);
            }
            return matches;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Page findNear(String world, double x, double y, double z, double radius,
                         List<String> include, List<String> exclude, List<String> actions,
                         Long sinceMillis, String userFilter, String targetFilter,
                         int page, int pageSize) {
        List<LogEntry> matches = matchesNear(world, x, y, z, radius, include, exclude, actions, sinceMillis, userFilter, targetFilter);
        return paginate(matches, page, pageSize);
    }

    public boolean hasEntity(UUID entityUuid) {
        lock.readLock().lock();
        try {
            Deque<LogEntry> dq = byEntity.get(entityUuid);
            return dq != null && !dq.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    public LogEntry findEntity(UUID entityUuid) {
        lock.readLock().lock();
        try {
            Deque<LogEntry> dq = byEntity.get(entityUuid);
            return (dq == null || dq.isEmpty()) ? null : dq.peekLast();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void logEntityDamage(Entity entity, double damage, EntityDamageEvent.DamageCause cause,
                                Player player, Entity damagerEntity) {
        Location loc = entity.getLocation();
        String playerName = (player != null) ? player.getName() : sourceTag(damagerEntity, cause);

        LogEntry entry = new LogEntry(
                System.currentTimeMillis(), playerName, LogEntry.Action.DAMAGE,
                entity.getType().name(), entity.getUniqueId(),
                loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                cause.name(), (int) Math.round(damage)
        );
        log(entry);
    }

    public void logEntityDamage(Entity entity, double damage, EntityDamageEvent.DamageCause cause, Player player) {
        logEntityDamage(entity, damage, cause, player, null);
    }

    public void logEntityDeath(Entity entity, Player player, EntityDamageEvent.DamageCause cause, Entity damagerEntity) {
        Location loc = entity.getLocation();
        String playerName = (player != null) ? player.getName() : sourceTag(damagerEntity, cause);

        LogEntry entry = new LogEntry(
                System.currentTimeMillis(), playerName, LogEntry.Action.DEATH,
                entity.getType().name(), entity.getUniqueId(),
                loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                cause != null ? cause.name() : "DEATH", 0
        );
        log(entry);
    }

    public void logEntityDeath(Entity entity, Player player, EntityDamageEvent.DamageCause cause) {
        logEntityDeath(entity, player, cause, null);
    }

    public void logInventoryLoss(Entity entity, Player player, Entity damagerEntity, String material, int amount) {
        String playerName = (player != null) ? player.getName() : sourceTag(damagerEntity, null);
        LogEntry entry = LogEntry.create(playerName, LogEntry.Action.REMOVE, entity, material, amount);
        log(entry);
    }

    public void logEntityPlace(Entity entity, Player player) {
        Location loc = entity.getLocation();
        String playerName = (player != null) ? player.getName() : "#unknown";

        LogEntry entry = new LogEntry(
                System.currentTimeMillis(), playerName, LogEntry.Action.PLACE,
                entity.getType().name(), entity.getUniqueId(),
                loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                "PLACE", 0
        );
        log(entry);
    }

    private String causeTag(EntityDamageEvent.DamageCause cause) {
        if (cause == null) return "#unknown";
        return switch (cause) {
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> "#tnt";
            case PROJECTILE -> "#arrow";
            case FIRE, FIRE_TICK -> "#fire";
            case LAVA -> "#lava";
            case FALL -> "#fall";
            case DROWNING -> "#drown";
            case SUFFOCATION -> "#suffocation";
            case LIGHTNING -> "#lightning";
            case CONTACT -> "#cactus";
            case MAGIC -> "#magic";
            case POISON -> "#poison";
            case WITHER -> "#wither";
            case VOID -> "#void";
            case STARVATION -> "#starvation";
            case FREEZE -> "#freeze";
            case CRAMMING -> "#cramming";
            case FLY_INTO_WALL -> "#collision";
            default -> "#other";
        };
    }

    private String sourceTag(Entity damager, EntityDamageEvent.DamageCause cause) {
        if (damager != null) {
            switch (damager.getType()) {
                case TNT: return "#tnt";
                case TNT_MINECART: return "#tnt_minecart";
                case CREEPER: return "#creeper";
                case END_CRYSTAL: return "#end_crystal";
                case LIGHTNING_BOLT: return "#lightning";
                case ZOMBIE:
                case ZOMBIE_VILLAGER:
                case HUSK:
                case DROWNED: return "#zombie";
                case ZOMBIFIED_PIGLIN: return "#zombified_piglin";
                case SHULKER_BULLET: return "#shulker";
                case WITHER_SKULL:
                case WITHER: return "#wither";
                case TRIDENT: return "#trident";
                case FIREBALL:
                case SMALL_FIREBALL: {
                    if (damager instanceof org.bukkit.entity.Fireball fireball) {
                        org.bukkit.projectiles.ProjectileSource shooter = fireball.getShooter();
                        if (shooter instanceof org.bukkit.entity.Ghast) return "#ghast_fireball";
                    }
                    return "#fireball_dispenser";
                }
                case ARROW:
                case SPECTRAL_ARROW: {
                    if (damager instanceof org.bukkit.entity.Projectile projectile) {
                        org.bukkit.projectiles.ProjectileSource shooter = projectile.getShooter();
                        if (shooter == null || shooter instanceof BlockProjectileSource) {
                            return "#arrow_dispenser";
                        }
                    }
                    return "#arrow";
                }
                default:
                    break;
            }
        }
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return "#explosion";
        return causeTag(cause);
    }

    public List<LogEntry> matchesBlock(String world, int x, int y, int z,
                                       List<String> include, List<String> exclude,
                                       Long sinceMillis, String userFilter) {
        Predicate<LogEntry> filter = e ->
                e.getWorld().equalsIgnoreCase(world)
                        && (sinceMillis == null || e.getTime() >= sinceMillis)
                        && matchesTypeFilters(e, include, exclude)
                        && e.getX() == x && e.getY() == y && e.getZ() == z;

        lock.readLock().lock();
        try {
            Deque<LogEntry> source = userFilter != null
                    ? byPlayer.get(userFilter.toLowerCase(Locale.ROOT))
                    : entries;
            return collectFilteredDesc(source, filter);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<LogEntry> matchesEntityByUuid(UUID entityUuid) {
        lock.readLock().lock();
        try {
            Deque<LogEntry> dq = byEntity.get(entityUuid);
            return collectFilteredDesc(dq, e -> true);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Page findBlock(String world, int x, int y, int z,
                          List<String> include, List<String> exclude,
                          Long sinceMillis, String userFilter,
                          int page, int pageSize) {
        List<LogEntry> matches = matchesBlock(world, x, y, z, include, exclude, sinceMillis, userFilter);
        return paginate(matches, page, pageSize);
    }

    public Page findEntityByUuid(UUID entityUuid, int page, int pageSize) {
        List<LogEntry> matches = matchesEntityByUuid(entityUuid);
        return paginate(matches, page, pageSize);
    }

    public Page findGlobal(String world, List<String> include, List<String> exclude, List<String> actions,
                           Long sinceMillis, String userFilter, String targetFilter, int page, int pageSize) {
        List<LogEntry> matches = matchesGlobal(world, include, exclude, actions, sinceMillis, userFilter, targetFilter);
        return paginate(matches, page, pageSize);
    }

    public Page findByTarget(String targetType, List<String> include, List<String> exclude, List<String> actions,
                             Long sinceMillis, int page, int pageSize) {
        String normTarget = normalizeEntityType(targetType);
        Predicate<LogEntry> filter = e ->
                normalizeEntityType(e.getEntityType()).equalsIgnoreCase(normTarget)
                        && (sinceMillis == null || e.getTime() >= sinceMillis)
                        && matchesTypeFilters(e, include, exclude)
                        && matchesActionFilter(e, actions);

        List<LogEntry> matches;
        lock.readLock().lock();
        try {
            matches = collectFilteredDesc(entries, filter);
        } finally {
            lock.readLock().unlock();
        }
        return paginate(matches, page, pageSize);
    }

    private boolean matchesActionFilter(LogEntry e, List<String> actions) {
        if (actions == null || actions.isEmpty()) return true;
        return actions.contains(e.getAction().name());
    }

    public static final LinkedHashMap<String, List<String>> ENTITY_CATEGORIES = new LinkedHashMap<>();
    static {
        ENTITY_CATEGORIES.put("CHEST_BOAT", Collections.singletonList("_CHEST_BOAT"));

        ENTITY_CATEGORIES.put("BOAT", Collections.singletonList("_BOAT"));

        ENTITY_CATEGORIES.put("MINECART_ANY", Collections.singletonList("_MINECART"));
    }

    private String normalizeEntityType(String type) {
        if (type == null) return type;
        String upper = type.toUpperCase();

        if (ENTITY_CATEGORIES.containsKey(upper)) {
            return upper;
        }

        for (Map.Entry<String, List<String>> category : ENTITY_CATEGORIES.entrySet()) {
            for (String rule : category.getValue()) {
                boolean isSuffix = rule.startsWith("_");
                if (isSuffix ? upper.endsWith(rule) : upper.equals(rule)) {
                    return category.getKey();
                }
            }
        }
        return type;
    }

    public Page findByUserAndTarget(String userFilter, String targetFilter,
                                    List<String> include, List<String> exclude, List<String> actions,
                                    Long sinceMillis, int page, int pageSize) {
        List<LogEntry> matches = matchesByUserAndTarget(userFilter, targetFilter, include, exclude, actions, sinceMillis);
        return paginate(matches, page, pageSize);
    }

    private boolean matchesTypeFilters(LogEntry e, List<String> include, List<String> exclude) {
        String type = normalizeEntityType(e.getEntityType());
        String material = e.getMaterial();

        if (include != null && !include.isEmpty()) {
            boolean found = false;
            for (String t : include) {
                String normFilter = normalizeEntityType(t);
                if (type.equalsIgnoreCase(normFilter) || (material != null && material.equalsIgnoreCase(t))) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }

        if (exclude != null) {
            for (String t : exclude) {
                String normFilter = normalizeEntityType(t);
                if (type.equalsIgnoreCase(normFilter) || (material != null && material.equalsIgnoreCase(t))) {
                    return false;
                }
            }
        }
        return true;
    }

    public Page paginate(List<LogEntry> matches, int page, int pageSize) {
        int totalMatches = matches.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalMatches / (double) pageSize));
        int safePage = Math.min(Math.max(1, page), totalPages);

        int fromIndex = (safePage - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, totalMatches);

        List<LogEntry> pageItems = fromIndex >= totalMatches
                ? new ArrayList<>()
                : new ArrayList<>(matches.subList(fromIndex, toIndex));

        return new Page(pageItems, totalMatches, totalPages, safePage);
    }
}
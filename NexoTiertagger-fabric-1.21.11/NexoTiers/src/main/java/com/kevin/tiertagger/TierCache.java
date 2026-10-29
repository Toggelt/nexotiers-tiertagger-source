package com.kevin.tiertagger;

import com.google.gson.JsonObject;
import com.kevin.tiertagger.model.GameMode;
import com.kevin.tiertagger.model.PlayerInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.concurrent.*;

/**
 * Keeps the whole tierlist in memory (one request, refreshed periodically) and looks players up by name.
 * The tierlist database is keyed by username, so UUIDs are resolved to names through the current server's player list.
 */
public class TierCache {
    private static final long REFRESH_MINUTES = 5;

    private static volatile Map<String, PlayerInfo> players = Map.of();
    private static ScheduledExecutorService scheduler;

    public static synchronized void init() {
        refresh();

        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NexoTiertagger-refresh");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(TierCache::refresh, REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES);
        }
    }

    public static CompletableFuture<Void> refresh() {
        return NexoApi.fetchAll()
                .thenAccept(TierCache::store)
                .exceptionally(t -> {
                    TierTagger.getLogger().error("Failed to load the tierlist!", t);
                    return null;
                });
    }

    private static void store(List<JsonObject> rows) {
        List<PlayerInfo> infos = new ArrayList<>();
        for (JsonObject row : rows) {
            if (NexoApi.str(row, "player") == null) continue;
            infos.add(PlayerInfo.fromRow(row));
        }

        // overall rank = 1 + number of players with strictly more points (ties share a rank)
        List<Integer> sorted = infos.stream().map(PlayerInfo::points).sorted(Comparator.reverseOrder()).toList();
        Map<String, PlayerInfo> map = new HashMap<>();
        for (PlayerInfo info : infos) {
            int rank = info.points() > 0 ? firstIndexOf(sorted, info.points()) + 1 : 0;
            map.put(info.name().toLowerCase(Locale.ROOT), info.withOverall(rank));
        }

        players = map;
        TierTagger.getLogger().info("Loaded {} players from the tierlist", map.size());
    }

    private static int firstIndexOf(List<Integer> sortedDesc, int points) {
        for (int i = 0; i < sortedDesc.size(); i++) {
            if (sortedDesc.get(i) <= points) return i;
        }
        return sortedDesc.size();
    }

    private static int rankFor(int points) {
        if (points <= 0) return 0;
        return 1 + (int) players.values().stream().filter(p -> p.points() > points).count();
    }

    public static List<GameMode> getGamemodes() {
        return GameMode.ALL;
    }

    public static Optional<Map<String, PlayerInfo.Ranking>> getPlayerRankings(UUID uuid) {
        String name = nameOf(uuid);
        if (name == null) return Optional.empty();

        PlayerInfo info = players.get(name.toLowerCase(Locale.ROOT));
        return info == null ? Optional.empty() : Optional.of(info.rankings());
    }

    private static String nameOf(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();

        ClientPacketListener connection = mc.getConnection();
        if (connection != null) {
            net.minecraft.client.multiplayer.PlayerInfo entry = connection.getPlayerInfo(uuid);
            if (entry != null) return entry.getProfile().name();
        }

        if (mc.level != null) {
            Player player = mc.level.getPlayerByUUID(uuid);
            if (player != null) return player.getScoreboardName();
        }

        return null;
    }

    public static CompletableFuture<PlayerInfo> searchPlayer(String query) {
        PlayerInfo cached = players.get(query.toLowerCase(Locale.ROOT));
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return NexoApi.fetchByName(query).thenApply(rows -> {
            if (rows.isEmpty()) throw new CompletionException(new NoSuchElementException("Unknown player " + query));
            PlayerInfo info = PlayerInfo.fromRow(rows.getFirst());
            return info.withOverall(rankFor(info.points()));
        });
    }

    public static void clearCache() {
        players = Map.of();
        refresh();
    }

    public static GameMode findNextMode(GameMode current) {
        List<GameMode> modes = getGamemodes();
        return modes.get((modes.indexOf(current) + 1) % modes.size());
    }

    public static Optional<GameMode> findMode(String id) {
        return getGamemodes().stream().filter(m -> m.id().equalsIgnoreCase(id)).findFirst();
    }

    public static GameMode findModeOrUgly(String id) {
        return findMode(id).orElseGet(() -> new GameMode(id, id));
    }

    private TierCache() {
    }
}

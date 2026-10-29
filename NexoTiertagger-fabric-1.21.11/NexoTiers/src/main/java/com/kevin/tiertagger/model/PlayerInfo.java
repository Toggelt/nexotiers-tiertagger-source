package com.kevin.tiertagger.model;

import com.google.gson.JsonObject;
import com.kevin.tiertagger.NexoApi;
import com.kevin.tiertagger.TierCache;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A player's tierlist profile. {@code pos} is 0 for High Tier (HT) and 1 for Low Tier (LT).
 */
public record PlayerInfo(@Nullable String uuid, String name, Map<String, Ranking> rankings, int points, int overall) {
    // index = tier - 1
    private static final int[] HT_POINTS = {1000, 500, 200, 75, 25};
    private static final int[] LT_POINTS = {750, 350, 125, 50, 15};
    private static final Pattern TIER_PATTERN = Pattern.compile("^([HL])T([1-5])$", Pattern.CASE_INSENSITIVE);

    public record Ranking(int tier, int pos, @Nullable Integer peakTier, @Nullable Integer peakPos, long attained,
                          boolean retired) {

        /** Lower is better. */
        public int comparableTier() {
            return tier * 2 + pos;
        }

        /** Lower is better. */
        public int comparablePeak() {
            if (peakTier == null || peakPos == null) {
                return Integer.MAX_VALUE;
            } else {
                return peakTier * 2 + peakPos;
            }
        }

        public int points() {
            if (tier < 1 || tier > 5) return 0;
            return (pos == 0 ? HT_POINTS : LT_POINTS)[tier - 1];
        }

        public NamedRanking asNamed(GameMode mode) {
            return new NamedRanking(mode, this);
        }
    }

    public record NamedRanking(@Nullable GameMode mode, Ranking ranking) {
    }

    /** Builds a profile from a row of the {@code players} table. Rank (overall) is filled in by the cache. */
    public static PlayerInfo fromRow(JsonObject row) {
        Map<String, Ranking> rankings = new LinkedHashMap<>();
        int points = 0;

        for (GameMode mode : GameMode.ALL) {
            String col = columnFor(row, mode);
            Ranking ranking = parseRanking(NexoApi.str(row, col + "_tier"), NexoApi.str(row, col + "_peaktier"));
            if (ranking != null) {
                rankings.put(mode.id(), ranking);
                if (mode.countsForPoints()) points += ranking.points();
            }
        }

        return new PlayerInfo(null, NexoApi.str(row, "player"), rankings, points, 0);
    }

    private static String columnFor(JsonObject row, GameMode mode) {
        if (mode.id().equals("triathlon") && !row.has("triathlon_tier") && row.has("lowblockranked_tier")) {
            return "lowblockranked";
        }
        return mode.id();
    }

    @Nullable
    private static int[] parseTier(@Nullable String s) {
        if (s == null) return null;
        Matcher m = TIER_PATTERN.matcher(s.trim());
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(2)), m.group(1).equalsIgnoreCase("H") ? 0 : 1};
    }

    @Nullable
    private static Ranking parseRanking(@Nullable String current, @Nullable String peak) {
        int[] cur = parseTier(current);
        if (cur == null) return null;
        int[] pk = parseTier(peak);
        return new Ranking(cur[0], cur[1], pk == null ? null : pk[0], pk == null ? null : pk[1], 0L, false);
    }

    public PlayerInfo withOverall(int overall) {
        return new PlayerInfo(this.uuid, this.name, this.rankings, this.points, overall);
    }

    public static Optional<NamedRanking> getHighestRanking(Map<String, Ranking> rankings) {
        return rankings.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .min(Comparator.comparingInt(e -> e.getValue().comparableTier()))
                .map(e -> e.getValue().asNamed(TierCache.findModeOrUgly(e.getKey())));
    }

    /** Titles and thresholds mirror the website: base requirement x number of live gamemodes. */
    @Getter
    @AllArgsConstructor
    public enum PointInfo {
        // colors are the website's title colors; gradients run from "color" to "endColor"
        LEGEND("Legend", 750, 0xcbd4ec, 0x6468ab),
        GRANDMASTER("Grandmaster", 650, 0xE2C473, 0xE8BA3A),
        MASTER("Master", 500, 0xD5B355, 0xD5B355),
        CHAMPION("Champion", 300, 0xA744D9, 0xA744D9),
        EXPERT("Expert", 150, 0xd15dde, 0xd15dde),
        CADET("Cadet", 50, 0xe79eed, 0xe79eed),
        NOVICE("Novice", 0, 0x9a9aa5, 0x9a9aa5),
        UNRANKED("Unranked", 0, 0xFFFFFF, 0xFFFFFF);

        /** Color of the point requirements on the website ("1500+ points"). */
        public static final int REQUIREMENT_COLOR = 0x8b8b96;

        private final String title;
        private final int base;
        private final int color;
        private final int endColor;

        public int minPoints() {
            int v = this.base * GameMode.LIVE_COUNT;
            // the website drops a trailing 50 (1950 -> 1900), except for Cadet
            if (this != CADET && v % 100 == 50 && v > 50) v -= 50;
            return v;
        }

        /** The title in its website colors (left-to-right gradient for Legend and Grandmaster). */
        public MutableComponent styledTitle() {
            MutableComponent out = Component.empty();
            int n = this.title.length();
            for (int i = 0; i < n; i++) {
                float t = n <= 1 ? 0f : (float) i / (n - 1);
                int rgb = lerp(this.color, this.endColor, t);
                out.append(Component.literal(String.valueOf(this.title.charAt(i))).withStyle(st -> st.withColor(rgb)));
            }
            return out;
        }

        private static int lerp(int a, int b, float t) {
            int r = Math.round(((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t));
            int g = Math.round(((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t));
            int bl = Math.round((a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * t));
            return (r << 16) | (g << 8) | bl;
        }
    }

    public PointInfo getPointInfo() {
        if (this.points <= 0) return PointInfo.UNRANKED;

        for (PointInfo info : List.of(PointInfo.LEGEND, PointInfo.GRANDMASTER, PointInfo.MASTER,
                PointInfo.CHAMPION, PointInfo.EXPERT, PointInfo.CADET)) {
            if (this.points >= info.minPoints()) return info;
        }
        return PointInfo.NOVICE;
    }

    public List<NamedRanking> getSortedTiers() {
        List<NamedRanking> tiers = new ArrayList<>(this.rankings.entrySet().stream()
                .map(e -> e.getValue().asNamed(TierCache.findModeOrUgly(e.getKey())))
                .toList());

        tiers.sort(Comparator.comparing((NamedRanking a) -> a.ranking.retired, Boolean::compare)
                .thenComparingInt(a -> a.ranking.tier)
                .thenComparingInt(a -> a.ranking.pos));

        return tiers;
    }
}

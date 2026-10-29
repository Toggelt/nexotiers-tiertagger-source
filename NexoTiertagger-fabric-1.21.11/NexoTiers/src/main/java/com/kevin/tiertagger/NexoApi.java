package com.kevin.tiertagger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Talks to the tierlist's Supabase REST API (read-only, using the public anon key).
 * Never put a service_role key in here: mods can be decompiled.
 */
public final class NexoApi {
    private static final String URL = "https://dazigsqkyuejrwcbumtx.supabase.co";
    private static final String KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRhemlnc3FreXVlanJ3Y2J1bXR4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEzNTkyNTQsImV4cCI6MjEwNjkzNTI1NH0.fD-amuhdDveY9TV2Uwpq6dZLjt_Yd8WsORqOlz4ulLw";
    private static final int PAGE_SIZE = 1000;

    /** Column sets to try, in order. Mirrors the website's fallbacks (triathlon may live in "lowblockranked"). */
    private static final List<String> SELECTS = buildSelects();

    private static volatile String workingSelect = null;

    private static List<String> buildSelects() {
        List<String> out = new ArrayList<>();
        for (boolean sword : new boolean[]{true, false}) {
            for (boolean vanilla : new boolean[]{true, false}) {
                for (String third : new String[]{"triathlon", "lowblockranked"}) {
                    StringBuilder sb = new StringBuilder("id,player,nelismp_tier,nelismp_peaktier,")
                            .append(third).append("_tier,").append(third).append("_peaktier");
                    if (vanilla) sb.append(",vanilla_tier,vanilla_peaktier");
                    if (sword) sb.append(",sword_tier,sword_peaktier");
                    out.add(sb.toString());
                }
            }
        }
        return List.copyOf(out);
    }

    public static CompletableFuture<List<JsonObject>> fetchAll() {
        String known = workingSelect;
        if (known != null) return fetchPages(known, 0, new ArrayList<>());
        return tryCandidate(0);
    }

    private static CompletableFuture<List<JsonObject>> tryCandidate(int i) {
        if (i >= SELECTS.size()) {
            return CompletableFuture.failedFuture(new IOException("No usable column set found on the players table"));
        }
        String select = SELECTS.get(i);
        return fetchPages(select, 0, new ArrayList<>())
                .thenApply(rows -> {
                    workingSelect = select;
                    return rows;
                })
                .exceptionallyCompose(t -> tryCandidate(i + 1));
    }

    private static CompletableFuture<List<JsonObject>> fetchPages(String select, int offset, List<JsonObject> acc) {
        String path = "players?select=" + select + "&order=id.asc&limit=" + PAGE_SIZE + "&offset=" + offset;
        return get(path).thenCompose(rows -> {
            acc.addAll(rows);
            return rows.size() < PAGE_SIZE
                    ? CompletableFuture.completedFuture(acc)
                    : fetchPages(select, offset + PAGE_SIZE, acc);
        });
    }

    /** Exact (case-insensitive) name lookup, used when a player is not in the cache. */
    public static CompletableFuture<List<JsonObject>> fetchByName(String name) {
        String select = workingSelect;
        if (select == null) {
            return fetchAll().thenApply(rows -> rows.stream()
                    .filter(r -> name.equalsIgnoreCase(str(r, "player"))).toList());
        }
        // escape LIKE wildcards so "a_b" does not match "axb"
        String pattern = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String path = "players?select=" + select + "&player=ilike." + URLEncoder.encode(pattern, StandardCharsets.UTF_8) + "&limit=5";
        return get(path).thenApply(rows -> rows.stream()
                .filter(r -> name.toLowerCase(Locale.ROOT).equals(str(r, "player") == null ? null : str(r, "player").toLowerCase(Locale.ROOT)))
                .toList());
    }

    private static CompletableFuture<List<JsonObject>> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL + "/rest/v1/" + path))
                .header("apikey", KEY)
                .header("Authorization", "Bearer " + KEY)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return TierTagger.getClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    if (r.statusCode() / 100 != 2) {
                        throw new CompletionException(new IOException("HTTP " + r.statusCode() + ": " + r.body()));
                    }
                    JsonArray array = TierTagger.GSON.fromJson(r.body(), JsonArray.class);
                    List<JsonObject> out = new ArrayList<>();
                    for (JsonElement e : array) {
                        if (e.isJsonObject()) out.add(e.getAsJsonObject());
                    }
                    return out;
                });
    }

    public static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private NexoApi() {
    }
}

package com.kevin.tiertagger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.model.GameMode;
import com.kevin.tiertagger.model.PlayerInfo;
import com.mojang.brigadier.context.CommandContext;
import lombok.Getter;
import com.kevin.tiertagger.tierlist.PlayerSearchScreen;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.uku3lig.ukulib.config.ConfigManager;
import net.uku3lig.ukulib.utils.PlayerArgumentType;
import net.uku3lig.ukulib.utils.Ukutils;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class TierTagger implements ModInitializer {
    public static final String MOD_ID = "nexotiers";

    public static final Gson GSON = new GsonBuilder().create();

    @Getter
    private static final ConfigManager<TierTaggerConfig> manager = ConfigManager.createDefault(TierTaggerConfig.class, MOD_ID);
    @Getter
    private static final Logger logger = LoggerFactory.getLogger(TierTagger.class);
    @Getter
    private static final HttpClient client = HttpClient.newHttpClient();


    // the chat screen closes right after a command runs, so the profile screen is opened on the next client tick
    private static volatile Screen pendingScreen = null;

    @Override
    public void onInitialize() {
        TierCache.init();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Screen screen = pendingScreen;
            if (screen != null) {
                pendingScreen = null;
                mc.setScreen(screen);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                literal(MOD_ID)
                        .then(argument("player", PlayerArgumentType.player())
                                .executes(TierTagger::displayTierInfo))));

        Ukutils.registerKeybinding(new KeyMapping("tiertagger.keybind.gamemode", GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.register(Identifier.fromNamespaceAndPath("tiertagger", "key"))),
                mc -> {
                    GameMode next = TierCache.findNextMode(manager.getConfig().getGameMode());
                    manager.getConfig().setGameMode(next.id());

                    if (mc.player != null) {
                        Component message = Component.literal("Displayed gamemode: ").append(next.asStyled(false));
                        mc.player.displayClientMessage(message, true);
                    }
                });

    }

    public static Component appendTier(UUID uuid, Component text) {
        MutableComponent following = getPlayerTier(uuid)
                .map(entry -> {
                    Component tierText = getRankingText(entry.ranking(), false);

                    if (manager.getConfig().isShowIcons() && entry.mode() != null && entry.mode().icon().isPresent()) {
                        return Component.literal(entry.mode().icon().get().toString()).append(tierText);
                    } else {
                        return tierText.copy();
                    }
                })
                .orElse(null);

        if (following != null) {
            following.append(Component.literal(" | ").withStyle(ChatFormatting.GRAY));
            return following.append(text);
        }

        return text;
    }

    public static Optional<PlayerInfo.NamedRanking> getPlayerTier(UUID uuid) {
        GameMode mode = manager.getConfig().getGameMode();

        return TierCache.getPlayerRankings(uuid)
                .map(rankings -> {
                    PlayerInfo.Ranking ranking = rankings.get(mode.id());
                    Optional<PlayerInfo.NamedRanking> highest = PlayerInfo.getHighestRanking(rankings);
                    TierTaggerConfig.HighestMode highestMode = manager.getConfig().getHighestMode();

                    if (ranking == null) {
                        if (highestMode != TierTaggerConfig.HighestMode.NEVER && highest.isPresent()) {
                            return highest.get();
                        } else {
                            return null;
                        }
                    } else {
                        if (highestMode == TierTaggerConfig.HighestMode.ALWAYS && highest.isPresent()) {
                            return highest.get();
                        } else {
                            return ranking.asNamed(mode);
                        }
                    }
                });
    }

    private static MutableComponent getTierText(int tier, int pos, boolean retired) {
        StringBuilder text = new StringBuilder();
        if (retired) text.append("R");
        text.append(pos == 0 ? "H" : "L").append("T").append(tier);

        int color = TierTagger.getTierColor(text.toString());
        return Component.literal(text.toString()).withStyle(s -> s.withColor(color));
    }

    public static Component getRankingText(PlayerInfo.Ranking ranking, boolean showPeak) {
        if (ranking.retired() && ranking.peakTier() != null && ranking.peakPos() != null) {
            return getTierText(ranking.peakTier(), ranking.peakPos(), true);
        } else {
            MutableComponent tierText = getTierText(ranking.tier(), ranking.pos(), false);

            if (showPeak && ranking.comparablePeak() < ranking.comparableTier()) {
                // warning caused by potential NPE by unboxing of peak{Tier,Pos} which CANNOT happen, see impl of comparablePeak
                // noinspection DataFlowIssue
                tierText.append(Component.literal(" (peak: ").withStyle(s -> s.withColor(ChatFormatting.GRAY)))
                        .append(getTierText(ranking.peakTier(), ranking.peakPos(), false))
                        .append(Component.literal(")").withStyle(s -> s.withColor(ChatFormatting.GRAY)));
            }

            return tierText;
        }
    }

    private static int displayTierInfo(CommandContext<FabricClientCommandSource> ctx) {
        PlayerArgumentType.PlayerSelector selector = ctx.getArgument("player", PlayerArgumentType.PlayerSelector.class);

        // the selector may be an UUID of a player in the world: resolve it to a name, the tierlist is keyed by name
        String name = ctx.getSource().getWorld().players().stream()
                .filter(p -> p.getStringUUID().equalsIgnoreCase(selector.name()))
                .findFirst()
                .map(Player::getScoreboardName)
                .orElse(selector.name());

        PlayerSearchScreen.loadProfile(null, name)
                .thenAccept(screen -> pendingScreen = screen)
                .exceptionally(t -> {
                    Minecraft.getInstance().execute(() -> ctx.getSource().sendError(Component.literal("Could not find player " + name)));
                    return null;
                });

        return 0;
    }

    private static Component printPlayerInfo(String name, Map<String, PlayerInfo.Ranking> rankings) {
        if (rankings.isEmpty()) {
            return Component.literal(name + " does not have any tiers.");
        } else {
            MutableComponent text = Component.empty().append("=== Rankings for " + name + " ===");

            rankings.forEach((m, r) -> {
                if (m == null) return;
                GameMode mode = TierCache.findModeOrUgly(m);
                Component tierText = getRankingText(r, true);
                text.append(Component.literal("\n").append(mode.asStyled(true)).append(": ").append(tierText));
            });

            return text;
        }
    }

    public static int getTierColor(String tier) {
        if (tier.startsWith("R")) {
            return manager.getConfig().getRetiredColor();
        } else {
            return manager.getConfig().getTierColors().getOrDefault(tier, 0xD3D3D3);
        }
    }

}
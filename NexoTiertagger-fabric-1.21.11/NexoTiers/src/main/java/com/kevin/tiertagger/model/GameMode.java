package com.kevin.tiertagger.model;

import it.unimi.dsi.fastutil.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public record GameMode(String id, String title) {
    public static final GameMode NONE = new GameMode("annoying_long_id_that_no_one_will_ever_use_just_to_make_sure", "§cNone§r");

    public static final GameMode NELISMP = new GameMode("nelismp", "NeliSMP");
    public static final GameMode VANILLA = new GameMode("vanilla", "Vanilla");
    public static final GameMode SWORD = new GameMode("sword", "Sword");
    public static final GameMode TRIATHLON = new GameMode("triathlon", "Triathlon");

    /** Every gamemode that can be picked and shown in the mod, in the website's order. */
    public static final List<GameMode> ALL = List.of(NELISMP, VANILLA, SWORD, TRIATHLON);

    /**
     * Gamemodes that are selectable in the mod but not live on the website yet: they are left out of
     * points and titles (like the website does). Remove "vanilla" here once it goes live.
     */
    public static final Set<String> COMING_SOON = Set.of("vanilla");

    /** Number of gamemodes that count towards points and titles. */
    public static final int LIVE_COUNT = (int) ALL.stream().filter(GameMode::countsForPoints).count();

    public boolean countsForPoints() {
        return !COMING_SOON.contains(this.id);
    }

    public boolean isNone() {
        return this.id.equals(NONE.id);
    }

    private Pair<Character, TextColor> iconAndColor() {
        return switch (this.id) {
            case "nelismp" -> Pair.of('\uE701', TextColor.fromRgb(0x579b8c));
            case "vanilla" -> Pair.of('\uE702', TextColor.fromLegacyFormat(ChatFormatting.LIGHT_PURPLE));
            case "triathlon" -> Pair.of('\uE703', TextColor.fromRgb(0xE8BA3A));
            case "sword" -> Pair.of('\uE704', TextColor.fromRgb(0x5AD2E0));
            default -> Pair.of('•', TextColor.fromLegacyFormat(ChatFormatting.WHITE));
        };
    }

    public Optional<Character> icon() {
        Pair<Character, TextColor> pair = this.iconAndColor();

        return pair.right().getValue() == 0xFFFFFF ? Optional.empty() : Optional.of(pair.left());
    }

    public Component asStyled(boolean withDefaultDot) {
        Pair<Character, TextColor> pair = this.iconAndColor();

        if (pair.right().getValue() == 0xFFFFFF && !withDefaultDot) {
            return Component.literal(this.title);
        } else {
            Component name = Component.literal(this.title).withStyle(s -> s.withColor(pair.right()));
            return Component.literal(pair.left() + " ").append(name);
        }
    }
}

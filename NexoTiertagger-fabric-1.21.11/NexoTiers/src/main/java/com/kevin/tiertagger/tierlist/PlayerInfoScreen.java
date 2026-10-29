package com.kevin.tiertagger.tierlist;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.model.GameMode;
import com.kevin.tiertagger.model.PlayerInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.uku3lig.ukulib.config.screen.CloseableScreen;
import org.jetbrains.annotations.NotNull;


public class PlayerInfoScreen extends CloseableScreen {
    private final PlayerInfo info;
    private final PlayerSkinWidget skin;

    public PlayerInfoScreen(Screen parent, PlayerInfo info, PlayerSkinWidget skin) {
        super(Component.literal("Player Info"), parent);
        this.info = info;
        this.skin = skin;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> Minecraft.getInstance().setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 27, 200, 20)
                .build());

        this.skin.setPosition(this.width / 2 - 65, (this.height - 144) / 2);
        this.addRenderableWidget(this.skin);

        int rankingHeight = this.info.rankings().size() * 11;
        int infoHeight = 56; // 4 lines of text (10 px tall, 15 px apart) + 6 px padding
        int startY = (this.height - infoHeight - rankingHeight) / 2;
        int rankingY = startY + infoHeight;

        for (PlayerInfo.NamedRanking namedRanking : this.info.getSortedTiers()) {
            // ugly "fix" to avoid crashes if upstream doesn't have the right names
            if (namedRanking.mode() == null) continue;

            StringWidget text = new StringWidget(formatTier(namedRanking.mode(), namedRanking.ranking()), this.font);
            text.setX(this.width / 2 + 5);
            text.setY(rankingY);

            Component tooltipText = Component.literal(namedRanking.mode().countsForPoints()
                    ? "Points: " + namedRanking.ranking().points()
                    : "Coming soon: not counted in points yet").withStyle(ChatFormatting.GRAY);
            text.setTooltip(Tooltip.create(tooltipText));
            this.addRenderableWidget(text);
            rankingY += 11;
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        int rankingHeight = this.info.rankings().size() * 11;
        int infoHeight = 56; // 4 lines of text (10 px tall, 15 px apart) + 6 px padding
        int startY = (this.height - infoHeight - rankingHeight) / 2;

        graphics.drawString(this.font, this.info.name(), this.width / 2 + 5, startY, 0xFFFFFFFF);
        graphics.drawString(this.font, getRankText(this.info), this.width / 2 + 5, startY + 15, 0xFFFFFFFF);
        graphics.drawString(this.font, getTitleText(this.info), this.width / 2 + 5, startY + 30, 0xFFFFFFFF);
        graphics.drawString(this.font, "Tiers:", this.width / 2 + 5, startY + 45, 0xFFFFFFFF);
    }

    private Component formatTier(@NotNull GameMode gamemode, PlayerInfo.Ranking ranking) {
        Component tierText = TierTagger.getRankingText(ranking, true);

        return Component.empty()
                .append(gamemode.asStyled(true))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(tierText);
    }

    private Component getTitleText(PlayerInfo info) {
        PlayerInfo.PointInfo pointInfo = info.getPointInfo();

        MutableComponent text = Component.empty()
                .append(Component.literal("Title: "))
                .append(pointInfo.styledTitle());

        if (info.points() > 0) {
            text.append(Component.literal(" (" + info.points() + " points)")
                    .withStyle(s -> s.withColor(PlayerInfo.PointInfo.REQUIREMENT_COLOR)));
        }

        return text;
    }

    private Component getRankText(PlayerInfo info) {
        if (info.overall() <= 0) {
            return Component.empty()
                    .append(Component.literal("Global rank: "))
                    .append(Component.literal("-").withStyle(ChatFormatting.GRAY));
        }

        int color = switch (info.overall()) {
            case 1 -> 0xe5ba43;
            case 2 -> 0x808c9c;
            case 3 -> 0xb56326;
            default -> 0x1e2634;
        };

        return Component.empty()
                .append(Component.literal("Global rank: "))
                .append(Component.literal("#" + info.overall()).withStyle(s -> s.withColor(color)));
    }

}

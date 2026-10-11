package art.yigua.minecraft.client;

import art.yigua.core.Method;
import art.yigua.minecraft.YiguaMod;
import art.yigua.minecraft.client.RitualPreferences.UiMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** 透明的角落操作區，保留中央遊戲視野；開始後立即返回遊戲。 */
final class CastingToolScreen extends Screen {
    private final InteractionHand hand;
    private final YiguaMod.ToolItem tool;
    private boolean submitted;
    private Button ritualMode;
    private Button ritualUi;

    CastingToolScreen(InteractionHand hand, YiguaMod.ToolItem tool) {
        super(Component.literal(tool.method() == Method.COINS ? "三錢起卦" : "竹籌起卦"));
        this.hand = hand;
        this.tool = tool;
    }

    @Override protected void init() {
        int panelWidth = Math.max(1, Math.min(124, this.width - 20));
        int left = Math.max(8, this.width - panelWidth - 10);
        int top = Math.max(34, this.height - 158);
        this.addRenderableOnly(new StringWidget(left, top - 15, panelWidth, 9,
            Component.literal("結果會顯示在聊天框"), this.font));
        this.ritualMode = this.addRenderableWidget(Button.builder(modeLabel(), button -> {
            if (YiguaClient.cycleRitual(this.minecraft, this.tool.method())) button.setMessage(modeLabel());
        }).bounds(left, top, panelWidth, 20).build());
        this.ritualUi = this.addRenderableWidget(Button.builder(uiLabel(), button -> {
            UiMode next = YiguaClient.ritualUi().next();
            if (YiguaClient.changeRitualUi(this.minecraft, next)) button.setMessage(uiLabel());
        }).bounds(left, top + 24, panelWidth, 20).build());
        this.addRenderableWidget(Button.builder(animationLabel(), button -> {
            if (YiguaClient.changeAnimation(this.minecraft, !YiguaClient.revealAnimation())) button.setMessage(animationLabel());
        }).bounds(left, top + 48, panelWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("開始起卦"), button -> start(false))
            .bounds(left, top + 72, panelWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("練習起卦"), button -> start(true))
            .bounds(left, top + 96, panelWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("關閉"), button -> this.onClose())
            .bounds(left, top + 120, panelWidth, 20).build());
    }

    private Component modeLabel() {
        return Component.literal("起卦：" + YiguaClient.ritualMode(this.tool.method()).label() + "（切換）");
    }

    private Component uiLabel() {
        return Component.literal("傳統介面：" + YiguaClient.ritualUi().label());
    }

    private Component animationLabel() {
        return Component.literal("眼前動畫：" + (YiguaClient.revealAnimation() ? "開啟" : "關閉"));
    }

    private void start(boolean practice) {
        if (this.submitted) return;
        this.submitted = true;
        YiguaClient.submit(this.minecraft, this.hand, this.tool, practice);
    }

    @Override public void tick() {
        if (this.minecraft.player == null || this.minecraft.level == null
                || this.minecraft.player.getItemInHand(this.hand).getItem() != this.tool) {
            this.onClose();
        }
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 不繪製暗色背景或模糊，世界保持原本的亮度。
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int panelWidth = Math.max(1, Math.min(124, this.width - 20));
        int left = Math.max(8, this.width - panelWidth - 10);
        int top = Math.max(34, this.height - 158);
        ItemStack visual = YiguaClient.heldVisual(this.minecraft, this.hand, this.tool);
        graphics.item(visual, left, top - 33);
        graphics.text(this.font, visual.getHoverName(), left + 20, top - 29, 0xFFFFE6A1);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}

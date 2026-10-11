package art.yigua.minecraft.client;

import art.yigua.core.CastingEngine;
import art.yigua.minecraft.Library;
import art.yigua.minecraft.YiguaMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** 太極的三頁選擇器；只選擇與確認，實際套用由伺服器再次驗證。 */
public final class TaijiScreen extends Screen {
    private enum Tab { LINES, HEXAGRAMS, EFFECTS }
    private record Choice(String command, String label, String description, boolean dangerous) {}
    private static final String[] LINE_NAMES = {"初爻", "二爻", "三爻", "四爻", "五爻", "上爻"};
    private static final String[] VALUE_NAMES = {"老陰", "少陽", "少陰", "老陽"};
    private static final int PAGE_SIZE = 16;
    private static final int COLUMNS = 4;
    private static final List<Choice> EFFECTS = effectChoices();
    private final int[] values = {7, 7, 7, 7, 7, 7};
    private final ClientLevel openedLevel = Minecraft.getInstance().level;
    private Tab tab = Tab.LINES;
    private Choice pending;
    private int hexagramPage;
    private int effectPage;
    private boolean submitted;
    private int left, top, panelWidth, panelHeight, contentTop;

    public TaijiScreen() { super(Component.literal("太極・隨心擇象")); }

    @Override protected void init() {
        this.panelWidth = Math.min(396, this.width - 16);
        this.panelHeight = Math.min(260, this.height - 16);
        this.left = (this.width - this.panelWidth) / 2;
        this.top = (this.height - this.panelHeight) / 2;
        this.contentTop = this.top + 70;
        this.addRenderableWidget(Button.builder(Component.literal("解除一卦狀態").withStyle(ChatFormatting.YELLOW),
            button -> execute("yigua taiji clear"))
            .bounds(this.left + this.panelWidth - 118, this.top + 8, 108, 20).build());
        int tabWidth = (this.panelWidth - 28) / 3;
        tabButton("選六爻", Tab.LINES, this.left + 10, tabWidth);
        tabButton("選六十四卦", Tab.HEXAGRAMS, this.left + 14 + tabWidth, tabWidth);
        tabButton("選遊戲效果", Tab.EFFECTS, this.left + 18 + tabWidth * 2, tabWidth);
        if (this.pending != null) {
            confirmation();
        } else if (this.tab == Tab.LINES) {
            lines();
        } else {
            grid();
        }
    }

    private void tabButton(String label, Tab target, int x, int width) {
        Button button = this.addRenderableWidget(Button.builder(Component.literal(label), clicked -> {
            this.tab = target;
            this.pending = null;
            this.rebuildWidgets();
        }).bounds(x, this.top + 34, width, 20).build());
        button.active = this.tab != target;
    }

    private void lines() {
        int rowHeight = Math.min(21, (this.panelHeight - 108) / 6);
        for (int row = 0; row < 6; row++) {
            int line = 5 - row;
            int y = this.contentTop + row * rowHeight;
            this.addRenderableWidget(Button.builder(lineLabel(line), button -> {
                this.values[line] = this.values[line] == 9 ? 6 : this.values[line] + 1;
                button.setMessage(lineLabel(line));
            }).bounds(this.left + 10, y, this.panelWidth - 84, rowHeight - 2).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("確認六爻起卦"), button -> {
            StringBuilder ordered = new StringBuilder(6);
            for (int value : this.values) ordered.append(value);
            execute("yigua taiji lines " + ordered);
        }).bounds(this.left + 10, this.top + this.panelHeight - 30, this.panelWidth - 94, 20).build());
        closeButton();
    }

    private Component lineLabel(int line) {
        int value = this.values[line];
        return Component.literal(LINE_NAMES[line] + "  " + value + " " + VALUE_NAMES[value - 6]
            + (value == 6 || value == 9 ? "（動）" : ""));
    }

    private void grid() {
        int page = this.tab == Tab.HEXAGRAMS ? this.hexagramPage : this.effectPage;
        int size = this.tab == Tab.HEXAGRAMS ? 64 : EFFECTS.size();
        int pages = (size + PAGE_SIZE - 1) / PAGE_SIZE;
        int cellWidth = (this.panelWidth - 32) / COLUMNS;
        int cellHeight = Math.min(25, (this.panelHeight - 112) / 4);
        int end = Math.min(size, (page + 1) * PAGE_SIZE);
        for (int entry = page * PAGE_SIZE; entry < end; entry++) {
            int local = entry - page * PAGE_SIZE;
            Choice choice = this.tab == Tab.HEXAGRAMS ? hexagramChoice(entry + 1) : EFFECTS.get(entry);
            Button button = this.addRenderableWidget(Button.builder(Component.literal(choice.label()).withStyle(
                choice.dangerous() ? ChatFormatting.RED : ChatFormatting.WHITE), clicked -> {
                    this.pending = choice;
                    this.rebuildWidgets();
                }).bounds(this.left + 10 + local % COLUMNS * (cellWidth + 4),
                    this.contentTop + local / COLUMNS * cellHeight, cellWidth, cellHeight - 3).build());
            button.setTooltip(Tooltip.create(Component.literal(choice.description())));
        }
        Button previous = this.addRenderableWidget(Button.builder(Component.literal("上一頁"), button -> changePage(-1))
            .bounds(this.left + 10, this.top + this.panelHeight - 30, 72, 20).build());
        previous.active = page > 0;
        Button next = this.addRenderableWidget(Button.builder(Component.literal("下一頁"), button -> changePage(1))
            .bounds(this.left + 86, this.top + this.panelHeight - 30, 72, 20).build());
        next.active = page + 1 < pages;
        closeButton();
    }

    private void changePage(int amount) {
        if (this.tab == Tab.HEXAGRAMS) this.hexagramPage += amount;
        else this.effectPage += amount;
        this.rebuildWidgets();
    }

    private void confirmation() {
        Choice selection = this.pending;
        String confirm = selection.dangerous() ? "確認承受天雷" : "確認套用";
        this.addRenderableWidget(Button.builder(Component.literal(confirm).withStyle(
            selection.dangerous() ? ChatFormatting.RED : ChatFormatting.WHITE), button -> execute(selection.command()))
            .bounds(this.left + 10, this.top + this.panelHeight - 30, this.panelWidth - 94, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("返回選擇"), button -> {
            this.pending = null;
            this.rebuildWidgets();
        }).bounds(this.left + this.panelWidth - 80, this.top + this.panelHeight - 30, 70, 20).build());
    }

    private void closeButton() {
        this.addRenderableWidget(Button.builder(Component.literal("關閉"), button -> this.onClose())
            .bounds(this.left + this.panelWidth - 80, this.top + this.panelHeight - 30, 70, 20).build());
    }

    private void execute(String command) {
        if (this.submitted || this.minecraft.player == null || this.minecraft.level == null) return;
        this.submitted = true;
        // 同步快捷列後再送唯一指令，主手、副手及解鎖資格由伺服器核對。
        this.minecraft.player.connection.send(new ServerboundSetCarriedItemPacket(
            this.minecraft.player.getInventory().getSelectedSlot()));
        this.minecraft.player.connection.sendCommand(command);
        this.onClose();
    }

    @Override public void tick() {
        if (this.minecraft.player == null || this.minecraft.level == null || this.minecraft.level != this.openedLevel
                || !this.minecraft.player.isAlive()
                || (this.minecraft.player.getMainHandItem().getItem() != YiguaMod.TAIJI
                    && this.minecraft.player.getOffhandItem().getItem() != YiguaMod.TAIJI)) this.onClose();
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x50000000);
        graphics.fill(this.left, this.top, this.left + this.panelWidth, this.top + this.panelHeight, 0xF0181C25);
        graphics.outline(this.left, this.top, this.panelWidth, this.panelHeight, 0xFFDAC891);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.item(new ItemStack(YiguaMod.TAIJI), this.left + 10, this.top + 10);
        graphics.text(this.font, "太極・隨心擇象", this.left + 32, this.top + 14, 0xFFFFE6A1);
        if (this.pending != null) {
            graphics.text(this.font, "已選：" + this.pending.label(), this.left + 10, this.top + 59, 0xFFFFE6A1);
            String description = this.pending.description() + (this.pending.dangerous()
                ? "\n危險：天雷可能立即致命並掉落物品。確定要作用於自己時才按確認；可返回重新選擇。" : "\n按確認後才會套用。");
            List<FormattedCharSequence> wrapped = this.font.split(Component.literal(description), this.panelWidth - 24);
            int y = this.contentTop;
            int maxLines = (this.panelHeight - 104) / 12;
            for (int line = 0; line < Math.min(maxLines, wrapped.size()); line++) {
                graphics.text(this.font, wrapped.get(line), this.left + 12, y + line * 12,
                    this.pending.dangerous() ? 0xFFFFA5A5 : 0xFFE2E2E2);
            }
        } else if (this.tab == Tab.LINES) {
            graphics.text(this.font, "點每爻切換 6／7／8／9，初爻在下", this.left + 10, this.top + 59, 0xFFD2D8E1);
            int rowHeight = Math.min(21, (this.panelHeight - 108) / 6);
            for (int row = 0; row < 6; row++) {
                int line = 5 - row;
                int x = this.left + this.panelWidth - 62;
                int y = this.contentTop + row * rowHeight + Math.max(4, rowHeight / 2 - 1);
                int color = this.values[line] == 6 || this.values[line] == 9 ? 0xFFFFCF74 : 0xFFFFFFFF;
                if ((this.values[line] & 1) == 1) graphics.fill(x, y, x + 46, y + 3, color);
                else {
                    graphics.fill(x, y, x + 18, y + 3, color);
                    graphics.fill(x + 28, y, x + 46, y + 3, color);
                }
            }
        } else {
            int page = this.tab == Tab.HEXAGRAMS ? this.hexagramPage : this.effectPage;
            int size = this.tab == Tab.HEXAGRAMS ? 64 : EFFECTS.size();
            graphics.text(this.font, "點選後確認・第 " + (page + 1) + "/" + ((size + PAGE_SIZE - 1) / PAGE_SIZE) + " 頁",
                this.left + 10, this.top + 59, 0xFFD2D8E1);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }

    private static Choice hexagramChoice(int number) {
        String label = number + " " + CastingEngine.nameForNumber(number);
        return new Choice("yigua taiji hexagram " + number, label,
            "選擇第" + number + "卦「" + CastingEngine.nameForNumber(number) + "」的基礎遊戲效果。", false);
    }

    private static List<Choice> effectChoices() {
        List<Choice> choices = new ArrayList<>();
        for (String profile : Library.generalProfiles()) {
            Library.Rule example = new Library.Rule("taiji_preview_" + profile, 1, 0, profile,
                Library.baseAmount(profile), 12000, 0, "", "");
            choices.add(new Choice("yigua taiji effect " + profile, Library.profileName(profile),
                Library.effectSummary(example), false));
        }
        choices.add(new Choice("yigua taiji effect divine", "神光降臨", "降下神光與特殊裝備贈禮。", false));
        choices.add(new Choice("yigua taiji effect thunder", "天雷之劫", "主動對自己觸發天雷之劫。", true));
        choices.add(new Choice("yigua taiji effect undying", "生生不息", "短暫的不死守護。", false));
        choices.add(new Choice("yigua taiji effect five_qi", "五氣朝元", "五色氣流匯聚的異象。", false));
        choices.add(new Choice("yigua taiji effect flowers", "三花聚頂", "三花聚頂的異象。", false));
        choices.add(new Choice("yigua taiji effect birds", "百鳥朝鳳", "鳳凰盤旋、振翅飛行兩分鐘；空白升高、潛行下降。", false));
        choices.add(new Choice("yigua taiji effect rapport", "萬物親和", "短暫的生物親和。", false));
        choices.add(new Choice("yigua taiji effect sea", "分海", "有效期間隨行分開前方水道。", false));
        choices.add(new Choice("yigua taiji effect obstruction", "山重阻路", "寬闊柱牆依生態逐層升起；留下永久方塊。", false));
        return List.copyOf(choices);
    }
}

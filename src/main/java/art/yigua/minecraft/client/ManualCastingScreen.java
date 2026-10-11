package art.yigua.minecraft.client;

import art.yigua.core.Method;
import art.yigua.minecraft.CastingProgressPayload;
import art.yigua.minecraft.YiguaMod;
import art.yigua.minecraft.client.RitualPreferences.UiMode;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** 文字與拖曳共用同一個手動 session；玩家分堆，錢面與揲四結果由伺服器決定。 */
final class ManualCastingScreen extends Screen {
    private final InteractionHand hand;
    private final YiguaMod.ToolItem tool;
    private final boolean yarrow;
    private final RitualInteraction sticks = new RitualInteraction();
    private final RitualInteraction.CoinThrow coinDrag = new RitualInteraction.CoinThrow();
    private UiMode uiMode;
    private CastingProgressPayload progress;
    private ItemStack visual = ItemStack.EMPTY;
    private ItemStack tossVisual = ItemStack.EMPTY;
    private List<Integer> completedCoins = List.of();
    private int completedCoinLine;
    private boolean waiting = true;
    private boolean finished;
    private boolean rebuildUi;
    private EditBox leftPile;
    private String textDraft = "";
    private Button submit;
    private int panelLeft;
    private int panelWidth;
    private int panelTop;
    private int hintTop;
    private int hintRows;
    private int hintOffset;
    private int boardLeft;
    private int boardTop;
    private int boardWidth;
    private int boardBottom;
    private int coinNoteTop;
    private int coinNoteRows;
    private int coinNoteOffset;
    private RitualInteraction.Rect leftZone;
    private RitualInteraction.Rect rightZone;
    private RitualInteraction.Rect coinBank;
    private RitualInteraction.Rect coinTarget;

    ManualCastingScreen(InteractionHand hand, YiguaMod.ToolItem tool) {
        super(Component.literal(tool.method() == Method.COINS ? "三錢逐枚投擲" : "手動傳統蓍草"));
        this.hand = hand;
        this.tool = tool;
        this.yarrow = tool.method() == Method.YARROW;
        this.uiMode = YiguaClient.ritualUi();
    }

    @Override protected void init() {
        int preferredPanelWidth = this.yarrow ? 164 : Math.min(164, Math.max(108, this.width / 3));
        this.panelWidth = Math.max(1, Math.min(preferredPanelWidth, this.width - 20));
        this.panelLeft = Math.max(8, this.width - this.panelWidth - 10);
        this.panelTop = Math.max(96, this.height - 162);
        this.hintTop = this.panelTop + 31;
        int switchTop = this.height - 94;
        this.hintRows = Math.max(1, (switchTop - this.hintTop - 8) / 10);
        if (this.visual.isEmpty()) this.visual = YiguaClient.heldVisual(this.minecraft, this.hand, this.tool).copy();
        this.tossVisual = CastingProgressPayload.animationVisual(this.visual);
        this.leftPile = null;
        this.addRenderableWidget(Button.builder(Component.literal("傳統介面：" + this.uiMode.label()), button -> {
            UiMode next = this.uiMode.next();
            if (YiguaClient.changeRitualUi(this.minecraft, next)) applyUi(next);
        }).bounds(this.panelLeft, switchTop, this.panelWidth, 20).build());
        if (this.yarrow && this.uiMode == UiMode.TEXT) {
            this.leftPile = new EditBox(this.font, this.panelLeft, this.height - 70, this.panelWidth, 20,
                Component.literal("左堆數量"));
            this.leftPile.setMaxLength(2);
            this.leftPile.setValue(this.textDraft);
            this.leftPile.setHint(Component.literal(this.progress == null ? "輸入左堆數量"
                : "左堆 1 至 " + (this.progress.remaining() - 2)));
            this.leftPile.setResponder(value -> { this.textDraft = value; updateButtons(); });
            this.addRenderableWidget(this.leftPile);
        }
        this.submit = this.addRenderableWidget(Button.builder(
            Component.literal(this.yarrow ? "確認分堆・掛一揲四" : "投擲一枚"), button -> performStep())
            .bounds(this.panelLeft, this.height - 46, this.panelWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("取消起卦"), button -> this.onClose())
            .bounds(this.panelLeft, this.height - 22, this.panelWidth, 20).build());
        layoutBoard();
        updateButtons();
        if (this.leftPile != null) this.setInitialFocus(this.leftPile);
    }

    private void layoutBoard() {
        this.boardLeft = 8;
        this.boardWidth = Math.max(86, this.panelLeft - this.boardLeft - 10);
        this.boardTop = this.yarrow ? Math.max(56, this.height / 5) : Math.max(48, this.height / 7);
        this.boardBottom = this.yarrow ? Math.max(this.boardTop + 105, this.height - 52) : this.height - 12;
        int zoneTop = this.boardTop + 25;
        int zoneHeight = Math.max(49, this.boardBottom - zoneTop - 38);
        int zoneWidth = Math.max(35, (this.boardWidth - 8) / 2);
        this.leftZone = new RitualInteraction.Rect(this.boardLeft, zoneTop, zoneWidth, zoneHeight);
        this.rightZone = new RitualInteraction.Rect(this.boardLeft + zoneWidth + 8, zoneTop, zoneWidth, zoneHeight);
        this.sticks.layout(this.leftZone, this.rightZone);
        int coinBankWidth = Math.max(56, Math.min(74, this.boardWidth / 4));
        int headerHeight = (this.font.split(Component.literal("三錢法・逐枚記錄"), this.boardWidth).size()
            + this.font.split(Component.literal("三枚合一爻；六爻由下至上"), this.boardWidth).size()) * 10 + 8;
        int coinColumnWidth = Math.max(1, (Math.max(30, this.boardWidth - coinBankWidth) - 4) / 3);
        int faceRows = Math.max(this.font.split(Component.literal("字面\n陰 2"), coinColumnWidth).size(),
            this.font.split(Component.literal("背面\n陽 3"), coinColumnWidth).size());
        this.coinBank = new RitualInteraction.Rect(this.boardLeft + 8, this.boardTop + headerHeight + 18, 24, 24);
        this.coinTarget = new RitualInteraction.Rect(this.boardLeft + coinBankWidth,
            this.boardTop + headerHeight + 10, Math.max(30, this.boardWidth - coinBankWidth), Math.max(60, 34 + faceRows * 10));
    }

    /** 切換只保留／呈現目前草稿，不重新 start，也不要求伺服器抽新結果。 */
    void applyUi(UiMode selected) {
        if (selected == this.uiMode) return;
        if (this.yarrow) {
            if (this.uiMode == UiMode.TEXT) {
                int draft = textValue(false);
                if (draft >= 0 && draft <= this.sticks.remaining()) this.sticks.setLeftCount(draft);
            } else {
                this.textDraft = this.sticks.leftCount() == 0 ? "" : Integer.toString(this.sticks.leftCount());
            }
        }
        this.sticks.cancelGesture();
        this.coinDrag.cancel();
        this.uiMode = selected;
        // 原生滑鼠／鍵盤事件會在按鈕回呼後設定焦點；下個 tick 再重建，避免焦點留在已移除的按鈕。
        this.rebuildUi = true;
    }

    void acceptProgress(CastingProgressPayload next) {
        if (!next.method().equals(this.yarrow ? "yarrow" : "coins")) return;
        CastingProgressPayload previous = this.progress;
        this.progress = next;
        ItemStack suppliedVisual = YiguaClient.visualStack(next);
        if (!suppliedVisual.isEmpty()) {
            this.visual = suppliedVisual;
            this.tossVisual = CastingProgressPayload.animationVisual(suppliedVisual);
        }
        if (!this.yarrow && previous != null && next.lines().size() == previous.lines().size() + 1
                && previous.coins().size() == 2) {
            int third = next.lines().getLast() - previous.coins().stream().mapToInt(Integer::intValue).sum();
            // 第三枚的值只由伺服器合計與前兩枚還原，不抽新錢面；保留至下一爻完成。
            if (third == 2 || third == 3) {
                this.completedCoins = List.of(previous.coins().get(0), previous.coins().get(1), third);
                this.completedCoinLine = next.lines().size();
            }
        }
        this.waiting = false;
        this.coinDrag.cancel();
        this.hintOffset = 0;
        this.coinNoteOffset = 0;
        if (!next.active()) {
            // 完成或拒絕由伺服器結束；此路徑不送 cancel，以免取消完成後的揭曉。
            this.finished = true;
            this.minecraft.gui.setScreen(null);
            return;
        }
        if (this.yarrow && (previous == null || previous.line() != next.line()
                || previous.change() != next.change() || previous.remaining() != next.remaining())) {
            this.sticks.reset(next.remaining());
            this.textDraft = "";
            if (this.leftPile != null) this.leftPile.setValue("");
        }
        if (this.leftPile != null) this.leftPile.setHint(Component.literal("左堆 1 至 " + (next.remaining() - 2)));
        updateButtons();
    }

    private boolean ready() {
        return !this.finished && !this.waiting && this.progress != null && this.progress.active()
            && this.minecraft.player != null && this.minecraft.player.isAlive();
    }

    private void updateButtons() {
        boolean ready = ready();
        if (this.leftPile != null) this.leftPile.setEditable(ready);
        if (this.submit != null) this.submit.active = ready && (!this.yarrow || splitValue() > 0);
    }

    private int textValue(boolean requireValid) {
        try {
            if (!this.textDraft.matches("[0-9]{1,2}")) return -1;
            int amount = Integer.parseInt(this.textDraft);
            if (!requireValid) return amount;
            return this.progress != null && amount > 0 && amount <= this.progress.remaining() - 2 ? amount : -1;
        } catch (NumberFormatException failure) {
            return -1;
        }
    }

    private int splitValue() {
        return this.uiMode == UiMode.INTERACTIVE
            ? this.sticks.validSplit() ? this.sticks.leftCount() : -1
            : textValue(true);
    }

    private void performStep() {
        if (!ready()) return;
        int amount = this.yarrow ? splitValue() : 0;
        if (this.yarrow && amount <= 0) return;
        this.waiting = true;
        this.sticks.cancelGesture();
        this.coinDrag.cancel();
        updateButtons();
        this.minecraft.player.connection.sendCommand(this.yarrow ? "yigua manual split " + amount : "yigua manual throw");
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (RitualInteraction.isPrimaryButton(event.button()) && this.uiMode == UiMode.INTERACTIVE && ready()) {
            if (this.yarrow && this.sticks.press(event.x(), event.y(), event.hasShiftDown())) return true;
            if (!this.yarrow && this.coinDrag.press(this.coinBank, event.x(), event.y())) return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (RitualInteraction.isPrimaryButton(event.button()) && this.uiMode == UiMode.INTERACTIVE && ready()) {
            if (this.yarrow && this.sticks.move(event.x(), event.y())) return true;
            if (!this.yarrow && this.coinDrag.move(event.x(), event.y())) return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (RitualInteraction.isPrimaryButton(event.button()) && this.uiMode == UiMode.INTERACTIVE) {
            if (this.yarrow && this.sticks.release(event.x(), event.y())) {
                updateButtons();
                return true;
            }
            if (!this.yarrow && this.coinDrag.carrying()) {
                boolean thrown = this.coinDrag.release(this.coinTarget, event.x(), event.y());
                if (thrown) performStep();
                return true;
            }
        }
        return super.mouseReleased(event);
    }

    @Override public void onClose() {
        if (!this.finished && this.minecraft.player != null && this.minecraft.level != null) {
            this.finished = true;
            YiguaClient.cancelManual(this.minecraft);
        }
        this.minecraft.gui.setScreen(null);
    }

    @Override public void tick() {
        if (this.minecraft.player == null || this.minecraft.level == null) {
            this.finished = true;
            this.minecraft.gui.setScreen(null);
        } else if (this.minecraft.player.getItemInHand(this.hand).getItem() != this.tool) this.onClose();
        else if (this.rebuildUi) {
            this.rebuildUi = false;
            this.rebuildWidgets();
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.item(this.visual, this.panelLeft, this.panelTop - 2);
        graphics.text(this.font, this.visual.getHoverName(), this.panelLeft + 20, this.panelTop + 2, 0xFFFFE6A1);
        String step;
        if (this.progress == null) step = "等待伺服器開始起卦…";
        else if (this.yarrow) step = "第 " + this.progress.line() + " 爻・第 " + this.progress.change()
            + "/3 變・剩 " + this.progress.remaining() + " 策";
        else step = "第 " + this.progress.line() + " 爻・已投 " + this.progress.coins().size() + "/3 枚";
        graphics.text(this.font, step, this.panelLeft, this.panelTop + 17, 0xFFFFFFFF);
        String hint = this.progress == null ? "每一步由伺服器計算，完成六爻後回到遊戲。" : this.progress.hint();
        if (this.waiting && this.progress != null) hint = "正在等待這一步的結果…\n" + hint;
        List<FormattedCharSequence> lines = this.font.split(Component.literal(hint), this.panelWidth);
        this.hintOffset = Math.max(0, Math.min(this.hintOffset, Math.max(0, lines.size() - this.hintRows)));
        for (int index = 0; index < this.hintRows && index + this.hintOffset < lines.size(); index++) {
            graphics.text(this.font, lines.get(index + this.hintOffset), this.panelLeft, this.hintTop + index * 10, 0xFFFFFFFF);
        }
        if (lines.size() > this.hintRows) {
            graphics.text(this.font, "滾輪查看提示", this.panelLeft, this.hintTop + this.hintRows * 10, 0xFFBFBFBF);
        }
        if (!this.yarrow) drawCoinBoard(graphics);
        else if (this.uiMode == UiMode.INTERACTIVE) drawSticks(graphics);
    }

    private void drawSticks(GuiGraphicsExtractor graphics) {
        graphics.text(this.font, "拖曳籌策分成兩堆", this.boardLeft, this.boardTop, 0xFFFFE6A1);
        drawZone(graphics, this.leftZone, "左堆 " + this.sticks.leftCount());
        drawZone(graphics, this.rightZone, "右堆 " + this.sticks.rightCount());
        int materialColor = stalkColor();
        for (RitualInteraction.Stick stick : this.sticks.sticks()) {
            if (stick.carried()) continue;
            RitualInteraction.Rect rect = stick.bounds();
            int x = (int)rect.x(), y = (int)rect.y();
            int width = Math.max(2, (int)rect.width()), height = Math.max(3, (int)rect.height());
            graphics.fill(x, y, x + width, y + height, materialColor);
            graphics.fill(x, y, x + 1, y + height, 0xFFDDD4A0);
            graphics.fill(x, y + height / 2, x + width, y + height / 2 + 1, 0xFF655731);
            if (stick.selected()) graphics.outline(x - 1, y - 1, width + 2, height + 2, 0xFFFFE6A1);
        }
        if (this.sticks.selecting()) {
            RitualInteraction.Rect area = this.sticks.selectionRect();
            graphics.fill((int)area.x(), (int)area.y(), (int)(area.x() + area.width()),
                (int)(area.y() + area.height()), 0x337ABBDD);
            graphics.outline((int)area.x(), (int)area.y(), (int)area.width(), (int)area.height(), 0xFFB3E5FF);
        }
        if (this.sticks.carrying()) {
            int x = (int)this.sticks.pointerX(), y = (int)this.sticks.pointerY();
            for (int index = 0; index < Math.min(8, this.sticks.selectedCount()); index++) {
                graphics.fill(x + index * 3, y - 12, x + index * 3 + 2, y + 10, materialColor);
            }
            graphics.text(this.font, this.sticks.selectedCount() + " 根", x, y + 14, 0xFFFFFFFF);
        }
        int noteTop = (int)(this.leftZone.y() + this.leftZone.height()) + 7;
        List<FormattedCharSequence> help = this.font.split(Component.literal(
            "Shift＋拖曳框選多根。\n框選後拖到另一堆。\n左至少 1，右至少 2。"), this.boardWidth);
        for (int line = 0; line < Math.min(3, help.size()); line++) {
            graphics.text(this.font, help.get(line), this.boardLeft, noteTop + line * 10, 0xFFDDDDDD);
        }
    }

    private int stalkColor() {
        String id = BuiltInRegistries.ITEM.getKey(this.visual.getItem()).getPath();
        if (id.contains("wooden")) return 0xFFB28455;
        if (id.contains("grass")) return 0xFF91BA65;
        if (id.contains("roasted_vine")) return 0xFF946537;
        if (id.contains("roasted")) return 0xFFC38B45;
        if (id.contains("vine")) return 0xFF83A34C;
        return 0xFFC9CB75;
    }

    private void drawZone(GuiGraphicsExtractor graphics, RitualInteraction.Rect zone, String label) {
        int x = (int)zone.x(), y = (int)zone.y(), width = (int)zone.width(), height = (int)zone.height();
        graphics.fill(x, y, x + width, y + height, 0x660B2117);
        graphics.outline(x, y, width, height, 0x88B2C8A0);
        graphics.text(this.font, label, x + 2, y - 11, 0xFFFFFFFF);
    }

    private void drawCoinBoard(GuiGraphicsExtractor graphics) {
        int captionTop = drawCoinText(graphics, "三錢法・逐枚記錄", this.boardTop, 0xFFFFE6A1);
        drawCoinText(graphics, "三枚合一爻；六爻由下至上", captionTop + 2, 0xFFDDDDDD);
        drawZone(graphics, this.coinBank, this.uiMode == UiMode.INTERACTIVE ? "取一枚" : "每次一枚");
        drawZone(graphics, this.coinTarget, this.uiMode == UiMode.INTERACTIVE ? "放手投擲" : "按投擲一枚");
        if (!this.coinDrag.carrying()) {
            graphics.item(this.tossVisual, (int)this.coinBank.x() + 4, (int)this.coinBank.y() + 4);
        } else graphics.item(this.tossVisual, (int)this.coinDrag.x() - 8, (int)this.coinDrag.y() - 8);
        int throwsSoFar = this.progress == null ? 0 : this.progress.coins().size();
        graphics.text(this.font, "已投 " + throwsSoFar + "/3", this.boardLeft + 2,
            (int)(this.coinBank.y() + this.coinBank.height()) + 5, 0xFFDDDDDD);
        graphics.text(this.font, "剩餘 " + (3 - throwsSoFar) + " 枚", this.boardLeft + 2,
            (int)(this.coinBank.y() + this.coinBank.height()) + 17, 0xFFDDDDDD);
        List<Integer> current = this.progress == null ? List.of() : this.progress.coins();
        boolean showingPrevious = current.isEmpty() && !this.completedCoins.isEmpty();
        List<Integer> shown = showingPrevious ? this.completedCoins : current;
        graphics.text(this.font, showingPrevious ? "上一爻三錢" : "本爻三錢",
            (int)this.coinTarget.x() + 3, (int)this.coinTarget.y() + 2, 0xFFDDDDDD);
        drawCoins(graphics, shown, (int)this.coinTarget.x() + 2, (int)this.coinTarget.y() + 14,
            Math.max(1, ((int)this.coinTarget.width() - 4) / 3));
        int noteTop = (int)(this.coinTarget.y() + this.coinTarget.height()) + 5;
        String equation = (showingPrevious ? "第 " + this.completedCoinLine + " 爻：" : "本爻：") + coinEquation(shown);
        if (shown.size() == 3) equation += "・" + coinLineName(shown.stream().mapToInt(Integer::intValue).sum());
        noteTop = drawCoinText(graphics, equation, noteTop, 0xFFFFE6A1);
        if (!showingPrevious && !this.completedCoins.isEmpty()) {
            int sum = this.completedCoins.stream().mapToInt(Integer::intValue).sum();
            noteTop = drawCoinText(graphics, "上一爻 " + this.completedCoinLine + "："
                + coinEquation(this.completedCoins) + "・" + coinLineName(sum), noteTop + 2, 0xFFDDDDDD);
        }
        List<FormattedCharSequence> help = this.font.split(Component.literal(
            "6 老陰・陰爻會變為陽\n7 少陽・陽爻不變\n8 少陰・陰爻不變\n9 老陽・陽爻會變為陰\n"
            + "字面（陰）＝2；背面（陽）＝3，為本模組固定約定。\n"
            + "傳統常三枚一起擲；此選項逐枚記錄。拖到區外不投，也可按『投擲一枚』。"), this.boardWidth);
        this.coinNoteTop = noteTop + 4;
        int availableRows = Math.max(0, (this.boardBottom - this.coinNoteTop) / 10);
        this.coinNoteRows = Math.max(0, availableRows - (help.size() > availableRows ? 1 : 0));
        this.coinNoteOffset = Math.max(0, Math.min(this.coinNoteOffset, Math.max(0, help.size() - this.coinNoteRows)));
        for (int line = 0; line < this.coinNoteRows && line + this.coinNoteOffset < help.size(); line++) {
            graphics.text(this.font, help.get(line + this.coinNoteOffset), this.boardLeft,
                this.coinNoteTop + line * 10, 0xFFDDDDDD);
        }
        if (help.size() > this.coinNoteRows && availableRows > 0) {
            graphics.text(this.font, "滾輪查看三錢說明", this.boardLeft,
                this.coinNoteTop + this.coinNoteRows * 10, 0xFFBFBFBF);
        }
    }

    private int drawCoinText(GuiGraphicsExtractor graphics, String text, int top, int color) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), this.boardWidth);
        int rows = Math.max(0, Math.min(lines.size(), (this.boardBottom - top) / 10));
        for (int line = 0; line < rows; line++) {
            graphics.text(this.font, lines.get(line), this.boardLeft, top + line * 10, color);
        }
        return top + rows * 10;
    }

    private static String coinEquation(List<Integer> coins) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 3; index++) {
            if (index > 0) text.append(" + ");
            text.append(index < coins.size() ? coins.get(index).toString() : "？");
        }
        text.append(" = ");
        return text.append(coins.size() == 3 ? Integer.toString(coins.stream().mapToInt(Integer::intValue).sum()) : "？").toString();
    }

    private static String coinLineName(int value) {
        return switch (value) {
            case 6 -> "老陰・動爻";
            case 7 -> "少陽・不變";
            case 8 -> "少陰・不變";
            case 9 -> "老陽・動爻";
            default -> "未成爻";
        };
    }

    private void drawCoins(GuiGraphicsExtractor graphics, List<Integer> coins, int left, int top, int spacing) {
        for (int index = 0; index < 3; index++) {
            int x = left + index * spacing + Math.max(0, (spacing - 16) / 2);
            if (index < coins.size()) {
                graphics.item(this.tossVisual, x, top);
                boolean yin = coins.get(index) == 2;
                int labelLeft = left + index * spacing;
                drawCoinFaceText(graphics, yin ? "字面\n陰 2" : "背面\n陽 3", labelLeft, top + 18, spacing);
            } else {
                graphics.fill(x, top + 2, x + 15, top + 14, 0x44999999);
                drawCoinFaceText(graphics, "未投", left + index * spacing, top + 18, spacing);
            }
        }
    }

    private void drawCoinFaceText(GuiGraphicsExtractor graphics, String text, int left, int top, int width) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), Math.max(1, width));
        for (int line = 0; line < lines.size(); line++) {
            int textLeft = left + Math.max(0, (width - this.font.width(lines.get(line))) / 2);
            graphics.text(this.font, lines.get(line), textLeft, top + line * 10, 0xFFFFE6A1);
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!this.yarrow && x >= this.boardLeft && x <= this.boardLeft + this.boardWidth
                && y >= this.coinNoteTop && y <= this.boardBottom && this.coinNoteRows > 0) {
            this.coinNoteOffset = Math.max(0, this.coinNoteOffset - (int)Math.signum(scrollY));
            return true;
        }
        if (x >= this.panelLeft && x <= this.panelLeft + this.panelWidth
                && y >= this.hintTop && y <= this.hintTop + (this.hintRows + 1) * 10) {
            this.hintOffset = Math.max(0, this.hintOffset - (int)Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 操作區之外保持透明，不暗化中央世界視野。
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}

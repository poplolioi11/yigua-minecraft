package art.yigua.minecraft.client;

import art.yigua.core.Method;
import art.yigua.minecraft.CastingProgressPayload;
import art.yigua.minecraft.YiguaMod;
import art.yigua.minecraft.client.RitualPreferences.EntryMode;
import art.yigua.minecraft.client.RitualPreferences.RitualMode;
import art.yigua.minecraft.client.RitualPreferences.UiMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** 本機的操作方式與介面；抽卦、冷卻和圖鑑仍全部交由伺服器決定。 */
public final class YiguaClient implements ClientModInitializer {
    private static RitualPreferences preferences = RitualPreferences.defaults();
    private static CastingProgressPayload progress;
    private static ItemStack progressVisual = ItemStack.EMPTY;
    private static ItemStack animationVisual = ItemStack.EMPTY;
    private static long progressReceivedAt;
    private static boolean awaitingManualCancel;
    private static long progressExpiresAt;
    private static int pendingAchievements;

    @Override public void onInitializeClient() {
        SkyOmenClient.init();
        TaijiClient.init();
        loadMode();
        CastingProgressPayload.init();
        ClientPlayNetworking.registerGlobalReceiver(CastingProgressPayload.TYPE,
            (payload, context) -> context.client().execute(() -> receiveProgress(context.client(), payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((connection, minecraft) -> clearProgress());
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            if (minecraft.level == null || minecraft.player == null
                    || (progress != null && !progress.active() && System.nanoTime() >= progressExpiresAt)) clearProgress();
            if (minecraft.level == null || minecraft.player == null) pendingAchievements = 0;
            else if (pendingAchievements > 0 && --pendingAchievements == 0) {
                var advancements = minecraft.player.connection.getAdvancements();
                minecraft.gui.setScreen(new AdvancementsScreen(advancements));
                var root = advancements.get(Identifier.fromNamespaceAndPath("yigua", "hexagrams/root"));
                if (root != null) advancements.setSelectedTab(root, true);
            }
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("yigua", "casting_progress"),
            (graphics, delta) -> extractProgressHud(graphics));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(ClientCommands.literal("yigua-mode")
                .then(ClientCommands.literal("button").executes(context -> selectMode(context.getSource(), EntryMode.BUTTON)))
                .then(ClientCommands.literal("direct").executes(context -> selectMode(context.getSource(), EntryMode.DIRECT))));
            var ritualModes = ClientCommands.literal("yigua-ritual-mode");
            for (RitualMode selected : RitualMode.values()) {
                ritualModes.then(ClientCommands.literal(selected.id())
                    .executes(context -> selectBothRitualModes(context.getSource(), selected)));
            }
            dispatcher.register(ritualModes);
            var ritualUis = ClientCommands.literal("yigua-ritual-ui");
            for (UiMode selected : UiMode.values()) {
                ritualUis.then(ClientCommands.literal(selected.id())
                    .executes(context -> selectRitualUi(context.getSource(), selected)));
            }
            dispatcher.register(ritualUis);
            dispatcher.register(ClientCommands.literal("yigua-animation")
                .then(ClientCommands.literal("on").executes(context -> selectAnimation(context.getSource(), true)))
                .then(ClientCommands.literal("off").executes(context -> selectAnimation(context.getSource(), false))));
            dispatcher.register(ClientCommands.literal("yigua-ritual")
                .then(ClientCommands.literal("yarrow")
                    .then(ClientCommands.literal("on").executes(context -> selectRitual(context.getSource(), Method.YARROW, true)))
                    .then(ClientCommands.literal("off").executes(context -> selectRitual(context.getSource(), Method.YARROW, false))))
                .then(ClientCommands.literal("coins")
                    .then(ClientCommands.literal("on").executes(context -> selectRitual(context.getSource(), Method.COINS, true)))
                    .then(ClientCommands.literal("off").executes(context -> selectRitual(context.getSource(), Method.COINS, false)))));
            dispatcher.register(ClientCommands.literal("yigua-achievements").executes(context -> {
                var source = context.getSource();
                if (source.getClient().gui.screen() instanceof ManualCastingScreen manual) manual.onClose();
                // 聊天與書本在指令返回後會關閉原畫面，延後開啟才不會被覆蓋。
                pendingAchievements = 2;
                return 1;
            }));
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!level.isClientSide() || player.isSpectator()) return InteractionResult.PASS;
            if (!(player.getItemInHand(hand).getItem() instanceof YiguaMod.ToolItem tool)) return InteractionResult.PASS;
            Minecraft minecraft = Minecraft.getInstance();
            if (player != minecraft.player) return InteractionResult.PASS;
            if (awaitingManualCancel || (progress != null && progress.active())) {
                minecraft.player.sendOverlayMessage(Component.literal("目前起卦尚未完成，完成或取消後再開始。"));
                return InteractionResult.FAIL;
            }
            if (minecraft.gui.screen() == null) {
                if (preferences.entry() == EntryMode.BUTTON) minecraft.gui.setScreen(new CastingToolScreen(hand, tool));
                else submit(minecraft, hand, tool, false);
            }
            // FAIL 取消原版 useItem，Fabric 不會另送使用物品封包，避免同一次右鍵重複起卦。
            return InteractionResult.FAIL;
        });
    }

    static void submit(Minecraft minecraft, InteractionHand hand, YiguaMod.ToolItem expectedTool, boolean practice) {
        if (minecraft.player == null || minecraft.level == null) return;
        if (awaitingManualCancel || (progress != null && progress.active())) {
            minecraft.gui.setScreen(null);
            minecraft.player.sendOverlayMessage(Component.literal("目前起卦尚未完成，完成或取消後再開始。"));
            return;
        }
        if (minecraft.player.getItemInHand(hand).getItem() != expectedTool) {
            minecraft.gui.setScreen(null);
            minecraft.player.sendSystemMessage(Component.literal("手上的筮具已變更，請重新使用。"));
            return;
        }
        minecraft.gui.setScreen(null);
        // UseItemCallback 在原版同步快捷列之前攔截；按鈕指令必須先同步目前選取的槽位。
        minecraft.player.connection.send(new ServerboundSetCarriedItemPacket(minecraft.player.getInventory().getSelectedSlot()));
        String handName = hand == InteractionHand.MAIN_HAND ? "mainhand" : "offhand";
        if (manualEnabled(expectedTool.method())) {
            clearProgress();
            minecraft.gui.setScreen(new ManualCastingScreen(hand, expectedTool));
            minecraft.player.connection.sendCommand("yigua manual start " + handName + (practice ? " practice" : ""));
        } else {
            String command = "yigua " + (practice ? "practice " : "cast ") + handName
                + (ritualMode(expectedTool.method()) == RitualMode.INSTANT ? " instant" : "");
            minecraft.player.connection.sendUnattendedCommand(command, null);
        }
    }

    private static int selectMode(FabricClientCommandSource source, EntryMode selected) {
        if (!savePreferences(preferences.withEntry(selected), source::sendError)) return 0;
        closeSettingsScreen(source.getClient());
        source.sendFeedback(Component.literal(selected == EntryMode.BUTTON
            ? "已切換為按鈕模式：右鍵筮具後，在畫面角落按『開始起卦』。"
            : "已切換為直接模式：右鍵筮具即開始起卦，結果顯示在聊天框。"));
        return 1;
    }

    private static int selectRitual(FabricClientCommandSource source, Method method, boolean enabled) {
        if (!savePreferences(preferences.withMethod(method, enabled ? RitualMode.TRADITIONAL : RitualMode.AUTO),
                source::sendError)) return 0;
        closeSettingsScreen(source.getClient());
        source.sendFeedback(Component.literal(method == Method.YARROW
            ? (enabled ? "已開啟手動蓍草：每爻親自分堆，依序掛一、揲四、歸奇。" : "已關閉手動蓍草：使用自動起卦。")
            : (enabled ? "已開啟三錢逐枚投擲：每爻依序投擲三枚銅錢。" : "已關閉三錢逐枚投擲：使用自動起卦。")));
        return 1;
    }

    static boolean manualEnabled(Method method) {
        return ritualMode(method) == RitualMode.TRADITIONAL;
    }

    static RitualMode ritualMode(Method method) { return preferences.forMethod(method); }
    static UiMode ritualUi() { return preferences.ui(); }
    static boolean revealAnimation() { return preferences.revealAnimation(); }

    /** 角落面板只修改手上這種筮法；儲存失敗時按鈕保持原模式。 */
    static boolean cycleRitual(Minecraft minecraft, Method method) {
        return savePreferences(preferences.withMethod(method, ritualMode(method).next()),
            error -> showLocalError(minecraft, error));
    }

    static boolean changeRitualUi(Minecraft minecraft, UiMode selected) {
        return savePreferences(preferences.withUi(selected), error -> showLocalError(minecraft, error));
    }

    static boolean changeAnimation(Minecraft minecraft, boolean selected) {
        return savePreferences(preferences.withAnimation(selected), error -> showLocalError(minecraft, error));
    }

    private static int selectAnimation(FabricClientCommandSource source, boolean enabled) {
        if (!savePreferences(preferences.withAnimation(enabled), source::sendError)) return 0;
        closeSettingsScreen(source.getClient());
        source.sendFeedback(Component.literal("眼前筮具動畫已" + (enabled ? "開啟" : "關閉")
            + "；六爻進度、起卦結果與冷卻保持原本設定。"));
        return 1;
    }

    private static int selectBothRitualModes(FabricClientCommandSource source, RitualMode selected) {
        if (!savePreferences(preferences.withBoth(selected), source::sendError)) return 0;
        closeSettingsScreen(source.getClient());
        source.sendFeedback(Component.literal("三錢與籌束已切換為『" + selected.label() + "』，下次起卦生效。"));
        return 1;
    }

    private static int selectRitualUi(FabricClientCommandSource source, UiMode selected) {
        if (!savePreferences(preferences.withUi(selected), source::sendError)) return 0;
        if (source.getClient().gui.screen() instanceof ManualCastingScreen manual) manual.applyUi(selected);
        else closeSettingsScreen(source.getClient());
        source.sendFeedback(Component.literal("傳統卜筮介面已切換為『" + selected.label() + "』；起卦進度保留。"));
        return 1;
    }

    private static void showLocalError(Minecraft minecraft, Component error) {
        if (minecraft.player != null) minecraft.player.sendSystemMessage(error);
    }

    private static void closeSettingsScreen(Minecraft minecraft) {
        Screen current = minecraft.gui.screen();
        if (!(current instanceof ManualCastingScreen)) minecraft.gui.setScreen(null);
    }

    private static boolean savePreferences(RitualPreferences selected, Consumer<Component> error) {
        try {
            selected.save(configPath());
        } catch (IOException | RuntimeException failure) {
            error.accept(Component.literal("起卦設定未切換：無法保存本機設定。"));
            YiguaMod.LOG.warn("一卦本機起卦設定無法保存。", failure);
            return false;
        }
        preferences = selected;
        return true;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("yigua-client.json");
    }

    private static void loadMode() {
        Path path = configPath();
        if (!Files.exists(path)) return;
        try {
            preferences = RitualPreferences.parse(Files.readString(path));
        } catch (IOException | RuntimeException failure) {
            preferences = RitualPreferences.defaults();
            YiguaMod.LOG.warn("一卦本機起卦設定無法讀取，使用按鈕模式與自動起卦。", failure);
        }
    }

    private static void receiveProgress(Minecraft minecraft, CastingProgressPayload payload) {
        if (minecraft.player == null || minecraft.level == null) return;
        progress = payload;
        if (payload.manual() && !payload.active()) awaitingManualCancel = false;
        progressVisual = visualStack(payload);
        animationVisual = CastingProgressPayload.animationVisual(progressVisual);
        progressReceivedAt = System.nanoTime();
        progressExpiresAt = payload.active() ? Long.MAX_VALUE : System.nanoTime() + 8_000_000_000L;
        if (minecraft.gui.screen() instanceof ManualCastingScreen manual && payload.manual()) {
            manual.acceptProgress(payload);
        }
    }

    private static void clearProgress() {
        progress = null;
        progressVisual = ItemStack.EMPTY;
        animationVisual = ItemStack.EMPTY;
        progressReceivedAt = 0;
        awaitingManualCancel = false;
        progressExpiresAt = 0;
    }

    private static void extractProgressHud(GuiGraphicsExtractor graphics) {
        CastingProgressPayload state = progress;
        Minecraft minecraft = Minecraft.getInstance();
        if (state == null || minecraft.player == null || minecraft.level == null) return;
        int left = Math.max(4, graphics.guiWidth() - 144);
        int bottom = Math.min(95, graphics.guiHeight() - 24);
        int headerLeft = Math.max(4, graphics.guiWidth() - 244);
        String title = "coins".equals(state.method()) ? "三錢" : "蓍草";
        title += state.manual() ? "・手動" : "・起卦";
        if (!progressVisual.isEmpty()) {
            graphics.item(progressVisual, headerLeft, 4);
            graphics.text(minecraft.font, progressVisual.getHoverName(), headerLeft + 20, 8, 0xFFFFFFFF);
        }
        graphics.text(minecraft.font, title + " " + state.lines().size() + "/6", headerLeft, 27, 0xFFFFE6A1);
        String[] positions = {"初爻", "二爻", "三爻", "四爻", "五爻", "上爻"};
        for (int index = 0; index < 6; index++) {
            int y = bottom - index * 9;
            boolean completed = index < state.lines().size();
            boolean current = state.active() && !completed && state.line() == index + 1;
            int value = completed ? state.lines().get(index) : 0;
            int color = completed ? (value == 6 || value == 9 ? 0xFFFFD467 : 0xFFFFFFFF)
                : (current ? 0xFFA9DDFF : 0xFF999999);
            String label = positions[index] + (completed ? " " + value + (value == 6 || value == 9 ? " 動" : "")
                : current ? " 起卦中" : " 未成");
            graphics.text(minecraft.font, label, left, y, color);
            int barX = graphics.guiWidth() - 58;
            if (completed) {
                if (value == 6 || value == 8) {
                    graphics.fill(barX, y + 3, barX + 17, y + 5, color);
                    graphics.fill(barX + 27, y + 3, barX + 44, y + 5, color);
                } else graphics.fill(barX, y + 3, barX + 44, y + 5, color);
            } else graphics.fill(barX, y + 4, barX + 44, y + 5, color & 0x66FFFFFF);
        }
        if (state.active() && !state.manual() && !animationVisual.isEmpty()) {
            // GUI item 使用完整模型與 tint；原版碎片粒子僅能取 sprite，無法表現模型 tint。
            double fraction = Math.clamp((System.nanoTime() - progressReceivedAt) / 500_000_000.0, 0, 1);
            double arc = Math.sin(Math.PI * fraction);
            int center = graphics.guiWidth() - 82;
            int baseY = bottom + 40;
            if ("coins".equals(state.method())) {
                for (int coin = -1; coin <= 1; coin++) {
                    graphics.item(animationVisual, center + coin * 22, baseY - (int)(arc * 12));
                }
            } else {
                int spread = 7 + (int)(arc * 15);
                graphics.item(animationVisual, center - spread, baseY - (int)(arc * 4));
                graphics.item(animationVisual, center + spread, baseY - (int)(arc * 4));
            }
            if (preferences.revealAnimation() && minecraft.gui.screen() == null) extractCentralAnimation(graphics, state);
        }
    }

    /** 完整 GUI item 模型保留木、金、綠銅及纖維加工的像素外觀；只呈現既有伺服器進度。 */
    private static void extractCentralAnimation(GuiGraphicsExtractor graphics, CastingProgressPayload state) {
        double fraction = Math.clamp((System.nanoTime() - progressReceivedAt) / 500_000_000.0, 0, 1);
        double arc = Math.sin(Math.PI * fraction);
        float centerX = graphics.guiWidth() / 2.0F;
        float centerY = graphics.guiHeight() / 2.0F + 13 - (float)(arc * 24);
        float scale = 2.1F + (float)(arc * 0.7);
        boolean coins = "coins".equals(state.method());
        for (int item = -1; item <= 1; item++) {
            if (!coins && item == 0) continue;
            float spread = coins ? 37 : 17 + (float)(arc * 35);
            graphics.pose().pushMatrix();
            graphics.pose().translate(centerX + item * spread, centerY);
            graphics.pose().rotate((float)(Math.sin(fraction * Math.PI * 2) * 0.38 * (item == 0 ? 1 : item)));
            graphics.pose().scale(scale, scale);
            graphics.item(animationVisual, -8, -8);
            graphics.pose().popMatrix();
        }
    }

    static ItemStack visualStack(CastingProgressPayload payload) {
        Identifier id = Identifier.tryParse(payload.visualItemId());
        return id == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(id)
            .map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    static ItemStack heldVisual(Minecraft minecraft, InteractionHand hand, YiguaMod.ToolItem expected) {
        if (minecraft.player != null && minecraft.player.getItemInHand(hand).getItem() == expected) {
            return minecraft.player.getItemInHand(hand);
        }
        return new ItemStack(expected);
    }

    static void cancelManual(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) return;
        awaitingManualCancel = true;
        minecraft.player.connection.sendCommand("yigua manual cancel");
    }
}

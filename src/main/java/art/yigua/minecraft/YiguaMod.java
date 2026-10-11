package art.yigua.minecraft;

import art.yigua.core.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.effect.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.*;
import static net.minecraft.commands.Commands.*;

/** 新增工具、起卦操作與原版書頁，正式結果與圖鑑由伺服器決定。 */
public final class YiguaMod implements ModInitializer {
    public static final String ID = "yigua";
    public static final Logger LOG = LoggerFactory.getLogger(ID);
    private static final SecureRandom RANDOM = new SecureRandom();
    public static Item COIN, THREE_COINS, WOOD_COIN, WOOD_THREE_COINS, IRON_COIN, IRON_THREE_COINS, GOLD_COIN, GOLD_THREE_COINS, GREEN_COIN, GREEN_THREE_COINS, YARROW, VINE_YARROW, ROASTED_YARROW, ROASTED_VINE_YARROW, WOOD_STALKS, GRASS_STALKS, GUIDE, ATLAS;
    public static Item TAIJI;
    private static final Map<String, Holder<MobEffect>> EFFECTS = new HashMap<>();
    private static final Map<String, String> PROFILE_ATTRIBUTES = new HashMap<>();
    private static final Map<String, GroupSharing.Bonus> SHAREABLE = new HashMap<>();
    private static Set<String> protectedAttributes = Set.of();
    private static Set<String> protectedEffects = Set.of();
    private static final Map<UUID, PendingBook> PENDING_BOOKS = new HashMap<>();
    private static PlayerLedger ledger;
    private static PartyLedger partyLedger;
    private static boolean effectsEnabled = true;
    private static boolean specialEventsEnabled = true;
    private static boolean groupSharingEnabled = true;
    private static boolean worldChangesEnabled;
    private static int ticksUntilSave;

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(ID, path); }

    @Override public void onInitialize() {
        Library.load();
        CastingProgressPayload.init();
        SkyOmenPayload.init();
        TaijiPanelPayload.init();
        SpecialEvents.init();
        ManualCasting.setProgressListener((player, progress) -> new CastingProgressPayload(
            progress.method(), progress.manual(), progress.line(), progress.change(), progress.remaining(),
            progress.lines(), progress.coins(), progress.active(), progress.hint(), progress.visualItemId()).send(player));
        readConfig();
        COIN = register("copper_coin", new Item(properties("copper_coin")));
        THREE_COINS = register("three_coins", new ToolItem(properties("three_coins").stacksTo(1), Method.COINS));
        WOOD_COIN = register("wooden_coin", new Item(properties("wooden_coin")));
        WOOD_THREE_COINS = register("wooden_three_coins", new ToolItem(properties("wooden_three_coins").stacksTo(1), Method.COINS, CastingMaterial.WOOD));
        IRON_COIN = register("iron_coin", new Item(properties("iron_coin")));
        IRON_THREE_COINS = register("iron_three_coins", new ToolItem(properties("iron_three_coins").stacksTo(1), Method.COINS, CastingMaterial.IRON));
        GOLD_COIN = register("gold_coin", new Item(properties("gold_coin")));
        GOLD_THREE_COINS = register("gold_three_coins", new ToolItem(properties("gold_three_coins").stacksTo(1), Method.COINS, CastingMaterial.GOLD));
        GREEN_COIN = register("green_copper_coin", new Item(properties("green_copper_coin")));
        GREEN_THREE_COINS = register("green_copper_three_coins", new ToolItem(properties("green_copper_three_coins").stacksTo(1), Method.COINS, CastingMaterial.GREEN_COPPER));
        YARROW = register("yarrow_stalks", new ToolItem(properties("yarrow_stalks").stacksTo(1), Method.YARROW));
        VINE_YARROW = register("vine_yarrow_stalks", new ToolItem(properties("vine_yarrow_stalks").stacksTo(1), Method.YARROW));
        ROASTED_YARROW = register("roasted_yarrow_stalks", new ToolItem(properties("roasted_yarrow_stalks").stacksTo(1), Method.YARROW));
        ROASTED_VINE_YARROW = register("roasted_vine_yarrow_stalks", new ToolItem(properties("roasted_vine_yarrow_stalks").stacksTo(1), Method.YARROW));
        WOOD_STALKS = register("wooden_stalks", new ToolItem(properties("wooden_stalks").stacksTo(1), Method.YARROW, CastingMaterial.WOOD_STALKS));
        GRASS_STALKS = register("grass_stalks", new ToolItem(properties("grass_stalks").stacksTo(1), Method.YARROW, CastingMaterial.GRASS_STALKS));
        GUIDE = register("overview_book", new BookItem(properties("overview_book").stacksTo(1), false));
        ATLAS = register("hexagram_atlas", new BookItem(properties("hexagram_atlas").stacksTo(1), true));
        TAIJI = register("taiji_tool", new TaijiItem(properties("taiji_tool").stacksTo(1)));
        registerEffect("travel", Attributes.MOVEMENT_SPEED, 0.1, true, 0x67a36c);
        registerEffect("caution", Attributes.MOVEMENT_SPEED, -0.1, false, 0xb27353);
        registerEffect("focus", Attributes.BLOCK_BREAK_SPEED, 0.1, true, 0x67a36c);
        registerEffect("fatigue", Attributes.BLOCK_BREAK_SPEED, -0.1, false, 0xb27353);
        registerEffect("strength", Attributes.ATTACK_DAMAGE, 1, true, 0x67a36c);
        registerEffect("restraint", Attributes.ATTACK_DAMAGE, -1, false, 0xb27353);
        registerEffect("guard", Attributes.ARMOR, 1, true, 0x67a36c);
        registerEffect("frail", Attributes.ARMOR, -1, false, 0xb27353);
        registerEffect("quick", Attributes.ATTACK_SPEED, 0.1, true, 0x8abfb5);
        registerEffect("slowhand", Attributes.ATTACK_SPEED, -0.1, false, 0x927267);
        registerEffect("luck", Attributes.LUCK, 1, true, 0x8abfb5);
        registerEffect("illluck", Attributes.LUCK, -1, false, 0x927267);
        registerEffect("lightness", Attributes.GRAVITY, 0.1, true, 0x8abfb5);
        registerEffect("heaviness", Attributes.GRAVITY, -0.1, false, 0x927267);
        registerEffect("landing", Attributes.FALL_DAMAGE_MULTIPLIER, 0.1, true, 0x8abfb5);
        registerEffect("fallrisk", Attributes.FALL_DAMAGE_MULTIPLIER, -0.1, false, 0x927267);
        registerEffect("airloss", null, -0.1, false, 0x536eb2);
        registerEffect("vitality", Attributes.MAX_HEALTH, 2, true, 0x67a36c);
        registerEffect("depletion", Attributes.MAX_HEALTH, -2, false, 0xb27353);
        registerEffect("leap", Attributes.JUMP_STRENGTH, 0.1, true, 0x8abfb5);
        registerEffect("heavyfeet", Attributes.JUMP_STRENGTH, -0.1, false, 0x927267);
        registerEffect("steady", Attributes.KNOCKBACK_RESISTANCE, 0.1, true, 0x8abfb5);
        registerEffect("exposed", Attributes.KNOCKBACK_RESISTANCE, -0.1, false, 0x927267);
        registerEffect("fireward", Attributes.BURNING_TIME, 0.1, true, 0x8abfb5);
        registerEffect("embers", Attributes.BURNING_TIME, -0.1, false, 0x927267);
        registerEffect("reach", Attributes.BLOCK_INTERACTION_RANGE, 0.5, true, 0x8abfb5);
        registerEffect("nearreach", Attributes.BLOCK_INTERACTION_RANGE, -0.5, false, 0x927267);
        registerEffect("breath", Attributes.OXYGEN_BONUS, 1, true, 0x8abfb5);
        registerEffect("deepwork", Attributes.SUBMERGED_MINING_SPEED, 0.1, true, 0x8abfb5);
        registerEffect("drownedwork", Attributes.SUBMERGED_MINING_SPEED, -0.1, false, 0x927267);
        registerEffect("closecombat", Attributes.ENTITY_INTERACTION_RANGE, 0.5, true, 0x8abfb5);
        registerEffect("direction", null, 0.1, true, 0x8abfb5);
        PROFILE_ATTRIBUTES.put("direction", Attributes.MOVEMENT_SPEED.unwrapKey().orElseThrow().identifier().toString());

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("main")),
            FabricCreativeModeTab.builder()
                .title(Component.translatable("tab.yigua"))
                .icon(() -> new ItemStack(COIN))
                .displayItems((parameters, entries) -> {
                    for (Item item : allItems()) entries.accept(item);
                })
                .build());
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            for (Item item : allItems()) entries.accept(item);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            CastingRitual.clear(); OxygenPenalty.clear(); DirectionalGuidance.clear(); SpecialEvents.clear();
            TemporaryWaterCorridor.load(server, server.getWorldPath(LevelResource.ROOT));
            ManualCasting.clear();
            PENDING_BOOKS.clear();
            ledger = new PlayerLedger(server);
            partyLedger = new PartyLedger(server);
            ticksUntilSave = 100;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TemporaryWaterCorridor.restoreAll(server);
            GroupSharing.clear(server);
            if (ledger != null) ledger.save();
            if (partyLedger != null) partyLedger.save();
        });
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> { if (ledger != null) ledger.save(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            CastingRitual.clear(); OxygenPenalty.clear(); DirectionalGuidance.clear(); SpecialEvents.clear(); ManualCasting.clear(); PENDING_BOOKS.clear(); ledger = null;
            TemporaryWaterCorridor.clear();
            GroupSharing.clear(server); partyLedger = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ledger == null) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PlayerLedger.State state = ledger.state(player.getUUID());
                int before = state.remainingTicks;
                ledger.tick(player.getUUID());
                if (before == FortunePhases.HALF_TICKS + 1 && state.controlledEffect == null && state.latest != null && state.latest.changedNumber() != 0) {
                    applyEffects(player, FortunePhases.resolve(state.latest, state.latestMaterial, state.latestEnvironment).changed(), FortunePhases.HALF_TICKS);
                    player.sendSystemMessage(Component.literal("卦象進入後五分鐘：第 " + state.latest.changedNumber() + " 卦「"
                        + CastingEngine.nameForNumber(state.latest.changedNumber()) + "」的變卦效果。")
                        .append(resultLink()));
                }
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PlayerLedger.State state = ledger.state(player.getUUID());
                double airLoss = 0;
                double directionStrength = 0;
                if (state.remainingTicks > 0 && effectsEnabled) {
                    for (Library.Rule rule : currentRules(state)) {
                        if (Set.of("airloss", "direction").contains(rule.profile()) && !effectProtected(rule.profile())) {
                            Holder<MobEffect> effect = EFFECTS.get(effectKey(rule.profile(), rule.amount() / baseAmount(rule.profile())));
                            if (effect != null && player.hasEffect(effect)) {
                                if (rule.profile().equals("airloss")) airLoss = Math.abs(rule.amount());
                                else directionStrength = Math.abs(rule.amount());
                            }
                        }
                    }
                }
                OxygenPenalty.tick(player, airLoss);
                DirectionalGuidance.tick(player, directionStrength);
            }
            GroupSharing.tick(server, ledger, partyLedger);
            SpecialEvents.tick(server);
            TemporaryWaterCorridor.tick(server);
            ManualCasting.tick(server);
            CastingRitual.tick(server);
            tickBookOpenings(server);
            if (--ticksUntilSave <= 0) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    PlayerLedger.State state = ledger.state(player.getUUID());
                    if (!player.isCreative() && !state.taijiClaimed && canUseTaiji(player)) {
                        give(player, new ItemStack(TAIJI)); state.taijiClaimed = true; ledger.markDirty();
                        player.sendSystemMessage(Component.literal("六十四卦、一般效果與秘效成就已收集完成；太極工具已解鎖，右鍵可選六爻、卦象、效果或解除狀態。"));
                    }
                }
                ledger.save(); ticksUntilSave = 100;
            }
        });
        ServerPlayerEvents.LEAVE.register(player -> {
            GroupSharing.clear(player);
            CastingRitual.cancel(player.getUUID());
            OxygenPenalty.cancel(player.getUUID());
            DirectionalGuidance.cancel(player.getUUID());
            ManualCasting.cancel(player.getUUID());
            PENDING_BOOKS.remove(player.getUUID());
            if (ledger != null) ledger.save();
        });
        ServerPlayerEvents.JOIN.register(player -> {
            if (ledger == null) return;
            DiscoveryAchievements.welcome(player);
            PlayerLedger.State state = ledger.state(player.getUUID());
            for (int number : state.discovered) DiscoveryAchievements.hexagram(player, number);
            // 玩家與世界資料重讀時依剩餘在線刻數恢復當前段，保留已被清除的狀態。
            boolean hadOwnEffect = EFFECTS.values().stream().anyMatch(player::hasEffect);
            if (hadOwnEffect && (state.latest != null || state.controlledEffect != null) && state.remainingTicks > 0) {
                int duration = state.controlledEffect == null && state.latest.changedNumber() != 0 && state.remainingTicks > FortunePhases.HALF_TICKS
                    ? state.remainingTicks - FortunePhases.HALF_TICKS : state.remainingTicks;
                applyEffects(player, currentRules(state), duration);
            }
            if (!state.welcomed) {
                give(player, new ItemStack(GUIDE));
                state.welcomed = true;
                ledger.markDirty(); ledger.save();
                player.sendSystemMessage(Component.literal("一卦：已贈送易經總覽書。右鍵閱讀教學與操作設定；拿著三錢或竹籌右鍵，可選開始起卦或練習。"));
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            dispatcher.register(literal("yigua")
                .executes(ctx -> show(ctx.getSource().getPlayerOrException(), "menu", 0))
                .then(literal("menu").executes(ctx -> show(ctx.getSource().getPlayerOrException(), "menu", 0)))
                .then(literal("traditional").executes(ctx -> show(ctx.getSource().getPlayerOrException(), "traditional", 0)))
                .then(literal("operation").executes(ctx -> show(ctx.getSource().getPlayerOrException(), "operation", 0)))
                .then(literal("catalog").executes(ctx -> show(ctx.getSource().getPlayerOrException(), "catalog", 0)))
                .then(literal("taiji")
                    .executes(ctx -> openTaiji(ctx.getSource().getPlayerOrException()))
                    .then(literal("claim").executes(ctx -> claimTaiji(ctx.getSource().getPlayerOrException())))
                    .then(literal("clear").executes(ctx -> taijiClear(ctx.getSource().getPlayerOrException())))
                    .then(literal("lines").then(argument("values", StringArgumentType.word())
                        .executes(ctx -> taijiLines(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "values")))))
                    .then(literal("hexagram").then(argument("number", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> taijiHexagram(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "number")))))
                    .then(literal("effect").then(argument("profile", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            if (!canUseTaiji(ctx.getSource().getPlayer())) return builder.buildFuture();
                            Library.generalProfiles().forEach(builder::suggest);
                            for (SpecialFortune.Event event : SpecialFortune.Event.values()) if (event != SpecialFortune.Event.NONE) builder.suggest(event.name().toLowerCase(Locale.ROOT));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> taijiEffect(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "profile"))))))
                .then(literal("sharing").executes(ctx -> show(ctx.getSource().getPlayerOrException(), "sharing", 0))
                    .then(literal("send").then(literal("on").executes(ctx -> sharing(ctx.getSource().getPlayerOrException(), true, true)))
                        .then(literal("off").executes(ctx -> sharing(ctx.getSource().getPlayerOrException(), true, false))))
                    .then(literal("receive").then(literal("on").executes(ctx -> sharing(ctx.getSource().getPlayerOrException(), false, true)))
                        .then(literal("off").executes(ctx -> sharing(ctx.getSource().getPlayerOrException(), false, false)))))
                .then(literal("group")
                    .then(literal("create").executes(ctx -> group(ctx.getSource().getPlayerOrException(), "create", "")))
                    .then(literal("join").then(argument("code", StringArgumentType.word())
                        .executes(ctx -> group(ctx.getSource().getPlayerOrException(), "join", StringArgumentType.getString(ctx, "code")))))
                    .then(literal("leave").executes(ctx -> group(ctx.getSource().getPlayerOrException(), "leave", "")))
                    .then(literal("status").executes(ctx -> show(ctx.getSource().getPlayerOrException(), "sharing", 0))))
                .then(literal("guide").executes(ctx -> show(ctx.getSource().getPlayerOrException(), "guide", 0)))
                .then(literal("atlas").executes(ctx -> show(ctx.getSource().getPlayerOrException(), "atlas", 0)))
                .then(literal("result").executes(ctx -> show(ctx.getSource().getPlayerOrException(), "result", 0)))
                .then(literal("cast")
                    .executes(ctx -> castHeldTool(ctx.getSource().getPlayerOrException(), InteractionHand.MAIN_HAND, false))
                    .then(literal("mainhand").executes(ctx -> castHeldTool(ctx.getSource().getPlayerOrException(), InteractionHand.MAIN_HAND, false))
                        .then(literal("instant").executes(ctx -> castInstant(ctx.getSource().getPlayerOrException(), InteractionHand.MAIN_HAND, false))))
                    .then(literal("offhand").executes(ctx -> castHeldTool(ctx.getSource().getPlayerOrException(), InteractionHand.OFF_HAND, false))
                        .then(literal("instant").executes(ctx -> castInstant(ctx.getSource().getPlayerOrException(), InteractionHand.OFF_HAND, false)))))
                .then(literal("practice")
                    .executes(ctx -> castHeldTool(ctx.getSource().getPlayerOrException(), InteractionHand.MAIN_HAND, true))
                    .then(literal("mainhand").executes(ctx -> castHeldTool(ctx.getSource().getPlayerOrException(), InteractionHand.MAIN_HAND, true))
                        .then(literal("instant").executes(ctx -> castInstant(ctx.getSource().getPlayerOrException(), InteractionHand.MAIN_HAND, true))))
                    .then(literal("offhand").executes(ctx -> castHeldTool(ctx.getSource().getPlayerOrException(), InteractionHand.OFF_HAND, true))
                        .then(literal("instant").executes(ctx -> castInstant(ctx.getSource().getPlayerOrException(), InteractionHand.OFF_HAND, true)))))
                .then(literal("manual")
                    .then(literal("start")
                        .then(literal("mainhand")
                            .executes(ctx -> ManualCasting.start(ctx.getSource().getPlayerOrException(), InteractionHand.MAIN_HAND, false) ? 1 : 0)
                            .then(literal("practice").executes(ctx -> ManualCasting.start(ctx.getSource().getPlayerOrException(), InteractionHand.MAIN_HAND, true) ? 1 : 0)))
                        .then(literal("offhand")
                            .executes(ctx -> ManualCasting.start(ctx.getSource().getPlayerOrException(), InteractionHand.OFF_HAND, false) ? 1 : 0)
                            .then(literal("practice").executes(ctx -> ManualCasting.start(ctx.getSource().getPlayerOrException(), InteractionHand.OFF_HAND, true) ? 1 : 0))))
                    .then(literal("throw").executes(ctx -> ManualCasting.toss(ctx.getSource().getPlayerOrException()) ? 1 : 0))
                    .then(literal("split").then(argument("left", IntegerArgumentType.integer())
                        .executes(ctx -> ManualCasting.split(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "left")) ? 1 : 0)))
                    .then(literal("cancel").executes(ctx -> { ManualCasting.cancel(ctx.getSource().getPlayerOrException().getUUID()); return 1; })))
                .then(literal("read").then(argument("number", IntegerArgumentType.integer(1, 64))
                    .executes(ctx -> show(ctx.getSource().getPlayerOrException(), "read", IntegerArgumentType.getInteger(ctx, "number")))))
                .then(literal("status").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    PlayerLedger.State s = ledger.state(p.getUUID());
                    p.sendSystemMessage(Component.literal("一卦圖鑑 " + s.discovered.size() + "/64；正式起卦冷卻約 " + (s.remainingTicks + 19) / 20 + " 秒在線時間。"));
                    return 1;
                }))
                .then(literal("kit").requires(source -> source.getPlayer() != null && source.getPlayer().isCreative())
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        for (Item item : List.of(THREE_COINS, YARROW, GUIDE, ATLAS)) give(p, new ItemStack(item));
                        p.sendSystemMessage(Component.literal("一卦：已給予創造模式測試工具。"));
                        return 1;
                    })));
        });
        LOG.info("一卦 {}：64卦、384爻、圖鑑與書頁已載入；效果啟用={}。",
            FabricLoader.getInstance().getModContainer("yigua").orElseThrow().getMetadata().getVersion().getFriendlyString(), effectsEnabled);
    }

    private static Item.Properties properties(String name) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(name)));
    }
    private static Item register(String name, Item item) { return Registry.register(BuiltInRegistries.ITEM, id(name), item); }

    private static List<Item> allItems() {
        return List.of(GUIDE, ATLAS, WOOD_COIN, WOOD_THREE_COINS, IRON_COIN, IRON_THREE_COINS,
            COIN, THREE_COINS, GREEN_COIN, GREEN_THREE_COINS, GOLD_COIN, GOLD_THREE_COINS,
            GRASS_STALKS, WOOD_STALKS, YARROW, VINE_YARROW, ROASTED_YARROW, ROASTED_VINE_YARROW, TAIJI);
    }

    private static double baseAmount(String profile) {
        return Library.baseAmount(profile);
    }

    private static String effectKey(String profile, double scale) {
        long fixed = Math.round(scale * 10000);
        return fixed == 10000 ? profile : profile + "_x" + fixed;
    }

    private static void registerEffect(String name, Holder<Attribute> attribute, double amount, boolean positive, int color) {
        if (attribute != null) PROFILE_ATTRIBUTES.put(name, attribute.unwrapKey().orElseThrow().identifier().toString());
        class FortuneEffect extends MobEffect {
            FortuneEffect() { super(positive ? MobEffectCategory.BENEFICIAL : MobEffectCategory.HARMFUL, color); }
        }
        boolean inverse = Set.of("lightness", "heaviness", "landing", "fallrisk", "fireward", "embers").contains(name);
        AttributeModifier.Operation operation = Math.abs(amount) == 0.1 && !Set.of("steady", "exposed").contains(name)
            ? AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL : AttributeModifier.Operation.ADD_VALUE;
        if (positive && attribute != null) SHAREABLE.put(name, new GroupSharing.Bonus(name, attribute, amount * (inverse ? -1 : 1), operation));
        Set<Long> scales = new TreeSet<>();
        for (CastingMaterial material : CastingMaterial.values()) {
            for (double rarity : new double[] {1, 2, 2.5, 3, 3.5, 4}) {
                for (double environment : new double[] {0.90, 0.92, 0.94, 0.96, 0.98, 1, 1.02, 1.04, 1.06, 1.08, 1.10}) {
                    scales.add(Math.round(material.multiplier() * rarity * environment * 10000));
                }
            }
        }
        for (long fixed : scales) {
            double scale = fixed / 10000.0;
            String key = effectKey(name, scale);
            MobEffect effect = new FortuneEffect();
            if (attribute != null) effect.addAttributeModifier(attribute, id("fortune_" + key), amount * scale * (inverse ? -1 : 1),
                operation);
            EFFECTS.put(key, Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, id(key), effect));
        }
    }

    private static void readConfig() {
        Path config = FabricLoader.getInstance().getConfigDir().resolve("yigua.json");
        try {
            if (!Files.exists(config)) Files.writeString(config,
                "{\n  \"effects_enabled\": true,\n  \"special_events_enabled\": true,\n  \"group_sharing_enabled\": true,\n  \"protected_attributes\": [],\n  \"protected_effects\": []\n}\n");
            JsonObject o = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            effectsEnabled = o.has("effects_enabled") && o.get("effects_enabled").getAsBoolean();
            specialEventsEnabled = !o.has("special_events_enabled") || o.get("special_events_enabled").getAsBoolean();
            groupSharingEnabled = !o.has("group_sharing_enabled") || o.get("group_sharing_enabled").getAsBoolean();
            // 方塊權限沒有通用查詢協定；獨立伺服器預設不改地形，由服主明確開啟。
            worldChangesEnabled = o.has("world_changes_enabled") ? o.get("world_changes_enabled").getAsBoolean()
                : FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
            protectedAttributes = protectionList(o, "protected_attributes", true);
            protectedEffects = protectionList(o, "protected_effects", false);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("一卦設定無法讀取：" + config, e);
        }
    }

    private static Set<String> protectionList(JsonObject object, String key, boolean attributes) {
        if (!object.has(key)) return Set.of();
        Set<String> names = new HashSet<>();
        for (JsonElement value : object.getAsJsonArray(key)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(key + " 必須是名稱字串陣列");
            }
            String name = value.getAsString();
            if (attributes ? Identifier.tryParse(name) == null
                    : !(Library.profiles().contains(name) || Arrays.stream(SpecialFortune.Event.values())
                        .anyMatch(event -> event != SpecialFortune.Event.NONE && event.name().toLowerCase(Locale.ROOT).equals(name)))) {
                throw new IllegalArgumentException(key + " 含無效名稱：" + name);
            }
            names.add(name);
        }
        return Set.copyOf(names);
    }

    public static boolean effectProtected(String profile) {
        String attribute = PROFILE_ATTRIBUTES.get(profile);
        return protectedEffects.contains(profile) || (attribute != null && protectedAttributes.contains(attribute));
    }

    public static boolean specialAllowed(SpecialFortune.Event event) {
        return effectsEnabled && specialEventsEnabled && (event != SpecialFortune.Event.SEA || worldChangesEnabled)
            && !effectProtected(event.name().toLowerCase(Locale.ROOT));
    }

    public static boolean sharingEnabled() { return effectsEnabled && groupSharingEnabled; }

    private static List<Library.Rule> currentRules(PlayerLedger.State state) {
        if (state.remainingTicks <= 0) return List.of();
        if (state.controlledEffect != null) {
            return Library.generalProfiles().contains(state.controlledEffect) ? List.of(controlledRule(state.controlledEffect)) : List.of();
        }
        return state.latest == null ? List.of() : FortunePhases.resolve(state.latest, state.latestMaterial, state.latestEnvironment).current(state.remainingTicks);
    }

    private static Library.Rule controlledRule(String profile) {
        return new Library.Rule("taiji_" + profile, 0, 0, profile, baseAmount(profile), 12000, 0, "", "太極指定效果；不新增卦象圖鑑。");
    }

    private static boolean taijiHeld(ServerPlayer player) {
        return Arrays.stream(InteractionHand.values()).anyMatch(hand -> player.getItemInHand(hand).is(TAIJI));
    }
    private static boolean authorizeTaiji(ServerPlayer player) {
        if (canUseTaiji(player) && taijiHeld(player)) return true;
        player.sendSystemMessage(Component.literal("太極工具需手持使用：創造模式，或正式收集全64卦及32種一般、8種秘效成就後解鎖。")); return false;
    }
    private static int openTaiji(ServerPlayer player) {
        if (!authorizeTaiji(player)) return 0;
        TaijiPanelPayload.send(player); return 1;
    }
    private static int claimTaiji(ServerPlayer player) {
        if (!canUseTaiji(player)) { player.sendSystemMessage(Component.literal("尚未完成太極工具解鎖條件。")); return 0; }
        if (taijiHeld(player)) { player.sendSystemMessage(Component.literal("手上已有太極工具。")); return 0; }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(TAIJI)) { player.sendSystemMessage(Component.literal("背包已有太極工具。")); return 0; }
        }
        give(player, new ItemStack(TAIJI));
        PlayerLedger.State state = ledger.state(player.getUUID()); state.taijiClaimed = true;
        ledger.markDirty(); ledger.save(); return 1;
    }

    private static void clearFortune(ServerPlayer player, boolean stopReceiving) {
        clearFortune(player, stopReceiving, false);
    }
    private static void clearFortune(ServerPlayer player, boolean stopReceiving, boolean replacing) {
        CastingRitual.cancel(player.getUUID()); ManualCasting.cancel(player.getUUID());
        PENDING_BOOKS.remove(player.getUUID());
        OxygenPenalty.cancel(player.getUUID()); DirectionalGuidance.cancel(player.getUUID());
        // 更換效果由最後一次 applyEffects 完成交接；先移除生命上限會讓原生提早扣血。
        if (!replacing) applyEffects(player, List.of(), 0);
        SpecialEvents.cancel(player);
        if (!replacing) GroupSharing.clear(player);
        PlayerLedger.State state = ledger.state(player.getUUID());
        state.remainingTicks = 0; state.controlledEffect = null; state.taijiSelected = false;
        if (stopReceiving) state.shareReceive = false;
        ledger.markDirty(); ledger.save();
        if (!replacing) GroupSharing.tick(player.level().getServer(), ledger, partyLedger);
    }
    private static int taijiClear(ServerPlayer player) {
        if (!authorizeTaiji(player)) return 0;
        clearFortune(player, true);
        player.sendSystemMessage(Component.literal("已解除本人的一卦效果、起卦流程與冷卻，並關閉接受共享。圖鑑、成就及其他來源效果保留。")); return 1;
    }
    private static int taijiLines(ServerPlayer player, String values) {
        if (!authorizeTaiji(player)) return 0;
        if (!values.matches("[6789]{6}")) { player.sendSystemMessage(Component.literal("六爻請由初爻往上填六位6、7、8或9。")); return 0; }
        return taijiCast(player, CastingEngine.fromLines(Method.COINS, values.chars().map(value -> value - '0').boxed().toList()));
    }
    private static int taijiHexagram(ServerPlayer player, int number) {
        if (!authorizeTaiji(player)) return 0;
        int mask = CastingEngine.maskForNumber(number);
        List<Integer> values = new ArrayList<>();
        for (int line = 0; line < 6; line++) values.add((mask & (1 << line)) == 0 ? 8 : 7);
        return taijiCast(player, CastingEngine.fromLines(Method.COINS, values));
    }
    private static int taijiCast(ServerPlayer player, CastResult result) {
        clearFortune(player, false, true);
        ledger.record(player.getUUID(), result, CastingMaterial.COPPER, 1);
        PlayerLedger.State state = ledger.state(player.getUUID()); state.taijiSelected = true; ledger.markDirty(); ledger.save();
        FortunePhases.Plan plan = FortunePhases.resolve(result, CastingMaterial.COPPER, 1);
        applyEffects(player, plan.primary(), result.changedNumber() == 0 ? 12000 : 6000);
        RareFortune.Outcome fortune = RareFortune.resolve(result, CastingMaterial.COPPER);
        player.sendSystemMessage(Component.literal("太極指定六爻，並非隨機卜筮：第" + result.primaryNumber() + "卦「" + CastingEngine.nameForNumber(result.primaryNumber()) + "」。").append(resultLink()));
        player.sendSystemMessage(Component.literal(String.join("；", plan.primary().stream().map(YiguaMod::presentationRule).map(Library::effectSummary).toList())));
        if (!plan.changed().isEmpty()) player.sendSystemMessage(Component.literal("後五分鐘：" + String.join("；", plan.changed().stream().map(YiguaMod::presentationRule).map(Library::effectSummary).toList())));
        if (effectsEnabled && specialEventsEnabled) SpecialEvents.reveal(player, result, fortune);
        GroupSharing.tick(player.level().getServer(), ledger, partyLedger);
        return 1;
    }
    private static int taijiEffect(ServerPlayer player, String profile) {
        if (!authorizeTaiji(player)) return 0;
        SpecialFortune.Event event = Arrays.stream(SpecialFortune.Event.values()).filter(value -> value != SpecialFortune.Event.NONE
            && value.name().toLowerCase(Locale.ROOT).equals(profile)).findFirst().orElse(null);
        if (!Library.generalProfiles().contains(profile) && event == null) { player.sendSystemMessage(Component.literal("未知的太極效果。")); return 0; }
        if (event == null ? !effectsEnabled || effectProtected(profile) : !specialAllowed(event)) {
            player.sendSystemMessage(Component.literal("此效果由伺服器設定保護或停用。")); return 0;
        }
        clearFortune(player, false, true);
        PlayerLedger.State state = ledger.state(player.getUUID()); state.controlledEffect = profile; state.taijiSelected = true;
        state.remainingTicks = event == null ? 12000 : 400; ledger.markDirty(); ledger.save();
        if (event == null) applyEffects(player, List.of(controlledRule(profile)), 12000);
        else { applyEffects(player, List.of(), 0); SpecialEvents.apply(player, event); }
        GroupSharing.tick(player.level().getServer(), ledger, partyLedger);
        player.sendSystemMessage(Component.literal("太極直接指定效果「" + profile + "」；不新增卦象紀錄。").append(resultLink())); return 1;
    }

    /** 解鎖由伺服器成就與正式卦象紀錄決定；取得物品本身不等於取得權限。 */
    public static boolean canUseTaiji(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isSpectator() || player.hasDisconnected()) return false;
        if (player.isCreative()) return true;
        if (ledger == null || ledger.state(player.getUUID()).discovered.size() != 64) return false;
        List<String> paths = new ArrayList<>();
        for (int number = 1; number <= 64; number++) paths.add("hexagrams/" + String.format(Locale.ROOT, "%02d", number));
        for (String profile : Library.generalProfiles()) paths.add("effects/" + profile);
        for (SpecialFortune.Event event : SpecialFortune.Event.values()) {
            if (event != SpecialFortune.Event.NONE) paths.add("effects/secret_" + event.name().toLowerCase(Locale.ROOT));
        }
        return paths.stream().allMatch(path -> {
            var advancement = player.level().getServer().getAdvancements().get(id(path));
            return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
        });
    }

    static Map<String, GroupSharing.Bonus> shareableBonuses(ServerPlayer player, PlayerLedger.State state) {
        Map<String, GroupSharing.Bonus> bonuses = new HashMap<>();
        for (Library.Rule rule : FortunePhases.resolve(state.latest, state.latestMaterial, state.latestEnvironment).current(state.remainingTicks)) {
            GroupSharing.Bonus spec = SHAREABLE.get(rule.profile());
            if (spec == null || effectProtected(rule.profile())) continue;
            Holder<MobEffect> holder = EFFECTS.get(effectKey(rule.profile(), rule.amount() / baseAmount(rule.profile())));
            MobEffectInstance active = holder == null ? null : player.getEffect(holder);
            AttributeInstance attribute = player.getAttribute(spec.attribute());
            if (active == null || active.getDuration() <= 0 || attribute == null) continue;
            AttributeModifier modifier = attribute.getModifier(id("fortune_" + effectKey(rule.profile(), rule.amount() / baseAmount(rule.profile()))));
            if (modifier != null) bonuses.put(rule.profile(), new GroupSharing.Bonus(rule.profile(), spec.attribute(), modifier.amount(), modifier.operation()));
        }
        return bonuses;
    }

    static double ownBonusAmount(ServerPlayer player, String profile, Holder<Attribute> holder, AttributeModifier.Operation operation) {
        AttributeInstance attribute = player.getAttribute(holder);
        if (attribute == null) return 0;
        double combined = operation == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL ? 1 : 0;
        String key = "fortune_" + profile;
        for (AttributeModifier modifier : attribute.getModifiers()) {
            String path = modifier.id().getPath();
            if (!modifier.id().getNamespace().equals(ID) || !(path.equals(key) || path.startsWith(key + "_x"))
                    || modifier.operation() != operation) continue;
            if (operation == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) combined *= 1 + modifier.amount();
            else combined += modifier.amount();
        }
        return operation == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL ? combined - 1 : combined;
    }

    private static int sharing(ServerPlayer player, boolean send, boolean enabled) {
        PlayerLedger.State state = ledger.state(player.getUUID());
        if (send) state.shareSend = enabled; else state.shareReceive = enabled;
        ledger.markDirty(); ledger.save();
        GroupSharing.tick(player.level().getServer(), ledger, partyLedger);
        player.sendSystemMessage(Component.literal((send ? "送出" : "接受") + "群組普通增益：" + (enabled ? "開啟" : "關閉") + "。"));
        return show(player, "sharing", 0);
    }

    private static int group(ServerPlayer player, String action, String code) {
        try {
            switch (action) {
                case "create" -> player.sendSystemMessage(Component.literal("已建立一卦群組，邀請碼：" + partyLedger.create(player.getUUID()) + "。分享邀請碼後，對方輸入 /yigua group join 邀請碼。"));
                case "join" -> { partyLedger.join(player.getUUID(), code); player.sendSystemMessage(Component.literal("已加入一卦群組；送出與接受共享需各自開啟。")); }
                case "leave" -> { partyLedger.leave(player.getUUID()); player.sendSystemMessage(Component.literal("已離開一卦群組。")); }
                default -> throw new IllegalArgumentException("未知群組操作");
            }
            GroupSharing.tick(player.level().getServer(), ledger, partyLedger);
            return show(player, "sharing", 0);
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(Component.literal(e.getMessage())); return 0;
        }
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false, Prediction.SERVER_ONLY);
    }

    private static InteractionHand carrierHand(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            Item item = player.getItemInHand(hand).getItem();
            if (item instanceof ToolItem || item == GUIDE || item == ATLAS || item == TAIJI) return hand;
        }
        return null;
    }

    private static int show(ServerPlayer player, String view, int number) {
        InteractionHand hand = carrierHand(player);
        if (hand == null) {
            give(player, new ItemStack(view.equals("atlas") ? ATLAS : GUIDE));
            player.sendSystemMessage(Component.literal("一卦：已給予書本，請拿在手上按右鍵閱讀。"));
            return 1;
        }
        PlayerLedger.State state = ledger.state(player.getUUID());
        WrittenBookContent content = switch (view) {
            case "menu" -> BookBuilder.menu();
            case "guide" -> BookBuilder.guide();
            case "traditional" -> BookBuilder.traditionalOptions();
            case "operation" -> BookBuilder.operationOptions();
            case "catalog" -> BookBuilder.catalog();
            case "sharing" -> BookBuilder.sharingOptions(state.shareSend, state.shareReceive,
                partyLedger.group(player.getUUID()), partyLedger.members(player.getUUID()).size(), sharingEnabled());
            case "atlas" -> BookBuilder.atlas(state.discovered, state.counts);
            case "read" -> BookBuilder.reading(number);
            case "result" -> state.controlledEffect != null ? BookBuilder.controlledEffect(state.controlledEffect, state.remainingTicks)
                : state.latest == null ? BookBuilder.menu()
                : resultBook(state);
            default -> BookBuilder.menu();
        };
        // 書內指令會隨後關掉原畫面；稍後重新開啟已同步的新首頁。
        prepareBook(player, hand, content);
        PENDING_BOOKS.put(player.getUUID(), new PendingBook(player, hand, player.getItemInHand(hand), 3));
        return 1;
    }

    private static void open(ServerPlayer player, InteractionHand hand, WrittenBookContent content) {
        PENDING_BOOKS.remove(player.getUUID());
        prepareBook(player, hand, content);
        player.openItemGui(player.getItemInHand(hand), hand);
    }

    private static void prepareBook(ServerPlayer player, InteractionHand hand, WrittenBookContent content) {
        ItemStack stack = player.getItemInHand(hand);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        player.containerMenu.broadcastChanges();
        // 開書封包只指定手別；先同步該手的書頁，客戶端才會以新內容建立首頁。
        int slot = hand == InteractionHand.MAIN_HAND
            ? player.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
        player.connection.send(new ClientboundSetPlayerInventoryPacket(slot, stack.copy()));
    }

    private record PendingBook(ServerPlayer player, InteractionHand hand, ItemStack stack, int ticks) {}

    private static void tickBookOpenings(MinecraftServer server) {
        for (UUID id : List.copyOf(PENDING_BOOKS.keySet())) {
            PendingBook pending = PENDING_BOOKS.get(id);
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != pending.player() || !player.isAlive()
                    || player.getItemInHand(pending.hand()) != pending.stack()) {
                PENDING_BOOKS.remove(id);
            } else if (pending.ticks() <= 1) {
                PENDING_BOOKS.remove(id);
                player.openItemGui(pending.stack(), pending.hand());
            } else {
                PENDING_BOOKS.put(id, new PendingBook(player, pending.hand(), pending.stack(), pending.ticks() - 1));
            }
        }
    }

    private static Library.Rule presentationRule(Library.Rule rule) {
        if (effectsEnabled && !effectProtected(rule.profile())) return rule;
        return new Library.Rule("disabled", rule.number(), rule.line(), "neutral", 0, 0, rule.priority(),
            effectsEnabled ? "本效果涉及受保護設定，已讓其他模組或整合包優先。" : "本世界已關閉卦象效果，圖鑑與原文閱讀仍可使用。", rule.origText());
    }

    private static int castHeldTool(ServerPlayer player, InteractionHand hand, boolean practice) {
        if (ledger == null || !player.isAlive()) return 0;
        if (!(player.getItemInHand(hand).getItem() instanceof ToolItem tool)) {
            player.sendSystemMessage(Component.literal("一卦：請先把三錢或竹籌拿在指定的手上。"));
            return 0;
        }
        return tool.cast(player, hand, practice);
    }

    private static int castInstant(ServerPlayer player, InteractionHand hand, boolean practice) {
        if (!canBeginCasting(player, practice)) return 0;
        if (!(player.getItemInHand(hand).getItem() instanceof ToolItem tool)) {
            player.sendSystemMessage(Component.literal("一卦：請先把筮具拿在指定的手上。")); return 0;
        }
        return finishCast(player, hand, practice, CastingEngine.cast(tool.method(), RANDOM), tool.material(), true);
    }

    private static Component resultLink() {
        return Component.literal(" [查看最近正式卦]").withStyle(style -> style
            .withColor(ChatFormatting.AQUA).withUnderlined(true)
            .withClickEvent(new ClickEvent.RunCommand("/yigua result")));
    }

    private static void applyEffects(ServerPlayer player, List<Library.Rule> rules, int remainingDuration) {
        Map<Holder<MobEffect>, Library.Rule> desired = new LinkedHashMap<>();
        if (effectsEnabled && remainingDuration > 0) {
            for (Library.Rule rule : rules) {
                double base = baseAmount(rule.profile());
                if (base == 0 || effectProtected(rule.profile())) continue;
                Holder<MobEffect> effect = EFFECTS.get(effectKey(rule.profile(), rule.amount() / base));
                if (effect == null) throw new IllegalStateException("未註冊的卦象強度：" + rule.profile() + " " + rule.amount());
                desired.put(effect, rule);
            }
        }
        // 新的正向生命上限先加入，避免切換倍率時產生短暫低谷；不直接補血。
        GroupSharing.beforeOwnEffectsChanged(player, desired.values().stream()
            .filter(rule -> rule.profile().equals("vitality")).mapToDouble(Library.Rule::amount).max().orElse(0));
        Set<Holder<MobEffect>> addedFirst = new HashSet<>();
        for (var entry : desired.entrySet()) {
            if (entry.getValue().profile().equals("vitality") && !player.hasEffect(entry.getKey())) {
                applyEffect(player, entry.getKey(), entry.getValue(), remainingDuration);
                addedFirst.add(entry.getKey());
            }
        }
        // 相同強度的效果保留，重登與換段不會先移除再重建 MAX_HEALTH。
        // 指令或外部模組可能同時加入正負上限；先移除舊負向，避免短暫低谷。
        for (var entry : EFFECTS.entrySet()) {
            if ((entry.getKey().equals("depletion") || entry.getKey().startsWith("depletion_x"))
                    && !desired.containsKey(entry.getValue()) && player.hasEffect(entry.getValue())) {
                player.removeEffect(entry.getValue());
            }
        }
        for (Holder<MobEffect> effect : EFFECTS.values()) {
            if (!desired.containsKey(effect) && player.hasEffect(effect)) player.removeEffect(effect);
        }
        for (var entry : desired.entrySet()) {
            if (!addedFirst.contains(entry.getKey())) applyEffect(player, entry.getKey(), entry.getValue(), remainingDuration);
        }
    }

    private static void applyEffect(ServerPlayer player, Holder<MobEffect> effect, Library.Rule rule, int remainingDuration) {
        MobEffectInstance instance = new MobEffectInstance(effect, Math.min(rule.durationTicks(), remainingDuration), 0, false, true, true);
        if (player.hasEffect(effect)) {
            // 原版在移除／重加 modifier 後才同步屬性；精確更新剩餘時長且不產生中間上限。
            player.forceAddEffect(instance, null);
            DiscoveryAchievements.effect(player, rule.profile());
        } else if (player.addEffect(instance)) {
            DiscoveryAchievements.effect(player, rule.profile());
        }
    }

    private static WrittenBookContent resultBook(PlayerLedger.State state) {
        RareFortune.Outcome fortune = RareFortune.resolve(state.latest, state.latestMaterial);
        FortunePhases.Plan plan = FortunePhases.resolve(state.latest, state.latestMaterial, state.latestEnvironment);
        String context = state.latestMaterial.displayName() + " " + state.latestMaterial.multiplier()
            + " 倍；環境會微幅修正效果，每項不超過3%、合計不超過10%，權重自行探索。"
            + (fortune.rare() ? "稀有事件：" + fortune.name() + "；標準隨機法參考機率 "
                + fortune.numerator() + "/" + fortune.denominator() + "。人工分堆會改變實際機率。" : "");
        if (state.taijiSelected) context = "太極指定六爻，並非隨機卜筮。\n" + context;
        return BookBuilder.result(state.latest, plan.primary().stream().map(YiguaMod::presentationRule).toList(),
            plan.changed().stream().map(YiguaMod::presentationRule).toList(), state.remainingTicks,
            fortune.rare() ? fortune.name() : "", context);
    }

    private static void announce(ServerPlayer player, CastResult result, RareFortune.Outcome fortune,
                                 FortunePhases.Plan plan, CastingMaterial material, EnvironmentContext environment,
                                 boolean practice, PlayerLedger.State state) {
        String changing = result.changingLines().isEmpty() ? "無動爻" : "動爻（由下往上）：" + result.changingLines();
        String changed = result.changedNumber() == 0 ? "" : "；變卦為第 " + result.changedNumber()
            + " 卦「" + CastingEngine.nameForNumber(result.changedNumber()) + "」";
        player.sendSystemMessage(Component.literal((practice ? "練習卦：" : "你卜得") + "第 "
            + result.primaryNumber() + " 卦「" + CastingEngine.nameForNumber(result.primaryNumber())
            + "」；" + changing + changed + "。"));
        if (fortune.rare()) player.sendSystemMessage(Component.literal("特殊卦象「" + fortune.name()
            + "」；標準隨機法參考機率 " + fortune.numerator() + "/" + fortune.denominator()
            + "，人工選擇分堆會改變實際機率。"));
        String effects = String.join("；", plan.primary().stream().map(YiguaMod::presentationRule)
            .map(Library::effectSummary).toList());
        player.sendSystemMessage(Component.literal((practice ? "效果示例：" : "") + effects
            + (practice ? "；練習不生效、不記圖鑑、不刷新冷卻。" : (plan.changed().isEmpty() ? "；持續十分鐘。" : "；前五分鐘本卦效果。") + "圖鑑 " + state.discovered.size() + "/64。"))
            .append(practice ? Component.empty() : resultLink()));
        if (!plan.changed().isEmpty()) {
            String changedEffects = String.join("；", plan.changed().stream().map(YiguaMod::presentationRule)
                .map(Library::effectSummary).toList());
            player.sendSystemMessage(Component.literal((practice ? "後五分鐘示例：" : "後五分鐘變卦：") + changedEffects));
        }
        player.sendSystemMessage(Component.literal(material.displayName() + " " + material.multiplier()
            + " 倍；" + environment.description()));
    }

    public static boolean canBeginCasting(ServerPlayer player, boolean practice) {
        if (ledger == null || !player.isAlive() || player.hasDisconnected()) return false;
        if (CastingRitual.isActive(player.getUUID()) || ManualCasting.isActive(player.getUUID())) {
            player.sendSystemMessage(Component.literal("目前起卦尚未完成，完成或取消後再開始。"), true);
            return false;
        }
        PlayerLedger.State state = ledger.state(player.getUUID());
        if (!practice && state.remainingTicks > 0 && state.controlledEffect != null) {
            player.sendSystemMessage(Component.literal("太極指定效果仍在倒數；可等待結束或以太極工具解除狀態。")); return false;
        }
        if (!practice && state.remainingTicks > 0 && state.latest != null) {
            player.sendSystemMessage(Component.literal("本次正式卦仍為第 " + state.latest.primaryNumber()
                + " 卦「" + CastingEngine.nameForNumber(state.latest.primaryNumber())
                + "」，冷卻約剩 " + (state.remainingTicks + 19) / 20 + " 秒在線時間。")
                .append(resultLink()));
            return false;
        }
        return true;
    }

    /** 手動流程已依實際操作形成六爻；此入口只提交結果，不再隨機重抽。 */
    public static boolean completeManualCast(ServerPlayer player, InteractionHand hand, boolean practice,
                                         CastResult result, CastingMaterial material) {
        return finishCast(player, hand, practice, result, material) > 0;
    }

    private static int finishCast(ServerPlayer player, InteractionHand hand, boolean practice,
                                  CastResult result, CastingMaterial material) {
        return finishCast(player, hand, practice, result, material, false);
    }

    private static int finishCast(ServerPlayer player, InteractionHand hand, boolean practice,
                                  CastResult result, CastingMaterial material, boolean instant) {
        if (!canBeginCasting(player, practice) || !material.compatibleWith(result.method())) return 0;
        PENDING_BOOKS.remove(player.getUUID());
        PlayerLedger.State state = ledger.state(player.getUUID());
        EnvironmentContext environment = EnvironmentContext.capture(player);
        RareFortune.Outcome fortune = RareFortune.resolve(result, material);
        FortunePhases.Plan plan = FortunePhases.resolve(result, material, environment.multiplier());
        if (!practice) {
            ledger.record(player.getUUID(), result, material, environment.multiplier());
            DiscoveryAchievements.hexagram(player, result.primaryNumber());
            applyEffects(player, plan.primary(), result.changedNumber() == 0
                ? FortunePhases.TOTAL_TICKS : FortunePhases.HALF_TICKS);
        }
        ItemStack visual = result.method() == Method.COINS ? new ItemStack(coinFor(material))
            : player.getItemInHand(hand).copy();
        Runnable reveal = () -> {
            announce(player, result, fortune, plan, material, environment, practice, state);
            if (!practice && effectsEnabled && specialEventsEnabled) SpecialEvents.reveal(player, result, fortune);
        };
        if (instant || !CastingRitual.start(player, result, visual, player.getItemInHand(hand), reveal)) reveal.run();
        return 1;
    }

    private static Item coinFor(CastingMaterial material) {
        return switch (material) {
            case WOOD -> WOOD_COIN; case IRON -> IRON_COIN; case GOLD -> GOLD_COIN;
            case GREEN_COPPER -> GREEN_COIN; default -> COIN;
        };
    }

    public static final class ToolItem extends Item {
        private final Method method;
        private final CastingMaterial material;
        public ToolItem(Properties properties, Method method) {
            this(properties, method, method == Method.COINS ? CastingMaterial.COPPER : CastingMaterial.BAMBOO);
        }
        public ToolItem(Properties properties, Method method, CastingMaterial material) {
            super(properties); this.method = method; this.material = material;
            if (!material.compatibleWith(method)) throw new IllegalArgumentException("筮法與材質不符");
        }
        public Method method() { return method; }
        public CastingMaterial material() { return material; }
        @Override public InteractionResult use(Level level, Player user, InteractionHand hand) {
            if (user instanceof ServerPlayer player && ledger != null) cast(player, hand, player.isShiftKeyDown());
            return InteractionResult.SUCCESS;
        }
        private int cast(ServerPlayer player, InteractionHand hand, boolean practice) {
            if (!canBeginCasting(player, practice)) return 0;
            return finishCast(player, hand, practice, CastingEngine.cast(method, RANDOM), material);
        }
    }

    public static final class BookItem extends Item {
        private final boolean atlas;
        public BookItem(Properties properties, boolean atlas) { super(properties); this.atlas = atlas; }
        @Override public InteractionResult use(Level level, Player user, InteractionHand hand) {
            if (user instanceof ServerPlayer player && ledger != null) {
                PlayerLedger.State s = ledger.state(player.getUUID());
                open(player, hand, atlas ? BookBuilder.atlas(s.discovered, s.counts) : BookBuilder.menu());
            }
            return InteractionResult.SUCCESS;
        }
    }

    public static final class TaijiItem extends Item {
        public TaijiItem(Properties properties) { super(properties); }
        @Override public InteractionResult use(Level level, Player user, InteractionHand hand) {
            if (user instanceof ServerPlayer player && ledger != null) openTaiji(player);
            return InteractionResult.SUCCESS;
        }
    }
}

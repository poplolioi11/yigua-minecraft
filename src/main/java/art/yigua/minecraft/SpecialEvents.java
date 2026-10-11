package art.yigua.minecraft;

import art.yigua.core.CastResult;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

/** 有限時長的稀有事件；暫時世界改動保留復原紀錄，永久障路採原版方塊儲存。 */
public final class SpecialEvents {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final class Visual {
        final UUID player;
        final ServerLevel level;
        final Vec3 origin;
        final int startedTick;
        final boolean ray;
        final List<Integer> entities = new ArrayList<>();
        int nextBolt;
        boolean damageIssued;
        Visual(ServerPlayer player, boolean ray) {
            this.player = player.getUUID(); this.level = (ServerLevel)player.level();
            this.origin = ray ? player.position().add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(8)) : player.position();
            this.startedTick = level.getServer().getTickCount(); this.ray = ray;
        }
    }
    private record Blessing(Holder<MobEffect> effect, MobEffectInstance instance, int grantedTick, int duration,
                            SpecialFortune.Event event) {}
    private record AuraVisual(UUID player, ServerLevel level, SpecialFortune.Event event, int startedTick, long seed) {}
    private static final Map<UUID, Visual> VISUALS = new HashMap<>();
    private static final Map<UUID, List<Blessing>> BLESSINGS = new HashMap<>();
    private static final Map<UUID, AuraVisual> AURAS = new HashMap<>();
    private static Holder<MobEffect> undying, rapport, sea;
    private static final TagKey<EntityType<?>> RAPPORT_IMMUNE = TagKey.create(Registries.ENTITY_TYPE, YiguaMod.id("rapport_immune"));
    private SpecialEvents() {}

    public static void init() {
        class Undying extends MobEffect {
            Undying() { super(MobEffectCategory.BENEFICIAL, 0xffe6a1); }
        }
        undying = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, YiguaMod.id("undying"), new Undying());
        class Aura extends MobEffect {
            Aura(int color) { super(MobEffectCategory.BENEFICIAL, color); }
        }
        rapport = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, YiguaMod.id("rapport"), new Aura(0xa5d899));
        sea = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, YiguaMod.id("sea"), new Aura(0x72cce3));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (YiguaMod.specialAllowed(SpecialFortune.Event.UNDYING) && entity.hasEffect(undying)) return false;
            if (YiguaMod.specialAllowed(SpecialFortune.Event.SEA) && entity.hasEffect(sea) && source.is(DamageTypes.DROWN)) return false;
            return !(YiguaMod.specialAllowed(SpecialFortune.Event.RAPPORT) && entity.hasEffect(rapport)
                && source.getEntity() instanceof Mob mob && approachable(mob));
        });
    }

    public static void reveal(ServerPlayer player, CastResult cast, RareFortune.Outcome fortune) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (fortune.rare()) {
            String mode = fortune.rules().stream().anyMatch(rule -> rule.amount() < 0) ? "dark"
                : cast.primaryNumber() == 12 && cast.changedNumber() == 11 ? "purple" : "cloud";
            new SkyOmenPayload(mode, 200, RANDOM.nextLong()).send(player);
        }
        apply(player, SpecialFortune.select(cast, fortune, RANDOM.nextInt(SpecialFortune.DRAW_SIZE)));
    }

    // 整合測試以隔離外掛反射驗證，不向玩家註冊強制事件指令。
    static void apply(ServerPlayer player, SpecialFortune.Event event) {
        if (event == SpecialFortune.Event.NONE || !YiguaMod.specialAllowed(event)) return;
        ServerLevel level = (ServerLevel)player.level();
        switch (event) {
            case DIVINE -> {
                replaceVisual(player, new Visual(player, true));
                new SkyOmenPayload("divine", SpecialEventPlan.DIVINE_VISUAL_TICKS, RANDOM.nextLong()).send(player);
                for (Holder<MobEffect> effect : YiguaMod.divineBlessings(player)) {
                    if (effect.value().isInstantaneous()) {
                        effect.value().applyInstantaneousEffect(level, null, player, player, 0, 1);
                        continue;
                    }
                    if (grantBlessing(player, effect, event)) {
                        Identifier effectId = BuiltInRegistries.MOB_EFFECT.getKey(effect.value());
                        if (effectId.getNamespace().equals(YiguaMod.ID)) DiscoveryAchievements.effect(player, effectId.getPath());
                    }
                }
                Item[] armor = {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
                ItemStack reward = EnchantmentHelper.enchantItem(player.getRandom(),
                    new ItemStack(armor[RANDOM.nextInt(armor.length)]), 30, player.registryAccess(), Optional.empty());
                if (!player.getInventory().add(reward)) player.drop(reward, false, net.minecraft.util.Prediction.SERVER_ONLY);
                level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8f, 1.3f);
                player.sendSystemMessage(Component.literal("雲向兩旁散開，神光緩緩降臨；全套正面狀態護持了你，並帶來意外的贈禮。"));
                DiscoveryAchievements.secret(player, "divine");
            }
            case UNDYING -> {
                grantBlessing(player, undying, event);
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1,
                    player.getZ(), 30, 0.4, 0.7, 0.4, 0.1);
                player.sendSystemMessage(Component.literal("一股力量暫時護住了你的生命。"));
                DiscoveryAchievements.secret(player, "undying");
            }
            case THUNDER -> {
                new SkyOmenPayload("dark", SpecialEventPlan.THUNDER_VISUAL_TICKS, RANDOM.nextLong()).send(player);
                Visual visual = new Visual(player, false);
                replaceVisual(player, visual); sendBolt(player, visual, 0); visual.nextBolt = 1;
                player.sendSystemMessage(Component.literal("烏雲密布，多道天雷接連劈落；這是一場極罕見的劫數。"));
                DiscoveryAchievements.secret(player, "thunder");
                // 六道雷光演出後才結算一次傷害，不在第一道就切到死亡畫面。
            }
            case FIVE_QI, FLOWERS, BIRDS -> {
                String mode = event.name().toLowerCase(java.util.Locale.ROOT);
                startAura(player, event);
                int added = 0;
                if (event == SpecialFortune.Event.FIVE_QI) {
                    added = grantNativeBlessings(player, event, List.of(MobEffects.REGENERATION, MobEffects.RESISTANCE,
                        MobEffects.SPEED, MobEffects.HASTE, MobEffects.STRENGTH));
                    TemporalInsight.start(player, InsightPayload.Mode.PAST, SpecialEventPlan.AURA_TICKS);
                } else if (event == SpecialFortune.Event.FLOWERS) {
                    added = grantNativeBlessings(player, event, List.of(MobEffects.NIGHT_VISION, MobEffects.LUCK, MobEffects.SLOW_FALLING));
                    TemporalInsight.start(player, InsightPayload.Mode.FUTURE, SpecialEventPlan.AURA_TICKS);
                } else {
                    PhoenixFlight.start(player, SpecialEventPlan.AURA_TICKS);
                }
                player.sendSystemMessage(Component.literal(switch (event) {
                    case FIVE_QI -> "五氣朝元：五色氣流護持兩分鐘，已新增 " + added + " 項增益；附近生物留下過去的虛影。";
                    case FLOWERS -> "三花聚頂：頭頂光花綻放兩分鐘，已新增 " + added + " 項增益；附近生物顯現可能的未來軌跡。";
                    default -> PhoenixFlight.remainingTicks(player) > 0
                        ? "百鳥朝鳳：鳳凰盤旋，雙翼振動；兩分鐘內可振翅飛行。"
                        : "百鳥朝鳳：天際鳥群盤旋，演出持續兩分鐘。";
                }));
                if (event != SpecialFortune.Event.BIRDS && added < (event == SpecialFortune.Event.FIVE_QI ? 5 : 3)) {
                    player.sendSystemMessage(Component.literal("既有同名狀態與受保護項目已略過，原有狀態會保留。"));
                }
                if (event == SpecialFortune.Event.FIVE_QI) {
                    player.sendSystemMessage(Component.literal(TemporalInsight.canRewind(player)
                        ? "在允許區域攻擊生物，可回到近期位置或暫時返幼；敲擊自然樹木、草地、作物或氧化銅，可回溯至較早狀態。"
                        : "此世界的保護設定停用了攻擊回溯；過去虛影與允許的增益仍有效。"));
                }
                if (event == SpecialFortune.Event.BIRDS) player.sendSystemMessage(Component.literal(
                    PhoenixFlight.remainingTicks(player) > 0
                        ? "空白鍵振翅上升，潛行鍵下降，放開緩緩滑落；既有飛行能力優先。"
                        : "鳳凰演出已啟動；此世界的保護設定或連線停用了振翅飛行。"));
                DiscoveryAchievements.secret(player, mode);
            }
            case RAPPORT -> {
                grantBlessing(player, rapport, event);
                startAura(player, event);
                player.sendSystemMessage(Component.literal("萬物親和：附近原版生物暫時平息對你的敵意。"));
                DiscoveryAchievements.secret(player, "rapport");
            }
            case SEA -> {
                if (TemporaryWaterCorridor.start(player, SpecialEventPlan.AURA_TICKS)) {
                    grantBlessing(player, sea, event);
                    startAura(player, event);
                    player.sendSystemMessage(Component.literal("分海異象已啟動；踏入水域時會向上開路，已開水道會保留至期限結束。"));
                    DiscoveryAchievements.secret(player, "sea");
                } else player.sendSystemMessage(Component.literal("分海異象被世界保護設定或未完成的復原阻止。"));
            }
            case OBSTRUCTION -> {
                if (TemporaryObstruction.start(player, SpecialEventPlan.OBSTRUCTION_TICKS)) {
                    new SkyOmenPayload("dark", 200, RANDOM.nextLong()).send(player);
                    player.sendSystemMessage(Component.literal("山重阻路：前方接連升起寬闊障壁，水中由海床升起；依生態形成的方塊會永久留下。"));
                    DiscoveryAchievements.secret(player, "obstruction");
                } else player.sendSystemMessage(Component.literal("障路異象被世界保護設定或未完成的復原阻止。"));
            }
            default -> {}
        }
    }

    private static boolean grantBlessing(ServerPlayer player, Holder<MobEffect> effect, SpecialFortune.Event event) {
        if (!YiguaMod.specialAllowed(event) || player.hasEffect(effect)) return false;
        MobEffectInstance instance = new MobEffectInstance(effect, SpecialEventPlan.AURA_TICKS, 0, false, true, true);
        if (!player.addEffect(instance) || player.getEffect(effect) != instance) return false;
        BLESSINGS.computeIfAbsent(player.getUUID(), key -> new ArrayList<>()).add(
            new Blessing(effect, instance, player.level().getServer().getTickCount(), SpecialEventPlan.AURA_TICKS, event));
        return true;
    }

    private static int grantNativeBlessings(ServerPlayer player, SpecialFortune.Event event, List<Holder<MobEffect>> effects) {
        int added = 0;
        for (Holder<MobEffect> effect : effects) {
            if (YiguaMod.nativeBlessingAllowed(player, effect) && grantBlessing(player, effect, event)) added++;
        }
        return added;
    }

    private static void startAura(ServerPlayer player, SpecialFortune.Event event) {
        AuraVisual aura = new AuraVisual(player.getUUID(), (ServerLevel)player.level(), event,
            player.level().getServer().getTickCount(), RANDOM.nextLong());
        AURAS.put(player.getUUID(), aura);
        sendAuraStage(player, aura, 0);
    }

    private static void sendAuraStage(ServerPlayer player, AuraVisual aura, int age) {
        int duration = SpecialEventPlan.stageDuration(age);
        if (duration > 0) new SkyOmenPayload(aura.event.name().toLowerCase(java.util.Locale.ROOT), duration, aura.seed).send(player);
    }

    public static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive()) continue;
            if (YiguaMod.specialAllowed(SpecialFortune.Event.SEA) && player.hasEffect(sea)) player.setAirSupply(player.getMaxAirSupply());
            else TemporaryWaterCorridor.cancel(player);
            if (YiguaMod.specialAllowed(SpecialFortune.Event.RAPPORT) && player.hasEffect(rapport) && player.tickCount % 10 == 0) {
                for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16), SpecialEvents::approachable)) {
                    if (mob.getTarget() == player) mob.setTarget(null);
                }
            }
        }
        VISUALS.values().removeIf(visual -> {
            ServerPlayer player = server.getPlayerList().getPlayer(visual.player);
            int age = server.getTickCount() - visual.startedTick;
            if (player == null || player.level() != visual.level || age >= (visual.ray ? SpecialEventPlan.DIVINE_VISUAL_TICKS : SpecialEventPlan.THUNDER_VISUAL_TICKS)) {
                removeVisual(player, visual);
                return true;
            }
            if (!visual.ray) {
                while (visual.nextBolt < SpecialEventPlan.boltCount() && age >= SpecialEventPlan.boltAge(visual.nextBolt)) {
                    sendBolt(player, visual, visual.nextBolt++);
                }
                if (!visual.damageIssued && age >= SpecialEventPlan.thunderDamageAge()) {
                    visual.damageIssued = true;
                    // 走原版途徑，尊重其他模組拒絕傷害、無敵狀態和不死圖騰。
                    if (player.isAlive()) player.hurtServer(visual.level, visual.level.damageSources().lightningBolt(),
                        Math.max(1024f, player.getMaxHealth() * 16));
                }
            } else if (age % 2 == 0) {
                double bottom = SpecialEventPlan.beamBottom(age);
                for (int height = 0; height < 6; height++) {
                    visual.level.sendParticles(ParticleTypes.END_ROD, visual.origin.x, visual.origin.y + bottom + height,
                        visual.origin.z, 2, 0.25, 0.06, 0.25, 0);
                }
            }
            return false;
        });
        BLESSINGS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true; // 原版保存後按剩餘時長自然到期，不偽造第三方狀態所有權。
            entry.getValue().removeIf(blessing -> {
                MobEffectInstance active = player.getEffect(blessing.effect);
                int expected = blessing.duration - (server.getTickCount() - blessing.grantedTick);
                boolean owned = active != null && SpecialEventPlan.owns(active, blessing.instance, active.getDuration(), active.getAmplifier(), expected);
                if (owned && !YiguaMod.specialAllowed(blessing.event)) player.removeEffect(blessing.effect);
                return !owned || !YiguaMod.specialAllowed(blessing.event);
            });
            return entry.getValue().isEmpty();
        });
        AURAS.values().removeIf(aura -> {
            ServerPlayer player = server.getPlayerList().getPlayer(aura.player);
            int age = server.getTickCount() - aura.startedTick;
            if (player == null || !player.isAlive() || player.isSpectator() || player.level() != aura.level
                    || age >= SpecialEventPlan.AURA_TICKS || !YiguaMod.specialAllowed(aura.event)) {
                if (player != null) {
                    if (aura.event == SpecialFortune.Event.FIVE_QI || aura.event == SpecialFortune.Event.FLOWERS) TemporalInsight.cancel(player);
                    if (aura.event == SpecialFortune.Event.BIRDS) PhoenixFlight.cancel(player);
                    new SkyOmenPayload("cloud", 1, 0).send(player);
                }
                return true;
            }
            if (age > 0 && SpecialEventPlan.refreshStage(age)) sendAuraStage(player, aura, age);
            if (aura.event == SpecialFortune.Event.FIVE_QI || aura.event == SpecialFortune.Event.FLOWERS) {
                for (SpecialEventPlan.AuraParticle point : SpecialEventPlan.auraParticles(
                        aura.event == SpecialFortune.Event.FLOWERS, age, player.getBbHeight())) {
                    aura.level.sendParticles(new DustParticleOptions(point.color(), 0.7f), player.getX() + point.x(),
                        player.getY() + point.y(), player.getZ() + point.z(), 1, 0, 0, 0, 0);
                }
            }
            return false;
        });
    }

    private static void sendBolt(ServerPlayer player, Visual visual, int index) {
        // 不生成真實世界雷實體，避免清銅、點火、啟動其他模組避雷針。
        LightningBolt bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, visual.level);
        double angle = (index - 1) * Math.PI * 2 / 5;
        double x = visual.origin.x + (index == 0 ? 0 : Math.cos(angle) * 5);
        double z = visual.origin.z + (index == 0 ? 0 : Math.sin(angle) * 5);
        player.connection.send(new ClientboundAddEntityPacket(bolt.getId(), bolt.getUUID(), x, visual.origin.y, z,
            0, 0, EntityTypes.LIGHTNING_BOLT, 0, Vec3.ZERO, 0));
        visual.entities.add(bolt.getId());
        visual.level.playSound(null, net.minecraft.core.BlockPos.containing(x, visual.origin.y, z),
            SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1, 0.9f + index * 0.03f);
    }
    private static void replaceVisual(ServerPlayer player, Visual visual) {
        Visual old = VISUALS.put(player.getUUID(), visual);
        if (old != null) removeVisual(player, old);
    }
    private static void removeVisual(ServerPlayer player, Visual visual) {
        if (player != null && !visual.entities.isEmpty()) player.connection.send(new ClientboundRemoveEntitiesPacket(
            visual.entities.stream().mapToInt(Integer::intValue).toArray()));
    }

    private static boolean approachable(Mob mob) {
        return mob.getClass().getName().startsWith("net.minecraft.")
            && !mob.getType().builtInRegistryHolder().is(RAPPORT_IMMUNE);
    }

    static double divineDirection(ServerPlayer player) {
        if (!YiguaMod.specialAllowed(SpecialFortune.Event.DIVINE) || YiguaMod.effectProtected("direction")) return 0;
        for (Blessing blessing : BLESSINGS.getOrDefault(player.getUUID(), List.of())) {
            Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(blessing.effect.value());
            MobEffectInstance active = player.getEffect(blessing.effect);
            int expected = blessing.duration - (player.level().getServer().getTickCount() - blessing.grantedTick);
            if (id.equals(YiguaMod.id("direction")) && active == blessing.instance
                    && SpecialEventPlan.ownedDuration(active.getDuration(), active.getAmplifier(), expected)) return 0.1;
        }
        return 0;
    }

    public static void clear() { VISUALS.clear(); BLESSINGS.clear(); AURAS.clear(); TemporalInsight.clear(); PhoenixFlight.clear(); }

    public static void cancel(ServerPlayer player) {
        AURAS.remove(player.getUUID());
        TemporalInsight.cancel(player);
        PhoenixFlight.cancel(player);
        Visual visual = VISUALS.remove(player.getUUID());
        if (visual != null) removeVisual(player, visual);
        List<Blessing> blessings = BLESSINGS.remove(player.getUUID());
        if (blessings != null) for (Blessing blessing : blessings) {
            MobEffectInstance active = player.getEffect(blessing.effect);
            int expected = blessing.duration - (player.level().getServer().getTickCount() - blessing.grantedTick);
            if (active != null && SpecialEventPlan.owns(active, blessing.instance, active.getDuration(), active.getAmplifier(), expected)) {
                player.removeEffect(blessing.effect);
            }
        }
        TemporaryWaterCorridor.cancel(player);
        TemporaryObstruction.cancel(player);
        // 一刻、零起始透明度的替代視覺會立即結束原異象，不擴充秘密模式清單。
        new SkyOmenPayload("cloud", 1, 0).send(player);
    }
}

package art.yigua.minecraft;

import art.yigua.core.CastResult;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

/** 短時、自有識別名稱的罕見事件；不改天氣、世界生成或武器的基礎數值。 */
public final class SpecialEvents {
    private static final SecureRandom RANDOM = new SecureRandom();
    private record Visual(UUID player, ServerLevel level, int entityId, int untilTick, boolean ray) {}
    private static final Map<UUID, Visual> VISUALS = new HashMap<>();
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
                VISUALS.put(player.getUUID(), new Visual(player.getUUID(), level, -1, player.tickCount + 60, true));
                Item[] armor = {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
                ItemStack reward = EnchantmentHelper.enchantItem(player.getRandom(),
                    new ItemStack(armor[RANDOM.nextInt(armor.length)]), 30, player.registryAccess(), Optional.empty());
                if (!player.getInventory().add(reward)) player.drop(reward, false, net.minecraft.util.Prediction.SERVER_ONLY);
                level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8f, 1.3f);
                player.sendSystemMessage(Component.literal("一線神光自天而降，你收到了意外的贈禮。"));
                DiscoveryAchievements.secret(player, "divine");
            }
            case UNDYING -> {
                player.addEffect(new MobEffectInstance(undying, 400, 0, false, true, true));
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1,
                    player.getZ(), 30, 0.4, 0.7, 0.4, 0.1);
                player.sendSystemMessage(Component.literal("一股力量暫時護住了你的生命。"));
                DiscoveryAchievements.secret(player, "undying");
            }
            case THUNDER -> {
                new SkyOmenPayload("dark", 60, RANDOM.nextLong()).send(player);
                // 真實 LightningBolt.tick 仍會清理銅、啟動避雷針；只發送客戶端影像。
                LightningBolt bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, level);
                player.connection.send(new ClientboundAddEntityPacket(bolt.getId(), bolt.getUUID(),
                    player.getX(), player.getY(), player.getZ(), 0, 0, EntityTypes.LIGHTNING_BOLT, 0, Vec3.ZERO, 0));
                VISUALS.put(player.getUUID(), new Visual(player.getUUID(), level, bolt.getId(), player.tickCount + 40, false));
                level.playSound(null, player.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1, 1);
                player.sendSystemMessage(Component.literal("天雷驟落，這是一場極罕見的劫數。"));
                DiscoveryAchievements.secret(player, "thunder");
                // 走原版傷害途徑；尊重其他模組拒絕傷害、無敵狀態和不死圖騰。
                player.hurtServer(level, level.damageSources().lightningBolt(), Math.max(1024f, player.getMaxHealth() * 16));
            }
            case FIVE_QI, FLOWERS, BIRDS -> {
                String mode = event.name().toLowerCase(java.util.Locale.ROOT);
                new SkyOmenPayload(mode, 400, RANDOM.nextLong()).send(player);
                level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 2, player.getZ(), 24, 0.7, 0.8, 0.7, 0.04);
                player.sendSystemMessage(Component.literal(switch (event) {
                    case FIVE_QI -> "五色氣息環繞，五氣朝元的奇景在眼前浮現。";
                    case FLOWERS -> "三朵光花升起，三花聚頂的奇景在眼前浮現。";
                    default -> "群鳥光影盤旋，萬鳥朝拜的奇景在眼前浮現。";
                }));
                DiscoveryAchievements.secret(player, mode);
            }
            case RAPPORT -> {
                player.addEffect(new MobEffectInstance(rapport, 400, 0, false, true, true));
                new SkyOmenPayload("rapport", 400, RANDOM.nextLong()).send(player);
                player.sendSystemMessage(Component.literal("萬物親和：附近原版生物暫時平息對你的敵意。"));
                DiscoveryAchievements.secret(player, "rapport");
            }
            case SEA -> {
                new SkyOmenPayload("sea", 400, RANDOM.nextLong()).send(player);
                if (TemporaryWaterCorridor.start(player)) {
                    player.addEffect(new MobEffectInstance(sea, 400, 0, false, true, true));
                    player.sendSystemMessage(Component.literal("水域暫時向兩旁退開，一條短暫的分海路徑出現了。"));
                    DiscoveryAchievements.secret(player, "sea");
                } else player.sendSystemMessage(Component.literal("分海異象浮現；附近沒有可安全分開的水域。"));
            }
            default -> {}
        }
    }

    public static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive()) continue;
            if (YiguaMod.specialAllowed(SpecialFortune.Event.SEA) && player.hasEffect(sea)) player.setAirSupply(player.getMaxAirSupply());
            if (YiguaMod.specialAllowed(SpecialFortune.Event.RAPPORT) && player.hasEffect(rapport) && player.tickCount % 10 == 0) {
                for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16), SpecialEvents::approachable)) {
                    if (mob.getTarget() == player) mob.setTarget(null);
                }
            }
        }
        VISUALS.values().removeIf(visual -> {
            ServerPlayer player = server.getPlayerList().getPlayer(visual.player);
            if (player == null || player.level() != visual.level || player.tickCount >= visual.untilTick) {
                if (player != null && visual.entityId >= 0) player.connection.send(new ClientboundRemoveEntitiesPacket(visual.entityId));
                return true;
            }
            if (visual.ray && player.tickCount % 2 == 0) {
                for (int height = 1; height <= 12; height++) {
                    visual.level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + height,
                        player.getZ(), 1, 0.07, 0.07, 0.07, 0);
                }
            }
            return false;
        });
    }

    private static boolean approachable(Mob mob) {
        return mob.getClass().getName().startsWith("net.minecraft.")
            && !mob.getType().builtInRegistryHolder().is(RAPPORT_IMMUNE);
    }

    public static void clear() { VISUALS.clear(); }

    public static void cancel(ServerPlayer player) {
        player.removeEffect(undying); player.removeEffect(rapport); player.removeEffect(sea);
        Visual visual = VISUALS.remove(player.getUUID());
        if (visual != null && visual.entityId >= 0) player.connection.send(new ClientboundRemoveEntitiesPacket(visual.entityId));
        TemporaryWaterCorridor.cancel(player);
        // 一刻、零起始透明度的替代視覺會立即結束原異象，不擴充秘密模式清單。
        new SkyOmenPayload("cloud", 1, 0).send(player);
    }
}

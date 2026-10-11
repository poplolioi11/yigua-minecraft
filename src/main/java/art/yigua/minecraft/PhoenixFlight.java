package art.yigua.minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

/** 百鳥朝鳳的獨立振翅。絕不改 abilities、allow-flight、裝備、世界重力或其他飛行模組的旗標。 */
public final class PhoenixFlight {
    public static final int BEAT_TICKS = 24;
    public static final int LANDING_EFFECT_TICKS = 200;
    private static final int SAFE_LANDING_MAX_TICKS = 6000;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Landing> LANDINGS = new HashMap<>();
    private static final Set<UUID> VIEWERS = new HashSet<>();
    private static final class Session {
        final ServerPlayer player; final ServerLevel level; final int started; final int duration;
        boolean managed;
        Session(ServerPlayer player, int started, int duration) {
            this.player = player; level = (ServerLevel)player.level(); this.started = started; this.duration = duration;
        }
    }
    private static final class Landing {
        final ServerPlayer player; final ServerLevel level; final int started;
        final MobEffectInstance effect;
        Landing(ServerPlayer player, int started, MobEffectInstance effect) {
            this.player = player; level = (ServerLevel)player.level(); this.started = started; this.effect = effect;
        }
    }
    private PhoenixFlight() {}
    public static boolean start(ServerPlayer player, int durationTicks) {
        Objects.requireNonNull(player);
        if (durationTicks < 1 || durationTicks > 12000) throw new IllegalArgumentException("鳳翼期限超出範圍");
        if (!allowed(player) || !ServerPlayNetworking.canSend(player, PhoenixFlightPayload.TYPE)) return false;
        cancel(player); Landing previous = LANDINGS.remove(player.getUUID());
        if (previous != null) removeOwnLandingEffect(player, previous, player.level().getServer().getTickCount());
        SESSIONS.put(player.getUUID(), new Session(player, player.level().getServer().getTickCount(), durationTicks));
        broadcast(player.level().getServer());
        return true;
    }
    private static boolean allowed(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.hasDisconnected() && !player.isSpectator()
            && YiguaMod.phoenixFlightAllowed();
    }
    /** 不介入第三方／創造模式已取得的飛行、鞘翅、騎乘、流體或攀爬。 */
    public static boolean canManage(ServerPlayer player) {
        return YiguaMod.phoenixFlightAllowed() && !player.getAbilities().mayfly && !player.getAbilities().flying && !player.isCreative()
            && !player.isSpectator() && !player.isPassenger() && !player.isFallFlying()
            && !player.isInWater() && !player.isInLava() && !player.onClimbable() && !player.isSleeping()
            && !player.hasEffect(MobEffects.LEVITATION);
    }
    public static int remainingTicks(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.player == player && session.level == player.level() && allowed(player)
            ? Math.max(0, session.duration - (player.level().getServer().getTickCount() - session.started)) : 0;
    }
    /** 可核對的飛行曲線；保留強烈擊退與非有限外部速度，不以振翅吞掉攻擊。 */
    public static double verticalVelocity(double current, boolean jump, boolean shift, double age) {
        if (!Double.isFinite(current) || !Double.isFinite(age) || Math.abs(current) > 0.8) return current;
        double beat = Math.sin((age % BEAT_TICKS) * 2 * Math.PI / BEAT_TICKS) * 0.045;
        if (jump == shift) return -0.055 + beat; // 放開慢慢下滑；兩鍵同按同樣不升空。
        return jump ? 0.22 + beat : -0.24 + beat;
    }
    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        for (UUID id : List.copyOf(SESSIONS.keySet())) {
            Session session = SESSIONS.get(id); ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != session.player || player.level() != session.level || !allowed(player) || now - session.started >= session.duration) {
                if (player == session.player) cancel(player); else SESSIONS.remove(id);
                continue;
            }
            if (canManage(player)) {
                var input = player.getLastClientInput(); Vec3 motion = player.getDeltaMovement();
                boolean upward = input.jump() && player.getY() < player.level().getMaxY() + 32;
                double vertical = verticalVelocity(motion.y, upward, input.shift(), now - session.started);
                if (Double.isFinite(vertical) && Math.abs(motion.y) <= 0.8) {
                    player.setDeltaMovement(motion.x, vertical, motion.z); player.resetFallDistance();
                    player.connection.resetFlyingTicks(); session.managed = true;
                    if ((now - session.started) % 4 == 0) player.connection.send(new ClientboundSetEntityMotionPacket(player));
                }
            }
        }
        for (UUID id : List.copyOf(LANDINGS.keySet())) {
            Landing landing = LANDINGS.get(id); ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (!YiguaMod.phoenixFlightAllowed() || player != landing.player || player.level() != landing.level || !player.isAlive() || player.hasDisconnected()
                    || player.onGround() || player.isInWater() || player.isInLava() || player.isPassenger()
                    || player.getAbilities().mayfly || player.getAbilities().flying || now - landing.started >= SAFE_LANDING_MAX_TICKS) {
                if (player == landing.player) removeOwnLandingEffect(player, landing, now);
                LANDINGS.remove(id); continue;
            }
            // 翼在到期時撤回；僅保留這一次著陸的墜落保護，落地即終止，最多五分鐘。
            player.resetFallDistance();
        }
        if (now % 10 == 0) broadcast(server);
    }
    public static void cancel(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (!YiguaMod.phoenixFlightAllowed()) {
            Landing prior = LANDINGS.remove(player.getUUID());
            if (prior != null) removeOwnLandingEffect(player, prior, player.level().getServer().getTickCount());
        }
        if (YiguaMod.phoenixFlightAllowed() && session != null && session.player == player && session.level == player.level() && session.managed
                && player.isAlive() && !player.onGround() && canManage(player)) {
            int now = player.level().getServer().getTickCount(); MobEffectInstance effect = null;
            if (YiguaMod.nativeBlessingAllowed(player, MobEffects.SLOW_FALLING)) {
                effect = new MobEffectInstance(MobEffects.SLOW_FALLING, LANDING_EFFECT_TICKS, 0, false, false, true);
                if (!player.addEffect(effect) || player.getEffect(MobEffects.SLOW_FALLING) != effect) effect = null;
            }
            player.resetFallDistance(); LANDINGS.put(player.getUUID(), new Landing(player, now, effect));
        }
        if (player.level().getServer() != null) broadcast(player.level().getServer());
    }
    private static void removeOwnLandingEffect(ServerPlayer player, Landing landing, int now) {
        if (landing.effect != null && player.getEffect(MobEffects.SLOW_FALLING) == landing.effect
                && landing.effect.getDuration() == Math.max(0, LANDING_EFFECT_TICKS - (now - landing.started)))
            player.removeEffect(MobEffects.SLOW_FALLING);
    }
    public static void clear() { SESSIONS.clear(); LANDINGS.clear(); VIEWERS.clear(); }
    /** 發給同維度 64 格內真正已追蹤的角色；每人最多 16 名，過期快照自行撤回。 */
    private static void broadcast(MinecraftServer server) {
        int now = server.getTickCount();
        VIEWERS.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (!ServerPlayNetworking.canSend(viewer, PhoenixFlightPayload.TYPE)) continue;
            var nearby = SESSIONS.values().stream().filter(s -> s.level == viewer.level() && allowed(s.player)
                && now - s.started < s.duration && s.player.distanceToSqr(viewer) <= 64 * 64
                && !s.player.isInvisibleTo(viewer) && (s.player == viewer || PlayerLookup.tracking(s.player).contains(viewer)))
                .sorted(Comparator.comparingDouble(s -> s.player.distanceToSqr(viewer))).limit(PhoenixFlightPayload.MAX_ACTORS).toList();
            var actors = new ArrayList<PhoenixFlightPayload.Actor>(nearby.size());
            for (Session session : nearby) actors.add(new PhoenixFlightPayload.Actor(session.player.getId(), session.player.getUUID(),
                Math.max(0, Math.min(PhoenixFlightPayload.MAX_AGE, now - session.started)), canManage(session.player)));
            if (!actors.isEmpty()) { VIEWERS.add(viewer.getUUID()); new PhoenixFlightPayload(30, actors).send(viewer); }
            else if (VIEWERS.remove(viewer.getUUID())) new PhoenixFlightPayload(1, List.of()).send(viewer);
        }
    }
}

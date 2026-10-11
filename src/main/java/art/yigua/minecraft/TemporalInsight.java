package art.yigua.minecraft;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 只向持有異象的玩家送出附近非玩家生物軌跡；不改動 AI、速度或實體位置。 */
public final class TemporalInsight {
    static final int SAMPLE_TICKS = 10;
    static final int HISTORY_TICKS = 60;
    static final int MAX_ENTITIES = 24;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    record Snapshot(int tick, Vec3 position, float yaw) {}
    private static final class Session {
        final InsightPayload.Mode mode;
        final ServerLevel level;
        final int started;
        final int duration;
        final Map<UUID, ArrayDeque<Snapshot>> history = new HashMap<>();
        Session(InsightPayload.Mode mode, ServerLevel level, int tick, int duration) {
            this.mode = mode; this.level = level; this.started = tick; this.duration = duration;
        }
    }
    private TemporalInsight() {}

    public static void start(ServerPlayer player, InsightPayload.Mode mode, int durationTicks) {
        Objects.requireNonNull(player); Objects.requireNonNull(mode);
        if (durationTicks < 1 || durationTicks > 12000) throw new IllegalArgumentException("虛影期限超出範圍");
        cancel(player);
        if (!allowed(player, mode)) return;
        SESSIONS.put(player.getUUID(), new Session(mode, (ServerLevel)player.level(),
            player.level().getServer().getTickCount(), durationTicks));
    }

    private static boolean allowed(ServerPlayer player, InsightPayload.Mode mode) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.hasDisconnected()
            && YiguaMod.specialAllowed(mode == InsightPayload.Mode.PAST ? SpecialFortune.Event.FIVE_QI : SpecialFortune.Event.FLOWERS);
    }

    public static int remainingTicks(ServerPlayer player) {
        Session state = SESSIONS.get(player.getUUID());
        if (state == null || state.level != player.level() || !allowed(player, state.mode)) return 0;
        int elapsed = player.level().getServer().getTickCount() - state.started;
        return Math.max(0, state.duration - elapsed);
    }

    public static boolean canRewind(ServerPlayer player) {
        Session state = SESSIONS.get(player.getUUID());
        return state != null && state.mode == InsightPayload.Mode.PAST && WorldChangePolicy.enabled() && remainingTicks(player) > 0;
    }

    public static Optional<Vec3> previousPosition(ServerPlayer player, Entity entity) {
        Session state = SESSIONS.get(player.getUUID());
        if (!canRewind(player) || !(entity instanceof Mob) || entity.level() != player.level()) return Optional.empty();
        ArrayDeque<Snapshot> history = state.history.get(entity.getUUID());
        if (history == null || history.isEmpty()) return Optional.empty();
        int now = player.level().getServer().getTickCount();
        return history.stream().filter(sample -> now - sample.tick() >= SAMPLE_TICKS)
            .findFirst().map(Snapshot::position);
    }

    public static void tick(MinecraftServer server) {
        for (UUID id : List.copyOf(SESSIONS.keySet())) {
            Session state = SESSIONS.get(id);
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.level() != state.level || !allowed(player, state.mode)
                    || server.getTickCount() - state.started >= state.duration) {
                if (player == null) SESSIONS.remove(id); else cancel(player);
                continue;
            }
            if ((server.getTickCount() - state.started) % SAMPLE_TICKS != 0) continue;
            List<Mob> entities = state.level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(32),
                    mob -> mob.isAlive() && !mob.isRemoved() && mob.distanceToSqr(player) <= 32 * 32)
                .stream().sorted(Comparator.comparingDouble(mob -> mob.distanceToSqr(player))).limit(MAX_ENTITIES).toList();
            Set<UUID> present = new HashSet<>();
            List<InsightPayload.Trace> traces = new ArrayList<>();
            for (Mob mob : entities) {
                UUID uuid = mob.getUUID(); present.add(uuid);
                ArrayDeque<Snapshot> history = state.history.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
                history.addLast(new Snapshot(server.getTickCount(), mob.position(), safeYaw(mob.getYRot())));
                trim(history, server.getTickCount());
                List<InsightPayload.Sample> samples = state.mode == InsightPayload.Mode.PAST
                    ? past(history, server.getTickCount()) : future(state.level, mob, history);
                if (!samples.isEmpty()) traces.add(new InsightPayload.Trace(mob.getId(), uuid,
                    BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()), samples));
            }
            state.history.keySet().retainAll(present);
            new InsightPayload(state.mode, 20, traces).send(player);
        }
    }

    static void trim(ArrayDeque<Snapshot> history, int now) {
        while (!history.isEmpty() && now - history.getFirst().tick() > HISTORY_TICKS) history.removeFirst();
        while (history.size() > 7) history.removeFirst();
    }

    static List<InsightPayload.Sample> past(ArrayDeque<Snapshot> history, int now) {
        List<InsightPayload.Sample> result = new ArrayList<>();
        Iterator<Snapshot> samples = history.descendingIterator();
        while (samples.hasNext() && result.size() < 6) {
            Snapshot sample = samples.next();
            if (now - sample.tick() >= SAMPLE_TICKS) result.add(new InsightPayload.Sample(sample.position(), sample.yaw()));
        }
        return List.copyOf(result);
    }

    private static List<InsightPayload.Sample> future(ServerLevel level, Mob mob, ArrayDeque<Snapshot> history) {
        Vec3 velocity = mob.getDeltaMovement();
        if (history.size() > 1) {
            Snapshot previous = history.stream().skip(history.size() - 2).findFirst().orElseThrow();
            int elapsed = history.getLast().tick() - previous.tick();
            if (elapsed > 0) velocity = mob.position().subtract(previous.position()).scale(1.0 / elapsed);
        }
        velocity = boundedVelocity(velocity);
        Path path = mob.getNavigation().getPath();
        List<Vec3> waypoints = new ArrayList<>();
        if (path != null && !path.isDone()) {
            for (int i = path.getNextNodeIndex(); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 12); i++)
                waypoints.add(Vec3.atBottomCenterOf(path.getNodePos(i)));
        }
        double navigationSpeed = Double.isFinite(mob.getSpeed()) ? Math.clamp(mob.getSpeed(), 0, 0.35) : 0;
        double speed = Math.max(velocity.horizontalDistance(), navigationSpeed);
        List<InsightPayload.Sample> result = new ArrayList<>();
        Vec3 previous = mob.position();
        for (int offset = SAMPLE_TICKS; offset <= HISTORY_TICKS; offset += SAMPLE_TICKS) {
            Vec3 predicted = predict(mob.position(), velocity, waypoints, speed, offset);
            Vec3 eye = new Vec3(0, mob.getEyeHeight(), 0);
            AABB body = mob.getBoundingBox().move(predicted.subtract(mob.position()));
            AABB ray = new AABB(previous.add(eye), predicted.add(eye));
            Predicate<ChunkPos> loaded = chunk -> level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null;
            // clip 的原生 getBlockState/getFluidState 可載入／生成區塊，必須先保守涵蓋整段射線。
            if (!loadedRegion(body, loaded) || !loadedRegion(ray, loaded)) break;
            BlockPos pos = BlockPos.containing(predicted);
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.noCollision(mob, body)) break;
            if (level.clip(new ClipContext(previous.add(eye), predicted.add(eye), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, mob)).getType() != HitResult.Type.MISS) break;
            Vec3 delta = predicted.subtract(previous);
            float yaw = delta.horizontalDistanceSqr() < 1.0E-8 ? safeYaw(mob.getYRot())
                : (float)(Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            result.add(new InsightPayload.Sample(predicted, yaw)); previous = predicted;
        }
        return List.copyOf(result);
    }

    /** 一個半秒步距的 body／眼位射線最多探測四個 chunk；空清單代表不安全，並非全載入。 */
    static List<ChunkPos> requiredChunks(AABB region) {
        if (region == null || !finite(region.minX) || !finite(region.minY) || !finite(region.minZ)
                || !finite(region.maxX) || !finite(region.maxY) || !finite(region.maxZ)) return List.of();
        int minX = ((int)Math.floor(region.minX)) >> 4, maxX = ((int)Math.floor(region.maxX)) >> 4;
        int minZ = ((int)Math.floor(region.minZ)) >> 4, maxZ = ((int)Math.floor(region.maxZ)) >> 4;
        long count = (maxX - minX + 1L) * (maxZ - minZ + 1L);
        if (count < 1 || count > 4) return List.of();
        List<ChunkPos> chunks = new ArrayList<>((int)count);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) chunks.add(new ChunkPos(x, z));
        return List.copyOf(chunks);
    }
    private static boolean finite(double coordinate) { return Double.isFinite(coordinate) && Math.abs(coordinate) < 30_000_000; }
    static boolean loadedRegion(AABB region, Predicate<ChunkPos> loaded) {
        List<ChunkPos> chunks = requiredChunks(region);
        return !chunks.isEmpty() && chunks.stream().allMatch(loaded);
    }

    static Vec3 boundedVelocity(Vec3 velocity) {
        if (!Double.isFinite(velocity.x) || !Double.isFinite(velocity.y) || !Double.isFinite(velocity.z)) return Vec3.ZERO;
        double length = velocity.length();
        return length > 0.35 ? velocity.scale(0.35 / length) : velocity;
    }

    static float safeYaw(float yaw) { return Float.isFinite(yaw) ? yaw : 0; }

    static Vec3 predict(Vec3 origin, Vec3 velocity, List<Vec3> path, double speed, int ticks) {
        if (path.isEmpty()) return origin.add(boundedVelocity(velocity).scale(ticks));
        double remaining = (Double.isFinite(speed) ? Math.clamp(speed, 0, 0.35) : 0) * ticks;
        Vec3 current = origin;
        for (Vec3 next : path) {
            Vec3 delta = next.subtract(current); double length = delta.length();
            if (length <= 1.0E-8) continue;
            if (remaining <= length) return current.add(delta.scale(remaining / length));
            remaining -= length; current = next;
        }
        return current;
    }

    public static void cancel(ServerPlayer player) {
        Session state = SESSIONS.remove(player.getUUID());
        TemporalRegression.cancel(player);
        if (state != null && !player.hasDisconnected()) new InsightPayload(state.mode, 1, List.of()).send(player);
    }

    public static void clear() { SESSIONS.clear(); }
}

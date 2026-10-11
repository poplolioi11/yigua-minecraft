package art.yigua.minecraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** 短生命週期、數量有界的非玩家生物軌跡；不含實體或世界變更指令。 */
public record InsightPayload(Mode mode, int durationTicks, List<Trace> traces) implements CustomPacketPayload {
    public enum Mode { PAST, FUTURE }
    public static final int MAX_TRACES = 24;
    public static final int MAX_SAMPLES = 6;
    public static final int MAX_DURATION_TICKS = 40;
    private static final int MAX_TYPE_LENGTH = 128;
    public static final Type<InsightPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("yigua", "temporal_insight_v1"));
    private static boolean initialized;

    public record Sample(Vec3 position, float yaw) {
        public Sample {
            Objects.requireNonNull(position, "position");
            if (!finiteCoordinate(position.x) || !finiteCoordinate(position.y) || !finiteCoordinate(position.z)
                    || !Float.isFinite(yaw)) throw new IllegalArgumentException("虛影座標或方位無效");
        }
    }
    public record Trace(int entityId, UUID entityUuid, Identifier entityType, List<Sample> samples) {
        public Trace {
            Objects.requireNonNull(entityUuid, "entityUuid");
            Objects.requireNonNull(entityType, "entityType");
            if (entityId < 0 || entityType.toString().length() > MAX_TYPE_LENGTH) throw new IllegalArgumentException("虛影實體識別無效");
            samples = List.copyOf(samples);
            if (samples.isEmpty() || samples.size() > MAX_SAMPLES) throw new IllegalArgumentException("虛影樣本超出上限");
        }
    }
    public InsightPayload {
        Objects.requireNonNull(mode, "mode");
        if (durationTicks < 1 || durationTicks > MAX_DURATION_TICKS) throw new IllegalArgumentException("虛影封包時長超出上限");
        traces = List.copyOf(traces);
        if (traces.size() > MAX_TRACES) throw new IllegalArgumentException("虛影生物超出上限");
        var ids = new HashSet<Integer>();
        var uuids = new HashSet<UUID>();
        for (Trace trace : traces) {
            if (!ids.add(trace.entityId()) || !uuids.add(trace.entityUuid())) throw new IllegalArgumentException("重複的虛影實體");
        }
    }
    private static boolean finiteCoordinate(double value) { return Double.isFinite(value) && Math.abs(value) <= 30_000_000; }

    public static final StreamCodec<RegistryFriendlyByteBuf, InsightPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeByte(payload.mode().ordinal());
            buffer.writeVarInt(payload.durationTicks());
            buffer.writeVarInt(payload.traces().size());
            for (Trace trace : payload.traces()) {
                buffer.writeVarInt(trace.entityId()); buffer.writeUUID(trace.entityUuid());
                buffer.writeUtf(trace.entityType().toString(), MAX_TYPE_LENGTH);
                buffer.writeVarInt(trace.samples().size());
                for (Sample sample : trace.samples()) {
                    buffer.writeDouble(sample.position().x); buffer.writeDouble(sample.position().y); buffer.writeDouble(sample.position().z);
                    buffer.writeFloat(sample.yaw());
                }
            }
        }, buffer -> {
            int mode = buffer.readUnsignedByte();
            if (mode >= Mode.values().length) throw new IllegalArgumentException("未知的虛影模式");
            int duration = buffer.readVarInt();
            int count = boundedCount(buffer.readVarInt(), MAX_TRACES);
            var traces = new ArrayList<Trace>(count);
            for (int index = 0; index < count; index++) {
                int id = buffer.readVarInt(); UUID uuid = buffer.readUUID();
                Identifier type = Identifier.parse(buffer.readUtf(MAX_TYPE_LENGTH));
                int sampleCount = boundedCount(buffer.readVarInt(), MAX_SAMPLES);
                var samples = new ArrayList<Sample>(sampleCount);
                for (int sample = 0; sample < sampleCount; sample++) {
                    samples.add(new Sample(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readFloat()));
                }
                traces.add(new Trace(id, uuid, type, samples));
            }
            return new InsightPayload(Mode.values()[mode], duration, traces);
        });
    private static int boundedCount(int count, int maximum) {
        if (count < 0 || count > maximum) throw new IllegalArgumentException("虛影列表超出上限");
        return count;
    }
    public static synchronized void init() {
        if (initialized) return;
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        initialized = true;
    }
    public void send(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, TYPE)) ServerPlayNetworking.send(player, this);
    }
    public static void clear(ServerPlayer player) { new InsightPayload(Mode.PAST, 1, List.of()).send(player); }
    @Override public Type<InsightPayload> type() { return TYPE; }
}

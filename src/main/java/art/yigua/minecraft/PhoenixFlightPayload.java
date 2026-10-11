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

/** 僅伺服器傳入的有界翼／鳳凰快照；沒有客戶端可要求飛行的封包。 */
public record PhoenixFlightPayload(int durationTicks, List<Actor> actors) implements CustomPacketPayload {
    public static final int MAX_ACTORS = 16;
    public static final int MAX_TTL = 40;
    public static final int MAX_AGE = 12000;
    public static final Type<PhoenixFlightPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("yigua", "phoenix_flight_v1"));
    private static boolean initialized;
    public record Actor(int entityId, UUID entityUuid, int age, boolean managed) {
        public Actor {
            Objects.requireNonNull(entityUuid);
            if (entityId < 0 || age < 0 || age > MAX_AGE) throw new IllegalArgumentException("鳳翼實體或年齡無效");
        }
    }
    public PhoenixFlightPayload {
        if (durationTicks < 1 || durationTicks > MAX_TTL) throw new IllegalArgumentException("鳳翼快照期限無效");
        actors = List.copyOf(actors);
        if (actors.size() > MAX_ACTORS) throw new IllegalArgumentException("鳳翼數量超過上限");
        var ids = new HashSet<Integer>(); var uuids = new HashSet<UUID>();
        for (Actor actor : actors) if (!ids.add(actor.entityId()) || !uuids.add(actor.entityUuid()))
            throw new IllegalArgumentException("重複的鳳翼實體");
    }
    public static final StreamCodec<RegistryFriendlyByteBuf, PhoenixFlightPayload> CODEC = StreamCodec.of((buffer, payload) -> {
        buffer.writeVarInt(payload.durationTicks()); buffer.writeVarInt(payload.actors().size());
        for (Actor actor : payload.actors()) {
            buffer.writeVarInt(actor.entityId()); buffer.writeUUID(actor.entityUuid());
            buffer.writeVarInt(actor.age()); buffer.writeBoolean(actor.managed());
        }
    }, buffer -> {
        int ttl = buffer.readVarInt(); int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ACTORS) throw new IllegalArgumentException("鳳翼數量超過上限");
        var actors = new ArrayList<Actor>(size);
        for (int i = 0; i < size; i++) actors.add(new Actor(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt(), buffer.readBoolean()));
        return new PhoenixFlightPayload(ttl, actors);
    });
    public static synchronized void init() {
        if (initialized) return;
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC); initialized = true;
    }
    public void send(ServerPlayer player) { if (ServerPlayNetworking.canSend(player, TYPE)) ServerPlayNetworking.send(player, this); }
    @Override public Type<PhoenixFlightPayload> type() { return TYPE; }
}

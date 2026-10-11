package art.yigua.minecraft;

import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** 當事玩家的短暫天空視覺；不包含天氣、世界或其他玩家的寫入指令。 */
public record SkyOmenPayload(String mode, int durationTicks, long seed) implements CustomPacketPayload {
    public static final Set<String> MODES = Set.of(
        "purple", "cloud", "dark", "five_qi", "flowers", "birds", "rapport", "sea");
    public static final int MAX_DURATION_TICKS = 1200;
    private static final int MAX_MODE_LENGTH = 16;
    public static final Type<SkyOmenPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("yigua", "sky_omen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkyOmenPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeUtf(payload.mode(), MAX_MODE_LENGTH);
            buffer.writeVarInt(payload.durationTicks());
            buffer.writeLong(payload.seed());
        },
        buffer -> new SkyOmenPayload(buffer.readUtf(MAX_MODE_LENGTH), buffer.readVarInt(), buffer.readLong()));
    private static boolean initialized;

    public SkyOmenPayload {
        if (mode == null || !MODES.contains(mode)) throw new IllegalArgumentException("未知的天空異象");
        if (durationTicks < 1 || durationTicks > MAX_DURATION_TICKS) {
            throw new IllegalArgumentException("天空異象時長必須介於 1 與 1200 遊戲刻");
        }
    }

    /** 僅註冊伺服器到玩家端的遊戲封包；共同初始化可安全重複呼叫。 */
    public static synchronized void init() {
        if (initialized) return;
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        initialized = true;
    }

    public void send(ServerPlayer player) {
        send(player, this);
    }

    public static void send(ServerPlayer player, SkyOmenPayload omen) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(omen, "omen");
        if (ServerPlayNetworking.canSend(player, TYPE)) ServerPlayNetworking.send(player, omen);
    }

    @Override public Type<SkyOmenPayload> type() { return TYPE; }
}

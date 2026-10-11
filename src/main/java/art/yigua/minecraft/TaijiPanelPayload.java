package art.yigua.minecraft;

import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** 只有伺服器驗證解鎖及手持工具後，才送出無參數的開啟通知。 */
public record TaijiPanelPayload() implements CustomPacketPayload {
    public static final Type<TaijiPanelPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("yigua", "taiji_panel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TaijiPanelPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> Objects.requireNonNull(payload, "payload"),
        buffer -> new TaijiPanelPayload());
    private static boolean initialized;

    public static synchronized void init() {
        if (initialized) return;
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        initialized = true;
    }

    public static void send(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (!player.isAlive() || !YiguaMod.canUseTaiji(player)
                || (player.getMainHandItem().getItem() != YiguaMod.TAIJI
                    && player.getOffhandItem().getItem() != YiguaMod.TAIJI)) return;
        if (ServerPlayNetworking.canSend(player, TYPE)) ServerPlayNetworking.send(player, new TaijiPanelPayload());
    }

    @Override public Type<TaijiPanelPayload> type() { return TYPE; }
}

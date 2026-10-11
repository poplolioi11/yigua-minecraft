package art.yigua.minecraft;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** 獨立封包核對：正常模式與邊界往返，拒絕未知模式、非法時長與截斷內容。 */
public final class SkyOmenPayloadSelfTest {
    private static int checks;
    private SkyOmenPayloadSelfTest() {}

    public static void main(String[] args) {
        for (String mode : SkyOmenPayload.MODES) {
            for (int ticks : new int[] {1, 60, 1200}) {
                for (long seed : new long[] {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
                    var value = new SkyOmenPayload(mode, ticks, seed);
                    var buffer = buffer();
                    try {
                        SkyOmenPayload.CODEC.encode(buffer, value);
                        require(SkyOmenPayload.CODEC.decode(buffer).equals(value), "封包往返不一致");
                        require(buffer.readableBytes() == 0, "合法封包留下額外內容");
                    } finally { buffer.release(); }
                }
            }
        }
        for (String mode : new String[] {null, "", "PURPLE", "DIVINE", "unknown", "purple ", "divine ", "abcdefghijklmnopq"}) {
            rejected(() -> new SkyOmenPayload(mode, 60, 0));
            if (mode != null) invalidWire(mode, 60);
        }
        for (int ticks : new int[] {Integer.MIN_VALUE, -1, 0, 1201, Integer.MAX_VALUE}) {
            rejected(() -> new SkyOmenPayload("purple", ticks, 0));
            invalidWire("purple", ticks);
        }
        var truncated = buffer();
        try {
            truncated.writeUtf("sea"); truncated.writeVarInt(60); truncated.writeInt(1);
            rejected(() -> SkyOmenPayload.CODEC.decode(truncated));
        } finally { truncated.release(); }
        require(SkyOmenPayload.TYPE.id().toString().equals("yigua:sky_omen_v2"), "封包識別名稱不符");
        require(SkyOmenPayload.MODES.contains("divine"), "神光模式未加入封包白名單");
        art.yigua.minecraft.client.SkyOmenWorldRendererSelfTest.verify();
        System.out.println("SkyOmenPayloadSelfTest：" + checks + " 項封包核對通過。");
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static void invalidWire(String mode, int ticks) {
        var buffer = buffer();
        try {
            buffer.writeUtf(mode); buffer.writeVarInt(ticks); buffer.writeLong(0);
            rejected(() -> SkyOmenPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    private static void rejected(Runnable action) {
        try { action.run(); }
        catch (RuntimeException expected) { checks++; return; }
        throw new AssertionError("非法封包未被拒絕");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

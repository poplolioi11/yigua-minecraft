package art.yigua.minecraft.client;

import art.yigua.minecraft.InsightPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/** 伺服器軌跡短暫快取；死亡、換世界、斷線或停止更新即撤回虛影。 */
public final class TemporalInsightClient {
    private static boolean initialized;
    private static InsightPayload current;
    private static ClientLevel level;
    private static LocalPlayer player;
    private static int elapsed;
    private TemporalInsightClient() {}

    public static synchronized void init() {
        if (initialized) return;
        InsightPayload.init();
        TemporalInsightWorldRenderer.init();
        ClientPlayNetworking.registerGlobalReceiver(InsightPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                Minecraft minecraft = context.client();
                if (minecraft.player == null || minecraft.level == null || !minecraft.player.isAlive()
                        || payload.traces().isEmpty()) { clear(); return; }
                current = payload; level = minecraft.level; player = minecraft.player; elapsed = 0;
            }));
        ClientPlayConnectionEvents.DISCONNECT.register((connection, minecraft) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            if (current == null) return;
            if (minecraft.level != level || minecraft.player != player || player == null || !player.isAlive()
                    || player.isSpectator()) clear();
            else if (!minecraft.isPaused() && ++elapsed >= current.durationTicks()) clear();
        });
        initialized = true;
    }
    static InsightPayload current(Minecraft minecraft) {
        return minecraft.level == level && minecraft.player == player && player != null && player.isAlive() ? current : null;
    }
    private static void clear() { current = null; level = null; player = null; elapsed = 0; }
}

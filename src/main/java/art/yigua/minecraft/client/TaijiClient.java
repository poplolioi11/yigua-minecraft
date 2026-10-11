package art.yigua.minecraft.client;

import art.yigua.minecraft.TaijiPanelPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 不攔截工具使用或推算成就；只接受伺服器許可後顯示選擇頁。 */
public final class TaijiClient {
    private static boolean initialized;

    private TaijiClient() {}

    public static synchronized void init() {
        if (initialized) return;
        TaijiPanelPayload.init();
        ClientPlayNetworking.registerGlobalReceiver(TaijiPanelPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (context.client().player != null && context.client().level != null
                        && context.client().player.isAlive()) {
                    context.client().gui.setScreen(new TaijiScreen());
                }
            }));
        initialized = true;
    }
}

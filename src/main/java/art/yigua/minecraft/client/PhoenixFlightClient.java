package art.yigua.minecraft.client;

import art.yigua.minecraft.PhoenixFlight;
import art.yigua.minecraft.PhoenixFlightPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.effect.MobEffects;

/** 只接受伺服器授權的翼快照；本機預測保持與伺服器同一振翅公式。 */
public final class PhoenixFlightClient {
    private static boolean initialized;
    private static PhoenixFlightPayload current;
    private static ClientLevel level;
    private static LocalPlayer owner;
    private static int elapsed;
    private PhoenixFlightClient() {}
    public static synchronized void init() {
        if (initialized) return;
        PhoenixFlightPayload.init(); PhoenixFlightWorldRenderer.init();
        ClientPlayNetworking.registerGlobalReceiver(PhoenixFlightPayload.TYPE, (payload, context) -> context.client().execute(() -> {
            Minecraft minecraft = context.client();
            if (minecraft.player == null || minecraft.level == null || !minecraft.player.isAlive() || payload.actors().isEmpty()) { clear(); return; }
            current = payload; level = minecraft.level; owner = minecraft.player; elapsed = 0;
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((connection, minecraft) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            if (current == null) return;
            if (minecraft.level != level || minecraft.player != owner || owner == null || !owner.isAlive()) { clear(); return; }
            if (minecraft.isPaused()) return;
            if (++elapsed >= current.durationTicks()) { clear(); return; }
            PhoenixFlightPayload.Actor actor = ownActor(minecraft);
            if (actor == null || !actor.managed() || !canPredict(owner)) return;
            Vec3 motion = owner.getDeltaMovement();
            if (Math.abs(motion.y) > 0.8 || !Double.isFinite(motion.y)) return;
            boolean upward = owner.input.keyPresses.jump() && owner.getY() < level.getMaxY() + 32;
            double vertical = PhoenixFlight.verticalVelocity(motion.y, upward, owner.input.keyPresses.shift(), actor.age() + elapsed);
            if (Double.isFinite(vertical)) { owner.setDeltaMovement(motion.x, vertical, motion.z); owner.resetFallDistance(); }
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("yigua", "phoenix_wings"),
            (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(false)));
        initialized = true;
    }
    private static boolean canPredict(LocalPlayer player) {
        return !player.getAbilities().mayfly && !player.getAbilities().flying && !player.isCreative() && !player.isSpectator()
            && !player.isPassenger() && !player.isFallFlying() && !player.isInWater() && !player.isInLava() && !player.onClimbable()
            && !player.isSleeping() && !player.hasEffect(MobEffects.LEVITATION);
    }
    static PhoenixFlightPayload current(Minecraft minecraft) { return minecraft.level == level && minecraft.player == owner ? current : null; }
    static int elapsed() { return elapsed; }
    static PhoenixFlightPayload.Actor ownActor(Minecraft minecraft) {
        PhoenixFlightPayload payload = current(minecraft);
        if (payload == null || minecraft.player == null) return null;
        return payload.actors().stream().filter(actor -> actor.entityId() == minecraft.player.getId()
            && actor.entityUuid().equals(minecraft.player.getUUID())).findFirst().orElse(null);
    }
    private static void clear() { current = null; level = null; owner = null; elapsed = 0; }
    private static void draw(GuiGraphicsExtractor graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance(); PhoenixFlightPayload.Actor actor = ownActor(minecraft);
        if (actor == null || minecraft.player == null || !minecraft.player.isAlive() || minecraft.gui.screen() != null
                || minecraft.gui.hud.isHidden() || !minecraft.options.getCameraType().isFirstPerson()) return;
        double beat = PhoenixFlightWorldRenderer.wingBeat(actor.age() + elapsed + partialTick);
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        for (int side : new int[] {-1, 1}) for (int feather = 0; feather < 9; feather++) {
            int distance = 7 + feather * 4;
            int x = side < 0 ? distance : width - distance - 9;
            int y = height - 24 - feather * 3 + (int)Math.round(beat * (5 + feather));
            int color = feather % 3 == 0 ? 0xC5FFE09B : feather % 3 == 1 ? 0xBFFFBA56 : 0xAEEB7450;
            graphics.fill(x, y, x + 9, y + 4, color);
            graphics.fill(x + (side < 0 ? -2 : 6), y + 4, x + (side < 0 ? 3 : 11), y + 8, color);
        }
        if (actor.managed()) graphics.text(minecraft.font, "鳳翼：空白升空・潛行下降・放開緩降", 8, height - 54, 0xFFFFD99B, true);
    }
}

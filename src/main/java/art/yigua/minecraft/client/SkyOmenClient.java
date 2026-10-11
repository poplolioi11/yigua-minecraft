package art.yigua.minecraft.client;

import art.yigua.minecraft.SkyOmenPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;

/** 上方三分之一內的透明像素異象；不替換天空渲染器、不改世界天氣。 */
public final class SkyOmenClient {
    private static final int[] FIVE_COLORS = {0x86D9C5, 0xF49A88, 0xEBD681, 0xD9EBF4, 0x98ACDF};
    private static boolean initialized;
    private static SkyOmenPayload current;
    private static ClientLevel currentLevel;
    private static int elapsedTicks;

    private SkyOmenClient() {}

    public static synchronized void init() {
        if (initialized) return;
        SkyOmenPayload.init();
        ClientPlayNetworking.registerGlobalReceiver(SkyOmenPayload.TYPE,
            (payload, context) -> context.client().execute(() -> receive(context.client(), payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((connection, minecraft) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            if (current == null) return;
            if (minecraft.player == null || minecraft.level == null || minecraft.level != currentLevel
                    || !minecraft.player.isAlive()) {
                clear();
            } else if (!minecraft.isPaused() && ++elapsedTicks >= current.durationTicks()) {
                clear();
            }
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("yigua", "sky_omen"),
            (graphics, delta) -> draw(graphics, delta.getGameTimeDeltaPartialTick(false)));
        initialized = true;
    }

    private static void receive(Minecraft minecraft, SkyOmenPayload payload) {
        if (minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive()) return;
        // 同時僅保留一個異象；新秘效替換一般紫氣／雲霧，不累積繪圖工作。
        current = payload;
        currentLevel = minecraft.level;
        elapsedTicks = 0;
    }

    private static void clear() {
        current = null;
        currentLevel = null;
        elapsedTicks = 0;
    }

    private static void draw(GuiGraphicsExtractor graphics, float partialTick) {
        SkyOmenPayload omen = current;
        Minecraft minecraft = Minecraft.getInstance();
        if (omen == null || minecraft.level != currentLevel || minecraft.player == null
                || !minecraft.player.isAlive() || minecraft.gui.screen() != null) return;
        int width = graphics.guiWidth();
        int sky = Math.min(100, graphics.guiHeight() / 3);
        if (width < 1 || sky < 1) return;
        double time = Math.clamp(elapsedTicks + partialTick, 0, omen.durationTicks());
        double fade = Math.min(1, time / Math.min(10.0, omen.durationTicks() / 3.0))
            * Math.min(1, (omen.durationTicks() - time) / Math.min(20.0, omen.durationTicks() / 3.0));
        Painter painter = new Painter(graphics, width, sky, fade);
        switch (omen.mode()) {
            case "purple" -> {
                halo(painter, width / 2, sky / 3, Math.max(15, width / 3), 0xAC8BDD, time);
                clouds(painter, omen.seed(), time, 0xBAA2E5, 7, 30);
            }
            case "cloud" -> clouds(painter, omen.seed(), time, 0xD6DCE5, 9, 42);
            case "dark" -> clouds(painter, omen.seed(), time, 0x343A4F, 11, 60);
            case "five_qi" -> fiveQi(painter, time);
            case "flowers" -> flowers(painter, time);
            case "birds" -> birds(painter, omen.seed(), time);
            case "rapport" -> {
                halo(painter, width / 2, sky / 3, Math.max(15, width / 4), 0x8CD9AE, time);
                motes(painter, omen.seed(), time);
            }
            case "sea" -> sea(painter, time);
            default -> throw new IllegalStateException("天空異象模式未處理");
        }
    }

    private static void clouds(Painter p, long seed, double time, int color, int count, int alpha) {
        for (int index = 0; index < count; index++) {
            int size = 16 + (int)(unit(seed, index * 3) * 27);
            int span = p.width + size * 2;
            int x = (int)((unit(seed, index * 3 + 1) * span + time * 0.18) % span) - size;
            int y = 3 + (int)(unit(seed, index * 3 + 2) * Math.max(1, p.sky - 25));
            p.box(x - size / 2, y + 8, size * 2, 7, color, alpha / 2);
            p.box(x, y + 4, size, 13, color, alpha);
            p.box(x + size / 4, y, size / 2, 20, color, alpha / 2);
        }
    }

    private static void halo(Painter p, int centerX, int centerY, int radius, int color, double time) {
        for (int layer = 0; layer < 5; layer++) {
            int r = radius * (5 - layer) / 5;
            int yRadius = 5 + (5 - layer) * 3;
            int pulse = (int)(Math.sin(time * 0.045) * 2);
            p.box(centerX - r, centerY - yRadius + pulse, r * 2, yRadius * 2, color, 7 + layer * 2);
            p.box(centerX - r * 3 / 4, centerY - yRadius - 3 + pulse, r * 3 / 2, yRadius * 2 + 6, color, 5);
        }
    }

    private static void fiveQi(Painter p, double time) {
        int span = Math.min(180, Math.max(30, p.width * 2 / 3));
        int start = (p.width - span) / 2;
        for (int color = 0; color < FIVE_COLORS.length; color++) {
            for (int step = 0; step < 22; step++) {
                int x = start + step * span / 22;
                int y = p.sky / 3 + color * 4 + (int)(Math.sin(step * 0.35 - time * 0.09 + color) * 5);
                p.box(x, y, Math.max(3, span / 22), 3, FIVE_COLORS[color], 74);
            }
        }
        halo(p, p.width / 2, p.sky / 3 + 8, span / 4, 0xFFF0C1, time);
    }

    private static void flowers(Painter p, double time) {
        int center = p.width / 2;
        int spread = Math.min(30, p.width / 6);
        for (int flower = -1; flower <= 1; flower++) {
            int x = center + flower * spread;
            int y = p.sky / 3 + Math.abs(flower) * 9 + (int)(Math.sin(time * 0.06 + flower) * 2);
            int color = FIVE_COLORS[flower + 1];
            for (int petal = 0; petal < 6; petal++) {
                double angle = petal * Math.PI / 3 + Math.sin(time * 0.025) * 0.2;
                int dx = (int)Math.round(Math.cos(angle) * 7);
                int dy = (int)Math.round(Math.sin(angle) * 7);
                p.box(x + dx - 2, y + dy - 2, 5, 5, color, 100);
                p.box(x + dx - 1, y + dy - 3, 3, 7, color, 48);
            }
            p.box(x - 2, y - 2, 5, 5, 0xFFE1A0, 150);
        }
    }

    private static void birds(Painter p, long seed, double time) {
        for (int bird = 0; bird < 24; bird++) {
            int span = p.width + 20;
            int x = (int)((unit(seed, bird * 2) * span + time * 0.55) % span) - 10;
            int y = 4 + (int)(unit(seed, bird * 2 + 1) * Math.max(1, p.sky - 15));
            int wing = (int)(Math.sin(time * 0.4 + bird) * 2);
            int color = bird % 3 == 0 ? 0xDBE7EF : 0x3D4856;
            p.box(x, y, 2, 3, color, 160);
            p.box(x - 3, y - 1 - wing, 3, 2, color, 160);
            p.box(x + 2, y - 1 - wing, 3, 2, color, 160);
            p.box(x - 5, y - 2 - wing, 2, 2, color, 110);
            p.box(x + 5, y - 2 - wing, 2, 2, color, 110);
        }
    }

    private static void motes(Painter p, long seed, double time) {
        for (int mote = 0; mote < 16; mote++) {
            int x = p.width / 4 + (int)(unit(seed, mote * 2) * p.width / 2)
                + (int)(Math.sin(time * 0.04 + mote) * 5);
            int y = (int)(unit(seed, mote * 2 + 1) * Math.max(1, p.sky - 5));
            int alpha = 36 + (int)((Math.sin(time * 0.08 + mote) + 1) * 25);
            p.box(x, y, 2, 2, 0xB8E5BE, alpha);
        }
    }

    private static void sea(Painter p, double time) {
        int wall = Math.max(8, p.width / 7);
        for (int y = 0; y < p.sky; y += 4) {
            int wave = (int)(Math.sin(y * 0.18 - time * 0.08) * 6);
            int thickness = wall + wave;
            p.box(0, y, thickness, 4, 0x4EADC9, 46);
            p.box(p.width - thickness, y, thickness, 4, 0x4EADC9, 46);
            p.box(thickness - 3, y, 3, 3, 0xC1EDF2, 110);
            p.box(p.width - thickness, y, 3, 3, 0xC1EDF2, 110);
        }
    }

    private static double unit(long seed, int index) {
        long value = seed + 0x9E3779B97F4A7C15L * (index + 1L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    /** 全部像素先裁至上方天空區；地面中央與其他介面不會收到填滿指令。 */
    private record Painter(GuiGraphicsExtractor graphics, int width, int sky, double fade) {
        void box(int x, int y, int boxWidth, int boxHeight, int rgb, int opacity) {
            int left = Math.max(0, x);
            int top = Math.max(0, y);
            int right = Math.min(width, x + boxWidth);
            int bottom = Math.min(sky, y + boxHeight);
            int alpha = (int)Math.clamp(Math.round(opacity * fade), 0, 255);
            if (left < right && top < bottom && alpha > 0) {
                graphics.fill(left, top, right, bottom, (alpha << 24) | (rgb & 0xFFFFFF));
            }
        }
    }
}

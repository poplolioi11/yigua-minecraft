package art.yigua.minecraft.client;

import art.yigua.minecraft.InsightPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** 原生生物主模型的半透明時間虛影；只修改獨立 RenderState，不修改活體實體。 */
final class TemporalInsightWorldRenderer {
    private static final RenderStateDataKey<Frame> FRAME = RenderStateDataKey.create(() -> "yigua:temporal_insight");
    private static boolean initialized;
    private TemporalInsightWorldRenderer() {}

    static synchronized void init() {
        if (initialized) return;
        LevelExtractionEvents.END_EXTRACTION.register(context -> context.levelState().setData(FRAME,
            extract(Minecraft.getInstance(), context.levelState().worldPartialTicks)));
        LevelRenderEvents.COLLECT_SUBMITS.register(TemporalInsightWorldRenderer::submit);
        initialized = true;
    }
    record Ghost(Vec3 position, int color, float yaw, LivingEntityRenderState state, List<Box> fallback) {
        Ghost { fallback = List.copyOf(fallback); }
    }
    record Frame(List<Ghost> ghosts) {
        static final Frame EMPTY = new Frame(List.of());
        Frame { ghosts = List.copyOf(ghosts); }
    }
    record Box(double x0, double y0, double z0, double x1, double y1, double z1) {}

    static int color(InsightPayload.Mode mode, int sample) {
        int alpha = Math.max(28, 100 - sample * 13);
        return alpha << 24 | (mode == InsightPayload.Mode.PAST ? 0x80DCC7 : 0xE9C8FF);
    }
    static boolean matching(Mob mob, InsightPayload.Trace trace) {
        return mob != null && mob.isAlive() && mob.getUUID().equals(trace.entityUuid())
            && BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).equals(trace.entityType());
    }
    private static Frame extract(Minecraft minecraft, float partialTick) {
        InsightPayload payload = TemporalInsightClient.current(minecraft);
        if (payload == null || minecraft.level == null || minecraft.player == null) return Frame.EMPTY;
        var ghosts = new ArrayList<Ghost>(InsightPayload.MAX_TRACES * InsightPayload.MAX_SAMPLES);
        var dispatcher = minecraft.getEntityRenderDispatcher();
        for (InsightPayload.Trace trace : payload.traces()) {
            if (!(minecraft.level.getEntity(trace.entityId()) instanceof Mob mob) || !matching(mob, trace)
                    || mob.distanceToSqr(minecraft.player) > 32 * 32 || mob.isInvisibleTo(minecraft.player)) continue;
            for (int index = 0; index < trace.samples().size(); index++) {
                InsightPayload.Sample sample = trace.samples().get(index);
                if (sample.position().distanceToSqr(minecraft.player.position()) > 80 * 80) continue;
                LivingEntityRenderState living = null;
                // 第三方自訂 renderer 可能不遵守原版透明語義，使用像素輪廓而不改它的模型。
                if (trace.entityType().getNamespace().equals("minecraft")
                        && dispatcher.getRenderer(mob).getClass().getName().startsWith("net.minecraft.")) {
                    try {
                        var extracted = dispatcher.extractEntity(mob, partialTick);
                        if (extracted instanceof LivingEntityRenderState state) {
                            living = state;
                            state.x = sample.position().x; state.y = sample.position().y; state.z = sample.position().z;
                            state.bodyRot = sample.yaw(); state.isInvisible = true; state.isInvisibleToPlayer = false;
                            state.displayFireAnimation = false; state.outlineColor = 0; state.nameTag = null;
                            state.scoreText = null; state.nameTagAttachment = null; state.leashStates = null;
                            state.shadowPieces.clear(); state.passengerOffset = null;
                        }
                    } catch (RuntimeException unsupportedRenderer) {
                        // 不讓單一模組／資源包 renderer 的特殊狀態中斷整個世界渲染。
                    }
                }
                ghosts.add(new Ghost(sample.position(), color(payload.mode(), index), sample.yaw(), living,
                    living == null ? silhouette(mob.getBbWidth(), mob.getBbHeight()) : List.of()));
            }
        }
        return ghosts.isEmpty() ? Frame.EMPTY : new Frame(ghosts);
    }

    private static void submit(LevelRenderContext context) {
        Frame frame = context.levelState().getDataOrDefault(FRAME, Frame.EMPTY);
        if (frame.ghosts().isEmpty()) return;
        Vec3 camera = context.levelState().cameraRenderState.pos;
        for (Ghost ghost : frame.ghosts()) {
            if (ghost.state() != null) {
                Minecraft.getInstance().getEntityRenderDispatcher().submit(ghost.state(), context.levelState().cameraRenderState,
                    ghost.position().x - camera.x, ghost.position().y - camera.y, ghost.position().z - camera.z,
                    context.poseStack(), mainModelCollector(context.submitNodeCollector(), ghost.state(), ghost.color()));
            } else {
                PoseStack poses = context.poseStack(); poses.pushPose();
                poses.translate(ghost.position().x - camera.x, ghost.position().y - camera.y, ghost.position().z - camera.z);
                poses.rotateDegrees(Axis.YP, -ghost.yaw());
                context.submitNodeCollector().submitCustomGeometry(poses, RenderTypes.debugQuads(),
                    (pose, buffer) -> { for (Box box : ghost.fallback()) drawBox(pose, buffer, box, ghost.color()); });
                poses.popPose();
            }
        }
    }

    /** 原生模型提交接口的限量代理：僅保留第一個主模型，抑制裝備、影子、名牌與附加層。 */
    static SubmitNodeCollector mainModelCollector(SubmitNodeCollector destination, Object state, int color) {
        boolean[] submitted = {false};
        return (SubmitNodeCollector)Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
            new Class<?>[] {SubmitNodeCollector.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "toString" -> "YiguaGhostCollector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
                if (method.getName().equals("order")) return proxy;
                if (method.getName().equals("submitModel") && args != null && args.length == 9
                        && args[1] == state && !submitted[0]) {
                    submitted[0] = true;
                    args[6] = tintedColor(((Number)args[6]).intValue(), color);
                    args[8] = 0;
                    method.invoke(destination, args);
                }
                return null;
            });
    }
    static int tintedColor(int original, int ghost) {
        return (ghost & 0xFF000000) | (ARGB.multiply(original | 0xFF000000, ghost | 0xFF000000) & 0xFFFFFF);
    }

    /** 自訂 renderer 的替代像素生物輪廓，明確保留頭、軀幹與肢體，不冒充原生模型。 */
    static List<Box> silhouette(double width, double height) {
        width = Math.clamp(width, 0.2, 8); height = Math.clamp(height, 0.2, 8);
        double half = width * 0.34, head = Math.min(width * 0.38, height * 0.22), legs = height * 0.3;
        return List.of(new Box(-half, legs, -half, half, height - head, half),
            new Box(-head, height - head, -head, head, height, head),
            new Box(-half, 0, -half, -half * 0.35, legs, -half * 0.35),
            new Box(half * 0.35, 0, -half, half, legs, -half * 0.35),
            new Box(-half, 0, half * 0.35, -half * 0.35, legs, half),
            new Box(half * 0.35, 0, half * 0.35, half, legs, half));
    }
    private static void drawBox(PoseStack.Pose pose, VertexConsumer buffer, Box b, int color) {
        double[][] v = {{b.x0,b.y0,b.z0},{b.x1,b.y0,b.z0},{b.x1,b.y0,b.z1},{b.x0,b.y0,b.z1},
            {b.x0,b.y1,b.z0},{b.x1,b.y1,b.z0},{b.x1,b.y1,b.z1},{b.x0,b.y1,b.z1}};
        for (int[] face : new int[][] {{0,1,2,3},{7,6,5,4},{0,4,5,1},{2,6,7,3},{3,7,4,0},{1,5,6,2}}) {
            for (int index : face) buffer.addVertex(pose,(float)v[index][0],(float)v[index][1],(float)v[index][2]).setColor(color);
        }
    }
}

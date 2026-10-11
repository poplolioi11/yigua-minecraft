package art.yigua.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

/**
 * 只增加當事玩家可見的局部世界幾何：可轉頭觀看的像素雲冠與緩降神光。
 * Fabric 提取／提交事件保留原版與其他模組的雲、天空、shader 與世界天氣。
 */
final class SkyOmenWorldRenderer {
    private static final RenderStateDataKey<Frame> FRAME = RenderStateDataKey.create(() -> "yigua:world_sky_omen");
    private static final double CLOUD_HEIGHT = 32;
    private static final double BEAM_TOP = 36;
    private static final double BEAM_FORWARD = 8;
    private static boolean initialized;

    private SkyOmenWorldRenderer() {}

    static synchronized void init() {
        if (initialized) return;
        LevelExtractionEvents.END_EXTRACTION.register(context ->
            context.levelState().setData(FRAME, SkyOmenClient.extractWorldFrame(context.levelState().worldPartialTicks)));
        LevelRenderEvents.COLLECT_SUBMITS.register(SkyOmenWorldRenderer::submit);
        initialized = true;
    }

    /** immutable 每幀快照；提交階段不碰玩家／世界或即時 client 狀態。 */
    record Frame(String mode, Vec3 anchor, float angle, double time, double fade, List<CloudBox> clouds) {
        static final Frame EMPTY = new Frame("", Vec3.ZERO, 0, 0, 0, List.of());
        Frame { clouds = List.copyOf(clouds); }
    }

    record CloudBox(double x, double y, double z, double width, double height, double depth, int color) {}

    static Frame frame(String mode, long seed, double time, int duration, Vec3 anchor, float angle) {
        if (!"dark".equals(mode) && !"divine".equals(mode)) return Frame.EMPTY;
        double bounded = Math.clamp(time, 0, duration);
        double fade = fade(bounded, duration);
        var boxes = new ArrayList<CloudBox>(363);
        if ("dark".equals(mode)) {
            // 11×11 連續雲塊包圍起始位置；地形仍以正常 depth test 遮擋雲層。
            for (int row = -5; row <= 5; row++) {
                for (int column = -5; column <= 5; column++) {
                    int cell = (row + 5) * 11 + column + 5;
                    double drift = Math.sin(bounded * 0.009 + cell * 0.4) * 1.6;
                    double x = column * 22 + drift;
                    double z = row * 22 + bounded * 0.025;
                    double y = CLOUD_HEIGHT + unit(seed, cell * 3) * 2;
                    addCloud(boxes, x, y, z, 27 + unit(seed, cell * 3 + 1) * 5,
                        4 + unit(seed, cell * 3 + 2) * 3, 25, 0x30384B);
                }
            }
        } else {
            double gap = divineGap(bounded);
            // 沿收到封包時的視線分兩側推開；中央通道逐步露出天光。
            for (int row = -3; row <= 3; row++) {
                for (int column = 0; column < 6; column++) {
                    int cell = (row + 3) * 6 + column;
                    double y = CLOUD_HEIGHT + unit(seed, cell * 2) * 1.7;
                    double z = BEAM_FORWARD + row * 20 + bounded * 0.02;
                    for (int side : new int[] {-1, 1}) {
                        double x = side * (8 + column * 18 + gap);
                        addCloud(boxes, x, y, z, 22 + unit(seed, cell * 2 + 1) * 3, 3.2, 25, 0xD5DDEA);
                    }
                }
            }
        }
        return new Frame(mode, anchor, angle, bounded, fade, boxes);
    }

    private static void addCloud(List<CloudBox> boxes, double x, double y, double z,
                                 double width, double height, double depth, int color) {
        boxes.add(new CloudBox(x - width / 2, y, z - depth / 2, width, height, depth, color));
        boxes.add(new CloudBox(x - width * 0.38, y + height, z - depth * 0.33,
            width * 0.68, height * 0.65, depth * 0.7, color));
        boxes.add(new CloudBox(x - width * 0.18, y - 1.4, z - depth * 0.22,
            width * 0.48, 1.4, depth * 0.5, color));
    }

    static double divineGap(double time) { return 28 * smooth(time / 70); }
    static double beamBottom(double time) { return CLOUD_HEIGHT * (1 - smooth(time / 100)); }
    static double fade(double time, int duration) {
        if (duration < 1 || !Double.isFinite(time)) return 0;
        return Math.clamp(time / Math.min(10.0, duration / 3.0), 0, 1)
            * Math.clamp((duration - time) / Math.min(20.0, duration / 3.0), 0, 1);
    }
    private static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static void submit(LevelRenderContext context) {
        Frame frame = context.levelState().getDataOrDefault(FRAME, Frame.EMPTY);
        if (frame.fade() <= 0 || frame.clouds().isEmpty()) return;
        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poses = context.poseStack();
        poses.pushPose();
        poses.translate(frame.anchor().x - camera.x, frame.anchor().y - camera.y, frame.anchor().z - camera.z);
        poses.rotateDegrees(Axis.YP, frame.angle());
        SubmitNodeCollector collector = context.submitNodeCollector();
        int cloudAlpha = (int)Math.round(("dark".equals(frame.mode()) ? 222 : 188) * frame.fade());
        collector.submitCustomGeometry(poses, RenderTypes.debugQuads(), (pose, buffer) -> {
            for (CloudBox cloud : frame.clouds()) cloudBox(pose, buffer, cloud, cloudAlpha);
        });
        if ("divine".equals(frame.mode())) {
            double lower = beamBottom(frame.time());
            int core = ((int)Math.round(210 * frame.fade()) << 24) | 0xFFF1B7;
            int glow = ((int)Math.round(30 * frame.fade()) << 24) | 0xFFE8A4;
            collector.submitCustomGeometry(poses, RenderTypes.beaconBeam(BeaconRenderer.BEAM_LOCATION, true),
                (pose, buffer) -> {
                    beam(pose, buffer, lower, BEAM_TOP, 0.32, 0.58, core, frame.time());
                    beam(pose, buffer, lower, BEAM_TOP, 1.1, 2.2, glow, frame.time());
                });
        }
        poses.popPose();
    }

    private static void cloudBox(PoseStack.Pose pose, VertexConsumer buffer, CloudBox box, int alpha) {
        float x0 = (float)box.x(), x1 = (float)(box.x() + box.width());
        float y0 = (float)box.y(), y1 = (float)(box.y() + box.height());
        float z0 = (float)box.z(), z1 = (float)(box.z() + box.depth());
        int bottom = alpha << 24 | shade(box.color(), 0.78);
        int top = alpha << 24 | box.color();
        int side = alpha << 24 | shade(box.color(), 0.90);
        quad(pose, buffer, bottom, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1);
        quad(pose, buffer, top, x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0);
        quad(pose, buffer, side, x0,y0,z0, x0,y1,z0, x1,y1,z0, x1,y0,z0);
        quad(pose, buffer, side, x1,y0,z1, x1,y1,z1, x0,y1,z1, x0,y0,z1);
        quad(pose, buffer, side, x0,y0,z1, x0,y1,z1, x0,y1,z0, x0,y0,z0);
        quad(pose, buffer, side, x1,y0,z0, x1,y1,z0, x1,y1,z1, x1,y0,z1);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, int color,
                             float x0,float y0,float z0, float x1,float y1,float z1,
                             float x2,float y2,float z2, float x3,float y3,float z3) {
        buffer.addVertex(pose, x0,y0,z0).setColor(color);
        buffer.addVertex(pose, x1,y1,z1).setColor(color);
        buffer.addVertex(pose, x2,y2,z2).setColor(color);
        buffer.addVertex(pose, x3,y3,z3).setColor(color);
    }

    private static void beam(PoseStack.Pose pose, VertexConsumer buffer, double lower, double top,
                             double radiusBottom, double radiusTop, int color, double time) {
        float v0 = (float)(-time * 0.025), v1 = (float)((top - lower) * 0.5 - time * 0.025);
        for (int side = 0; side < 4; side++) {
            double angle0 = side * Math.PI / 2 + time * 0.006;
            double angle1 = angle0 + Math.PI / 2;
            beamVertex(pose, buffer, color, radiusTop * Math.cos(angle0), top, BEAM_FORWARD + radiusTop * Math.sin(angle0), 0,v1);
            beamVertex(pose, buffer, color, radiusBottom * Math.cos(angle0), lower, BEAM_FORWARD + radiusBottom * Math.sin(angle0), 0,v0);
            beamVertex(pose, buffer, color, radiusBottom * Math.cos(angle1), lower, BEAM_FORWARD + radiusBottom * Math.sin(angle1), 1,v0);
            beamVertex(pose, buffer, color, radiusTop * Math.cos(angle1), top, BEAM_FORWARD + radiusTop * Math.sin(angle1), 1,v1);
        }
    }

    private static void beamVertex(PoseStack.Pose pose, VertexConsumer buffer, int color,
                                   double x, double y, double z, float u, float v) {
        buffer.addVertex(pose, (float)x,(float)y,(float)z).setColor(color).setUv(u,v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(pose, 0,1,0);
    }

    private static int shade(int color, double amount) {
        return ((int)(((color >> 16) & 255) * amount) << 16)
            | ((int)(((color >> 8) & 255) * amount) << 8) | (int)((color & 255) * amount);
    }

    private static double unit(long seed, int index) {
        long value = seed + 0x9E3779B97F4A7C15L * (index + 1L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }
}

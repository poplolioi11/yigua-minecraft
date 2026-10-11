package art.yigua.minecraft.client;

import art.yigua.minecraft.PhoenixFlight;
import art.yigua.minecraft.PhoenixFlightPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** 原創像素幾何：背上雙翼、環身飛舞的金紅鳳凰與隨行鳥群；不替換玩家模型。 */
public final class PhoenixFlightWorldRenderer {
    private static final RenderStateDataKey<Frame> KEY = RenderStateDataKey.create(() -> "yigua:phoenix_wings");
    private static boolean initialized;
    private PhoenixFlightWorldRenderer() {}
    static synchronized void init() {
        if (initialized) return;
        LevelExtractionEvents.END_EXTRACTION.register(context -> context.levelState().setData(KEY,
            extract(Minecraft.getInstance(), context.levelState().worldPartialTicks)));
        LevelRenderEvents.COLLECT_SUBMITS.register(PhoenixFlightWorldRenderer::submit); initialized = true;
    }
    public record Box(double x, double y, double z, double width, double height, double depth, int color) {}
    record Winged(Vec3 position, float yaw, List<Box> boxes) { Winged { boxes = List.copyOf(boxes); } }
    record Frame(List<Winged> actors) { static final Frame EMPTY = new Frame(List.of()); Frame { actors = List.copyOf(actors); } }
    public static double wingBeat(double age) { return Double.isFinite(age) ? Math.sin((age % PhoenixFlight.BEAT_TICKS) * 2 * Math.PI / PhoenixFlight.BEAT_TICKS) : 0; }
    public static List<Box> geometry(double age) {
        if (!Double.isFinite(age)) return List.of();
        var boxes = new ArrayList<Box>(140); double beat = wingBeat(age);
        // 左右羽翼由肩背向外展開；每根羽毛隨同一振翅相位上下擺動。
        wings(boxes, 0, 1.35, -0.12, 1, beat, 0xFFE9BD, 0xF0A355);
        double orbit = age * 0.021;
        double x = Math.cos(orbit) * 3.1, z = Math.sin(orbit) * 3.1;
        double y = 2.9 + Math.sin(age * 0.041) * 0.55;
        // 鳳凰用繞行切線朝向，尾羽向後延伸；幾何預旋轉避免可變 renderer 狀態。
        var phoenix = new ArrayList<Box>();
        phoenix.add(new Box(-0.23, -0.13, -0.42, 0.46, 0.43, 0.94, 0xFFE0A0));
        phoenix.add(new Box(-0.19, 0.24, 0.26, 0.38, 0.35, 0.36, 0xF2AF42));
        phoenix.add(new Box(-0.10, 0.37, 0.59, 0.20, 0.13, 0.28, 0xFFEFBB));
        phoenix.add(new Box(-0.07, 0.59, 0.26, 0.14, 0.27, 0.13, 0xD85A46));
        phoenix.add(new Box(-0.20, 0.42, 0.46, 0.07, 0.07, 0.07, 0x44222C));
        phoenix.add(new Box(0.13, 0.42, 0.46, 0.07, 0.07, 0.07, 0x44222C));
        wings(phoenix, 0, 0.12, -0.02, 0.85, -beat, 0xFFD277, 0xDC7150);
        for (int tail = -2; tail <= 2; tail++) for (int pixel = 0; pixel < 6; pixel++)
            phoenix.add(new Box(tail * 0.16 + Math.sin(age * 0.05 + pixel * 0.5 + tail) * 0.06,
                -0.12 - pixel * 0.07 + Math.sin(age * 0.065 + pixel * 0.4) * 0.09,
                -0.65 - pixel * 0.23, 0.16, 0.12, 0.3, pixel % 3 == 0 ? 0xE9B54B : pixel % 3 == 1 ? 0xDE694C : 0x78BFB5));
        double yaw = Math.PI - orbit;
        for (Box box : phoenix) rotateBox(boxes, box, x, y, z, yaw);
        // 十二隻有各自振翅相位的小鳥環繞鳳凰，並非生成可掉落的真實生物。
        for (int bird = 0; bird < 12; bird++) {
            double phase = orbit * 0.7 + bird * Math.PI / 6;
            double bx = Math.cos(phase) * (4.2 + bird % 3 * 0.25);
            double bz = Math.sin(phase) * (4.2 + bird % 3 * 0.25);
            double by = 2.2 + bird % 3 * 0.45 + Math.sin(age * 0.05 + bird) * 0.15;
            double flap = wingBeat(age + bird * 2) * 0.16;
            boxes.add(new Box(bx - 0.09, by, bz - 0.13, 0.18, 0.16, 0.32, 0xFFD490));
            boxes.add(new Box(bx - 0.36, by + flap, bz - 0.03, 0.27, 0.08, 0.19, 0xF2AF6B));
            boxes.add(new Box(bx + 0.09, by + flap, bz - 0.03, 0.27, 0.08, 0.19, 0xF2AF6B));
        }
        return List.copyOf(boxes);
    }
    private static void wings(List<Box> boxes, double x, double y, double z, double scale, double beat, int light, int edge) {
        for (int side : new int[] {-1, 1}) for (int feather = 0; feather < 10; feather++) {
            double span = (0.28 + feather * 0.18) * scale;
            double px = x + side * span;
            double py = y + beat * span * 0.65 - feather * 0.014 * scale;
            double pz = z - feather * 0.10 * scale;
            boxes.add(new Box(px - 0.16 * scale, py, pz, 0.33 * scale, 0.13 * scale,
                (0.45 + feather * 0.045) * scale, feather % 3 == 0 ? edge : light));
        }
    }
    private static void rotateBox(List<Box> boxes, Box b, double x, double y, double z, double yaw) {
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double cx = b.x + b.width / 2, cz = b.z + b.depth / 2;
        double width = Math.abs(cos) * b.width + Math.abs(sin) * b.depth;
        double depth = Math.abs(sin) * b.width + Math.abs(cos) * b.depth;
        boxes.add(new Box(x + cx * cos - cz * sin - width / 2, y + b.y,
            z + cx * sin + cz * cos - depth / 2, width, b.height, depth, b.color));
    }
    private static Frame extract(Minecraft minecraft, float partialTick) {
        PhoenixFlightPayload payload = PhoenixFlightClient.current(minecraft);
        if (payload == null || minecraft.level == null || minecraft.player == null) return Frame.EMPTY;
        var actors = new ArrayList<Winged>();
        for (PhoenixFlightPayload.Actor actor : payload.actors()) {
            if (!(minecraft.level.getEntity(actor.entityId()) instanceof Player player) || !player.getUUID().equals(actor.entityUuid())
                    || !player.isAlive() || player.isSpectator() || player.isInvisibleTo(minecraft.player)
                    || player.distanceToSqr(minecraft.player) > 64 * 64) continue;
            actors.add(new Winged(player.getPosition(partialTick), player.getYRot(), geometry(actor.age() + PhoenixFlightClient.elapsed() + partialTick)));
        }
        return new Frame(actors);
    }
    private static void submit(LevelRenderContext context) {
        Frame frame = context.levelState().getDataOrDefault(KEY, Frame.EMPTY);
        if (frame.actors.isEmpty()) return;
        Vec3 camera = context.levelState().cameraRenderState.pos;
        for (Winged actor : frame.actors) {
            PoseStack poses = context.poseStack(); poses.pushPose();
            poses.translate(actor.position.x - camera.x, actor.position.y - camera.y, actor.position.z - camera.z);
            poses.rotateDegrees(Axis.YP, -actor.yaw);
            context.submitNodeCollector().submitCustomGeometry(poses, RenderTypes.debugQuads(), (pose, buffer) -> {
                for (Box box : actor.boxes) box(pose, buffer, box);
            });
            poses.popPose();
        }
    }
    private static void box(PoseStack.Pose pose, VertexConsumer buffer, Box b) {
        float x = (float)b.x, y = (float)b.y, z = (float)b.z;
        float a = (float)(b.x + b.width), c = (float)(b.y + b.height), d = (float)(b.z + b.depth);
        int top = 0xEB000000 | b.color, side = 0xDE000000 | shade(b.color, 0.87), bottom = 0xD9000000 | shade(b.color, 0.75);
        quad(pose,buffer,top,x,c,d,a,c,d,a,c,z,x,c,z);
        quad(pose,buffer,bottom,x,y,z,a,y,z,a,y,d,x,y,d);
        quad(pose,buffer,side,x,y,z,x,c,z,a,c,z,a,y,z);
        quad(pose,buffer,side,a,y,d,a,c,d,x,c,d,x,y,d);
        quad(pose,buffer,side,x,y,d,x,c,d,x,c,z,x,y,z);
        quad(pose,buffer,side,a,y,z,a,c,z,a,c,d,a,y,d);
    }
    private static int shade(int color, double scale) { return (int)(((color >> 16) & 255) * scale) << 16
        | (int)(((color >> 8) & 255) * scale) << 8 | (int)((color & 255) * scale); }
    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, int color, float x0,float y0,float z0,
            float x1,float y1,float z1, float x2,float y2,float z2, float x3,float y3,float z3) {
        buffer.addVertex(pose,x0,y0,z0).setColor(color); buffer.addVertex(pose,x1,y1,z1).setColor(color);
        buffer.addVertex(pose,x2,y2,z2).setColor(color); buffer.addVertex(pose,x3,y3,z3).setColor(color);
    }
}

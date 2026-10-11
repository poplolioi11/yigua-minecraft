package art.yigua.minecraft;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** 虛影封包上限、惡意資料拒絕與不可變快照驗收。 */
public final class InsightPayloadSelfTest {
    private static int checks;
    public static void main(String[] args) {
        var samples = new ArrayList<InsightPayload.Sample>();
        for (int index = 0; index < 6; index++) samples.add(new InsightPayload.Sample(new Vec3(index, 64, -index), index * 30));
        for (InsightPayload.Mode mode : InsightPayload.Mode.values()) {
            for (int count : new int[] {0, 1, 24}) {
                var traces = new ArrayList<InsightPayload.Trace>();
                for (int id = 0; id < count; id++) traces.add(new InsightPayload.Trace(id, new UUID(1,id), Identifier.parse("minecraft:zombie"), samples));
                for (int ticks : new int[] {1, 20, 40}) {
                    var payload = new InsightPayload(mode, ticks, traces); var buffer = buffer();
                    try {
                        InsightPayload.CODEC.encode(buffer, payload);
                        require(buffer.readableBytes() < 6000, "最大合法封包應保持小於6KB");
                        require(payload.equals(InsightPayload.CODEC.decode(buffer)) && buffer.readableBytes() == 0, "合法虛影往返");
                    } finally { buffer.release(); }
                }
            }
        }
        var trace = new InsightPayload.Trace(1, new UUID(1,1), Identifier.parse("minecraft:zombie"), samples);
        samples.clear(); require(trace.samples().size() == 6, "建構後不可被外部清空樣本");
        rejected(() -> trace.samples().clear());
        for (int ticks : new int[] {-1,0,41,Integer.MAX_VALUE}) rejected(() -> new InsightPayload(InsightPayload.Mode.PAST,ticks,List.of()));
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY,30_000_001}) {
            rejected(() -> new InsightPayload.Sample(new Vec3(value,0,0),0));
            rejected(() -> new InsightPayload.Sample(new Vec3(0,value,0),0));
            rejected(() -> new InsightPayload.Sample(new Vec3(0,0,value),0));
        }
        rejected(() -> new InsightPayload.Sample(Vec3.ZERO, Float.NaN));
        rejected(() -> new InsightPayload.Trace(-1, new UUID(1,1), Identifier.parse("minecraft:zombie"), trace.samples()));
        rejected(() -> new InsightPayload.Trace(1, new UUID(1,1), Identifier.parse("minecraft:zombie"), List.of()));
        rejected(() -> new InsightPayload.Trace(1, new UUID(1,1), Identifier.parse("minecraft:zombie"), java.util.Collections.nCopies(7,trace.samples().getFirst())));
        rejected(() -> new InsightPayload(InsightPayload.Mode.PAST,20,List.of(trace,trace)));
        rejected(() -> new InsightPayload(InsightPayload.Mode.PAST,20,java.util.Collections.nCopies(25,trace)));
        for (int count : new int[] {-1,25,Integer.MAX_VALUE}) {
            var buffer = buffer();
            try { buffer.writeByte(0);buffer.writeVarInt(20);buffer.writeVarInt(count); rejected(() -> InsightPayload.CODEC.decode(buffer)); }
            finally { buffer.release(); }
        }
        {
            var buffer = buffer();
            try { buffer.writeByte(99); rejected(() -> InsightPayload.CODEC.decode(buffer)); } finally { buffer.release(); }
        }
        {
            var buffer = buffer();
            try { buffer.writeByte(0);buffer.writeVarInt(20);buffer.writeVarInt(1); rejected(() -> InsightPayload.CODEC.decode(buffer)); }
            finally { buffer.release(); }
        }
        require(InsightPayload.TYPE.id().toString().equals("yigua:temporal_insight_v1"), "獨立能力封包識別");
        art.yigua.minecraft.client.TemporalInsightWorldRendererSelfTest.verify();
        System.out.println("InsightPayloadSelfTest："+checks+"項封包核對通過。");
    }
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY); }
    private static void require(boolean condition,String message) { checks++;if(!condition)throw new AssertionError(message); }
    private static void rejected(Runnable action) { try {action.run();}catch(RuntimeException expected){checks++;return;}throw new AssertionError("非法資料未被拒絕"); }
}

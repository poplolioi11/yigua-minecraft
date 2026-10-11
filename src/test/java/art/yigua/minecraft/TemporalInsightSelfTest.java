package art.yigua.minecraft;

import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 真實 Vec3／資料型別的有界軌跡計算；不把預測測試當成 GPU 驗證。 */
public final class TemporalInsightSelfTest {
    private static int checks;
    public static void main(String[] args) {
        Vec3 origin = new Vec3(1, 60, 1);
        equal(TemporalInsight.predict(origin, new Vec3(0.1,0,0), List.of(),0.1,30), new Vec3(4,60,1), "無路徑按每刻位移預測");
        List<Vec3> path = List.of(new Vec3(3,60,1),new Vec3(3,60,5));
        equal(TemporalInsight.predict(origin, Vec3.ZERO,path,0.1,10),new Vec3(2,60,1),"跟隨 AI 目前路徑");
        equal(TemporalInsight.predict(origin,Vec3.ZERO,path,0.1,30),new Vec3(3,60,2),"路徑轉角而非穿牆直線");
        equal(TemporalInsight.predict(origin,Vec3.ZERO,path,0.1,60),new Vec3(3,60,5),"抵達 AI 目標停止");
        equal(TemporalInsight.predict(origin,Vec3.ZERO,path,0.35,60),new Vec3(3,60,5),"不跑出既有路徑尾端");
        equal(TemporalInsight.boundedVelocity(new Vec3(Double.NaN,0,0)),Vec3.ZERO,"忽略非法外部速度");
        equal(TemporalInsight.boundedVelocity(new Vec3(0,Double.POSITIVE_INFINITY,0)),Vec3.ZERO,"忽略無限外部速度");
        equal(TemporalInsight.predict(origin,Vec3.ZERO,path,Double.NaN,60),origin,"非法導航速度不污染封包");
        check(TemporalInsight.safeYaw(Float.NaN)==0 && TemporalInsight.safeYaw(Float.POSITIVE_INFINITY)==0,"非法外部方位以零替代");
        check(TemporalInsight.boundedVelocity(new Vec3(50,40,-30)).length() <=0.35000001,"預測速度有界且不改實際速度");
        ArrayDeque<TemporalInsight.Snapshot> history = new ArrayDeque<>();
        for(int now=0;now<=10000;now+=10) {
            history.addLast(new TemporalInsight.Snapshot(now,new Vec3(now,60,0),now));
            TemporalInsight.trim(history,now);
            check(history.size()<=7,"每個生物最多七個歷史取樣");
            List<InsightPayload.Sample> samples=TemporalInsight.past(history,now);
            check(samples.size()<=6,"封包最多六個過去虛影");
            int index=1;
            for(var sample:samples) {
                equal(sample.position(),new Vec3(now-index*10,60,0),"過去位置依半秒至三秒倒序");
                index++;
            }
        }
        TemporalInsight.trim(history,10100);
        check(history.isEmpty(),"過期樣本完整移除");
        int wrap=Integer.MIN_VALUE+30;
        history.add(new TemporalInsight.Snapshot(Integer.MAX_VALUE-29,new Vec3(1,60,0),0));
        TemporalInsight.trim(history,wrap);
        check(history.size()==1,"跨整數溢位的六十刻樣本保留");
        TemporalInsight.trim(history,wrap+1);
        check(history.isEmpty(),"跨整數溢位的六十一刻樣本移除");
        loadedGuards();
        System.out.println("生物軌跡計算驗收通過："+checks+" 項過去取樣、未來路徑與有界檢查。");
    }
    private static void loadedGuards() {
        AABB diagonal = new AABB(15.1,60,15.1,17.4,62,17.4);
        check(TemporalInsight.requiredChunks(diagonal).equals(List.of(new ChunkPos(0,0),new ChunkPos(0,1),new ChunkPos(1,0),new ChunkPos(1,1))),"斜角眼位射線連中間兩個 chunk 也涵蓋");
        int[] probes={0};
        check(TemporalInsight.loadedRegion(diagonal,chunk->{probes[0]++;return true;}),"body／射線涵蓋的全部 chunk 已載入才通行");
        check(probes[0]==4,"最多四個唯讀探測");
        check(!TemporalInsight.loadedRegion(diagonal,chunk->!chunk.equals(new ChunkPos(1,0))),"中間斜角未載入必須停止預測");
        check(!TemporalInsight.loadedRegion(diagonal,chunk->!chunk.equals(new ChunkPos(1,1))),"body 角落未載入亦停止");
        check(TemporalInsight.requiredChunks(new AABB(-0.2,60,-0.2,0.2,61,0.2)).equals(List.of(new ChunkPos(-1,-1),new ChunkPos(-1,0),new ChunkPos(0,-1),new ChunkPos(0,0))),"負座標按 floor 涵蓋");
        for(AABB invalid:List.of(new AABB(Double.NaN,0,0,1,1,1),new AABB(0,0,0,Double.POSITIVE_INFINITY,1,1),
                new AABB(0,Double.NaN,0,1,1,1),new AABB(0,0,0,64,1,1),new AABB(-30000000,0,0,-29999999,1,1))) {
            probes[0]=0;check(!TemporalInsight.loadedRegion(invalid,chunk->{probes[0]++;throw new AssertionError("非法範圍不得呼叫載入器");}),"NaN／無限／過大／世界外拒絕");
            check(probes[0]==0,"拒絕範圍無 chunk 請求");
        }
        for(int x=-32;x<=32;x++)for(int z=-32;z<=32;z++) {
            List<ChunkPos> chunks=TemporalInsight.requiredChunks(new AABB(x-0.4,60,z-0.4,x+0.4,62,z+0.4));
            check(!chunks.isEmpty()&&chunks.size()<=4,"小生物包圍盒於 chunk 邊界均有界");
            for(ChunkPos chunk:chunks)check(chunk.x()>=((int)Math.floor(x-0.4)>>4)&&chunk.x()<=((int)Math.floor(x+0.4)>>4)
                &&chunk.z()>=((int)Math.floor(z-0.4)>>4)&&chunk.z()<=((int)Math.floor(z+0.4)>>4),"沒有框外或重複 chunk");
            check(new java.util.HashSet<>(chunks).size()==chunks.size(),"chunk 清單不重複");
        }
    }
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    private static void equal(Vec3 actual,Vec3 expected,String text){check(actual.distanceToSqr(expected)<1.0E-16,text+": "+actual);}
}

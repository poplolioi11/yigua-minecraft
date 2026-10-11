package art.yigua.minecraft.client;

import art.yigua.minecraft.InsightPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import net.minecraft.client.renderer.SubmitNodeCollector;

/** 原生提交代理只保留透明主模型，以及像素替代模型的幾何上限。 */
public final class TemporalInsightWorldRendererSelfTest {
    private static int checks;
    public static void main(String[] args) { verify(); }
    public static void verify() {
        var received = new ArrayList<Object[]>();
        SubmitNodeCollector destination = (SubmitNodeCollector)Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
            new Class<?>[] {SubmitNodeCollector.class}, (proxy,method,args) -> {
                if (method.getName().equals("submitModel")) received.add(args.clone());
                else if (method.getName().equals("order")) return proxy;
                else throw new AssertionError("虛影不得提交影子、火光、名牌或裝備");
                return null;
            });
        Object owned = new Object();
        int color = TemporalInsightWorldRenderer.color(InsightPayload.Mode.FUTURE,0);
        SubmitNodeCollector collector = TemporalInsightWorldRenderer.mainModelCollector(destination,owned,color);
        var poses = new PoseStack();
        collector.submitShadow(poses,1,java.util.List.of());
        collector.submitModel(null,new Object(),poses,null,0,0,-1,null,0xFFFFFF);
        require(received.isEmpty(),"附加層與影子必須略過");
        collector.order(2).submitModel(null,owned,poses,null,12,13,0x26FFFFFF,null,0xFFFFFF);
        require(received.size()==1,"主模型可經原生 order 接口提交一次");
        require(received.getFirst()[6].equals(color) && received.getFirst()[8].equals(0),"保留指定透明色並關閉輪廓外光");
        collector.submitModel(null,owned,poses,null,0,0,-1,null,0);
        require(received.size()==1,"同一虛影不可重複提交模型");
        require(TemporalInsightWorldRenderer.tintedColor(0x12345678,color)>>>24 == color>>>24,"原始透明值不會將虛影重複乘暗");
        for (InsightPayload.Mode mode : InsightPayload.Mode.values()) {
            int previous = 256;
            for (int sample=0;sample<6;sample++) {
                int alpha=TemporalInsightWorldRenderer.color(mode,sample)>>>24;
                require(alpha>0&&alpha<128&&alpha<previous,"離現在越遠的虛影應更淡且全程透明"); previous=alpha;
            }
        }
        for (double width : new double[]{0.2,0.6,1.5,8}) for(double height:new double[]{0.2,1,2,8}) {
            var boxes=TemporalInsightWorldRenderer.silhouette(width,height);
            require(boxes.size()==6,"替代模型只使用六個像素形體");
            for(var box:boxes) require(box.x0()<box.x1()&&box.y0()<box.y1()&&box.z0()<box.z1()
                &&Double.isFinite(box.x0())&&Double.isFinite(box.y1()),"替代輪廓不產生退化或非法方塊");
        }
        System.out.println("TemporalInsightWorldRendererSelfTest："+checks+"項透明虛影提交核對通過。");
    }
    private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}

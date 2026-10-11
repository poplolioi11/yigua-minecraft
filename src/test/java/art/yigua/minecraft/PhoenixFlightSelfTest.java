package art.yigua.minecraft;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** 真實振翅控制曲線、封包往返、有界幾何及外部速度保持驗收。 */
public final class PhoenixFlightSelfTest {
    private static int checks;
    public static void main(String[] args) {
        double ascent = 0, descent = 0, glide = 0;
        for (int tick = 0; tick < 2400; tick++) {
            double up = PhoenixFlight.verticalVelocity(0, true, false, tick);
            double down = PhoenixFlight.verticalVelocity(0, false, true, tick);
            double rest = PhoenixFlight.verticalVelocity(0, false, false, tick);
            require(up >= 0.175 - 1E-12 && up <= 0.265 + 1E-12, "空白每刻皆有可控上升");
            require(down >= -0.285 - 1E-12 && down <= -0.195 + 1E-12, "潛行每刻皆下降");
            require(rest >= -0.1 - 1E-12 && rest <= -0.01 + 1E-12, "放開永遠緩降而非漂浮");
            require(rest == PhoenixFlight.verticalVelocity(0,true,true,tick), "同按兩鍵不升高");
            ascent += up; descent += down; glide += rest;
        }
        require(Math.abs(ascent - 528) < 1E-8 && Math.abs(descent + 576) < 1E-8 && Math.abs(glide + 132) < 1E-8, "完整百次振翅控制平均值");
        for (double impulse : new double[] {-4,-0.801,0.801,3,Double.POSITIVE_INFINITY})
            require(PhoenixFlight.verticalVelocity(impulse,true,false,0) == impulse, "不吞掉高幅度外部擊退");
        require(Double.isNaN(PhoenixFlight.verticalVelocity(Double.NaN,true,false,0)), "非有限外部速度不改寫");
        require(Double.isFinite(PhoenixFlight.verticalVelocity(0,true,false,Double.MAX_VALUE)), "有限極大相位不溢位");
        require(PhoenixFlight.verticalVelocity(0.3,true,false,Double.NaN) == 0.3, "拒絕非法相位");
        for (int count : new int[] {0,1,16}) for (int ttl : new int[] {1,30,40}) {
            var actors = new ArrayList<PhoenixFlightPayload.Actor>();
            for(int i=0;i<count;i++)actors.add(new PhoenixFlightPayload.Actor(i,new UUID(1,i),i*10,i%2==0));
            var payload = new PhoenixFlightPayload(ttl,actors);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try { PhoenixFlightPayload.CODEC.encode(buffer,payload);
                require(buffer.readableBytes() < 600,"最多16名角色封包小於600 bytes");
                require(PhoenixFlightPayload.CODEC.decode(buffer).equals(payload) && buffer.readableBytes()==0,"翼快照真實 codec 往返");
            } finally {buffer.release();}
            actors.clear(); require(payload.actors().size()==count,"不可變快照");
        }
        var actor = new PhoenixFlightPayload.Actor(1,new UUID(1,1),0,true);
        for(int ttl : new int[] {-1,0,41,Integer.MAX_VALUE})rejected(()->new PhoenixFlightPayload(ttl,List.of()));
        for(int age : new int[] {-1,12001,Integer.MAX_VALUE})rejected(()->new PhoenixFlightPayload.Actor(1,new UUID(1,1),age,true));
        rejected(()->new PhoenixFlightPayload.Actor(-1,new UUID(1,1),0,true));
        rejected(()->new PhoenixFlightPayload(30,List.of(actor,actor)));
        rejected(()->new PhoenixFlightPayload(30,java.util.Collections.nCopies(17,actor)));
        for(int bad : new int[] {-1,17,Integer.MAX_VALUE}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try {buffer.writeVarInt(30);buffer.writeVarInt(bad);rejected(()->PhoenixFlightPayload.CODEC.decode(buffer));}finally{buffer.release();}
        }
        require(PhoenixFlightPayload.TYPE.id().toString().equals("yigua:phoenix_flight_v1"),"獨立且無 C2S 授權入口");
        art.yigua.minecraft.client.PhoenixFlightWorldRendererSelfTest.verify();
        System.out.println("PhoenixFlightSelfTest："+checks+" 項核對通過。");
    }
    private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void rejected(Runnable action){try{action.run();}catch(RuntimeException expected){checks++;return;}throw new AssertionError("未拒絕非法快照");}
}

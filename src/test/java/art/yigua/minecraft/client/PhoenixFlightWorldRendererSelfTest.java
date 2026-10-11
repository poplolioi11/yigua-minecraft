package art.yigua.minecraft.client;

/** 鳳凰／雙翼的相位、輪廓、多鳥與有界頂點驗收；不冒充遊戲像素實測。 */
public final class PhoenixFlightWorldRendererSelfTest {
    private static int checks;
    public static void main(String[] args){verify();}
    public static void verify(){
        require(PhoenixFlightWorldRenderer.wingBeat(0)==0,"振翅起點");
        require(Math.abs(PhoenixFlightWorldRenderer.wingBeat(6)-1)<1E-12,"上振翅");
        require(Math.abs(PhoenixFlightWorldRenderer.wingBeat(18)+1)<1E-12,"下振翅");
        require(Double.isFinite(PhoenixFlightWorldRenderer.wingBeat(Double.MAX_VALUE)),"有限極大相位不溢位");
        require(PhoenixFlightWorldRenderer.wingBeat(Double.NaN)==0,"非法相位安全");
        require(PhoenixFlightWorldRenderer.geometry(Double.NaN).isEmpty(),"非法幾何拒絕");
        var up=PhoenixFlightWorldRenderer.geometry(6);var down=PhoenixFlightWorldRenderer.geometry(18);
        require(up.size()==112 && down.size()==112,"20片背翼／56片鳳凰／12隻三像素鳥，有界112個盒");
        require(up.get(9).y()>down.get(9).y()+2,"玩家羽翼實際由上往下擺動");
        for(int t=0;t<2400;t+=3){
            var boxes=PhoenixFlightWorldRenderer.geometry(t);require(boxes.size()<=140,"每人盒数上限");
            for(var box:boxes){
                require(Double.isFinite(box.x()) && Double.isFinite(box.y()) && Double.isFinite(box.z()),"原生幾何數值有限");
                require(box.width()>0 && box.height()>0 && box.depth()>0,"每片羽毛幾何實際有體積");
                require(Math.abs(box.x())<6 && Math.abs(box.y())<5 && Math.abs(box.z())<6,"所有像素局部範圍有界");
            }
        }
        require(!PhoenixFlightWorldRenderer.geometry(0).equals(PhoenixFlightWorldRenderer.geometry(24)),"鳳凰與鳥群會繞行並擺尾");
        System.out.println("PhoenixFlightWorldRendererSelfTest："+checks+" 項幾何核對通過。");
    }
    private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}

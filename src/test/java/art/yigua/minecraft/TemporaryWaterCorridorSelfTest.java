package art.yigua.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 真實 journal I/O 與共用累積／分批恢復交易；不啟動遊戲，不宣稱原生流體驗證。 */
public final class TemporaryWaterCorridorSelfTest {
    private static int checks;
    private TemporaryWaterCorridorSelfTest() {}
    private static TemporaryWaterCorridor.Journal empty(){return new TemporaryWaterCorridor.Journal("",List.of());}
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("yigua-water-selftest-");
        try{
            journalFormat(folder);columns();accumulation(folder);budgetAndMaximumBytes(folder);crashesAndRecovery(folder);flowOwnership();
            check(TemporaryWaterCorridor.validDuration(1)&&TemporaryWaterCorridor.validDuration(2400)&&TemporaryWaterCorridor.validDuration(12000),"合法期限");
            check(!TemporaryWaterCorridor.validDuration(0)&&!TemporaryWaterCorridor.validDuration(-1)&&!TemporaryWaterCorridor.validDuration(12001),"拒絕錯誤期限");
            check(!TemporaryWaterCorridor.expired(109,10,100)&&TemporaryWaterCorridor.expired(110,10,100),"移動不延長到期邊界");
            check(TemporaryWaterCorridor.expired(Integer.MIN_VALUE+300,Integer.MAX_VALUE-99,400),"tick溢位期限仍正確");
            check(TemporaryWaterCorridor.recoveryDue(false,100,500),"首次取消立即開始一批");
            check(!TemporaryWaterCorridor.recoveryDue(true,100,101),"同一刻重複cancel不做第二批");
            check(TemporaryWaterCorridor.recoveryDue(true,101,101),"有進展下一刻繼續");
            check(!TemporaryWaterCorridor.recoveryDue(true,150,200)&&TemporaryWaterCorridor.recoveryDue(true,200,200),"IO拒絕後retry期滿前不重試");
            check(!TemporaryWaterCorridor.recoveryDue(true,Integer.MAX_VALUE,Integer.MIN_VALUE+1)&&TemporaryWaterCorridor.recoveryDue(true,Integer.MIN_VALUE+1,Integer.MIN_VALUE+1),"retry刻溢位仍正確");
            System.out.println("分海累積／分批恢復／journal："+checks+" 項確定性驗收通過。");
        }finally{try(var files=Files.walk(folder)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void journalFormat(Path folder)throws Exception{
        Path file=folder.resolve("journal.json");var before=new TemporaryWaterCorridor.Journal("minecraft:overworld",List.of(new TemporaryWaterCorridor.Entry(15,60,15,0,true),new TemporaryWaterCorridor.Entry(16,61,16,15,false)));
        TemporaryWaterCorridor.writeJournal(file,before);equal(TemporaryWaterCorridor.readJournal(file),before,"舊schema1原LEVEL與撤權旗標相容");
        String valid=Files.readString(file);
        for(Consumer<JsonObject> corrupt:List.<Consumer<JsonObject>>of(r->r.addProperty("schemaVersion",2),r->r.addProperty("dimension","invalid dimension"),r->r.addProperty("dimension",""),r->r.remove("entries"),r->r.addProperty("unknown",true),r->first(r).addProperty("unknown",1),r->r.getAsJsonArray("entries").add(r.getAsJsonArray("entries").get(0).deepCopy()),r->first(r).addProperty("waterLevel",16),r->first(r).addProperty("waterLevel",-1),r->first(r).addProperty("waterLevel",1.1),r->first(r).addProperty("waterLevel","1"),r->first(r).addProperty("wantedAir","true"),r->first(r).remove("wantedAir"),r->first(r).addProperty("x",30000000),r->first(r).addProperty("x",-30000001),r->first(r).addProperty("y",20000000))){
            JsonObject r=JsonParser.parseString(valid).getAsJsonObject();corrupt.accept(r);String raw=r.toString();Files.writeString(file,raw);rejects(()->TemporaryWaterCorridor.readJournal(file),"損壞JSON拒讀");equal(Files.readString(file),raw,"拒讀不改損壞原檔");
        }
        String tooLarge=" ".repeat(TemporaryWaterCorridor.MAX_JOURNAL_BYTES+1);Files.writeString(file,tooLarge);rejects(()->TemporaryWaterCorridor.readJournal(file),"拒絕超過byte上限");equal(Files.size(file),(long)tooLarge.length(),"超大原檔保留");
        TemporaryWaterCorridor.writeJournal(file,before);valid=Files.readString(file);var duplicate=new TemporaryWaterCorridor.Journal("minecraft:overworld",List.of(before.entries().getFirst(),before.entries().getFirst()));rejects(()->TemporaryWaterCorridor.writeJournal(file,duplicate),"無效記憶體WAL不可落盤");equal(Files.readString(file),valid,"原有效WAL保留");
        TemporaryWaterCorridor.writeJournal(file,empty());equal(TemporaryWaterCorridor.readJournal(file),empty(),"完成後空journal可讀");
        Path block=folder.resolve("not-directory");Files.writeString(block,"keep");rejects(()->TemporaryWaterCorridor.writeJournal(block.resolve("journal.json"),before),"寫前I/O失敗");equal(Files.readString(block),"keep","路徑原檔保留");
        try(var files=Files.list(folder)){check(files.noneMatch(p->p.toString().endsWith(".pending")),"提交不留下pending");}
    }
    private static void columns()throws Exception{
        BlockPos feet=new BlockPos(15,40,15);
        for(Direction d:List.of(Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST)){
            List<BlockPos> columns=TemporaryWaterCorridor.columns(feet,d);equal(columns.size(),108,"九乘十二列");equal(new HashSet<>(columns).size(),108,"不重複");equal(columns.getFirst(),feet,"腳下正中優先");
            check(columns.contains(feet.relative(d.getClockWise(),4))&&columns.contains(feet.relative(d.getClockWise(),-4)),"左右各四格");check(columns.contains(feet.relative(d,11)),"向前十一格");check(!columns.contains(feet.relative(d,12))&&!columns.contains(feet.below()),"不挖海床");
        }
        rejects(()->TemporaryWaterCorridor.columns(feet,Direction.UP),"拒絕垂直方向");
    }
    private static List<BlockPos> volume(BlockPos feet,Direction d,int height){List<BlockPos> p=new ArrayList<>();for(BlockPos c:TemporaryWaterCorridor.columns(feet,d))for(int y=0;y<height;y++)p.add(c.above(y));return p;}
    private static TemporaryWaterCorridor.Journal expandAll(TemporaryWaterCorridor.Journal before,List<BlockPos> positions,Model model)throws Exception{
        var current=before;for(int n=0;n<4;n++){var result=TemporaryWaterCorridor.expand(current,"minecraft:overworld",positions,model);if(result.journal().equals(current))break;current=result.journal();}return current;
    }
    private static void accumulation(Path folder)throws Exception{
        Model model=new Model(folder.resolve("accumulation.json"));BlockPos feet=new BlockPos(15,40,15);List<BlockPos> first=volume(feet,Direction.NORTH,12);model.seed(first);
        var current=expandAll(empty(),first,model);equal(current.entries().size(),1296,"整列十二層水全開，無舊四層上限");equal(model.disk(),current,"整列WAL可重讀");
        check(model.events.indexOf("commit:512")<model.events.indexOf("open:512"),"每新批先WAL後移水");for(BlockPos p:first)equal(model.world.get(p),Model.AIR,"整列原版水已開");
        List<BlockPos> next=volume(feet.north(),Direction.NORTH,12);model.seed(next);model.events.clear();current=expandAll(current,next,model);equal(current.entries().size(),1404,"移動只新增，不收回旧水道");for(BlockPos p:first)equal(model.world.get(p),Model.AIR,"走過的路到期前保留AIR");check(model.events.stream().noneMatch(e->e.startsWith("restore")),"移動不觸發restore");
        List<BlockPos> turn=volume(feet.north(),Direction.WEST,12);model.seed(turn);current=expandAll(current,turn,model);check(current.entries().size()>1404,"轉向累積新路");for(BlockPos p:first)equal(model.world.get(p),Model.AIR,"轉向也保留旧路");
        var preserved=current;model.events.clear();current=TemporaryWaterCorridor.expand(current,"minecraft:overworld",List.of(new BlockPos(500,100,500)),model).journal();equal(current,preserved,"走上乾地不清旧journal");check(model.events.isEmpty()||model.events.equals(List.of("capture")),"乾地不寫空journal或改世界");
        Model waiting=new Model(folder.resolve("waiting.json"));List<BlockPos> dry=List.of(feet);equal(TemporaryWaterCorridor.expand(empty(),"minecraft:overworld",dry,waiting).journal(),empty(),"空水可等待，無假WAL");waiting.seed(dry);check(!TemporaryWaterCorridor.expand(empty(),"minecraft:overworld",dry,waiting).journal().entries().isEmpty(),"之後入水仍建立真WAL");
        var blocked=current.entries().getFirst();model.world.put(blocked.pos(),blocked.waterLevel());List<TemporaryWaterCorridor.Entry> released=new ArrayList<>(current.entries());released.set(0,blocked.release());current=new TemporaryWaterCorridor.Journal("minecraft:overworld",released);model.commit(current);var old=current;
        current=TemporaryWaterCorridor.expand(current,"minecraft:overworld",List.of(blocked.pos()),model).journal();equal(current,old,"同LEVEL外來水撤權後不重新取得");equal(model.world.get(blocked.pos()),blocked.waterLevel(),"同LEVEL桶水保留");
        rejects(()->TemporaryWaterCorridor.expand(currentReference(old),"minecraft:the_nether",first,model),"拒絕跨維度搬WAL");
        List<BlockPos> duplicate=List.of(feet,feet);int events=model.events.size();rejects(()->TemporaryWaterCorridor.expand(empty(),"minecraft:overworld",duplicate,model),"重複候選先拒");equal(model.events.size(),events,"錯誤候選不碰I/O");
        List<BlockPos> huge=new ArrayList<>();for(int n=0;n<TemporaryWaterCorridor.SCAN_BUDGET+1;n++)huge.add(new BlockPos(n,40,0));rejects(()->TemporaryWaterCorridor.expand(empty(),"minecraft:overworld",huge,model),"候選掃描budget");
    }
    private static TemporaryWaterCorridor.Journal currentReference(TemporaryWaterCorridor.Journal value){return value;}
    private static void budgetAndMaximumBytes(Path folder)throws Exception{
        List<TemporaryWaterCorridor.Entry> entries=new ArrayList<>();for(int n=0;n<TemporaryWaterCorridor.MAX_BLOCKS;n++)entries.add(new TemporaryWaterCorridor.Entry(29999488+(n/1024)*16+(n%16),-19999999+(n%1024)/16,-29999999,n%16,true));
        var maximum=new TemporaryWaterCorridor.Journal("minecraft:overworld",entries);Path file=folder.resolve("maximum.json");TemporaryWaterCorridor.writeJournal(file,maximum);equal(TemporaryWaterCorridor.readJournal(file),maximum,"32768格32chunks最長座標完整讀回");long bytes=Files.size(file);check(bytes<=TemporaryWaterCorridor.MAX_JOURNAL_BYTES,"最大合法journal低於8MiB硬上限");System.out.println("32768 格最長座標 journal UTF-8 bytes="+bytes+"，limit="+TemporaryWaterCorridor.MAX_JOURNAL_BYTES);
        Model m=new Model(folder.resolve("budget.json"));BlockPos extra=entries.getFirst().pos().above(100);m.seed(List.of(extra));var result=TemporaryWaterCorridor.expand(maximum,"minecraft:overworld",List.of(extra),m);check(result.limited(),"volume滿只停止擴張");equal(result.journal(),maximum,"額滿不收回旧WAL");equal(m.world.get(extra),m.original.get(extra),"額滿不改新水");
        List<TemporaryWaterCorridor.Entry> chunks=new ArrayList<>();for(int n=0;n<32;n++)chunks.add(new TemporaryWaterCorridor.Entry(n*16,40,0,0,true));var allChunks=new TemporaryWaterCorridor.Journal("minecraft:overworld",chunks);BlockPos chunk33=new BlockPos(512,40,0);m.seed(List.of(chunk33));result=TemporaryWaterCorridor.expand(allChunks,"minecraft:overworld",List.of(chunk33),m);check(result.limited(),"32chunks額滿只停止新增");equal(result.journal(),allChunks,"chunk額滿保留旧路");
        var tooMany=new ArrayList<>(chunks);tooMany.add(new TemporaryWaterCorridor.Entry(512,40,0,0,true));rejects(()->TemporaryWaterCorridor.writeJournal(file,new TemporaryWaterCorridor.Journal("minecraft:overworld",tooMany)),"損壞33chunk拒寫");equal(TemporaryWaterCorridor.readJournal(file),maximum,"拒寫保留旧diskWAL");
    }
    private static void crashesAndRecovery(Path folder)throws Exception{
        List<BlockPos> positions=volume(new BlockPos(0,40,0),Direction.NORTH,12);
        for(int failure=0;failure<2;failure++){
            Model model=new Model(folder.resolve("open-failure-"+failure+".json"));model.seed(positions);if(failure==0)model.failCommit=1;else model.failOpenAt=7;
            rejects(()->TemporaryWaterCorridor.expand(empty(),"minecraft:overworld",positions,model),"WAL或部分open crash");var disk=model.disk();if(failure==0){equal(disk,empty(),"首寫失敗沒有world修改");equal(model.world,model.original,"首寫失敗原水不動");}else equal(disk.entries().size(),512,"部分open每格皆已WAL");
            model.failCommit=0;model.failOpenAt=0;recoverAll(disk,model);for(BlockPos p:positions)equal(model.world.get(p),model.original.get(p),"crash依WAL恢復");
        }
        Model model=new Model(folder.resolve("batch.json"));model.seed(positions);var current=expandAll(empty(),positions,model);BlockPos denied=current.entries().getFirst().pos(),unloaded=current.entries().get(1).pos();model.denied.add(denied);model.unloaded.add(unloaded);
        int cursor=0;for(int n=0;n<8&&current.entries().size()>2;n++){int prior=current.entries().size(),readyBefore=model.lastReadyChecks;var r=TemporaryWaterCorridor.recover(current,cursor,model);check(r.completed()<=512,"每批恢復至多512格");check(model.lastReadyChecks-readyBefore<=2048,"恢復尋找有界");current=r.journal();cursor=r.cursor();check(current.entries().size()<prior,"受拒格不阻擋可復原其他格");}
        equal(current.entries().size(),2,"保護與未載入格保留WAL");check(TemporaryWaterCorridor.claimedPosition(current,denied)&&TemporaryWaterCorridor.claimedPosition(current,unloaded),"待重試格仍跨journal預留");var waiting=TemporaryWaterCorridor.recover(current,cursor,model);equal(waiting.journal(),current,"無進展不清journal");
        model.denied.clear();model.unloaded.clear();recoverAll(current,model);for(BlockPos p:positions)equal(model.world.get(p),model.original.get(p),"自然載入／permission允許後完成原LEVEL恢復");
        for(int failure=0;failure<2;failure++){
            Model crash=new Model(folder.resolve("restore-failure-"+failure+".json"));crash.seed(positions);var opened=expandAll(empty(),positions,crash);crash.commitCalls=0;if(failure==0)crash.failRestore=true;else crash.failCommit=1;
            rejects(()->TemporaryWaterCorridor.recover(opened,0,crash),"批次restore／縮WAL crash");equal(crash.disk(),opened,"world保存或WAL提交失敗不能遺失任何reservation");crash.failRestore=false;crash.failCommit=0;recoverAll(crash.disk(),crash);for(BlockPos p:positions)equal(crash.world.get(p),crash.original.get(p),"縮WAL失敗也可完整恢復");
        }
        Model foreign=new Model(folder.resolve("foreign.json"));foreign.seed(positions);var owned=expandAll(empty(),positions,foreign);var e=owned.entries().getFirst();foreign.world.put(e.pos(),Model.FOREIGN);var e2=owned.entries().get(1);foreign.world.put(e2.pos(),e2.waterLevel());recoverAll(owned,foreign);equal(foreign.world.get(e.pos()),Model.FOREIGN,"foreign實體方塊不覆寫");equal(foreign.world.get(e2.pos()),e2.waterLevel(),"foreign同LEVEL水不覆寫");
        Model protectedModel=new Model(folder.resolve("protected.json"));protectedModel.seed(positions);protectedModel.denied.addAll(positions);equal(TemporaryWaterCorridor.expand(empty(),"minecraft:overworld",positions,protectedModel).journal(),empty(),"permission拒絕不捕獲水");equal(protectedModel.world,protectedModel.original,"保護不改世界");
    }
    private static void recoverAll(TemporaryWaterCorridor.Journal before,Model model)throws Exception{var current=before;int cursor=0;for(int n=0;n<100&&!current.entries().isEmpty();n++){var r=TemporaryWaterCorridor.recover(current,cursor,model);check(r.completed()>0&&r.completed()<=512,"恢復有進展且有界");current=r.journal();cursor=r.cursor();}equal(current,empty(),"所有可復原格完成空WAL");}
    private static void flowOwnership(){BlockPos p=new BlockPos(0,40,0);var entry=new TemporaryWaterCorridor.Entry(0,40,0,0,true);var journal=new TemporaryWaterCorridor.Journal("minecraft:overworld",List.of(entry));check(TemporaryWaterCorridor.ownsAir(journal,p),"已走AIR持續阻自然流");check(!TemporaryWaterCorridor.ownsAir(journal,p.east()),"不阻其他位置");var released=new TemporaryWaterCorridor.Journal("minecraft:overworld",List.of(entry.release()));check(!TemporaryWaterCorridor.ownsAir(released,p),"撤權放行自然流");check(TemporaryWaterCorridor.claimedPosition(released,p),"撤權仍在WAL即預留");check(!TemporaryWaterCorridor.claimedPosition(empty(),p),"清WAL才釋放");}
    private static final class Model implements TemporaryWaterCorridor.ExpansionAccess,TemporaryWaterCorridor.RecoveryAccess{
        static final int AIR=-1,FOREIGN=-2;final Path file;final Map<BlockPos,Integer> world=new HashMap<>(),original=new HashMap<>(),saved=new HashMap<>();final Set<BlockPos> denied=new HashSet<>(),unloaded=new HashSet<>();final List<String> events=new ArrayList<>();int commitCalls,failCommit,failOpenAt,lastReadyChecks;boolean failRestore;
        Model(Path file)throws Exception{this.file=file;TemporaryWaterCorridor.writeJournal(file,empty());}
        void seed(List<BlockPos> p){for(BlockPos pos:p)if(!original.containsKey(pos)){int water=Math.floorMod(pos.getX()+pos.getY()+pos.getZ(),16);original.put(pos,water);world.put(pos,water);}}
        TemporaryWaterCorridor.Journal disk()throws IOException{return TemporaryWaterCorridor.readJournal(file);}
        @Override public TemporaryWaterCorridor.Entry capture(BlockPos p){events.add("capture");Integer s=world.get(p);return s==null||s<0||denied.contains(p)||unloaded.contains(p)?null:new TemporaryWaterCorridor.Entry(p.getX(),p.getY(),p.getZ(),s,true);}
        @Override public void commit(TemporaryWaterCorridor.Journal j)throws IOException{if(++commitCalls==failCommit)throw new IOException("injected WAL failure");TemporaryWaterCorridor.writeJournal(file,j);equal(disk(),j,"每提交立即真實讀回");events.add("commit:"+j.entries().size());}
        @Override public void open(List<TemporaryWaterCorridor.Entry> added)throws IOException{events.add("open:"+added.size());int n=0;for(var e:added){check(world.get(e.pos())==e.waterLevel(),"新open只有尚未改動原水");world.put(e.pos(),AIR);if(++n==failOpenAt)throw new IOException("injected partial open");}}
        @Override public boolean ready(TemporaryWaterCorridor.Entry e){lastReadyChecks++;return !denied.contains(e.pos())&&!unloaded.contains(e.pos());}
        @Override public void restoreAndSave(List<TemporaryWaterCorridor.Entry> batch)throws IOException{events.add("restore:"+batch.size());int n=0;for(var e:batch){if(e.wantedAir()&&world.get(e.pos())==AIR)world.put(e.pos(),e.waterLevel());if(failRestore&&++n==1)throw new IOException("injected save failure");saved.put(e.pos(),world.get(e.pos()));}for(var e:batch)equal(saved.get(e.pos()),world.get(e.pos()),"縮WAL前原block已真實模型保存＋verify");}
    }
    private static JsonObject first(JsonObject root){return root.getAsJsonArray("entries").get(0).getAsJsonObject();}
    @FunctionalInterface private interface Checked{void run()throws Exception;}
    private static void rejects(Checked action,String label)throws Exception{try{action.run();}catch(IOException|IllegalStateException e){checks++;return;}throw new AssertionError(label);}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static void equal(Object a,Object b,String label){check(Objects.equals(a,b),label+"："+a);}
}

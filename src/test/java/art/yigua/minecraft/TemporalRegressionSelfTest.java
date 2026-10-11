package art.yigua.minecraft;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** 不建立遊戲世界的決定性驗收：小樹辨識、元資料、所有權與可中斷的真日誌。 */
public final class TemporalRegressionSelfTest {
    private static int checks;
    private static final String OWNER = "12345678-1234-1234-1234-123456789012";
    private static final String TARGET = "87654321-4321-4321-4321-210987654321";
    private static final TemporalRegression.State AIR = new TemporalRegression.State("minecraft:air", Map.of());
    private static final TemporalRegression.Cell EMPTY = new TemporalRegression.Cell("air", "", true, true, false, false);
    private TemporalRegressionSelfTest() {}
    public static void main(String[] arguments) throws Exception {
        Path folder = Files.createTempDirectory("yigua-temporal-selftest-");
        try {
            mobOwnership(); trees(); blockPredecessors(folder); journal(folder); recovery(folder); batch(folder);
            System.out.println("回溯驗收通過：" + checks + " 項年齡、座標、天然樹、方塊前態、寫前紀錄與復原檢查。");
        } finally {
            try (var files = Files.walk(folder)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
    private static void mobOwnership() throws Exception {
        for (int age = -24000; age < -1; age += 13) {
            check(TemporalRegression.ownsAge("ageable",age,age),"原年齡標記仍屬我方");
            check(TemporalRegression.ownsAge("ageable",age,age+1),"自然長大一刻不失去所有權");
            check(!TemporalRegression.ownsAge("ageable",age,age+2),"外部餵食或跳躍年齡不被覆寫");
            check(!TemporalRegression.ownsAge("ageable",age,age-1),"外部向回改年齡不被覆寫");
        }
        check(TemporalRegression.ownsAge("zombie",-1,-1),"僵屍 baby 標記");
        check(!TemporalRegression.ownsAge("zombie",-1,0),"外部改成年則釋放");
        for (int observed : List.of(-24001,0,100,Integer.MIN_VALUE,Integer.MAX_VALUE)) check(!TemporalRegression.ownsAge("ageable",observed,observed),"超界年齡拒絕");
        check(!TemporalRegression.ownsAge("foreign",-1,-1),"未知轉換種類拒絕");
        for (String id : List.of("frog","parrot","zombie_nautilus","camel_husk","slime","magma_cube")) check(!TemporalRegression.supportsBaby(id),"不可幼化原版種類拒絕");
        for (String id : List.of("cow","pig","sheep","chicken","wolf","cat","horse","villager")) check(TemporalRegression.supportsBaby(id),"原版可幼化種類");
        for (Vec3 pos : List.of(Vec3.ZERO,new Vec3(29999983,319,-29999983))) check(TemporalRegression.finitePosition(pos),"合法有限座標");
        for (Vec3 pos : List.of(new Vec3(Double.NaN,0,0),new Vec3(0,Double.POSITIVE_INFINITY,0),new Vec3(29999984,0,0),new Vec3(0,20000000,0))) check(!TemporalRegression.finitePosition(pos),"NaN無限與世界外拒絕");
        for (String id : List.of("water","lava","fire","cactus","powder_snow","magma_block","nether_portal")) check(TemporalRegression.dangerous(id),"危險傳送落點拒絕");
        check(!TemporalRegression.dangerous("stone"),"一般實心地板");
        JsonObject lease = lease(); var parsed = TemporalRegression.readMobLease(lease.toString());
        equal(parsed.observedAge(),-24000,"真 attachment codec 可重讀");
        for (Consumer<JsonObject> mutate : List.<Consumer<JsonObject>>of(
                o->o.addProperty("kind","foreign"),o->o.addProperty("owner","invalid"),o->o.addProperty("target","invalid"),
                o->o.addProperty("type","foreign:cow"),o->o.addProperty("observedAge",0),o->o.addProperty("observedAge",-24001),
                o->o.addProperty("originalAge",-1),o->o.addProperty("expires",2402),o->o.addProperty("expires",1),
                o->o.addProperty("started",-1),o->o.addProperty("originalAge",1.5),o->o.addProperty("started","1"),
                o->o.addProperty("unknown","private"),o->o.remove("owner"))) {
            JsonObject damaged=lease.deepCopy();mutate.accept(damaged);rejects(()->TemporalRegression.readMobLease(damaged.toString()),"損壞 attachment 不啟動回溯");
        }
        rejects(()->TemporalRegression.readMobLease(lease+" {}"),"尾隨資料拒絕");
    }
    private static JsonObject lease() {
        JsonObject value = new JsonObject(); value.addProperty("owner",OWNER);value.addProperty("target",TARGET);
        value.addProperty("type","minecraft:cow");value.addProperty("kind","ageable");value.addProperty("originalAge",0);
        value.addProperty("observedAge",-24000);value.addProperty("started",1);value.addProperty("expires",2401);return value;
    }
    private static Map<BlockPos,TemporalRegression.Cell> tree(String species) {
        Map<BlockPos,TemporalRegression.Cell> cells=new HashMap<>();BlockPos root=new BlockPos(15,60,15);
        cells.put(root.below(),new TemporalRegression.Cell("soil","",true,true,false,false));
        for (int y=0;y<5;y++)cells.put(root.above(y),new TemporalRegression.Cell("log",species,true,true,false,false));
        for (int y=3;y<=5;y++)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++) {
            if(Math.abs(x)+Math.abs(z)>3 || x==0&&z==0&&y<5)continue;
            cells.put(root.offset(x,y,z),new TemporalRegression.Cell("leaves",species,true,true,false,false));
        }
        return cells;
    }
    private static List<BlockPos> plan(Map<BlockPos,TemporalRegression.Cell> cells) {
        return TemporalRegression.smallTree(new BlockPos(15,60,15),p->cells.getOrDefault(p,EMPTY));
    }
    private static Map<BlockPos,TemporalRegression.Cell> leaningAcacia(int leanHeight, Direction direction) {
        BlockPos root=new BlockPos(15,60,15);var cells=new HashMap<BlockPos,TemporalRegression.Cell>();
        cells.put(root.below(),new TemporalRegression.Cell("soil","",true,true,false,false));
        for(int y=0;y<5;y++)cells.put(root.relative(direction,Math.min(3,Math.max(0,y-leanHeight+1))).above(y),
            new TemporalRegression.Cell("log","acacia",true,true,false,false));
        for(var entry:tree("acacia").entrySet())if(entry.getValue().kind().equals("leaves"))
            cells.putIfAbsent(entry.getKey().relative(direction,3),entry.getValue());
        return cells;
    }
    private static void trees() {
        for(String species:List.of("oak","birch","spruce","cherry","acacia","dark_oak","mangrove","jungle","pale_oak")) {
            var cells=tree(species);var plan=plan(cells);equal(plan.size(),cells.size()-1,"九種完整跨 chunk 天然樹納入");
            equal(new HashSet<>(plan).size(),plan.size(),"不重複座標");check(plan.size()<=1024,"方塊上限");
            for(BlockPos hit:plan)equal(TemporalRegression.smallTree(hit,p->cells.getOrDefault(p,EMPTY)).size(),plan.size(),"從樹幹或天然冠葉起算完整樹");
        }
        BlockPos root=new BlockPos(15,60,15);
        var natural=tree("oak");
        for(String adjacent:List.of("short_grass","dandelion","snow","vine","other_species_leaves")) {
            var cells=new HashMap<>(natural);cells.put(root.offset(1,1,0),new TemporalRegression.Cell("other",adjacent,true,true,false,false));
            equal(plan(cells).size(),natural.size()-1,"鄰近花草雪層不再令自然樹整棵拒絕");
        }
        var branch=tree("acacia");branch.put(root.offset(1,4,0),new TemporalRegression.Cell("log","acacia",true,false,false,false));
        check(plan(branch).contains(root.offset(1,4,0)),"天然水平枝幹可回溯");
        // 原版相思樹最小五層，leanHeight 可為 1 或 2，不能要求底部三層同一 X/Z。
        for(Direction direction:Direction.Plane.HORIZONTAL)for(int leanHeight=1;leanHeight<=2;leanHeight++) {
            var cells=leaningAcacia(leanHeight,direction);var leaning=plan(cells);
            equal(leaning.size(),cells.size()-1,"第二或第三層起傾斜的原版相思樹完整納入");
            check(leaning.contains(root.relative(direction,3).above(4)),"連續斜幹頂端仍納入");
            equal(TemporalRegression.smallTree(root.relative(direction,3).above(5),p->cells.getOrDefault(p,EMPTY)).size(),leaning.size(),"從斜幹相思樹冠葉也能找回單一根");
        }
        List<Consumer<Map<BlockPos,TemporalRegression.Cell>>> brokenAcacia=List.of(
            cells->cells.remove(root.east().above()),
            cells->{cells.remove(root.east().above());cells.put(root.east(2).above(),new TemporalRegression.Cell("log","acacia",true,true,false,false));},
            cells->cells.put(root,new TemporalRegression.Cell("log","acacia",true,false,false,false)),
            cells->{cells.put(root.east().below(),new TemporalRegression.Cell("soil","",true,true,false,false));cells.put(root.east(),new TemporalRegression.Cell("log","acacia",true,true,false,false));},
            cells->cells.entrySet().removeIf(entry->entry.getValue().kind().equals("leaves")));
        for(var mutate:brokenAcacia){var cells=leaningAcacia(1,Direction.EAST);mutate.accept(cells);check(plan(cells).isEmpty(),"相思樹斷層、跳格、橫根、多根與無冠仍拒絕");}
        var leaningOak=leaningAcacia(1,Direction.EAST);leaningOak.replaceAll((pos,cell)->new TemporalRegression.Cell(cell.kind(),cell.species().equals("acacia")?"oak":cell.species(),cell.natural(),cell.vertical(),cell.fluid(),cell.blockEntity(),cell.distance()));
        check(plan(leaningOak).isEmpty(),"相思樹特例不放寬橡木直幹條件");
        for(String species:List.of("oak","birch","spruce","cherry","acacia")) {
            var cells=tree(species);cells.put(root.offset(3,4,0),new TemporalRegression.Cell("leaves",species,true,true,false,false));
            check(plan(cells).contains(root.offset(3,4,0)),"較寬天然冠葉可以回溯");
        }
        var artificial=tree("oak");artificial.replaceAll((p,c)->c.kind().equals("leaves")?new TemporalRegression.Cell("leaves","oak",false,true,false,false):c);
        check(plan(artificial).isEmpty(),"只有 persistent 葉的木造樹拒絕");
        var protectedLeaf=tree("oak");BlockPos protectedPos=root.offset(1,3,0);protectedLeaf.put(protectedPos,new TemporalRegression.Cell("leaves","oak",false,true,false,false));
        check(!plan(protectedLeaf).contains(protectedPos),"混在天然樹冠內的玩家葉仍保留");
        List<Consumer<Map<BlockPos,TemporalRegression.Cell>>> damage=List.of(
            cells->cells.put(root.above(1),new TemporalRegression.Cell("log","oak",true,false,false,false)),
            cells->cells.put(root.above(1),EMPTY),cells->cells.put(root.below(),EMPTY),
            cells->cells.put(root,new TemporalRegression.Cell("log","oak",true,true,true,false)),
            cells->cells.put(root,new TemporalRegression.Cell("log","oak",true,true,false,true)),
            cells->cells.put(root.offset(1,0,0),new TemporalRegression.Cell("unloaded","",false,false,false,false)));
        for(var mutate:damage){var cells=tree("oak");mutate.accept(cells);check(plan(cells).isEmpty(),"斷根、無土、水浸、資料與未載入根部拒絕");}
        var four=new HashMap<BlockPos,TemporalRegression.Cell>();
        for(int x=0;x<2;x++)for(int z=0;z<2;z++) { four.put(root.offset(x,-1,z),new TemporalRegression.Cell("soil","",true,true,false,false));
            for(int y=0;y<9;y++)four.put(root.offset(x,y,z),new TemporalRegression.Cell("log","dark_oak",true,true,false,false)); }
        for(int y=6;y<=10;y++)for(int x=-3;x<=4;x++)for(int z=-3;z<=4;z++) {
            BlockPos p=root.offset(x,y,z);if(!four.containsKey(p))four.put(p,new TemporalRegression.Cell("leaves","dark_oak",true,true,false,false)); }
        var large=plan(four);check(large.size()>128,"真實大小四幹樹冠超過舊 128 上限仍可回溯");check(large.size()<=1024,"大樹保留 1024 上限");
        var merged=tree("oak"); merged.put(root.offset(1,-1,0),new TemporalRegression.Cell("soil","",true,true,false,false));
        for(int y=0;y<5;y++)merged.put(root.offset(1,y,0),new TemporalRegression.Cell("log","oak",true,true,false,false));
        check(plan(merged).isEmpty(),"橡木多根合併拒絕");
        var sparse=tree("oak");sparse.entrySet().removeIf(e->e.getValue().kind().equals("leaves"));check(plan(sparse).isEmpty(),"無自然樹冠的玩家木柱拒絕");
        var unlisted=tree("foreign");check(plan(unlisted).isEmpty(),"非原版樹種拒絕");
        int[] queries={0};TemporalRegression.treePlan(root,p->{queries[0]++;return new TemporalRegression.Cell("log","oak",true,true,false,false);});check(queries[0]<=8192,"大型未知樹不超過原生查詢硬上限");
        var target=new TemporalRegression.State("minecraft:oak_sapling",Map.of("stage","0"));
        check(TemporalRegression.targetMatches(target,new TemporalRegression.State("minecraft:oak_sapling",Map.of("stage","1"))),"自然樹苗階段仍可收回");
        check(!TemporalRegression.targetMatches(target,new TemporalRegression.State("minecraft:oak_log",Map.of("axis","y"))),"長成新樹後不補另一份樹");
        var neighbor=tree("oak");for(var e:tree("oak").entrySet())neighbor.put(e.getKey().offset(6,0,0),e.getValue());
        for(int x=3;x<=5;x++)neighbor.put(root.offset(x,4,0),new TemporalRegression.Cell("leaves","oak",true,true,false,false));
        var one=plan(neighbor);check(!one.contains(root.offset(6,0,0)),"相接冠葉不吞掉鄰樹樹幹");check(!one.contains(root.offset(5,4,0)),"距離鄰樹較近的共用葉保留");
    }
    private static void blockPredecessors(Path folder) throws Exception {
        int coordinate=100;
        for(String id:List.of("wheat","carrots","potatoes","beetroots","nether_wart","cocoa","sweet_berry_bush","torchflower_crop","melon_stem","pumpkin_stem")) {
            int max=Set.of("beetroots","nether_wart","sweet_berry_bush").contains(id)?3:id.equals("cocoa")?2:id.equals("torchflower_crop")?1:7;
            for(int age=1;age<=max;age++) {
                Map<String,String> props=new TreeMap<>();props.put("age",Integer.toString(age));if(id.equals("cocoa"))props.put("facing","east");
                var old=new TemporalRegression.State("minecraft:"+id,props);var previous=TemporalRegression.previous(old);
                equal(previous.state().properties().get("age"),"0","作物由每個成熟階段回到幼苗");
                if(id.equals("cocoa"))equal(previous.state().properties().get("facing"),"east","保留可可方向");
                var entry=new TemporalRegression.Entry(coordinate++,60,15,old,previous.state());var group=new TemporalRegression.Group(OWNER,"minecraft:overworld",2400,previous.kind(),"active",List.of(entry));
                var journal=new TemporalRegression.Journal(List.of(group));Path file=folder.resolve("crop"+coordinate+".json");TemporalRegression.writeJournal(file,journal);equal(TemporalRegression.readJournal(file),journal,"作物前態日誌真寫入與重讀");
                var grown=new TemporalRegression.State(old.id(),props);check(!TemporalRegression.targetMatches(previous.state(),grown),"自然重新長大後不強行回復成熟作物");
            }
            Map<String,String> young=new HashMap<>();young.put("age","0");if(id.equals("cocoa"))young.put("facing","north");
            check(TemporalRegression.previous(new TemporalRegression.State("minecraft:"+id,young))==null,"幼苗不反覆生成新的回溯 lease");
        }
        for(String wax:List.of("","waxed_"))for(String stage:List.of("exposed_","weathered_","oxidized_"))for(String shape:List.of("copper","cut_copper","cut_copper_stairs","cut_copper_slab","chiseled_copper","copper_grate")) {
            Map<String,String> properties=switch(shape){case "cut_copper_stairs"->Map.of("facing","west","half","top","shape","outer_left","waterlogged","false");case "cut_copper_slab"->Map.of("type","double","waterlogged","false");case "copper_grate"->Map.of("waterlogged","false");default->Map.of();};
            var old=new TemporalRegression.State("minecraft:"+wax+stage+shape,properties);var previous=TemporalRegression.previous(old);
            check(previous!=null,"全部六種銅形狀及三階段可回溯");equal(previous.state().properties(),old.properties(),"銅階段轉換保留方向形狀");
            check(previous.state().id().startsWith("minecraft:"+wax),"含蠟銅保留蠟層");TemporalRegression.validate(new TemporalRegression.Journal(List.of(new TemporalRegression.Group(OWNER,"minecraft:overworld",2400,"copper","active",List.of(new TemporalRegression.Entry(coordinate++,60,15,old,previous.state()))))));
        }
        for(String id:List.of("dirt_path","rooted_dirt","coarse_dirt"))equal(TemporalRegression.previous(new TemporalRegression.State("minecraft:"+id,Map.of())).state().id(),"minecraft:dirt","其他地表回到泥土");
        equal(TemporalRegression.previous(new TemporalRegression.State("minecraft:farmland",Map.of("moisture","7"))).state().id(),"minecraft:dirt","耕地回到泥土");
        equal(TemporalRegression.previous(new TemporalRegression.State("minecraft:attached_melon_stem",Map.of("facing","west"))).state(),new TemporalRegression.State("minecraft:melon_stem",Map.of("age","0")),"已結果瓜藤回到幼苗");
        equal(TemporalRegression.previous(new TemporalRegression.State("minecraft:cave_vines",Map.of("age","12","berries","true"))).state(),new TemporalRegression.State("minecraft:cave_vines",Map.of("age","12","berries","false")),"發光漿果回到未結果藤蔓");
        for(String id:List.of("minecraft:diamond_block","minecraft:chest","foreign:wheat","minecraft:copper_bulb","minecraft:oxidized_copper_bulb","minecraft:oxidized_copper_door"))check(TemporalRegression.previous(new TemporalRegression.State(id,Map.of()))==null,"容器、外部方塊、機關銅材不碰");
        rejects(()->TemporalRegression.validateState(new TemporalRegression.State("minecraft:cut_copper_stairs",Map.of("facing","west","half","top","shape","outer_left","waterlogged","true")),true),"不回溯水浸銅材");
        rejects(()->TemporalRegression.validateState(new TemporalRegression.State("minecraft:wheat",Map.of("age","999")),true),"成長屬性仍嚴格有界");
    }
    private static TemporalRegression.Group grass(String phase) {
        var original=new TemporalRegression.State("minecraft:grass_block",Map.of("snowy","true"));
        var entry=new TemporalRegression.Entry(15,60,15,original,new TemporalRegression.State("minecraft:dirt",Map.of()));
        return new TemporalRegression.Group(OWNER,"minecraft:overworld",2400,"grass",phase,List.of(entry));
    }
    private static TemporalRegression.Group treeGroup(String phase) {
        var cells=tree("oak");BlockPos root=new BlockPos(15,60,15);List<TemporalRegression.Entry> entries=new ArrayList<>();
        entries.add(new TemporalRegression.Entry(15,60,15,new TemporalRegression.State("minecraft:oak_log",Map.of("axis","y")),new TemporalRegression.State("minecraft:oak_sapling",Map.of("stage","0"))));
        for(BlockPos pos:plan(cells))if(!pos.equals(root)){
            String kind=cells.get(pos).kind();var original=kind.equals("log")?new TemporalRegression.State("minecraft:oak_log",Map.of("axis","y"))
                :new TemporalRegression.State("minecraft:oak_leaves",Map.of("distance","2","persistent","false","waterlogged","false"));
            entries.add(new TemporalRegression.Entry(pos.getX(),pos.getY(),pos.getZ(),original,AIR));
        }
        return new TemporalRegression.Group(OWNER,"minecraft:overworld",2400,"tree",phase,entries);
    }
    private static void journal(Path folder) throws Exception {
        Path file=folder.resolve("journal/data.json");var value=new TemporalRegression.Journal(List.of(grass("active")));
        TemporalRegression.writeJournal(file,value);equal(TemporalRegression.readJournal(file),value,"原子草地日誌保留 snowy 原始值");
        var tree=new TemporalRegression.Journal(List.of(treeGroup("applying")));TemporalRegression.writeJournal(file,tree);equal(TemporalRegression.readJournal(file),tree,"完整樹狀態重讀");
        String valid=Files.readString(file);
        for(Consumer<JsonObject> mutate:List.<Consumer<JsonObject>>of(
            o->o.addProperty("schemaVersion",2),o->o.addProperty("schemaVersion","1"),o->o.addProperty("unknown",true),o->o.remove("groups"),
            o->first(o).addProperty("owner","invalid"),o->first(o).addProperty("expires",-1),o->first(o).addProperty("phase","foreign"),
            o->first(o).addProperty("dimension","invalid dimension"),o->entry(o).addProperty("x",29999984),o->entry(o).addProperty("x","15"),
            o->entry(o).addProperty("x",15.5),o->entry(o).addProperty("foreign_nbt",true),
            o->entry(o).getAsJsonObject("original").addProperty("id","foreign:log"),
            o->entry(o).getAsJsonObject("original").addProperty("id","minecraft:diamond_block"),
            o->entry(o).getAsJsonObject("target").addProperty("id","minecraft:chest"),
            o->first(o).getAsJsonArray("entries").add(entry(o).deepCopy()))) {
            JsonObject raw=JsonParser.parseString(valid).getAsJsonObject();mutate.accept(raw);String damaged=raw.toString();Files.writeString(file,damaged);
            rejects(()->TemporalRegression.readJournal(file),"惡意、損壞、重複、超界與非允許狀態拒絕");equal(Files.readString(file),damaged,"無效原檔不覆寫");
        }
        Files.writeString(file," ".repeat(1048577));rejects(()->TemporalRegression.readJournal(file),"解析前檔案大小限制");
        for(String damaged:List.of("[]","null","{} {}",valid+" // comment")){Files.writeString(file,damaged);rejects(()->TemporalRegression.readJournal(file),"非物件尾隨與註解拒絕");}
        TemporalRegression.writeJournal(file,value);String before=Files.readString(file);
        rejects(()->TemporalRegression.writeJournal(file,new TemporalRegression.Journal(Collections.nCopies(33,grass("active")))),"寫入也驗群組上限");equal(Files.readString(file),before,"拒絕寫入不傷既有紀錄");
        try(var files=Files.list(file.getParent())){check(files.noneMatch(p->p.toString().endsWith(".pending")),"提交後無 pending 殘留");}
    }
    private static JsonObject first(JsonObject root){return root.getAsJsonArray("groups").get(0).getAsJsonObject();}
    private static JsonObject entry(JsonObject root){return first(root).getAsJsonArray("entries").get(0).getAsJsonObject();}
    private static void recovery(Path folder) throws Exception {
        for(String phase:List.of("applying","active","restore"))for(int fail=0;fail<4;fail++) {
            var group=grass(phase);Model model=new Model(folder.resolve(phase+fail+"/journal.json"),group);model.fail=fail;
            rejects(()->TemporalRegression.completeRestoration(model.disk,group,model),"注入 phase／部分還原／保存讀回／clear 失敗");
            check(!TemporalRegression.readJournal(model.file).groups().isEmpty(),"未完成時仍有 crash journal");
            model.fail=-1;var saved=TemporalRegression.readJournal(model.file);TemporalRegression.completeRestoration(saved,saved.groups().getFirst(),model);
            check(TemporalRegression.readJournal(model.file).groups().isEmpty(),"重試成功才清除");
            equal(model.world.get(group.entries().getFirst().pos()),group.entries().getFirst().original(),"恢復原草地 snowy");
            check(model.events.lastIndexOf("saved")<model.events.lastIndexOf("clear"),"保存讀回早於清除");
        }
        for(String phase:List.of("active","applying")){
            var group=treeGroup(phase);Model model=new Model(folder.resolve("tree-"+phase+"/journal.json"),group);
            model.world.put(group.entries().getFirst().pos(),group.entries().getFirst().original());
            TemporalRegression.completeRestoration(model.disk,group,model);
            var second=group.entries().get(1);
            equal(model.world.get(second.pos()),phase.equals("active")?second.target():second.original(),"已長新樹釋放整棵；未完成寫入則仍恢復原樹");
        }
        var group=grass("active");Model unloaded=new Model(folder.resolve("unloaded/journal.json"),group);unloaded.loaded=false;
        rejects(()->TemporalRegression.completeRestoration(unloaded.disk,group,unloaded),"未載入不進行復原或強制生成");equal(unloaded.events,List.of(),"未載入沒有 journal rewrite/save/world mutation");
        Model foreign=new Model(folder.resolve("foreign/journal.json"),group);var stone=new TemporalRegression.State("minecraft:stone",Map.of());foreign.world.put(group.entries().getFirst().pos(),stone);
        TemporalRegression.completeRestoration(foreign.disk,group,foreign);equal(foreign.world.get(group.entries().getFirst().pos()),stone,"外部替換方塊保留");
    }
    private static final class Model implements TemporalRegression.RestorationAccess {
        final Path file;TemporalRegression.Journal disk;final Map<BlockPos,TemporalRegression.State>world=new HashMap<>();final List<String>events=new ArrayList<>();int fail=-1;boolean loaded=true;
        Model(Path file,TemporalRegression.Group group)throws IOException{this.file=file;disk=new TemporalRegression.Journal(List.of(group));TemporalRegression.writeJournal(file,disk);for(var entry:group.entries())world.put(entry.pos(),entry.target());}
        public boolean allLoaded(TemporalRegression.Group group){return loaded;}
        public TemporalRegression.State rootState(TemporalRegression.Group group){return world.get(group.entries().getFirst().pos());}
        public void commit(TemporalRegression.Journal next)throws IOException{
            boolean clear=next.groups().isEmpty();if(fail==0||(fail==3&&clear))throw new IOException("injected commit");
            TemporalRegression.writeJournal(file,next);disk=next;events.add(clear?"clear":"phase");
        }
        public void restoreAndSave(TemporalRegression.Group group)throws IOException{
            events.add("restore");for(var entry:group.entries())if(group.phase().equals("restore")&&TemporalRegression.targetMatches(entry.target(),world.get(entry.pos()))){world.put(entry.pos(),entry.original());if(fail==1)throw new IOException("injected partial");}
            if(fail==2)throw new IOException("injected disk readback");events.add("saved");
        }
    }
    private static void batch(Path folder)throws Exception {
        List<TemporalRegression.Group> groups=new ArrayList<>();
        for(int i=0;i<32;i++){
            var entry=new TemporalRegression.Entry(i,60,15,new TemporalRegression.State("minecraft:grass_block",Map.of("snowy","false")),new TemporalRegression.State("minecraft:dirt",Map.of()));
            groups.add(new TemporalRegression.Group(OWNER,"minecraft:overworld",2400,"grass","active",List.of(entry)));
        }
        Path file=folder.resolve("batch/journal.json");var initial=new TemporalRegression.Journal(groups);TemporalRegression.writeJournal(file,initial);
        Map<BlockPos,TemporalRegression.State> world=new HashMap<>();for(var group:groups)world.put(group.entries().getFirst().pos(),group.entries().getFirst().target());
        BlockPos foreign=groups.get(5).entries().getFirst().pos();var stone=new TemporalRegression.State("minecraft:stone",Map.of());world.put(foreign,stone);
        int[] saves={0},commits={0};List<String> events=new ArrayList<>();
        TemporalRegression.completeBatchRestoration(initial,groups,new TemporalRegression.BatchRestorationAccess(){
            public boolean allLoaded(TemporalRegression.Group group){return true;}
            public TemporalRegression.State rootState(TemporalRegression.Group group){return world.get(group.entries().getFirst().pos());}
            public void commit(TemporalRegression.Journal next)throws IOException{TemporalRegression.writeJournal(file,next);commits[0]++;events.add(next.groups().isEmpty()?"clear":"phase");}
            public void restoreAndSave(List<TemporalRegression.Group> values){saves[0]++;equal(values.size(),32,"32 group 同維度合併為一批");for(var group:values)for(var entry:group.entries())if(TemporalRegression.targetMatches(entry.target(),world.get(entry.pos())))world.put(entry.pos(),entry.original());events.add("saved");}
        });
        equal(saves[0],1,"所有32 group 僅一次 save+verify");equal(commits[0],2,"phase metadata 一次且保存後 clear 一次");equal(events,List.of("phase","saved","clear"),"多群組亦先記後還原／保存後清除");
        equal(world.get(foreign),stone,"批次保留 foreign replacement");check(TemporalRegression.readJournal(file).groups().isEmpty(),"批次成功才清完日誌");
        for(var group:groups)if(!group.entries().getFirst().pos().equals(foreign))equal(world.get(group.entries().getFirst().pos()),group.entries().getFirst().original(),"批次其餘草地恢復");
        TemporalRegression.writeJournal(file,initial);int[] operations={0};
        rejects(()->TemporalRegression.completeBatchRestoration(initial,groups,new TemporalRegression.BatchRestorationAccess(){
            public boolean allLoaded(TemporalRegression.Group group){return group!=groups.getFirst();}
            public TemporalRegression.State rootState(TemporalRegression.Group group){return group.entries().getFirst().target();}
            public void commit(TemporalRegression.Journal next)throws IOException{TemporalRegression.writeJournal(file,next);}
            public void restoreAndSave(List<TemporalRegression.Group> values){operations[0]++;equal(values.size(),31,"未載入單組不阻塞其餘31組");}
        }),"未載入仍明確報待重試");equal(operations[0],1,"已載入群組保存一次");equal(TemporalRegression.readJournal(file).groups(),List.of(groups.getFirst()),"未載入所有權 journal 保留");
    }
    @FunctionalInterface private interface Checked{void run()throws Exception;}
    private static void rejects(Checked action,String message)throws Exception{checks++;try{action.run();}catch(IOException|IllegalArgumentException error){return;}throw new AssertionError(message);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void equal(Object actual,Object expected,String message){check(Objects.equals(actual,expected),message+" actual="+actual+" expected="+expected);}
}

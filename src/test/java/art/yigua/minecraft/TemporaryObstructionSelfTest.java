package art.yigua.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 新永久柱體共用掃描／排程、舊 v1 真日誌 I/O；不建立原版世界。 */
public final class TemporaryObstructionSelfTest {
    private static int checks;
    private TemporaryObstructionSelfTest() {}
    public static void main(String[] arguments) throws Exception {
        Path folder = Files.createTempDirectory("yigua-obstruction-selftest-");
        try {
            journal(folder);
            growthAndLifecycle();
            permanentTerrainAndBudgets();
            permanentLifecycleAndProtection();
            replacementAndMaterials();
            restoreFailures(folder);
            System.out.println("障路驗收通過：" + checks + " 項永久地形、海床上升、材質、預留釋放與舊日誌安全檢查。");
        } finally {
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static TemporaryObstruction.Journal empty() { return new TemporaryObstruction.Journal("", List.of()); }
    private static TemporaryObstruction.Journal complete() {
        List<TemporaryObstruction.Entry> entries = new ArrayList<>();
        for (int x = 15; x <= 17; x++) for (int y = 60; y <= 61; y++)
            entries.add(new TemporaryObstruction.Entry(x, y, 15, List.of("air", "cave_air", "void_air").get(x - 15)));
        return new TemporaryObstruction.Journal("minecraft:overworld", entries);
    }
    private static void journal(Path folder) throws Exception {
        Path file = folder.resolve("journal/temporary-obstruction.json");
        var complete = complete(); TemporaryObstruction.writeJournal(file, complete);
        equal(TemporaryObstruction.readJournal(file), complete, "六格／三種原版空氣跨區塊原子保存並重讀");
        try (var files = Files.list(file.getParent())) { check(files.noneMatch(p -> p.toString().endsWith(".pending")), "提交成功不殘留 pending"); }
        String valid = Files.readString(file);
        for (Consumer<JsonObject> mutate : List.<Consumer<JsonObject>>of(
                o -> o.addProperty("schemaVersion", 2), o -> o.addProperty("schemaVersion", "1"),
                o -> o.addProperty("schemaVersion", 1.1), o -> o.remove("dimension"),
                o -> o.addProperty("dimension", 0), o -> o.addProperty("dimension", ""),
                o -> o.addProperty("dimension", "not a dimension"), o -> o.addProperty("unknown_metadata", true), o -> o.remove("entries"),
                o -> o.addProperty("entries", "wrong"), o -> o.getAsJsonArray("entries").add(0),
                o -> o.getAsJsonArray("entries").add(o.getAsJsonArray("entries").get(0).deepCopy()),
                o -> first(o).remove("original"), o -> first(o).addProperty("foreign_nbt", "ignored"), o -> first(o).addProperty("original", 0),
                o -> first(o).addProperty("original", "stone"), o -> first(o).addProperty("original", "mod:air"),
                o -> first(o).remove("x"), o -> first(o).addProperty("x", "15"),
                o -> first(o).addProperty("x", 15.1), o -> first(o).addProperty("x", 30000000),
                o -> first(o).addProperty("x", -30000000), o -> first(o).addProperty("y", 20000000),
                o -> first(o).addProperty("y", -20000000), o -> first(o).addProperty("y", 62),
                o -> first(o).addProperty("z", 16), o -> first(o).addProperty("z", 1000))) {
            var object = JsonParser.parseString(valid).getAsJsonObject(); mutate.accept(object);
            String damaged = object.toString(); Files.writeString(file, damaged);
            rejects(() -> TemporaryObstruction.readJournal(file), "嚴格拒絕缺欄位／型別／越界／非單一障壁");
            equal(Files.readString(file), damaged, "損壞紀錄保留且不能偷偷改寫");
        }
        for (String damaged : List.of("[]", "null", "1", "{", "{schemaVersion:1,dimension:'',entries:[]}",
                "{\"schemaVersion\":1,\"dimension\":\"\",\"entries\":[]} // comment", "{} {}")) {
            Files.writeString(file, damaged); rejects(() -> TemporaryObstruction.readJournal(file), "拒絕非物件或截斷 JSON");
            equal(Files.readString(file), damaged, "非物件與截斷 JSON 原檔保留");
        }
        String oversized = " ".repeat(8193); Files.writeString(file, oversized);
        rejects(() -> TemporaryObstruction.readJournal(file), "大小上限在解析前拒絕"); equal(Files.readString(file), oversized, "超大紀錄保留");
        TemporaryObstruction.writeJournal(file, complete); valid = Files.readString(file);
        var malicious = new TemporaryObstruction.Journal("minecraft:overworld", List.of(new TemporaryObstruction.Entry(0,60,0,"diamond_block")));
        rejects(() -> TemporaryObstruction.writeJournal(file, malicious), "寫入也拒絕可刷出的非原版空氣");
        equal(Files.readString(file), valid, "惡意記憶體 metadata 不覆蓋有效 journal");
        var invalidEmpty = new TemporaryObstruction.Journal("minecraft:overworld", List.of());
        rejects(() -> TemporaryObstruction.writeJournal(file, invalidEmpty), "空 journal 不得保留維度");
        TemporaryObstruction.writeJournal(file, empty()); equal(TemporaryObstruction.readJournal(file), empty(), "成功復原後空紀錄可讀回");
        Path blocked = folder.resolve("blocked"); Files.writeString(blocked, "do not replace");
        rejects(() -> TemporaryObstruction.writeJournal(blocked.resolve("journal.json"), complete), "寫入路徑失敗不進入升起流程");
        equal(Files.readString(blocked), "do not replace", "路徑原檔保留");
    }

    private static void growthAndLifecycle() {
        equal(TemporaryObstruction.growth(0,0), 1, "下層從八分之一開始");
        equal(TemporaryObstruction.growth(0,1), 0, "上層延後八刻開始");
        for (int age = 0; age < 60; age++) {
            equal(TemporaryObstruction.growth(age,0), Math.min(age + 1,8), "下層漸升至完整碰撞高度");
            equal(TemporaryObstruction.growth(age,1), Math.max(0,Math.min(age - 7,8)), "上層漸升且不超過八階");
        }
        check(TemporaryObstruction.shouldRise(false,true,false,false,0,1), "原版空氣可首次升起");
        check(!TemporaryObstruction.shouldRise(false,true,true,false,0,8), "已挖除自有障路在同 cycle 不重生");
        check(!TemporaryObstruction.shouldRise(false,false,false,false,0,8), "其他來源的新方塊不被覆寫");
        check(!TemporaryObstruction.shouldRise(true,false,true,true,1,8), "有生物進入時不把碰撞形狀升進生物");
        check(!TemporaryObstruction.shouldRise(false,true,false,false,0,0), "零高度不得提前放上層");
        check(!TemporaryObstruction.shouldRise(true,false,true,false,8,4), "已升起障路不回縮");
        check(!TemporaryObstruction.shouldRise(true,false,true,false,4,4), "相同高度不反覆寫方塊");
        check(TemporaryObstruction.shouldRise(true,false,true,false,4,5), "自有方塊可逐階升高");
        for (int duration : List.of(1,400,12000)) check(TemporaryObstruction.validDuration(duration), "有效期間邊界");
        for (int duration : List.of(-1,0,12001)) check(!TemporaryObstruction.validDuration(duration), "無效期間拒絕");
        check(!TemporaryObstruction.reached(59,60) && TemporaryObstruction.reached(60,60), "第六十刻才新一輪");
        BlockPos feet = new BlockPos(10, 60, 10);
        check(!TemporaryObstruction.needsNewCycle(1,60,feet,feet,Direction.NORTH,Direction.NORTH), "同格同方位不逐刻重置升起動畫");
        for (Direction direction : List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
            check(TemporaryObstruction.needsNewCycle(1,60,feet,feet.relative(direction),Direction.NORTH,Direction.NORTH), "每跨一格立即在新前方升起，不等三秒");
            if (direction != Direction.NORTH) check(TemporaryObstruction.needsNewCycle(1,60,feet,feet,Direction.NORTH,direction), "轉向立即改在視線前方升起");
        }
        check(TemporaryObstruction.needsNewCycle(1,60,feet,feet.above(),Direction.NORTH,Direction.NORTH), "走上台階立即配合腳下高度");
        check(TemporaryObstruction.needsNewCycle(60,60,feet,feet,Direction.NORTH,Direction.NORTH), "靜止時仍定時重現波次");
        check(TemporaryObstruction.reached(Integer.MIN_VALUE+5,Integer.MAX_VALUE), "cycle deadline 整數溢位仍可抵達");
        check(TemporaryObstruction.expired(Integer.MIN_VALUE+300,Integer.MAX_VALUE-99,400), "到期 tick 溢位保護");
        check(TemporaryObstruction.canContinue("minecraft:overworld","minecraft:overworld",true,true,399,0,400), "空窗無安全地面仍保留有效期等待下一輪");
        check(!TemporaryObstruction.canContinue("minecraft:overworld","minecraft:the_nether",true,true,1,0,400), "空 journal 亦必須在跨維度時收回");
        check(!TemporaryObstruction.canContinue("minecraft:overworld","minecraft:overworld",false,true,1,0,400), "離線／死亡／spectator 即復原");
        check(!TemporaryObstruction.canContinue("minecraft:overworld","minecraft:overworld",true,false,1,0,400), "world changes 停用即復原");
        check(!TemporaryObstruction.canContinue("minecraft:overworld","minecraft:overworld",true,true,400,0,400), "精確到期收回");
    }

    private record Planned(String original, String material) {}
    private record Placed(BlockPos pos, int tick, String material) {}
    private static final class Terrain implements TemporaryObstruction.PlanningAccess, TemporaryObstruction.PlacementAccess<Planned> {
        final int floor;
        final boolean sea;
        final Map<BlockPos,String> world = new HashMap<>();
        final Set<BlockPos> blocked = new HashSet<>(), occupied = new HashSet<>(), foreignClaims = new HashSet<>();
        final List<Placed> events = new ArrayList<>();
        final TemporaryObstruction.WorkQueue<Planned> work = new TemporaryObstruction.WorkQueue<>();
        int tick, inspections, assessments;
        String material;
        Terrain(int floor, boolean sea) { this.floor = floor; this.sea = sea; material = sea ? "sand" : "stone"; }
        String at(BlockPos pos) { return world.getOrDefault(pos, pos.getY() <= floor ? "foundation" : sea ? "water" : "air"); }
        boolean replacement(String value) { return Set.of("air","water","leaf_litter","short_grass").contains(value); }
        boolean solid(String value) { return Set.of("foundation","stone","sand","snow_block","foreign:stone").contains(value); }
        @Override public TemporaryObstruction.Cell inspect(BlockPos pos) {
            inspections++;
            if (blocked.contains(pos) || foreignClaims.contains(pos)) return TemporaryObstruction.Cell.BLOCKED;
            String value = at(pos);
            if (replacement(value)) {
                if (work.attempted.contains(pos) && !work.pending.containsKey(pos)) return TemporaryObstruction.Cell.BLOCKED;
                return TemporaryObstruction.Cell.REPLACEABLE;
            }
            return solid(value) ? TemporaryObstruction.Cell.SUPPORT : TemporaryObstruction.Cell.BLOCKED;
        }
        @Override public boolean reserve(BlockPos pos) {
            if (work.pending.containsKey(pos)) return true;
            return work.reserve(pos, new Planned(at(pos), material));
        }
        @Override public TemporaryObstruction.Placement assess(BlockPos pos, Planned pending, int deadline) {
            assessments++;
            if (blocked.contains(pos) || foreignClaims.contains(pos) || !at(pos).equals(pending.original()) || !replacement(at(pos)))
                return TemporaryObstruction.Placement.DROP;
            if (blocked.contains(pos.below()) || foreignClaims.contains(pos.below())) return TemporaryObstruction.Placement.DROP;
            return TemporaryObstruction.canPlaceLayer(tick,deadline,solid(at(pos.below())),occupied.contains(pos))
                ? TemporaryObstruction.Placement.READY : TemporaryObstruction.Placement.WAIT;
        }
        @Override public boolean place(BlockPos pos, Planned pending) {
            check(at(pos).equals(pending.original()),"正式排程在寫入之前仍保有原始狀態");
            check(solid(at(pos.below())),"每一層砂／石都有已存在的實心支撐，不懸空落走");
            world.put(pos,pending.material()); events.add(new Placed(pos,tick,pending.material()));
            return true;
        }
        void plan(TemporaryObstruction.ColumnPlanner planner, int budget) {
            int before = inspections, visits = planner.step(this,budget);
            check(visits <= budget && inspections - before == visits,"正式掃描器遵守每刻讀格預算");
        }
        void step(int now, int budget) {
            tick = now; int before = assessments;
            work.step(now,budget,this);
            check(assessments - before <= budget,"正式待建排程遵守每刻處理預算");
        }
    }

    private static void permanentTerrainAndBudgets() {
        BlockPos feet = new BlockPos(15,60,15);
        for (Direction direction : List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
            var footprint = TemporaryObstruction.footprint(feet,direction);
            equal(footprint.size(),21,"每波七格寬三排，共二十一條柱");
            equal(new HashSet<>(footprint).size(),21,"每波位置不重複且可跨既有區塊界線");
            check(!footprint.contains(feet),"不直接在玩家腳下生柱");
            for (BlockPos pos : footprint) {
                BlockPos delta = pos.subtract(feet);
                int forward = delta.getX() * direction.getStepX() + delta.getZ() * direction.getStepZ();
                Direction side = direction.getClockWise();
                int lateral = delta.getX() * side.getStepX() + delta.getZ() * side.getStepZ();
                check(forward >= 1 && forward <= 3 && Math.abs(lateral) <= 3,"真正覆蓋玩家前方大片區域");
            }
            Terrain land = new Terrain(59,false);
            var planner = new TemporaryObstruction.ColumnPlanner(feet,direction);
            land.plan(planner,512);
            check(planner.done(),"陸地整波能於一次五百一十二格預算完成規劃");
            equal(land.work.pending.size(),63,"新陸障是三層高六十三格，比舊六格大幅增多");
            land.step(0,128); equal(land.work.placed,21,"先落成二十一根柱的最底層");
            land.step(1,128); equal(land.work.placed,21,"每柱下一層等待兩刻，不瞬間全柱生成");
            land.step(2,128); equal(land.work.placed,42,"第二層由既有底層支撐");
            land.step(4,128); equal(land.work.placed,63,"第三層完成真正原版石塊");
            check(land.work.pending.isEmpty() && land.work.placementOrder.isEmpty(),"完成即釋放全部暫時預留");
            check(land.work.nextLayer.isEmpty() && land.work.columnCounts.isEmpty(),"已落成柱不保留無限記憶體 claims");
            for (BlockPos pos : footprint) for (int y = 60; y <= 62; y++) equal(land.at(new BlockPos(pos.getX(),y,pos.getZ())),"stone","留下可挖的普通永久石塊");
        }
        for (int depth : List.of(1,7,32,127)) {
            Terrain ocean = new Terrain(feet.getY() - depth - 1,true);
            var planner = new TemporaryObstruction.ColumnPlanner(feet,Direction.NORTH);
            while (!planner.done()) ocean.plan(planner,512);
            equal(ocean.work.pending.size(),21 * (depth + 3),"海中每柱從真海床一路到玩家上方兩格");
            int firstY = ocean.floor + 1;
            for (int now = 0; !ocean.work.pending.isEmpty() && now < 2000; now++) ocean.step(now,128);
            check(ocean.work.pending.isEmpty(),"深海全部柱在有界放置預算內完成");
            equal(ocean.work.placed,21 * (depth + 3),"海中保留二十一根完整永久砂柱");
            Map<String,Placed> previous = new HashMap<>();
            for (Placed event : ocean.events) {
                String key = event.pos().getX()+","+event.pos().getZ(); Placed below = previous.put(key,event);
                if (below == null) equal(event.pos().getY(),firstY,"所有海中柱從底開始");
                else {
                    equal(event.pos().getY(),below.pos().getY()+1,"每柱依高度嚴格由下往上");
                    check(event.tick()-below.tick() >= 2,"每柱相鄰層至少隔兩刻");
                }
                equal(event.material(),"sand","海洋使用砂柱，不使用視覺假石或浮空砂");
            }
        }
        Terrain tooDeep = new Terrain(-1000,true);
        var deeper = new TemporaryObstruction.ColumnPlanner(feet,Direction.NORTH);
        while (!deeper.done()) tooDeep.plan(deeper,512);
        equal(tooDeep.inspections,21*128,"無海床最多只往下探一百二十八格");
        check(tooDeep.work.pending.isEmpty(),"找不到已載入實心海床就不懸空造柱");
        Terrain budget = new Terrain(-67,true);
        var planner = new TemporaryObstruction.ColumnPlanner(feet,Direction.EAST);
        budget.plan(planner,1); equal(budget.inspections,1,"極小預算仍可逐刻續掃");
        budget.plan(planner,0); equal(budget.inspections,1,"零預算完全不觸碰世界");
        while (!planner.done()) budget.plan(planner,512);
        for (int now=0;!budget.work.pending.isEmpty()&&now<2000;now++) budget.step(now,3);
        check(budget.work.pending.isEmpty(),"極小放置預算不會餓死後面的柱");
    }

    private static void permanentLifecycleAndProtection() {
        BlockPos feet = new BlockPos(0,60,0);
        Terrain stopped = new Terrain(59,false);
        var planner = new TemporaryObstruction.ColumnPlanner(feet,Direction.NORTH);
        stopped.plan(planner,512); stopped.step(0,128);
        Map<BlockPos,String> permanent = Map.copyOf(stopped.world);
        stopped.work.stop();
        check(stopped.work.pending.isEmpty() && stopped.work.placementOrder.isEmpty(),"解除／到期停止未放下的格，釋放 claims");
        for (int now=1;now<100;now++) stopped.step(now,128);
        equal(stopped.world,permanent,"停止排程完全不刪除已經落成的永久方塊");
        check(!stopped.work.reserve(feet,new Planned("air","stone")),"停止後的舊排程無法重新生成");
        var reboot = new TemporaryObstruction.WorkQueue<Planned>();
        check(reboot.pending.isEmpty(),"重啟沒有永久復原 journal，不補建或回退舊柱");
        equal(stopped.world,permanent,"新排程初始化不接觸永久世界方塊");
        Terrain protectedWorld = new Terrain(59,false);
        BlockPos foreign = feet.north(), reserved = feet.north().east(), denied = feet.north().west();
        protectedWorld.foreignClaims.add(reserved); protectedWorld.blocked.add(denied);
        protectedWorld.plan(new TemporaryObstruction.ColumnPlanner(feet,Direction.NORTH),512);
        protectedWorld.world.put(foreign,"foreign:stone");
        for(int now=0;now<50;now++)protectedWorld.step(now,128);
        equal(protectedWorld.at(foreign),"foreign:stone","規劃后其他來源放下的新方塊保持原樣");
        check(!protectedWorld.work.pending.containsKey(foreign),"外來替換放棄 pending claim");
        check(!protectedWorld.work.attempted.contains(reserved) && !protectedWorld.work.attempted.contains(denied),"其他世界事件預留／保護鉤子有優先權");
        check(!protectedWorld.work.reserve(foreign,new Planned("air","stone")),"同一效果不重新覆蓋外來替換或已挖掉的位置");
        Terrain entity = new Terrain(59,false);
        entity.occupied.add(foreign);entity.plan(new TemporaryObstruction.ColumnPlanner(feet,Direction.NORTH),512);
        entity.step(0,128);equal(entity.at(foreign),"air","生物所在格不會填入方塊窒息");
        entity.occupied.clear();entity.step(2,128);equal(entity.at(foreign),"stone","生物離開後才可繼續從底生成");
        Terrain foliage = new Terrain(59,false);
        foliage.world.put(foreign,"leaf_litter");foliage.world.put(foreign.above(),"short_grass");
        foliage.plan(new TemporaryObstruction.ColumnPlanner(feet,Direction.NORTH),512);
        for(int now=0;now<10;now++)foliage.step(now,128);
        equal(foliage.at(foreign),"stone","落葉優先替換，不阻止真正障路");
        equal(foliage.at(foreign.above()),"stone","無碰撞自然植物也可由底替換");
        var bounded = new TemporaryObstruction.WorkQueue<Planned>();
        for(int i=0;i<4096;i++)check(bounded.reserve(new BlockPos(i,60,0),new Planned("air","stone")),"可容納四千零九十六格 pending");
        check(!bounded.reserve(new BlockPos(5000,60,0),new Planned("air","stone")),"pending 上限防止快速移動累積無界任務");
        check(!TemporaryObstruction.hasCapacity(0,32768,0),"單事件總位置有三萬二千七百六十八格上限");
        check(!TemporaryObstruction.hasCapacity(0,0,32768),"已落成總數亦受三萬二千七百六十八格上限");
        check(!TemporaryObstruction.hasCapacity(-1,0,0),"不接受負的容量計數");
        check(TemporaryObstruction.canPlaceLayer(Integer.MIN_VALUE+1,Integer.MAX_VALUE,true,false),"分層期限跨整數溢位仍正確");
        check(!TemporaryObstruction.canPlaceLayer(10,10,false,false),"無實心支撐的砂柱不得放置");
    }

    private static void replacementAndMaterials() {
        check(TemporaryObstruction.replaceable(true,false,true,false,false,false,false,false,false),"原版水即使有流體也可以替換");
        check(TemporaryObstruction.replaceable(true,false,false,true,true,true,false,false,true),"原版空氣可替換");
        check(TemporaryObstruction.replaceable(true,false,false,false,true,false,false,true,true),"落葉不必依靠一般 replaceable 標誌");
        check(TemporaryObstruction.replaceable(true,false,false,false,true,false,true,false,true),"無碰撞自然植被可以替換");
        check(!TemporaryObstruction.replaceable(false,false,true,true,true,true,true,true,true),"其他模組自訂方塊保留優先權");
        check(!TemporaryObstruction.replaceable(true,true,true,true,true,true,true,true,true),"任何方塊實體均不刪除");
        check(!TemporaryObstruction.replaceable(true,false,false,false,true,false,false,false,true),"非自然可替換設施如火把／鐵軌不因無碰撞而刪除");
        check(!TemporaryObstruction.replaceable(true,false,false,false,false,true,true,true,true),"非空碰撞形狀不被當成可覆蓋植物");
        check(!TemporaryObstruction.replaceable(true,false,false,false,true,true,true,true,false),"熔岩及非安全流體不按原版水假扮");
        check(TemporaryObstruction.replaceable(true,false,false,false,true,true,true,false,true),"有原版water流體的無碰撞海草可替換");
        check(TemporaryObstruction.replaceable(true,false,false,false,true,false,true,false,true),"無replaceable旗標的原版海帶被明列為安全自然水草");
        check(!TemporaryObstruction.replaceable(true,false,false,false,false,false,true,false,true),"即使帶water的植物，實心碰撞方塊仍保留");
        for(int flags=0;flags<128;flags++) {
            boolean nether=(flags&1)!=0,end=(flags&2)!=0,ocean=(flags&4)!=0,desert=(flags&8)!=0,snow=(flags&16)!=0,swamp=(flags&32)!=0,forest=(flags&64)!=0;
            int expected=nether?5:end?6:ocean||desert?1:snow?2:swamp?3:forest?4:0;
            equal(TemporaryObstruction.materialIndex(nether,end,ocean,desert,snow,swamp,forest),expected,"七種原版材質選擇以及重疊標籤優先序");
        }
    }

    private static void restoreFailures(Path folder) throws Exception {
        for (int fail = 0; fail < 3; fail++) {
            Model model = new Model(folder.resolve("recovery-" + fail + "/journal.json"));
            var journal = complete(); model.install(journal);
            BlockPos foreign = journal.entries().getFirst().pos(); model.world.put(foreign,"foreign:placed_block");
            model.failRestore = fail == 0; model.failSave = fail == 1; model.failClear = fail == 2;
            rejects(() -> TemporaryObstruction.completeRestoration(journal,model), "任何復原／保存／原子清除失敗都保留 journal");
            equal(TemporaryObstruction.readJournal(model.file),journal,"crash 仍能重讀全部可能的自有位置");
            model.failRestore = model.failSave = model.failClear = false;
            TemporaryObstruction.completeRestoration(TemporaryObstruction.readJournal(model.file),model);
            equal(TemporaryObstruction.readJournal(model.file),empty(),"重試保存讀回後才清 journal");
            equal(model.world.get(foreign),"foreign:placed_block","復原永不刪除玩家或其他模組新方塊");
            for (var entry : journal.entries()) if (!entry.pos().equals(foreign)) equal(model.world.get(entry.pos()),entry.original(),"依 crash journal 還原各原始空氣種類");
            check(model.events.lastIndexOf("save-and-readback") < model.events.lastIndexOf("clear"),"成功保存讀回先於清除日誌");
        }
        Model none = new Model(folder.resolve("empty/journal.json"));
        TemporaryObstruction.completeRestoration(empty(),none);
        equal(none.events,List.of("clear"),"空 session 清理不載入世界／生成區塊");
        boolean enabled = WorldChangePolicy.enabled();
        try { WorldChangePolicy.configure(false); check(!WorldChangePolicy.allow(null,new BlockPos(0,60,0)),"關閉政策在呼叫 permission callback 前拒絕變更"); }
        finally { WorldChangePolicy.configure(enabled); }
    }

    private static final class Model implements TemporaryObstruction.RestorationAccess {
        final Path file;
        final Map<BlockPos,String> world = new HashMap<>(), saved = new HashMap<>();
        final List<String> events = new ArrayList<>();
        boolean failRestore, failSave, failClear;
        Model(Path file) { this.file = file; }
        void install(TemporaryObstruction.Journal journal) throws IOException {
            TemporaryObstruction.writeJournal(file,journal);
            for (var entry : journal.entries()) world.put(entry.pos(),"own:obstacle");
        }
        @Override public void restoreAndSave(List<TemporaryObstruction.Entry> entries) throws IOException {
            events.add("restore"); int count = 0;
            for (var entry : entries) {
                if (world.get(entry.pos()).equals("own:obstacle")) world.put(entry.pos(),entry.original());
                if (failRestore && ++count == 2) throw new IOException("injected partial restore");
            }
            if (failSave) throw new IOException("injected save/readback failure");
            saved.putAll(world); equal(saved,world,"方塊保存後完整讀回"); events.add("save-and-readback");
        }
        @Override public void clearJournal() throws IOException {
            if (failClear) throw new IOException("injected atomic clear failure");
            TemporaryObstruction.writeJournal(file,empty()); events.add("clear");
        }
    }
    private static JsonObject first(JsonObject root) { return root.getAsJsonArray("entries").get(0).getAsJsonObject(); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action,String description) throws Exception {
        try { action.run(); } catch (IOException | IllegalStateException | com.google.gson.JsonParseException error) { checks++; return; }
        throw new AssertionError(description);
    }
    private static void equal(Object actual,Object expected,String description) { check(java.util.Objects.equals(actual,expected),description + ": " + actual); }
    private static void check(boolean value,String description) { if (!value) throw new AssertionError(description); checks++; }
}

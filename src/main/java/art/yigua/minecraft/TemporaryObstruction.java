package art.yigua.minecraft;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;

/** 永久的地形障路；新方塊由底逐層落成，舊 v1 暫時障路另保留安全復原。 */
public final class TemporaryObstruction {
    static final int MAX_BLOCKS = 6; // v1 legacy journal，不能放大舊格式的復原範圍。
    static final int WIDTH = 7, DEPTH = 3, HEIGHT = 3;
    static final int MAX_SEABED_DEPTH = 128;
    static final int SCAN_BUDGET = 512, PLACE_BUDGET = 128;
    static final int MAX_PENDING = 4096, MAX_EVENT_BLOCKS = 32768, MAX_WAVES = 8;
    static final int LAYER_PERIOD_TICKS = 2;
    static final int PERIOD_TICKS = 60;
    static final int MAX_DURATION_TICKS = 12000;
    private static final int MAX_JOURNAL_BYTES = 8192;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static final IntegerProperty RISE = IntegerProperty.create("rise", 1, 8);
    private static Block obstacle;
    private static MinecraftServer owner;
    private static Path journalFile;
    private static Session session; // 僅用於 legacy v1 復原。
    private static PermanentSession permanent;
    private static boolean available;
    record Entry(int x, int y, int z, String original) {
        BlockPos pos() { return new BlockPos(x, y, z); }
    }
    record Journal(String dimension, List<Entry> entries) {
        Journal { entries = List.copyOf(entries); }
    }
    private static final Journal EMPTY = new Journal("", List.of());
    private static final class Session {
        final UUID player;
        final String dimension;
        final int started;
        final int duration;
        Journal journal;
        int cycleStarted;
        int nextCycle;
        int retry;
        boolean restoring;
        BlockPos cycleOrigin;
        Direction cycleDirection;
        final Set<BlockPos> placed = new HashSet<>();
        Session(UUID player, String dimension, int duration, Journal journal, int tick) {
            this.player = player; this.dimension = dimension; this.started = tick;
            this.duration = duration; this.journal = journal;
            this.cycleStarted = tick; this.nextCycle = tick + PERIOD_TICKS;
            this.retry = tick;
        }
    }
    private TemporaryObstruction() {}

    public static void init() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, YiguaMod.id("rising_obstacle"));
        class RisingObstacleBlock extends Block {
            RisingObstacleBlock() {
                super(BlockBehaviour.Properties.of().setId(key).strength(0.8f).noLootTable());
                registerDefaultState(stateDefinition.any().setValue(RISE, 1));
            }
            @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
                builder.add(RISE);
            }
            @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
                return Block.box(0, 0, 0, 16, state.getValue(RISE) * 2, 16);
            }
        }
        obstacle = Registry.register(BuiltInRegistries.BLOCK, key, new RisingObstacleBlock());
    }

    public static void load(MinecraftServer server, Path world) {
        Objects.requireNonNull(server); Objects.requireNonNull(world);
        if (owner == server && (session != null || permanent != null)) {
            YiguaMod.LOG.error("障路尚有未完成復原，不能重新載入紀錄。"); return;
        }
        clear(); owner = server; journalFile = world.resolve("data/yigua/temporary-obstruction.json");
        try {
            Journal saved = Files.exists(journalFile) ? readJournal(journalFile) : EMPTY;
            available = true;
            if (!saved.entries().isEmpty()) {
                session = new Session(null, saved.dimension(), 0, saved, server.getTickCount());
                restoreAll(server);
            }
        } catch (IOException | RuntimeException error) {
            available = false;
            YiguaMod.LOG.error("障路復原紀錄無法讀取，保留原檔並暫停新事件。", error);
        }
    }

    /** 新模式使用原版方塊與原版區塊存檔，不把永久建造寫成可復原的 journal。 */
    public static boolean start(ServerPlayer player, int duration) {
        Objects.requireNonNull(player);
        MinecraftServer server = player.level().getServer();
        if (!available || session != null || permanent != null || server != owner || !server.isSameThread()
                || !validDuration(duration) || !WorldChangePolicy.enabled() || !activePlayer(player)) return false;
        permanent = new PermanentSession(player.getUUID(), player.level().dimension().identifier().toString(),
            duration, server.getTickCount());
        queueWave(player);
        return true; // 沒有安全海床或地面時仍保留有效期間，直到玩家走到可生成的位置。
    }

    private record Column(int x, int z) {}
    private record Pending(BlockState original, BlockState material) {}
    private record Wave(ColumnPlanner planner, BlockState material) {}
    private static final class PermanentSession {
        final UUID player;
        final String dimension;
        final int started, duration;
        final Deque<Wave> waves = new ArrayDeque<>();
        final WorkQueue<Pending> work = new WorkQueue<>();
        BlockPos origin;
        Direction direction;
        int nextWave;
        PermanentSession(UUID player, String dimension, int duration, int started) {
            this.player = player; this.dimension = dimension; this.duration = duration; this.started = started;
            this.nextWave = started;
        }
    }

    /** 純地形掃描器與正式世界共用；每次 step 只觸碰已授權的有限格數。 */
    enum Cell { REPLACEABLE, SUPPORT, BLOCKED }
    interface PlanningAccess {
        Cell inspect(BlockPos position);
        boolean reserve(BlockPos position); // false 僅表示待放置容量已滿，保留目前游標。
    }
    static final class ColumnPlanner {
        final List<BlockPos> columns;
        final int top;
        int column, depth, y;
        boolean filling;
        ColumnPlanner(BlockPos feet, Direction forward) {
            columns = footprint(feet, forward);
            top = feet.getY() + HEIGHT - 1;
            y = feet.getY() - 1;
        }
        boolean done() { return column >= columns.size(); }
        int step(PlanningAccess access, int budget) {
            int visits = 0;
            while (!done() && visits < Math.max(0, budget)) {
                BlockPos base = columns.get(column), pos = new BlockPos(base.getX(), y, base.getZ());
                Cell cell = access.inspect(pos); visits++;
                if (!filling) {
                    depth++;
                    if (cell == Cell.SUPPORT) { filling = true; y++; }
                    else if (cell == Cell.BLOCKED || depth >= MAX_SEABED_DEPTH) nextColumn();
                    else y--;
                } else if (cell != Cell.REPLACEABLE) {
                    nextColumn(); // 固體、方塊實體與受保護位置都不被覆蓋，也不跳過後懸空造柱。
                } else {
                    if (!access.reserve(pos)) return visits;
                    if (++y > top) nextColumn();
                }
            }
            return visits;
        }
        private void nextColumn() {
            column++; depth = 0; filling = false;
            if (!done()) y = columns.get(column).getY() - 1;
        }
    }
    static List<BlockPos> footprint(BlockPos feet, Direction forward) {
        if (forward == null || forward.getAxis().isVertical()) throw new IllegalArgumentException("障路方位必須水平");
        Objects.requireNonNull(feet);
        List<BlockPos> result = new ArrayList<>(WIDTH * DEPTH);
        Direction side = forward.getClockWise();
        for (int distance = 1; distance <= DEPTH; distance++)
            for (int lane = -WIDTH / 2; lane <= WIDTH / 2; lane++)
                result.add(feet.relative(forward, distance).relative(side, lane).immutable());
        return List.copyOf(result);
    }
    static boolean hasCapacity(int pending, int attempted, int placed) {
        return pending >= 0 && attempted >= 0 && placed >= 0 && pending < MAX_PENDING
            && attempted < MAX_EVENT_BLOCKS && placed < MAX_EVENT_BLOCKS;
    }
    /** 只改原版可替換地物；無碰撞並不代表可刪除鐵軌、火把或其他玩家設施。 */
    static boolean replaceable(boolean vanilla, boolean blockEntity, boolean water, boolean air,
            boolean noCollision, boolean replaceable, boolean vegetation, boolean leafLitter, boolean safeFluid) {
        return vanilla && !blockEntity && (air || water || (noCollision && safeFluid && (replaceable || vegetation || leafLitter)));
    }
    static int materialIndex(boolean nether, boolean end, boolean ocean, boolean desert, boolean snowy,
            boolean swamp, boolean forest) {
        if (nether) return 5;
        if (end) return 6;
        if (ocean || desert) return 1;
        if (snowy) return 2;
        if (swamp) return 3;
        return forest ? 4 : 0;
    }
    private static BlockState material(ServerLevel level, BlockPos pos) {
        var biome = level.getBiome(pos);
        int index = materialIndex(biome.is(BiomeTags.IS_NETHER), biome.is(BiomeTags.IS_END),
            biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_BEACH),
            biome.is(Biomes.DESERT) || biome.is(BiomeTags.HAS_DESERT_PYRAMID),
            biome.value().coldEnoughToSnow(pos, level.getSeaLevel()),
            biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP) || biome.is(BiomeTags.HAS_RUINED_PORTAL_SWAMP),
            biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_TAIGA));
        return switch (index) {
            case 1 -> Blocks.SAND.defaultBlockState();
            case 2 -> Blocks.SNOW_BLOCK.defaultBlockState();
            case 3 -> Blocks.PACKED_MUD.defaultBlockState();
            case 4 -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case 5 -> Blocks.BASALT.defaultBlockState();
            case 6 -> Blocks.END_STONE.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
            && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }
    private static boolean naturalReplacement(ServerLevel level, BlockPos pos, BlockState state) {
        boolean air = originalAir(state) != null;
        return replaceable(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft"),
            state.hasBlockEntity() || level.getBlockEntity(pos) != null, state.is(Blocks.WATER), air,
            state.getCollisionShape(level, pos).isEmpty(), state.canBeReplaced(),
            state.getBlock() instanceof VegetationBlock || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT),
            state.is(Blocks.LEAF_LITTER), state.getFluidState().isEmpty() || state.getFluidState().is(FluidTags.WATER));
    }
    private static boolean otherClaims(ServerLevel level, BlockPos pos) {
        return TemporaryWaterCorridor.claims(level, pos) || TemporalRegression.claims(level, pos);
    }
    private static void queueWave(ServerPlayer player) {
        if (permanent == null || permanent.waves.size() >= MAX_WAVES
                || permanent.work.attempted.size() >= MAX_EVENT_BLOCKS || permanent.work.placed >= MAX_EVENT_BLOCKS) return;
        ServerLevel level = (ServerLevel) player.level();
        BlockPos feet = player.blockPosition().immutable();
        if (!loaded(level, feet)) return;
        permanent.waves.addLast(new Wave(new ColumnPlanner(feet, player.getDirection()), material(level, feet)));
        permanent.origin = feet; permanent.direction = player.getDirection();
        permanent.nextWave = owner.getTickCount() + PERIOD_TICKS;
    }

    public static void tick(MinecraftServer server) {
        if (server != owner) return;
        if (session != null) {
            if (reached(server.getTickCount(), session.retry)) restoreAll(server);
            return; // 舊 journal 尚未復原，不能與新永久建造併行。
        }
        if (permanent == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(permanent.player);
        if (player == null || !canContinue(permanent.dimension, player.level().dimension().identifier().toString(),
                activePlayer(player), WorldChangePolicy.enabled(), server.getTickCount(), permanent.started, permanent.duration)) {
            stopPermanent(); return; // 永久方塊留在原地；沒有存檔復原、刪除或未放下位置的補建。
        }
        try {
            if (needsNewCycle(server.getTickCount(), permanent.nextWave, permanent.origin, player.blockPosition(),
                    permanent.direction, player.getDirection())) queueWave(player);
            ServerLevel level = (ServerLevel) player.level();
            int budget = SCAN_BUDGET;
            while (budget > 0 && !permanent.waves.isEmpty() && hasCapacity(permanent.work.pending.size(),
                    permanent.work.attempted.size(), permanent.work.placed)) {
                Wave wave = permanent.waves.getFirst();
                budget -= wave.planner().step(new PlanningAccess() {
                    @Override public Cell inspect(BlockPos pos) {
                        if (!loaded(level, pos) || !WorldChangePolicy.allow(player, pos) || otherClaims(level, pos)) return Cell.BLOCKED;
                        BlockState current = level.getBlockState(pos);
                        if (current.hasBlockEntity() || level.getBlockEntity(pos) != null) return Cell.BLOCKED;
                        if (naturalReplacement(level, pos, current)) {
                            if (permanent.work.attempted.contains(pos) && !permanent.work.pending.containsKey(pos)) return Cell.BLOCKED;
                            return Cell.REPLACEABLE;
                        }
                        return current.isCollisionShapeFullBlock(level, pos) ? Cell.SUPPORT : Cell.BLOCKED;
                    }
                    @Override public boolean reserve(BlockPos pos) {
                        if (permanent.work.pending.containsKey(pos)) return true;
                        if (!hasCapacity(permanent.work.pending.size(), permanent.work.attempted.size(), permanent.work.placed)) return false;
                        return permanent.work.reserve(pos, new Pending(level.getBlockState(pos), wave.material()));
                    }
                }, budget);
                if (wave.planner().done()) permanent.waves.removeFirst();
                else if (!hasCapacity(permanent.work.pending.size(), permanent.work.attempted.size(), permanent.work.placed)) break;
            }
            placePending(level, player, server.getTickCount());
        } catch (RuntimeException error) {
            YiguaMod.LOG.error("永久障路停止新生成；已放下的原版方塊保留。", error);
            stopPermanent();
        }
    }

    enum Placement { READY, WAIT, DROP }
    interface PlacementAccess<T> {
        Placement assess(BlockPos position, T pending, int nextLayerTick);
        boolean place(BlockPos position, T pending);
    }
    /** 共用的有界待建排程；stop 只釋放預留，不持有也不回退已落成的世界方塊。 */
    static final class WorkQueue<T> {
        final Map<BlockPos, T> pending = new HashMap<>();
        final Deque<Column> placementOrder = new ArrayDeque<>();
        final Map<Column, PriorityQueue<BlockPos>> columnPositions = new HashMap<>();
        final Set<BlockPos> attempted = new HashSet<>();
        final Map<Column, Integer> nextLayer = new HashMap<>(), columnCounts = new HashMap<>();
        int placed;
        boolean stopped;
        boolean capacity() { return !stopped && hasCapacity(pending.size(), attempted.size(), placed); }
        boolean reserve(BlockPos pos, T value) {
            if (stopped) return false;
            if (pending.containsKey(pos)) return true;
            if (attempted.contains(pos) || !capacity()) return false;
            BlockPos fixed = pos.immutable(); pending.put(fixed, Objects.requireNonNull(value)); attempted.add(fixed);
            Column column = new Column(fixed.getX(), fixed.getZ());
            if (!columnPositions.containsKey(column)) {
                columnPositions.put(column, new PriorityQueue<>(Comparator.comparingInt(BlockPos::getY)));
                placementOrder.addLast(column);
            }
            columnPositions.get(column).add(fixed); columnCounts.merge(column, 1, Integer::sum);
            return true;
        }
        int step(int tick, int budget, PlacementAccess<T> access) {
            if (stopped) return 0;
            // 輪流處理每柱最底下一格，不能讓數千格尚未有支撐的上層餓死其他海柱。
            int visits = Math.min(Math.max(0, budget), placementOrder.size()), count = 0;
            for (int i = 0; i < visits; i++) {
                Column column = placementOrder.removeFirst();
                PriorityQueue<BlockPos> positions = columnPositions.get(column);
                if (positions == null || positions.isEmpty()) continue;
                BlockPos pos = positions.peek(); T value = pending.get(pos);
                if (value == null) { release(pos, column); }
                else {
                    Placement action = access.assess(pos, value, nextLayer.getOrDefault(column, tick));
                    if (action == Placement.DROP) release(pos, column);
                    else if (action == Placement.READY) {
                        if (!access.place(pos, value)) throw new IllegalStateException("其他來源拒絕永久障路方塊");
                        placed++; count++; nextLayer.put(column, tick + LAYER_PERIOD_TICKS);
                        release(pos, column);
                    }
                }
                if (columnPositions.containsKey(column)) placementOrder.addLast(column);
            }
            return count;
        }
        private void release(BlockPos pos, Column column) {
            pending.remove(pos);
            PriorityQueue<BlockPos> positions = columnPositions.get(column);
            if (positions != null) positions.remove(pos);
            int remaining = columnCounts.getOrDefault(column, 1) - 1;
            if (remaining <= 0) { columnCounts.remove(column); nextLayer.remove(column); columnPositions.remove(column); }
            else columnCounts.put(column, remaining);
        }
        void stop() {
            stopped = true;
            pending.clear(); placementOrder.clear(); columnPositions.clear(); columnCounts.clear(); nextLayer.clear(); attempted.clear();
        }
    }
    private static void stopPermanent() {
        if (permanent != null) permanent.work.stop();
        permanent = null;
    }
    private static void placePending(ServerLevel level, ServerPlayer player, int tick) {
        permanent.work.step(tick, PLACE_BUDGET, new PlacementAccess<Pending>() {
            @Override public Placement assess(BlockPos pos, Pending pending, int nextLayerTick) {
                if (!loaded(level, pos) || !WorldChangePolicy.allow(player, pos) || otherClaims(level, pos)
                        || level.getBlockState(pos) != pending.original() || !naturalReplacement(level, pos, pending.original()))
                    return Placement.DROP; // 外來替換與政策拒絕都不重生。
                BlockPos support = pos.below();
                if (!loaded(level, support) || otherClaims(level, support) || !WorldChangePolicy.allow(player, support))
                    return Placement.DROP;
                return canPlaceLayer(tick, nextLayerTick,
                    level.getBlockState(support).isCollisionShapeFullBlock(level, support),
                    !level.getEntities(null, new AABB(pos)).isEmpty()) ? Placement.READY : Placement.WAIT;
            }
            @Override public boolean place(BlockPos pos, Pending pending) {
                // UPDATE_ALL 使用原版重力；實心支撐已驗證，真 sand 不會先從海中掉落。
                return level.getBlockState(pos) == pending.original()
                    && level.setBlock(pos, pending.material(), Block.UPDATE_ALL)
                    && level.getBlockState(pos) == pending.material();
            }
        });
    }
    static boolean canPlaceLayer(int now, int deadline, boolean solidSupport, boolean occupied) {
        return reached(now, deadline) && solidSupport && !occupied;
    }
    /** 給隔離測試與其他世界事件查詢，不啟動或改動世界。 */
    public static boolean active(ServerLevel level) {
        return level != null && level.getServer() == owner && (session != null || permanent != null);
    }

    static boolean validDuration(int duration) { return duration > 0 && duration <= MAX_DURATION_TICKS; }
    /** 尚未落成的位置與 legacy 復原位置才持有 claim，永久原版方塊完成即釋放。 */
    public static boolean claims(ServerLevel level, BlockPos pos) {
        if (level.getServer() != owner) return false;
        String dimension = level.dimension().identifier().toString();
        return (permanent != null && dimension.equals(permanent.dimension) && permanent.work.pending.containsKey(pos))
            || (session != null && dimension.equals(session.journal.dimension())
                && session.journal.entries().stream().anyMatch(entry -> entry.pos().equals(pos)));
    }
    static boolean needsNewCycle(int now, int deadline, BlockPos origin, BlockPos current,
            Direction previous, Direction forward) {
        return reached(now, deadline) || !current.equals(origin) || previous != forward;
    }
    static boolean reached(int now, int deadline) { return now - deadline >= 0; }
    static boolean expired(int now, int started, int duration) { return now - started >= duration; }
    static boolean canContinue(String dimension, String currentDimension, boolean playerActive, boolean enabled,
            int now, int started, int duration) {
        return playerActive && enabled && dimension.equals(currentDimension) && !expired(now, started, duration);
    }
    static int growth(int age, int layer) { return Math.clamp(age - layer * 8 + 1, 0, 8); }
    static boolean shouldRise(boolean own, boolean originalAir, boolean placed, boolean occupied, int currentRise, int growth) {
        return growth > 0 && !occupied && (own ? currentRise < growth : originalAir && !placed);
    }
    private static boolean activePlayer(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.hasDisconnected();
    }
    private static String originalAir(BlockState state) {
        if (state.is(Blocks.AIR)) return "air";
        if (state.is(Blocks.CAVE_AIR)) return "cave_air";
        if (state.is(Blocks.VOID_AIR)) return "void_air";
        return null; // 不把自訂 air 方塊按 path 假扮成原版空氣。
    }

    interface RestorationAccess {
        void restoreAndSave(List<Entry> entries) throws IOException;
        void clearJournal() throws IOException;
    }
    static void completeRestoration(Journal journal, RestorationAccess access) throws IOException {
        validate(journal);
        if (!journal.entries().isEmpty()) access.restoreAndSave(journal.entries());
        access.clearJournal();
    }

    /** 同步保存並讀回原空氣／他人新方塊後，才清除寫前紀錄。 */
    private static void restoreCycle(MinecraftServer server) throws IOException {
        completeRestoration(session.journal, new RestorationAccess() {
            @Override public void restoreAndSave(List<Entry> entries) throws IOException { restoreAndSaveCycle(server, entries); }
            @Override public void clearJournal() throws IOException {
                writeJournal(journalFile, EMPTY);
                session.journal = EMPTY;
            }
        });
    }
    private static void restoreAndSaveCycle(MinecraftServer server, List<Entry> entries) throws IOException {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(session.journal.dimension())));
        if (level == null) throw new IOException("障路維度不可用");
        Map<ChunkPos, SerializableChunkData> saved = new HashMap<>();
        Map<Entry, BlockState> expected = new LinkedHashMap<>();
        ServerPlayer player = session.player == null ? null : server.getPlayerList().getPlayer(session.player);
        for (Entry entry : entries) {
            BlockPos pos = entry.pos();
            if (!level.isInWorldBounds(pos)) throw new IOException("障路復原座標超出世界高度");
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null)
                throw new IOException("舊障路原區塊未載入；等待玩家載入，不生成或強制載入區塊");
            BlockState state = level.getBlockState(pos);
            if (state.is(obstacle)) {
                if (player != null && !WorldChangePolicy.permission(level, player, pos)) throw new IOException("其他來源拒絕障路復原");
                BlockState original = original(entry.original()).defaultBlockState();
                if (level.getBlockState(pos) != state || !level.setBlock(pos, original, FLAGS)) throw new IOException("自有障路方塊復原失敗");
                state = level.getBlockState(pos);
                if (state != original) throw new IOException("其他模組拒絕障路復原");
            }
            expected.put(entry, state); // 玩家／其他模組替換的方塊原樣保存。
        }
        level.getChunkSource().save(true);
        for (var entry : expected.entrySet()) {
            BlockPos pos = entry.getKey().pos(); ChunkPos chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
            if (!saved.containsKey(chunk)) {
                var tag = level.getChunkSource().chunkMap.read(chunk).join();
                if (tag.isEmpty()) throw new IOException("障路區塊保存後無法讀回");
                var data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag.get());
                if (data == null || !data.chunkPos().equals(chunk)) throw new IOException("障路區塊存檔位置錯誤");
                saved.put(chunk, data);
            }
            BlockState disk = Blocks.AIR.defaultBlockState();
            for (var section : saved.get(chunk).sectionData()) if (section.y() == (pos.getY() >> 4)) {
                if (section.chunkSection() != null) disk = section.chunkSection().getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                break;
            }
            if (disk != entry.getValue()) throw new IOException("障路方塊尚未落盤");
        }
    }

    public static void restoreAll(MinecraftServer server) {
        if (server != owner) return;
        stopPermanent(); // 停止新永久生成；原版方塊永遠不在 legacy journal 中。
        if (session == null) return;
        session.restoring = true; session.retry = server.getTickCount() + 100;
        try { restoreCycle(server); session = null; available = true; }
        catch (IOException | RuntimeException error) {
            available = false;
            YiguaMod.LOG.error("障路尚未完全復原／保存，保留 journal 並稍後重試。", error);
        }
    }
    public static void cancel(ServerPlayer player) {
        if (permanent != null && player.getUUID().equals(permanent.player)) stopPermanent();
        if (session != null && player.getUUID().equals(session.player)) restoreAll(player.level().getServer());
    }
    public static void clear() { stopPermanent(); owner = null; journalFile = null; session = null; available = false; }

    private static Block original(String name) {
        return switch (name) { case "air" -> Blocks.AIR; case "cave_air" -> Blocks.CAVE_AIR; case "void_air" -> Blocks.VOID_AIR;
            default -> throw new IllegalStateException("障路只能暫用原版空氣"); };
    }
    static void validate(Journal journal) {
        if (journal == null || journal.dimension() == null || journal.entries() == null
                || journal.entries().size() > MAX_BLOCKS) throw new IllegalStateException("障路紀錄缺漏／方塊超出6格");
        if (journal.entries().isEmpty()) {
            if (!journal.dimension().isEmpty()) throw new IllegalStateException("空障路紀錄不能保留維度");
            return;
        }
        if (!journal.dimension().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalStateException("障路維度無效");
        Set<BlockPos> unique = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Entry entry : journal.entries()) {
            if (entry == null || entry.original() == null || !Set.of("air", "cave_air", "void_air").contains(entry.original()) || Math.abs((long)entry.x()) >= 30000000
                    || Math.abs((long)entry.z()) >= 30000000 || Math.abs((long)entry.y()) >= 20000000 || !unique.add(entry.pos())) throw new IllegalStateException("障路原始空氣／座標無效");
            minX = Math.min(minX, entry.x()); maxX = Math.max(maxX, entry.x()); minY = Math.min(minY, entry.y()); maxY = Math.max(maxY, entry.y()); minZ = Math.min(minZ, entry.z()); maxZ = Math.max(maxZ, entry.z());
        }
        long dx = (long)maxX - minX, dz = (long)maxZ - minZ;
        if (!((dx <= 2 && dz == 0) || (dx == 0 && dz <= 2)) || (long)maxY - minY > 1)
            throw new IllegalStateException("障路紀錄超出單一暫時障壁");
    }
    static Journal readJournal(Path file) throws IOException {
        if (Files.size(file) > MAX_JOURNAL_BYTES) throw new IOException("障路 journal 超過上限");
        JsonElement document;
        try (JsonReader reader = new JsonReader(new StringReader(Files.readString(file, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            document = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalStateException("障路 journal 有多餘文件");
        }
        if (!document.isJsonObject()) throw new IllegalStateException("障路 journal 根必須是物件");
        JsonObject root = document.getAsJsonObject();
        if (!root.keySet().equals(Set.of("schemaVersion", "dimension", "entries"))
                || integer(root, "schemaVersion") != 1 || !string(root, "dimension")
                || !root.has("entries") || !root.get("entries").isJsonArray()) throw new IllegalStateException("障路 journal 格式無效");
        List<Entry> entries = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("entries")) {
            if (!value.isJsonObject()) throw new IllegalStateException("障路每筆座標必須是物件");
            JsonObject item = value.getAsJsonObject();
            if (!item.keySet().equals(Set.of("x", "y", "z", "original")) || !string(item, "original"))
                throw new IllegalStateException("障路空氣格式無效");
            entries.add(new Entry(integer(item, "x"), integer(item, "y"), integer(item, "z"), item.get("original").getAsString()));
            if (entries.size() > MAX_BLOCKS) throw new IllegalStateException("障路 journal 超過6格");
        }
        Journal journal = new Journal(root.get("dimension").getAsString(), entries); validate(journal); return journal;
    }
    private static boolean string(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonPrimitive() && root.getAsJsonPrimitive(key).isString();
    }
    private static int integer(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalStateException("障路整數格式無效");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException error) { throw new IllegalStateException("障路整數越界", error); }
    }
    static void writeJournal(Path file, Journal journal) throws IOException {
        validate(journal);
        JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 1); root.addProperty("dimension", journal.dimension()); root.add("entries", JSON.toJsonTree(journal.entries()));
        byte[] bytes = JSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JOURNAL_BYTES) throw new IOException("障路 journal 超過上限");
        Files.createDirectories(file.getParent()); Path pending = Files.createTempFile(file.getParent(), "obstruction-", ".pending");
        try {
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(pending); }
    }
}

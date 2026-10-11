package art.yigua.minecraft;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/** 五氣的有界回溯；不倒轉生命、掉落物、背包或玩家。 */
public final class TemporalRegression {
    static final int MAX_BLOCKS = 1024, MAX_GROUPS = 32, MAX_MOBS = 32, BABY_AGE = -24000;
    static final int TREE_RADIUS = 10, TREE_HEIGHT = 32, MAX_TREE_QUERIES = 8192;
    private static final int MAX_BYTES = 1048576, FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> SPECIES = Set.of("oak", "birch", "spruce", "cherry", "acacia", "dark_oak", "mangrove", "jungle", "pale_oak");
    private static final Map<UUID, Integer> lastFeedback = new HashMap<>();
    private static AttachmentType<MobLease> mobLeaseType;
    private static final Map<UUID, Entity> tracked = new HashMap<>();
    private static MinecraftServer owner;
    private static Path journalFile;
    private static Journal journal = new Journal(List.of());
    private static boolean initialized, available, blockRegistered, blockRegistrationPending;
    private static int policyProbe, retryTick;

    record State(String id, Map<String, String> properties) {
        State { properties = Map.copyOf(properties); }
    }
    record Entry(int x, int y, int z, State original, State target) {
        BlockPos pos() { return new BlockPos(x, y, z); }
    }
    record Group(String owner, String dimension, long expires, String kind, String phase, List<Entry> entries) {
        Group { entries = List.copyOf(entries); }
        Group phase(String next) { return new Group(owner, dimension, expires, kind, next, entries); }
    }
    record Journal(List<Group> groups) { Journal { groups = List.copyOf(groups); } }
    record MobLease(String owner, String target, String type, String kind, int originalAge, int observedAge,
                    long started, long expires) {}
    record Cell(String kind, String species, boolean natural, boolean vertical, boolean fluid, boolean blockEntity, int distance) {
        Cell(String kind, String species, boolean natural, boolean vertical, boolean fluid, boolean blockEntity) {
            this(kind, species, natural, vertical, fluid, blockEntity, 0);
        }
    }
    record TreePlan(BlockPos root, String species, List<BlockPos> blocks, String reason) {
        TreePlan { blocks = List.copyOf(blocks); }
        boolean accepted() { return root != null && !blocks.isEmpty(); }
    }
    record Previous(State state, String kind, String description) {}
    interface TreeAccess { Cell cell(BlockPos pos); }
    interface RestorationAccess {
        boolean allLoaded(Group group);
        State rootState(Group group);
        void commit(Journal next) throws IOException;
        void restoreAndSave(Group group) throws IOException;
    }
    interface BatchRestorationAccess {
        boolean allLoaded(Group group);
        State rootState(Group group);
        void commit(Journal next) throws IOException;
        void restoreAndSave(List<Group> sameDimension) throws IOException;
        default boolean replacementDetected(Group group) { return !targetMatches(group.entries().getFirst().target(), rootState(group)); }
    }
    private TemporalRegression() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        Codec<MobLease> codec = Codec.STRING.comapFlatMap(text -> {
            try { return DataResult.success(readMobLease(text)); }
            catch (RuntimeException error) { return DataResult.error(() -> "invalid yigua temporal lease"); }
        }, lease -> JSON.toJson(lease));
        mobLeaseType = AttachmentRegistry.createPersistent(YiguaMod.id("temporal_regression"), codec);
        // 使用原生傷害已獲准之後的事件；不重入攻擊回呼、不搶先短路其他模組。
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, originalDamage, damageTaken, blocked) -> {
            if (!blocked && damageTaken > 0 && entity.isAlive() && source.getEntity() instanceof ServerPlayer player) regressMob(player, entity);
        });
        // 自有泥土／樹苗不能被挖成可刷的物品；許可探測只讀，不能被這一條自己拒絕。
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) ->
            policyProbe > 0 || !(level instanceof ServerLevel serverLevel) || !ownedNonAir(serverLevel, pos, state));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            load(server, server.getWorldPath(LevelResource.ROOT)); blockRegistrationPending = !blockRegistered;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> restoreAll(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (owner == server) { owner = null; journalFile = null; journal = new Journal(List.of()); available = false; tracked.clear(); lastFeedback.clear(); }
        });
        ServerTickEvents.END_SERVER_TICK.register(TemporalRegression::tick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (mobLeaseType != null && attachment(entity).hasAttached(mobLeaseType)) {
                boolean overflow = tracked.size() >= MAX_MOBS;
                if (!overflow) tracked.put(entity.getUUID(), entity);
                updateMob(entity, level, overflow);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> tracked.remove(entity.getUUID()));
    }

    public static boolean claims(ServerLevel level, BlockPos pos) {
        return owner == level.getServer() && journal.groups().stream().anyMatch(group ->
            group.dimension().equals(level.dimension().identifier().toString())
                && group.entries().stream().anyMatch(entry -> entry.pos().equals(pos)));
    }

    private static AttachmentTarget attachment(Entity entity) { return (AttachmentTarget) entity; }
    private static boolean permit(ServerPlayer player, ServerLevel level, BlockPos pos, boolean recovery) {
        if (!loaded(level, pos) || claimsOther(level, pos)) return false;
        policyProbe++;
        try { return recovery ? WorldChangePolicy.permission(level, player, pos) : WorldChangePolicy.allow(player, pos); }
        finally { policyProbe--; }
    }
    private static boolean claimsOther(ServerLevel level, BlockPos pos) {
        return TemporaryObstruction.claims(level, pos) || TemporaryWaterCorridor.claims(level, pos);
    }
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
            && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }
    private static boolean vanilla(Entity entity) { return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals("minecraft"); }

    private static boolean regressMob(ServerPlayer player, Entity entity) {
        if (!TemporalInsight.canRewind(player) || !WorldChangePolicy.enabled() || !(entity instanceof Mob)
                || !vanilla(entity) || entity.level() != player.level() || !entity.isAlive() || entity.isRemoved()
                || entity.isPassenger() || entity.isVehicle()) return false;
        ServerLevel level = (ServerLevel) player.level();
        if (!permit(player, level, entity.blockPosition(), false)) return false;
        boolean ageChanged = false;
        if (!attachment(entity).hasAttached(mobLeaseType) && tracked.size() < MAX_MOBS) {
            String kind = entity instanceof Zombie zombie && !zombie.isBaby() ? "zombie"
                : entity instanceof AgeableMob animal && animal.getAge() >= 0 && !animal.isAgeLocked()
                    && supportsBaby(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath()) ? "ageable" : "";
            if (!kind.isEmpty()) {
                int original = entity instanceof AgeableMob animal ? animal.getAge() : 0;
                if (original <= 24000) {
                    long now = level.getGameTime();
                    MobLease lease = new MobLease(player.getUUID().toString(), entity.getUUID().toString(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), kind, original,
                        kind.equals("zombie") ? -1 : BABY_AGE, now, now + TemporalInsight.remainingTicks(player));
                    // attachment 和年齡同存於該 entity NBT；存檔只可能看見前或後的完整快照。
                    attachment(entity).setAttached(mobLeaseType, lease);
                    if (entity instanceof Zombie zombie) zombie.setBaby(true);
                    else ((AgeableMob) entity).setAge(BABY_AGE);
                    tracked.put(entity.getUUID(), entity); ageChanged = true;
                }
            }
        }
        Optional<Vec3> previous = TemporalInsight.previousPosition(player, entity);
        boolean moved = previous.filter(pos -> safePosition(player, entity, pos)).map(pos -> {
            entity.teleportTo(pos.x, pos.y, pos.z);
            entity.setDeltaMovement(Vec3.ZERO); entity.resetFallDistance();
            return true;
        }).orElse(false);
        return ageChanged || moved;
    }

    private static boolean safePosition(ServerPlayer player, Entity entity, Vec3 target) {
        if (!finitePosition(target) || entity.position().distanceToSqr(target) > 32 * 32) return false;
        ServerLevel level = (ServerLevel) player.level();
        AABB box = entity.getBoundingBox().move(target.subtract(entity.position()));
        if (box.getXsize() > 4 || box.getYsize() > 8 || box.getZsize() > 4) return false;
        BlockPos low = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos high = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
            if (!permit(player, level, pos, false) || claims(level, pos) || !level.getBlockState(pos).getFluidState().isEmpty()
                    || dangerous(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath())) return false;
        }
        BlockPos floor = BlockPos.containing(target).below();
        return permit(player, level, floor, false) && !dangerous(BuiltInRegistries.BLOCK.getKey(level.getBlockState(floor).getBlock()).getPath())
            && level.getBlockState(floor).getFluidState().isEmpty()
            && level.getBlockState(floor).isCollisionShapeFullBlock(level, floor) && level.noCollision(entity, box);
    }
    static boolean finitePosition(Vec3 pos) {
        return pos != null && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z)
            && Math.abs(pos.x) < 29999984 && Math.abs(pos.z) < 29999984 && Math.abs(pos.y) < 20000000;
    }
    static boolean dangerous(String id) {
        return Set.of("lava", "water", "fire", "soul_fire", "magma_block", "campfire", "soul_campfire",
            "cactus", "sweet_berry_bush", "wither_rose", "powder_snow", "bubble_column", "nether_portal", "end_portal").contains(id);
    }
    static boolean supportsBaby(String id) {
        // 此版六種原版 AgeableMob 明確覆寫 canBeABaby=false；不使用反射或 mixin。
        return !Set.of("camel_husk", "frog", "zombie_nautilus", "parrot", "magma_cube", "slime").contains(id);
    }
    static boolean ownsAge(String kind, int observed, int current) {
        return kind.equals("zombie") ? current == -1 : kind.equals("ageable") && observed >= BABY_AGE && observed < 0
            && current >= observed && current <= Math.min(-1, observed + 1);
    }
    private static void updateMob(Entity entity, ServerLevel level, boolean forceRestore) {
        MobLease lease = attachment(entity).getAttached(mobLeaseType);
        if (lease == null) { tracked.remove(entity.getUUID()); return; }
        if (!entity.getUUID().toString().equals(lease.target()) || !vanilla(entity)
                || !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(lease.type())
                || !entity.isAlive() || entity.isRemoved() || entity instanceof AgeableMob animal && animal.isAgeLocked()) { releaseMob(entity); return; }
        int current = entity instanceof Zombie zombie ? (zombie.isBaby() ? -1 : 0)
            : entity instanceof AgeableMob animal ? animal.getAge() : Integer.MAX_VALUE;
        if (!ownsAge(lease.kind(), lease.observedAge(), current)) { releaseMob(entity); return; }
        // 到期但保護暫時拒絕復原時，仍需追蹤自然 +1；不能下一刻誤判外改而遺失復原標記。
        if (current != lease.observedAge()) attachment(entity).setAttached(mobLeaseType,
            new MobLease(lease.owner(), lease.target(), lease.type(), lease.kind(), lease.originalAge(), current, lease.started(), lease.expires()));
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(UUID.fromString(lease.owner()));
        if (forceRestore || level.getGameTime() >= lease.expires() || player == null || player.level() != level
                || !TemporalInsight.canRewind(player)) {
            // 外部保護拒絕時保留 attachment，等待玩家權限恢復；離線崩潰復原不假扮玩家。
            if (player != null && !permit(player, level, entity.blockPosition(), true)) return;
            if (entity instanceof Zombie zombie) zombie.setBaby(false);
            else ((AgeableMob) entity).setAge((int) Math.max(0, lease.originalAge() - Math.max(0, level.getGameTime() - lease.started())));
            releaseMob(entity);
        }
    }
    private static void releaseMob(Entity entity) { attachment(entity).removeAttached(mobLeaseType); tracked.remove(entity.getUUID()); }

    private static boolean regressBlock(ServerPlayer player, BlockPos pos) {
        if (player.level().getServer() != owner || !available || !TemporalInsight.canRewind(player)
                || !WorldChangePolicy.enabled() || journal.groups().size() >= MAX_GROUPS) return false;
        ServerLevel level = (ServerLevel) player.level();
        if (!permit(player, level, pos, false) || claims(level, pos)) return false;
        BlockState hit = level.getBlockState(pos);
        List<Entry> entries;
        String kind;
        Previous previous = previous(encode(hit));
        String description;
        if (previous != null && level.getBlockEntity(pos) == null && hit.getFluidState().isEmpty()) {
            entries = List.of(entry(pos, hit, decode(previous.state()))); kind = previous.kind(); description = previous.description();
        } else {
            TreePlan tree = treePlan(pos, p -> cell(level, p));
            if (!tree.accepted()) {
                Cell hitCell = cell(level, pos);
                if (Set.of("log", "leaves", "root").contains(hitCell.kind())) feedback(player, "五氣回溯：" + tree.reason());
                return false;
            }
            BlockPos root = tree.root();
            Block sapling = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(saplingName(tree.species())));
            if (!(sapling instanceof SaplingBlock)) return false;
            BlockState seed = sapling.defaultBlockState();
            if (sapling instanceof MangrovePropaguleBlock) seed = seed.setValue(MangrovePropaguleBlock.AGE, 4)
                .setValue(MangrovePropaguleBlock.HANGING, false);
            if (!seed.canSurvive(level, root)) { feedback(player, "五氣回溯：這裡的地面無法支持樹苗"); return false; }
            entries = new ArrayList<>();
            // 第一筆是著地的種植點；根已生長時整棵釋放，避免重複回復另一棵樹。
            entries.add(entry(root, level.getBlockState(root), seed));
            boolean fourSeeds = !tree.species().equals("mangrove") && tree.blocks().stream().filter(p -> p.getY() == root.getY()
                && cell(level,p).kind().equals("log") && cell(level,p.below()).kind().equals("soil")).count() == 4;
            for (BlockPos p : tree.blocks()) if (!p.equals(root)) entries.add(entry(p, level.getBlockState(p),
                fourSeeds && p.getY() == root.getY() && cell(level,p).kind().equals("log") ? seed : Blocks.AIR.defaultBlockState()));
            kind = "tree"; description = "樹木 → " + (tree.species().equals("mangrove") ? "紅樹林胎生苗" : "樹苗");
        }
        if (journal.groups().stream().mapToInt(group -> group.entries().size()).sum() + entries.size() > MAX_BLOCKS) return false;
        for (Entry entry : entries) if (!permit(player, level, entry.pos(), false) || claims(level, entry.pos())
                || level.getBlockState(entry.pos()) != decode(entry.original())) return false;
        Group group = new Group(player.getUUID().toString(), level.dimension().identifier().toString(),
            level.getGameTime() + TemporalInsight.remainingTicks(player), kind, "applying", entries);
        try {
            List<Group> next = new ArrayList<>(journal.groups()); next.add(group); commit(new Journal(next));
            // 先移除樹冠，最後放樹苗，避免回呼看到未完成的冠葉而當成可成長樹。
            List<Entry> order = new ArrayList<>(entries); if (kind.equals("tree")) order.sort(Comparator.comparing(e -> !e.target().id().equals("minecraft:air")));
            for (Entry entry : order) {
                if (!permit(player, level, entry.pos(), false) || level.getBlockState(entry.pos()) != decode(entry.original())
                        || !level.setBlock(entry.pos(), decode(entry.target()), FLAGS)
                        || level.getBlockState(entry.pos()) != decode(entry.target())) throw new IOException("回溯轉換被外部拒絕");
            }
            Group completed = group.phase("active"); replace(group, completed); group = completed;
        } catch (IOException | RuntimeException error) {
            available = false;
            YiguaMod.LOG.error("回溯寫前或轉換失敗，保留紀錄等待復原。", error);
            if (!journal.groups().contains(group)) return false;
            try { restore(group); } catch (IOException | RuntimeException retry) { YiguaMod.LOG.error("回溯復原將重試。", retry); }
        }
        feedback(player, "五氣回溯：" + description);
        return true;
    }

    private static void feedback(ServerPlayer player, String message) {
        int now = player.level().getServer().getTickCount();
        Integer last = lastFeedback.get(player.getUUID());
        if (last == null || now - last >= 10) { player.sendOverlayMessage(Component.literal(message)); lastFeedback.put(player.getUUID(), now); }
    }

    private static Entry entry(BlockPos pos, BlockState from, BlockState to) {
        return new Entry(pos.getX(), pos.getY(), pos.getZ(), encode(from), encode(to));
    }
    private static String saplingName(String species) { return species.equals("mangrove") ? "mangrove_propagule" : species + "_sapling"; }
    private static Cell cell(ServerLevel level, BlockPos pos) {
        if (!loaded(level, pos)) return new Cell("unloaded", "", false, false, false, false);
        BlockState state = level.getBlockState(pos);
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock()); String path = id.getPath();
        String kind = path.endsWith("_log") ? "log" : path.endsWith("_leaves") ? "leaves"
            : Set.of("mangrove_roots", "muddy_mangrove_roots").contains(path) ? "root"
            : Set.of("dirt", "grass_block", "podzol", "coarse_dirt", "rooted_dirt", "mycelium", "mud", "moss_block").contains(path) ? "soil"
            : state.isAir() ? "air" : "other";
        String species = kind.equals("log") ? path.substring(0, path.length() - 4)
            : kind.equals("leaves") ? path.substring(0, path.length() - 7) : kind.equals("root") ? "mangrove" : "";
        boolean natural = id.getNamespace().equals("minecraft") && (!kind.equals("leaves") || state.hasProperty(BlockStateProperties.PERSISTENT)
            && !state.getValue(BlockStateProperties.PERSISTENT) && state.getValue(BlockStateProperties.DISTANCE) <= 6);
        boolean vertical = !kind.equals("log") || state.hasProperty(BlockStateProperties.AXIS) && state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y;
        int distance = kind.equals("leaves") && state.hasProperty(BlockStateProperties.DISTANCE) ? state.getValue(BlockStateProperties.DISTANCE) : 0;
        return new Cell(kind, species, natural, vertical, !state.getFluidState().isEmpty(), level.getBlockEntity(pos) != null, distance);
    }

    /** 非 persistent 冠葉是自然樹的證據，並非生成來源證明；不收無冠木造、巨樹或合併多樹根。 */
    static TreePlan treePlan(BlockPos hit, TreeAccess source) {
        Map<BlockPos, Cell> cache = new HashMap<>(); boolean[] overflow = {false};
        TreeAccess access = pos -> cache.computeIfAbsent(pos.immutable(), p -> {
            if (cache.size() >= MAX_TREE_QUERIES) { overflow[0] = true; return new Cell("unloaded", "", false, false, false, false); }
            return source.cell(p);
        });
        Cell initial = access.cell(hit);
        if (!Set.of("log", "leaves", "root").contains(initial.kind()) || !SPECIES.contains(initial.species())) return rejected("此方塊不是可辨識的原版樹木");
        if (!initial.natural() || initial.fluid() || initial.blockEntity()) return rejected("不回溯人工冠葉、水浸樹木或含資料的方塊");
        String species = initial.species();
        BlockPos seed = hit.immutable();
        if (initial.kind().equals("leaves")) {
            ArrayDeque<BlockPos> find = new ArrayDeque<>(); Map<BlockPos, Integer> distance = new HashMap<>();
            find.add(seed); distance.put(seed, 0); seed = null;
            while (!find.isEmpty() && seed == null) {
                BlockPos p = find.removeFirst(); int steps = distance.get(p);
                for (Direction dir : Direction.values()) {
                    BlockPos next = p.relative(dir); Cell c = access.cell(next);
                    if (wood(c, species) && c.natural() && !c.fluid() && !c.blockEntity()) { seed = next; break; }
                    if (steps < 6 && leaf(c, species) && !distance.containsKey(next)) { distance.put(next, steps + 1); find.addLast(next); }
                }
            }
            if (seed == null) return rejected("冠葉找不到六格內的樹幹");
        }
        Set<BlockPos> trunks = new LinkedHashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos p = queue.removeFirst(); if (!trunks.add(p)) continue;
            Cell c = access.cell(p);
            if (!wood(c, species) || !c.natural() || c.fluid() || c.blockEntity() || trunks.size() > 192
                    || Math.abs(p.getX() - seed.getX()) > TREE_RADIUS || Math.abs(p.getZ() - seed.getZ()) > TREE_RADIUS
                    || Math.abs(p.getY() - seed.getY()) > TREE_HEIGHT) return rejected("枝幹超出有界回溯範圍");
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                if (x == 0 && y == 0 && z == 0) continue;
                BlockPos next = p.offset(x,y,z); Cell adjacent = access.cell(next);
                if (adjacent.kind().equals("unloaded")) return rejected("請靠近樹木，讓周圍區塊完整載入");
                if (wood(adjacent, species) && !trunks.contains(next)) queue.addLast(next);
            }
        }
        List<BlockPos> roots = trunks.stream().filter(p -> {
            Cell below = access.cell(p.below()); return below.kind().equals("soil") && below.natural() && !below.fluid() && !below.blockEntity();
        }).sorted(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
        if (roots.isEmpty()) return rejected("樹根下方需要自然泥土、苔蘚或泥巴");
        BlockPos root = roots.getFirst();
        if (!species.equals("mangrove")) {
            int maxRoots = Set.of("dark_oak", "spruce", "jungle", "pale_oak").contains(species) ? 4 : 1;
            if (roots.size() > maxRoots || roots.stream().anyMatch(p -> p.getY() != root.getY()
                    || Math.abs(p.getX() - root.getX()) > 1 || Math.abs(p.getZ() - root.getZ()) > 1)) return rejected("偵測到多棵樹或人工連接的樹根");
            if (roots.size() != 1 && roots.size() != 4) return rejected("雙幹或多樹根形狀不明確");
            if (species.equals("acacia")) {
                if (!ascendingAcaciaBase(root,trunks,access)) return rejected("相思樹根需有至少三層連續向上的樹幹");
            } else for (BlockPos base : roots) for (int y = 0; y < 3; y++) if (!trunks.contains(base.above(y))
                    || !access.cell(base.above(y)).kind().equals("log") || !access.cell(base.above(y)).vertical()) return rejected("樹根需有至少三格直立樹幹");
        } else if (roots.size() > 12 || roots.stream().anyMatch(p -> Math.abs(p.getX() - root.getX()) > 5
                || Math.abs(p.getZ() - root.getZ()) > 5 || Math.abs(p.getY() - root.getY()) > 3)) return rejected("紅樹林根系過大或連接多樹");
        long logs = trunks.stream().filter(p -> access.cell(p).kind().equals("log")).count();
        if (logs < 3 || trunks.stream().anyMatch(p -> p.getY() < root.getY() || p.getY() > root.getY() + TREE_HEIGHT
                || Math.abs(p.getX() - root.getX()) > TREE_RADIUS || Math.abs(p.getZ() - root.getZ()) > TREE_RADIUS)) return rejected("樹幹過小、過高或根系不明確");
        // 六方向最短路徑符合原版 leaves distance；只移除由我方枝幹支持的冠葉。
        Map<BlockPos, Integer> leaves = new LinkedHashMap<>(); ArrayDeque<BlockPos> canopy = new ArrayDeque<>();
        for (BlockPos p : trunks) for (Direction dir : Direction.values()) {
            BlockPos next = p.relative(dir); Cell c = access.cell(next);
            if (leaf(c, species) && !leaves.containsKey(next)) { leaves.put(next,1); canopy.addLast(next); }
        }
        while (!canopy.isEmpty()) {
            BlockPos p = canopy.removeFirst(); int distance = leaves.get(p);
            if (leaves.size() + trunks.size() > MAX_BLOCKS || Math.abs(p.getX() - root.getX()) > TREE_RADIUS
                    || Math.abs(p.getZ() - root.getZ()) > TREE_RADIUS || p.getY() < root.getY() || p.getY() > root.getY() + TREE_HEIGHT) return rejected("冠葉超出有界回溯範圍");
            for (Direction dir : Direction.values()) {
                BlockPos next = p.relative(dir); Cell c = access.cell(next);
                if (c.kind().equals("unloaded")) return rejected("請靠近樹冠，讓周圍區塊完整載入");
                if (distance < 6 && leaf(c, species) && !leaves.containsKey(next)) { leaves.put(next,distance + 1); canopy.addLast(next); }
            }
        }
        // 邊界的其他樹幹同樣向冠葉傳播，距離相同亦留給原樹，避免拆掉鄰樹的共用樹冠。
        Map<BlockPos,Integer> foreign = new HashMap<>(); ArrayDeque<BlockPos> competitors = new ArrayDeque<>();
        for (BlockPos p : leaves.keySet()) for (Direction dir : Direction.values()) {
            BlockPos next = p.relative(dir); Cell c = access.cell(next);
            if (c.kind().equals("log") && !trunks.contains(next) && !foreign.containsKey(p)) { foreign.put(p,1); competitors.addLast(p); }
        }
        while (!competitors.isEmpty()) {
            BlockPos p = competitors.removeFirst(); int d = foreign.get(p);
            if (d >= 6) continue;
            for (Direction dir : Direction.values()) { BlockPos next = p.relative(dir);
                if (leaves.containsKey(next) && (!foreign.containsKey(next) || foreign.get(next) > d + 1)) { foreign.put(next,d + 1); competitors.addLast(next); }
            }
        }
        List<BlockPos> ownedLeaves = leaves.entrySet().stream().filter(e -> {
            Cell c = access.cell(e.getKey()); return foreign.getOrDefault(e.getKey(),7) > e.getValue()
                && (c.distance() == 0 || c.distance() >= e.getValue());
        }).map(Map.Entry::getKey).toList();
        if (overflow[0]) return rejected("樹木檢查超出查詢上限");
        if (ownedLeaves.size() < 8 || initial.kind().equals("leaves") && !ownedLeaves.contains(hit)) return rejected("此冠葉屬於鄰樹或缺少自然樹冠");
        List<BlockPos> result = new ArrayList<>(); result.add(root); for (BlockPos p : trunks) if (!p.equals(root)) result.add(p); result.addAll(ownedLeaves);
        return new TreePlan(root,species,result,"");
    }
    /** 原版 ForkingTrunkPlacer 可在第二層偏移；每層只接同格或一個水平四鄰格。 */
    private static boolean ascendingAcaciaBase(BlockPos root, Set<BlockPos> trunks, TreeAccess access) {
        Set<BlockPos> layer = Set.of(root);
        for (int y = 0; y < 3; y++) {
            Set<BlockPos> logs = new HashSet<>();
            for (BlockPos pos : layer) if (trunks.contains(pos) && access.cell(pos).kind().equals("log") && access.cell(pos).vertical()) logs.add(pos);
            if (logs.isEmpty()) return false;
            if (y < 2) {
                Set<BlockPos> next = new HashSet<>();
                for (BlockPos pos : logs) {
                    next.add(pos.above());
                    for (Direction direction : Direction.Plane.HORIZONTAL) next.add(pos.above().relative(direction));
                }
                layer = next;
            }
        }
        return true;
    }
    private static boolean wood(Cell cell, String species) { return species.equals(cell.species()) && Set.of("log", "root").contains(cell.kind()); }
    private static boolean leaf(Cell cell, String species) { return cell.kind().equals("leaves") && species.equals(cell.species()) && cell.natural() && !cell.fluid() && !cell.blockEntity(); }
    private static TreePlan rejected(String reason) { return new TreePlan(null,"",List.of(),reason); }
    static List<BlockPos> smallTree(BlockPos hit, TreeAccess access) { return treePlan(hit,access).blocks(); }

    /** 只允許單一、可核對的原版前態；保留方向、含蠟、台階等形狀，禁止容器／掉落途徑。 */
    static Previous previous(State original) {
        if (!original.id().startsWith("minecraft:")) return null;
        String name = original.id().substring(10); Map<String,String> properties = new TreeMap<>(original.properties());
        if (Set.of("grass_block", "mycelium", "podzol", "dirt_path", "farmland", "rooted_dirt", "coarse_dirt").contains(name))
            return new Previous(new State("minecraft:dirt",Map.of()),name.equals("grass_block") ? "grass" : "surface","地表 → 泥土");
        if (crop(name)) {
            String age = properties.get("age");
            if (age != null && boundedInteger(age,1,cropMax(name))) { properties.put("age","0"); return new Previous(new State(original.id(),properties),"crop","作物 → 幼苗"); }
        }
        if (Set.of("attached_melon_stem", "attached_pumpkin_stem").contains(name)) return new Previous(new State("minecraft:" + name.substring(9),Map.of("age","0")),"stem","瓜藤 → 幼苗");
        if (Set.of("cave_vines", "cave_vines_plant").contains(name) && "true".equals(properties.get("berries"))) {
            properties.put("berries","false"); return new Previous(new State(original.id(),properties),"berries","發光漿果 → 未結果藤蔓");
        }
        String copper = copperPrevious(name);
        return copper == null ? null : new Previous(new State("minecraft:" + copper,properties),"copper","銅材 → 前一氧化階段");
    }
    private static boolean crop(String name) { return Set.of("wheat", "carrots", "potatoes", "beetroots", "nether_wart", "cocoa", "sweet_berry_bush", "torchflower_crop", "melon_stem", "pumpkin_stem").contains(name); }
    private static int cropMax(String name) { return switch (name) { case "beetroots", "nether_wart", "sweet_berry_bush" -> 3; case "cocoa" -> 2; case "torchflower_crop" -> 1; default -> 7; }; }
    private static boolean boundedInteger(String value, int low, int high) {
        try { int number = Integer.parseInt(value); return value.equals(Integer.toString(number)) && number >= low && number <= high; }
        catch (NumberFormatException error) { return false; }
    }
    static String copperPrevious(String name) {
        String wax = name.startsWith("waxed_") ? "waxed_" : ""; String body = name.substring(wax.length());
        String prefix = body.startsWith("oxidized_") ? "oxidized_" : body.startsWith("weathered_") ? "weathered_" : body.startsWith("exposed_") ? "exposed_" : "";
        if (prefix.isEmpty()) return null;
        String shape = body.substring(prefix.length());
        if (!Set.of("copper", "cut_copper", "cut_copper_stairs", "cut_copper_slab", "chiseled_copper", "copper_grate").contains(shape)) return null;
        String previous = switch (prefix) { case "oxidized_" -> "weathered_"; case "weathered_" -> "exposed_"; default -> ""; };
        return wax + previous + (previous.isEmpty() && shape.equals("copper") ? "copper_block" : shape);
    }

    private static boolean ownedNonAir(ServerLevel level, BlockPos pos, BlockState state) {
        return journal.groups().stream().filter(group -> group.dimension().equals(level.dimension().identifier().toString()))
            .flatMap(group -> group.entries().stream()).anyMatch(entry -> entry.pos().equals(pos)
                && !entry.target().id().equals("minecraft:air") && targetMatches(entry.target(), encode(state)));
    }
    static boolean targetMatches(State target, State current) {
        return target.id().endsWith("_sapling") || target.id().equals("minecraft:mangrove_propagule") ? target.id().equals(current.id()) : target.equals(current);
    }
    private static void load(MinecraftServer server, Path world) {
        owner = server; tracked.clear(); lastFeedback.clear(); journalFile = world.resolve("data/yigua/temporal-regression.json");
        try { journal = Files.exists(journalFile) ? readJournal(journalFile) : new Journal(List.of()); available = true; restoreAll(server); }
        catch (IOException | RuntimeException error) { available = false; YiguaMod.LOG.error("回溯紀錄不可讀，保留原檔並停用新轉換。", error); }
    }
    private static void tick(MinecraftServer server) {
        if (blockRegistrationPending && !blockRegistered) {
            // 世界啟動 callbacks 都已完成；default phase 永遠先於本模組，亦涵蓋後續 default 註冊。
            Identifier phase = YiguaMod.id("temporal_regression_late");
            AttackBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, phase);
            AttackBlockCallback.EVENT.register(phase, (player, level, hand, pos, direction) -> {
                if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel)) return InteractionResult.PASS;
                return regressBlock(serverPlayer, pos) ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
            });
            blockRegistered = true; blockRegistrationPending = false;
        }
        for (Entity entity : List.copyOf(tracked.values())) {
            if (!(entity.level() instanceof ServerLevel level) || level.getServer() != server) continue;
            updateMob(entity, level, false);
        }
        if (server != owner || journal.groups().isEmpty() || server.getTickCount() - retryTick < 0) return;
        List<Group> expired = new ArrayList<>();
        for (Group group : List.copyOf(journal.groups())) {
            ServerLevel level = level(group.dimension()); ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(group.owner()));
            if (!available || !group.phase().equals("active") || level == null || level.getGameTime() >= group.expires()
                    || player == null || player.level() != level || !TemporalInsight.canRewind(player)) {
                expired.add(group);
            }
        }
        if (!expired.isEmpty()) try { restoreBatch(expired); }
        catch (IOException | RuntimeException error) { available = false; retryTick = server.getTickCount() + 100; YiguaMod.LOG.error("回溯復原等待重試，紀錄未清除。", error); }
        if (journal.groups().isEmpty()) available = true;
    }
    public static void cancel(ServerPlayer player) {
        for (Entity entity : List.copyOf(tracked.values())) {
            MobLease lease = attachment(entity).getAttached(mobLeaseType);
            if (lease != null && lease.owner().equals(player.getUUID().toString()) && entity.level() instanceof ServerLevel level) updateMob(entity, level, true);
        }
        if (owner != player.level().getServer()) return;
        List<Group> own = journal.groups().stream().filter(group -> group.owner().equals(player.getUUID().toString())).toList();
        if (!own.isEmpty()) try { restoreBatch(own); }
        catch (IOException | RuntimeException error) { available = false; retryTick = owner.getTickCount() + 100; YiguaMod.LOG.error("太極撤回回溯等待復原。", error); }
    }
    private static void restoreAll(MinecraftServer server) {
        for (Entity entity : List.copyOf(tracked.values())) if (entity.level() instanceof ServerLevel level && level.getServer() == server) updateMob(entity, level, true);
        if (server != owner) return;
        if (!journal.groups().isEmpty()) try { restoreBatch(journal.groups()); }
        catch (IOException | RuntimeException error) { available = false; retryTick = server.getTickCount() + 100; YiguaMod.LOG.error("回溯復原保留待重試紀錄。", error); }
    }
    private static ServerLevel level(String dimension) {
        return owner.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    }
    private static void restore(Group group) throws IOException {
        restoreBatch(List.of(group));
    }
    private static void restoreBatch(List<Group> groups) throws IOException {
        completeBatchRestoration(journal, groups, new BatchRestorationAccess() {
            public boolean allLoaded(Group value) { ServerLevel level = level(value.dimension()); return level != null && value.entries().stream().allMatch(entry -> loaded(level, entry.pos())); }
            public State rootState(Group value) { return encode(level(value.dimension()).getBlockState(value.entries().getFirst().pos())); }
            public boolean replacementDetected(Group value) {
                ServerLevel world = level(value.dimension());
                return value.entries().stream().filter(e -> !e.target().id().equals("minecraft:air"))
                    .anyMatch(e -> !targetMatches(e.target(),encode(world.getBlockState(e.pos()))));
            }
            public void commit(Journal next) throws IOException { TemporalRegression.commit(next); }
            public void restoreAndSave(List<Group> sameDimension) throws IOException {
                ServerLevel level = level(sameDimension.getFirst().dimension());
                Map<Entry, BlockState> expected = new LinkedHashMap<>();
                for (Group value : sameDimension) expected.putAll(restoreBlocks(level, value));
                flushAndVerify(level, expected);
            }
        });
    }
    static void completeRestoration(Journal initial, Group group, RestorationAccess access) throws IOException {
        completeBatchRestoration(initial, List.of(group), new BatchRestorationAccess() {
            public boolean allLoaded(Group value) { return access.allLoaded(value); }
            public State rootState(Group value) { return access.rootState(value); }
            public void commit(Journal next) throws IOException { access.commit(next); }
            public void restoreAndSave(List<Group> values) throws IOException { access.restoreAndSave(values.getFirst()); }
        });
    }
    static void completeBatchRestoration(Journal initial, List<Group> requested, BatchRestorationAccess access) throws IOException {
        validate(initial);
        if (requested.isEmpty()) return;
        if (new HashSet<>(requested).size() != requested.size() || !initial.groups().containsAll(requested)) throw new IOException("回溯所有權不存在或重複");
        List<Group> metadata = new ArrayList<>(initial.groups()); Map<String, List<Group>> ready = new LinkedHashMap<>();
        boolean pending = false;
        for (Group group : requested) {
            if (!access.allLoaded(group)) { pending = true; continue; }
            Group next = group;
            if (group.phase().equals("active") || group.phase().equals("applying")) {
                // 根已長新樹時放棄整棵；寫入中斷則按每格 target 所有權還原。
                boolean release = group.phase().equals("active") && group.kind().equals("tree")
                    && access.replacementDetected(group);
                next = group.phase(release ? "release" : "restore"); metadata.set(metadata.indexOf(group), next);
            }
            ready.computeIfAbsent(next.dimension(), unused -> new ArrayList<>()).add(next);
        }
        Journal current = new Journal(metadata);
        if (!current.equals(initial)) access.commit(current);
        for (List<Group> sameDimension : ready.values()) {
            access.restoreAndSave(List.copyOf(sameDimension));
            List<Group> remaining = new ArrayList<>(current.groups()); remaining.removeAll(sameDimension);
            current = new Journal(remaining); access.commit(current);
        }
        if (pending) throw new IOException("等待原區塊自然載入；未載入紀錄保留");
    }
    private static Map<Entry, BlockState> restoreBlocks(ServerLevel level, Group group) throws IOException {
        ServerPlayer player = owner.getPlayerList().getPlayer(UUID.fromString(group.owner()));
        Map<Entry, BlockState> expected = new LinkedHashMap<>();
        for (Entry entry : group.entries()) {
            BlockState current = level.getBlockState(entry.pos());
            if (group.phase().equals("restore") && targetMatches(entry.target(), encode(current))) {
                if (player != null && !permit(player, level, entry.pos(), true)) throw new IOException("保護來源拒絕回溯復原");
                BlockState original = decode(entry.original());
                if (level.getBlockState(entry.pos()) != current || !level.setBlock(entry.pos(), original, FLAGS)
                        || level.getBlockState(entry.pos()) != original) throw new IOException("回溯復原被外部改動");
                current = original;
            }
            expected.put(entry, current);
        }
        return expected;
    }
    private static void replace(Group old, Group next) throws IOException {
        List<Group> groups = new ArrayList<>(journal.groups()); int index = groups.indexOf(old);
        if (index < 0) throw new IOException("回溯所有權不存在"); groups.set(index, next); commit(new Journal(groups));
    }
    private static void commit(Journal next) throws IOException { writeJournal(journalFile, next); journal = next; }
    private static void flushAndVerify(ServerLevel level, Map<Entry, BlockState> expected) throws IOException {
        level.getChunkSource().save(true);
        Map<ChunkPos, SerializableChunkData> chunks = new HashMap<>();
        for (Entry entry : expected.keySet()) {
            ChunkPos chunk = ChunkPos.containing(entry.pos());
            if (!chunks.containsKey(chunk)) {
                var tag = level.getChunkSource().chunkMap.read(chunk).join();
                if (tag.isEmpty()) throw new IOException("回溯保存後缺少區塊");
                var data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag.get());
                if (data == null || !data.chunkPos().equals(chunk)) throw new IOException("回溯區塊不可讀"); chunks.put(chunk, data);
            }
            BlockState saved = Blocks.AIR.defaultBlockState();
            for (var section : chunks.get(chunk).sectionData()) if (section.y() == (entry.y() >> 4)) {
                if (section.chunkSection() != null) saved = section.chunkSection().getBlockState(entry.x() & 15, entry.y() & 15, entry.z() & 15); break;
            }
            if (saved != expected.get(entry)) throw new IOException("回溯方塊保存讀回不符");
        }
    }

    private static State encode(BlockState state) {
        Map<String, String> properties = new TreeMap<>();
        for (var property : state.getProperties()) properties.put(property.getName(), propertyName(state, property));
        return new State(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }
    private static <T extends Comparable<T>> String propertyName(BlockState state, Property<T> property) { return property.getName(state.getValue(property)); }
    private static BlockState decode(State state) {
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(state.id()));
        BlockState result = block.defaultBlockState();
        for (var entry : state.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("無效方塊屬性"); result = apply(result, property, entry.getValue());
        }
        if (!encode(result).equals(state)) throw new IllegalArgumentException("不完整方塊狀態"); return result;
    }
    private static <T extends Comparable<T>> BlockState apply(BlockState state, Property<T> property, String name) {
        return state.setValue(property, property.getValue(name).orElseThrow(() -> new IllegalArgumentException("無效狀態值")));
    }
    static void validate(Journal value) {
        if (value == null || value.groups().size() > MAX_GROUPS) throw new IllegalArgumentException("回溯群組上限");
        int count = 0; Set<String> coordinates = new HashSet<>();
        for (Group group : value.groups()) {
            UUID.fromString(group.owner());
            if (Identifier.tryParse(group.dimension()) == null || !group.dimension().contains(":" ) || group.expires() < 0
                    || !Set.of("grass", "tree", "surface", "crop", "stem", "berries", "copper").contains(group.kind())
                    || !Set.of("applying", "active", "restore", "release").contains(group.phase())
                    || group.entries().isEmpty() || !group.kind().equals("tree") && group.entries().size() != 1) throw new IllegalArgumentException("回溯群組 metadata 無效");
            for (Entry entry : group.entries()) {
                if (Math.abs((long) entry.x()) >= 29999984 || Math.abs((long) entry.z()) >= 29999984 || Math.abs((long) entry.y()) >= 20000000
                        || !coordinates.add(group.dimension() + ":" + entry.x() + ":" + entry.y() + ":" + entry.z()) || ++count > MAX_BLOCKS) throw new IllegalArgumentException("回溯位置或數量無效");
                validateState(entry.original(), true); validateState(entry.target(), false);
                if (!group.kind().equals("tree")) {
                    Previous previous = previous(entry.original());
                    if (previous == null || !previous.kind().equals(group.kind()) || !previous.state().equals(entry.target())) throw new IllegalArgumentException("回溯單格前態不符");
                }
            }
            if (group.kind().equals("tree")) validateTree(group);
        }
    }
    private static void validateTree(Group group) {
        Entry root = group.entries().getFirst(); String name = root.original().id().substring(10);
        String species = name.endsWith("_log") ? name.substring(0,name.length() - 4)
            : Set.of("mangrove_roots", "muddy_mangrove_roots").contains(name) ? "mangrove" : "";
        if (!SPECIES.contains(species) || !root.target().id().equals("minecraft:" + saplingName(species))) throw new IllegalArgumentException("回溯樹根無效");
        int logs = 0, leaves = 0, seeds = 0;
        for (Entry entry : group.entries()) {
            String id = entry.original().id();
            boolean wood = id.equals("minecraft:" + species + "_log") || species.equals("mangrove")
                && Set.of("minecraft:mangrove_roots", "minecraft:muddy_mangrove_roots").contains(id);
            boolean leaf = id.equals("minecraft:" + species + "_leaves");
            if (Math.abs(entry.x() - root.x()) > TREE_RADIUS || Math.abs(entry.z() - root.z()) > TREE_RADIUS
                    || entry.y() < root.y() || entry.y() > root.y() + TREE_HEIGHT || !wood && !leaf) throw new IllegalArgumentException("回溯樹形範圍無效");
            if (!entry.target().id().equals("minecraft:air")) {
                if (!wood || !entry.target().id().equals(root.target().id()) || entry.y() != root.y()
                        || Math.abs(entry.x() - root.x()) > 1 || Math.abs(entry.z() - root.z()) > 1) throw new IllegalArgumentException("回溯種植點無效");
                seeds++;
            }
            if (id.endsWith("_log")) logs++;
            if (leaf) leaves++;
        }
        if (logs < 3 || logs > 192 || leaves < 8 || seeds != 1 && seeds != 4
                || seeds == 4 && !Set.of("dark_oak", "spruce", "jungle", "pale_oak").contains(species)) throw new IllegalArgumentException("回溯樹幹、冠葉或種植點數量無效");
    }
    static void validateState(State state, boolean original) {
        if (state == null || state.properties().size() > 8 || !state.id().startsWith("minecraft:")) throw new IllegalArgumentException("回溯只允許原版有界狀態");
        String name = state.id().substring(10); Map<String,String> p = state.properties();
        if (Set.of("grass_block", "mycelium", "podzol").contains(name)) { requireProperties(p,Map.of("snowy",Set.of("true","false"))); return; }
        if (Set.of("air", "dirt", "dirt_path", "rooted_dirt", "coarse_dirt").contains(name) && p.isEmpty()) return;
        if (name.equals("farmland")) { requireInteger(p,Set.of("moisture"),"moisture",7); return; }
        if (crop(name)) {
            requireInteger(p,name.equals("cocoa") ? Set.of("age","facing") : Set.of("age"),"age",cropMax(name));
            if (name.equals("cocoa") && !Set.of("north","south","east","west").contains(p.get("facing"))) throw new IllegalArgumentException("可可方向無效");
            return;
        }
        if (Set.of("attached_melon_stem", "attached_pumpkin_stem").contains(name)) { requireProperties(p,Map.of("facing",Set.of("north","south","east","west"))); return; }
        if (name.equals("cave_vines")) { requireInteger(p,Set.of("age","berries"),"age",25); if (!Set.of("true","false").contains(p.get("berries"))) throw new IllegalArgumentException("漿果狀態無效"); return; }
        if (name.equals("cave_vines_plant")) { requireProperties(p,Map.of("berries",Set.of("true","false"))); return; }
        for (String species : SPECIES) {
            if (original && name.equals(species + "_log")) { requireProperties(p,Map.of("axis",Set.of("x","y","z"))); return; }
            if (original && name.equals(species + "_leaves")) { requireProperties(p,Map.of("distance",Set.of("1","2","3","4","5","6"),"persistent",Set.of("false"),"waterlogged",Set.of("false"))); return; }
            if (!original && name.equals(species + "_sapling") && p.equals(Map.of("stage","0"))) return;
        }
        if (original && name.equals("mangrove_roots") && p.equals(Map.of("waterlogged","false"))) return;
        if (original && name.equals("muddy_mangrove_roots")) { requireProperties(p,Map.of("axis",Set.of("x","y","z"))); return; }
        if (!original && name.equals("mangrove_propagule") && p.equals(Map.of("stage","0","age","4","hanging","false","waterlogged","false"))) return;
        String shape = name.replaceFirst("^waxed_","").replaceFirst("^(exposed|weathered|oxidized)_","");
        if (Set.of("copper_block","copper","cut_copper","chiseled_copper").contains(shape) && p.isEmpty() && (shape.equals("copper_block") || copperPrevious(name) != null || !name.contains("exposed_") && !name.contains("weathered_") && !name.contains("oxidized_") && !shape.equals("copper"))) return;
        if (shape.equals("cut_copper_stairs")) { requireProperties(p,Map.of("facing",Set.of("north","south","east","west"),"half",Set.of("top","bottom"),"shape",Set.of("straight","inner_left","inner_right","outer_left","outer_right"),"waterlogged",Set.of("false"))); return; }
        if (shape.equals("cut_copper_slab")) { requireProperties(p,Map.of("type",Set.of("top","bottom","double"),"waterlogged",Set.of("false"))); return; }
        if (shape.equals("copper_grate")) { requireProperties(p,Map.of("waterlogged",Set.of("false"))); return; }
        throw new IllegalArgumentException("回溯狀態不在允許表");
    }
    private static void requireProperties(Map<String,String> properties, Map<String,Set<String>> allowed) {
        if (!properties.keySet().equals(allowed.keySet()) || properties.entrySet().stream().anyMatch(e -> !allowed.get(e.getKey()).contains(e.getValue()))) throw new IllegalArgumentException("回溯方塊屬性無效");
    }
    private static void requireInteger(Map<String,String> properties, Set<String> keys, String key, int maximum) {
        if (!properties.keySet().equals(keys) || !boundedInteger(properties.get(key),0,maximum)) throw new IllegalArgumentException("回溯成長屬性無效");
    }
    static Journal readJournal(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("回溯 journal 大小超限");
        try {
            JsonObject root = object(parse(Files.readString(file)), Set.of("schemaVersion", "groups"));
            if (integer(root, "schemaVersion") != 1 || !root.get("groups").isJsonArray()) throw new IllegalArgumentException("回溯 schema 無效");
            List<Group> groups = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("groups")) {
                JsonObject group = object(element, Set.of("owner", "dimension", "expires", "kind", "phase", "entries"));
                if (!group.get("entries").isJsonArray()) throw new IllegalArgumentException("回溯 entries 無效");
                List<Entry> entries = new ArrayList<>();
                for (JsonElement item : group.getAsJsonArray("entries")) {
                    JsonObject entry = object(item, Set.of("x", "y", "z", "original", "target"));
                    entries.add(new Entry(integer(entry, "x"), integer(entry, "y"), integer(entry, "z"), state(entry.get("original")), state(entry.get("target"))));
                    if (entries.size() > MAX_BLOCKS) throw new IllegalArgumentException("回溯 entries 超限");
                }
                groups.add(new Group(string(group, "owner"), string(group, "dimension"), number(group, "expires"), string(group, "kind"), string(group, "phase"), entries));
                if (groups.size() > MAX_GROUPS) throw new IllegalArgumentException("回溯 groups 超限");
            }
            Journal result = new Journal(groups); validate(result); return result;
        } catch (RuntimeException error) { throw new IOException("回溯 journal 無效，原檔保留", error); }
    }
    static void writeJournal(Path file, Journal value) throws IOException {
        validate(value); JsonObject document = new JsonObject(); document.addProperty("schemaVersion", 1); document.add("groups", JSON.toJsonTree(value.groups()));
        byte[] bytes = JSON.toJson(document).getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_BYTES) throw new IOException("回溯 journal 大小超限");
        Files.createDirectories(file.getParent()); Path temporary = Files.createTempFile(file.getParent(), "temporal-", ".pending");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) channel.write(data); channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static JsonElement parse(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT); JsonElement result = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("尾端資料"); return result;
        } catch (IOException error) { throw new IllegalArgumentException(error); }
    }
    private static JsonObject object(JsonElement value, Set<String> keys) {
        if (!value.isJsonObject() || !value.getAsJsonObject().keySet().equals(keys)) throw new IllegalArgumentException("欄位或型別無效"); return value.getAsJsonObject();
    }
    private static String string(JsonObject value, String key) {
        JsonElement element = value.get(key); if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("字串型別無效"); return element.getAsString();
    }
    private static long number(JsonObject value, String key) {
        JsonElement element = value.get(key); if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("數字型別無效");
        try { return element.getAsBigDecimal().longValueExact(); } catch (ArithmeticException error) { throw new IllegalArgumentException("整數無效", error); }
    }
    private static int integer(JsonObject value, String key) { return Math.toIntExact(number(value, key)); }
    private static State state(JsonElement value) {
        JsonObject object = object(value, Set.of("id", "properties")); if (!object.get("properties").isJsonObject()) throw new IllegalArgumentException("方塊屬性型別無效");
        Map<String, String> properties = new TreeMap<>(); JsonObject raw = object.getAsJsonObject("properties");
        for (String key : raw.keySet()) properties.put(key, string(raw, key)); return new State(string(object, "id"), properties);
    }
    static MobLease readMobLease(String text) {
        if (text.length() > 2048) throw new IllegalArgumentException("生物標記超限");
        JsonObject value = object(parse(text), Set.of("owner", "target", "type", "kind", "originalAge", "observedAge", "started", "expires"));
        MobLease lease = new MobLease(string(value, "owner"), string(value, "target"), string(value, "type"), string(value, "kind"),
            integer(value, "originalAge"), integer(value, "observedAge"), number(value, "started"), number(value, "expires"));
        UUID.fromString(lease.owner()); UUID.fromString(lease.target()); Identifier type = Identifier.tryParse(lease.type());
        if (type == null || !type.getNamespace().equals("minecraft") || !Set.of("zombie", "ageable").contains(lease.kind())
                || lease.originalAge() < 0 || lease.originalAge() > 24000 || lease.started() < 0 || lease.expires() <= lease.started()
                || lease.expires() - lease.started() > 2400 || !ownsAge(lease.kind(), lease.observedAge(), lease.observedAge())) throw new IllegalArgumentException("生物標記無效");
        return lease;
    }
}

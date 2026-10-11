package art.yigua.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;

/** 有界的暫時分海；紀錄先落盤，任何非原版水／自有空氣都不覆寫。 */
public final class TemporaryWaterCorridor {
    static final int WIDTH = 9;
    static final int LENGTH = 12;
    static final int DURATION_TICKS = 400;
    static final int MAX_DURATION_TICKS = 12000;
    static final int MAX_BLOCKS = 32768;
    private static final int SCHEMA_VERSION = 1;
    static final int MAX_JOURNAL_BYTES = 8 * 1024 * 1024;
    static final int MAX_CHUNKS = 32;
    static final int SCAN_BUDGET = 2048;
    static final int AUDIT_BUDGET = 256;
    static final int RESTORE_BUDGET = 512;
    static final int EXPAND_BUDGET = 512;
    private static final int MAX_SAFE_DROP = 8;
    // 不發鄰居／形狀更新，不讓移水連帶破壞水生植物、waterlogged 方塊。
    private static final int UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Journal EMPTY = new Journal("", List.of());
    private static MinecraftServer owner;
    private static Path journalFile;
    private static Session session;
    private static boolean available;

    /** 只保存自有座標與原版水 LEVEL，不保存玩家或其他來源資料。 */
    record Entry(int x, int y, int z, int waterLevel, boolean wantedAir) {
        BlockPos pos() { return new BlockPos(x, y, z); }
        Entry release() { return new Entry(x, y, z, waterLevel, false); }
        ChunkPos chunk() { return new ChunkPos(x >> 4, z >> 4); }
    }
    record Journal(String dimension, List<Entry> entries) {
        Journal { entries = List.copyOf(entries); }
    }
    private static final class Session {
        UUID playerId;
        Journal journal;
        final String dimension;
        final int startedTick;
        final int durationTicks;
        boolean restoring, limitNotified, boundaryNotified;
        int retryTick, auditCursor, restoreCursor, columnCursor, scanY, scanTop, nextScanTick;
        BlockPos origin;
        Direction direction;
        List<BlockPos> columns = List.of();
        final Map<BlockPos, Entry> owned = new HashMap<>();
        final Set<ChunkPos> chunks = new HashSet<>();
        Session(Journal journal, int tick, boolean restoring) {
            this(journal, journal.dimension(), tick, DURATION_TICKS, restoring);
        }
        Session(Journal journal, String dimension, int tick, int durationTicks, boolean restoring) {
            this.dimension = dimension; this.startedTick = tick; this.durationTicks = durationTicks;
            this.restoring = restoring; this.retryTick = tick; install(journal);
        }
        void install(Journal next) {
            journal = next; owned.clear(); chunks.clear();
            for (Entry entry : next.entries()) { owned.put(entry.pos(), entry); chunks.add(entry.chunk()); }
            if (next.entries().isEmpty()) auditCursor = restoreCursor = 0;
            else { auditCursor = Math.floorMod(auditCursor, next.entries().size()); restoreCursor = Math.floorMod(restoreCursor, next.entries().size()); }
        }
    }

    private TemporaryWaterCorridor() {}

    /** SERVER_STARTED 呼叫；未完成的舊紀錄先復原，失敗時保留原檔並拒絕新事件。 */
    public static void load(MinecraftServer server, Path worldDir) {
        Objects.requireNonNull(server, "伺服器不得為空");
        Objects.requireNonNull(worldDir, "世界路徑不得為空");
        if (owner == server && session != null) {
            LOGGER.error("分海尚有未完成復原，不能重新載入紀錄。");
            return;
        }
        clear();
        owner = server;
        journalFile = worldDir.resolve("data/yigua/temporary-water.json");
        try {
            Journal saved = Files.exists(journalFile) ? readJournal(journalFile) : EMPTY;
            available = true;
            if (!saved.entries().isEmpty()) {
                session = new Session(saved, server.getTickCount(), true);
                restoreAll(server);
            }
        } catch (IOException | RuntimeException error) {
            available = false;
            LOGGER.error("分海復原紀錄無法讀取；保留原檔，暫停新分海：{}", journalFile, error);
        }
    }

    /** 寬九格、前方十一格、向上所有原版水；已開水道保留至原期限。 */
    public static boolean start(ServerPlayer player) {
        return start(player, DURATION_TICKS);
    }

    public static boolean start(ServerPlayer player, int durationTicks) {
        Objects.requireNonNull(player, "玩家不得為空");
        ServerLevel level = (ServerLevel)player.level();
        MinecraftServer server = level.getServer();
        if (!available || owner != server || session != null || !server.isSameThread()
                || !WorldChangePolicy.enabled() || !activePlayer(player)
                || !validDuration(durationTicks)) return false;
        String dimension = level.dimension().identifier().toString();
        session = new Session(EMPTY, dimension, server.getTickCount(), durationTicks, false);
        session.playerId = player.getUUID();
        try {
            advance(level, player);
            // 合法乾地起效也保留 session，等待兩分鐘內自然走入水域；不重設期限。
            return true;
        } catch (IOException | RuntimeException error) {
            LOGGER.error("分海未完整建立，立即嘗試復原。", error);
            restoreAll(server);
            return false;
        }
    }

    /** 到期、停用、死亡、離線或切換維度即收回；移動／轉向不延長時間。 */
    public static void tick(MinecraftServer server) {
        if (server != owner || session == null) return;
        if (session.restoring || expired(server.getTickCount(), session.startedTick, session.durationTicks)) {
            if (server.getTickCount() - session.retryTick >= 0) restoreAll(server);
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
        ServerLevel level = findLevel(server, session.dimension);
        if (!WorldChangePolicy.enabled() || player == null || !activePlayer(player)
                || level == null || player.level() != level) { restoreAll(server); return; }
        try {
            auditOwnership(level);
            advance(level, player);
        } catch (IOException | RuntimeException error) {
            LOGGER.error("分海所有權／寫前保存失敗，保留紀錄並開始有界復原。", error);
            restoreAll(server);
        }
    }

    private static void auditOwnership(ServerLevel level) throws IOException {
        List<Entry> entries = session.journal.entries();
        if (entries.isEmpty()) return;
        Map<BlockPos, Entry> replacements = new HashMap<>();
        Map<Entry, BlockState> changed = new LinkedHashMap<>();
        for (int n = 0; n < Math.min(AUDIT_BUDGET, entries.size()); n++) {
            Entry entry = entries.get(session.auditCursor++ % entries.size());
            if (!entry.wantedAir() || !loaded(level, entry.pos())) continue;
            BlockState state = level.getBlockState(entry.pos());
            // 已開 AIR 後，任何重新放入的方塊或水（包括相同 LEVEL）都撤權，不再清除。
            if (!state.is(Blocks.AIR)) { replacements.put(entry.pos(), entry.release()); changed.put(entry, state); }
        }
        session.auditCursor = Math.floorMod(session.auditCursor, entries.size());
        if (replacements.isEmpty()) return;
        flushAndVerify(level, changed);
        Journal next = journal(session.dimension, entries.stream().map(e -> replacements.getOrDefault(e.pos(), e)).toList());
        writeJournal(journalFile, next); session.install(next);
    }

    /** journal 仍保留的每格都預留，含撤權格／到期／待復原，避免其他暫態事件取得同格。 */
    public static boolean claims(ServerLevel level, BlockPos pos) {
        return session != null && level != null && pos != null && owner == level.getServer()
            && session.dimension.equals(level.dimension().identifier().toString())
            && session.owned.containsKey(pos);
    }

    static boolean claimedPosition(Journal journal, BlockPos pos) {
        return journal.entries().stream().anyMatch(entry -> entry.x() == pos.getX() && entry.y() == pos.getY() && entry.z() == pos.getZ());
    }

    /** 只阻擋原版自然水流回灌自有空氣；桶裝水／其他來源 setBlock 不經此入口。 */
    public static boolean blocksNaturalWaterFlow(ServerLevel level, BlockPos pos) {
        if (session == null || level == null || pos == null
                || owner != level.getServer() || !owner.isSameThread()
                || !level.dimension().identifier().toString().equals(session.dimension)
                || !session.owned.containsKey(pos) || !session.owned.get(pos).wantedAir() || !loaded(level, pos)
                || !level.getBlockState(pos).is(Blocks.AIR)) return false;
        ServerPlayer player = session.playerId == null ? null : owner.getPlayerList().getPlayer(session.playerId);
        if (session.restoring || expired(owner.getTickCount(), session.startedTick, session.durationTicks) || !WorldChangePolicy.enabled())
            return (player == null || WorldChangePolicy.permission(level, player, pos)) && level.getBlockState(pos).is(Blocks.AIR);
        return player != null && activePlayer(player) && player.level() == level
            && WorldChangePolicy.allow(player, pos) && level.getBlockState(pos).is(Blocks.AIR);
    }

    static boolean ownsAir(Journal journal, BlockPos pos) {
        return journal.entries().stream().anyMatch(entry -> entry.wantedAir() && entry.x() == pos.getX() && entry.y() == pos.getY() && entry.z() == pos.getZ());
    }

    static boolean validDuration(int ticks) { return ticks > 0 && ticks <= MAX_DURATION_TICKS; }

    static boolean expired(int now, int started, int duration) { return now - started >= duration; }

    static boolean recoveryDue(boolean restoring, int now, int retryTick) { return !restoring || now - retryTick >= 0; }

    private static boolean activePlayer(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.hasDisconnected();
    }

    /** 純水平幾何，腳下正中優先；每列向上範圍由真世界已載入 surface 決定。 */
    static List<BlockPos> columns(BlockPos feet, Direction forward) {
        if (forward.getAxis().isVertical()) throw invalid("走廊方向必須水平");
        List<BlockPos> positions = new ArrayList<>(WIDTH * LENGTH);
        Direction side = forward.getClockWise();
        for (int distance = 0; distance < LENGTH; distance++) {
            positions.add(feet.relative(forward, distance));
            for (int lane = 1; lane <= WIDTH / 2; lane++) {
                positions.add(feet.relative(forward, distance).relative(side, lane));
                positions.add(feet.relative(forward, distance).relative(side, -lane));
            }
        }
        return List.copyOf(positions);
    }

    interface ExpansionAccess {
        void commit(Journal journal) throws IOException;
        Entry capture(BlockPos pos);
        void open(List<Entry> added) throws IOException;
    }
    record ExpansionResult(Journal journal, boolean limited) {}

    /** 累積 transaction：新水 WAL 成功後才開；舊 AIR／撤權格都不可隨移動釋放。 */
    static ExpansionResult expand(Journal before, String dimension, List<BlockPos> candidates, ExpansionAccess access) throws IOException {
        validate(before);
        if (!before.entries().isEmpty() && !before.dimension().equals(dimension)) throw invalid("不能跨維度轉移紀錄");
        if (candidates.size() > SCAN_BUDGET || new HashSet<>(candidates).size() != candidates.size()) throw invalid("候選批次超量或重複");
        List<Entry> entries = new ArrayList<>(before.entries()), added = new ArrayList<>();
        Set<BlockPos> occupied = new HashSet<>(); Set<ChunkPos> chunks = new HashSet<>();
        for (Entry e : entries) { occupied.add(e.pos()); chunks.add(e.chunk()); }
        boolean limited = false;
        for (BlockPos pos : candidates) {
            if (occupied.contains(pos)) continue;
            Entry capture = access.capture(pos);
            if (capture == null) continue;
            if (!capture.pos().equals(pos) || !capture.wantedAir()) throw invalid("新水域紀錄不符候選");
            if (entries.size() >= MAX_BLOCKS || !chunks.contains(capture.chunk()) && chunks.size() >= MAX_CHUNKS) { limited = true; continue; }
            entries.add(capture); added.add(capture); occupied.add(pos); chunks.add(capture.chunk());
            if (added.size() >= EXPAND_BUDGET) break;
        }
        Journal next = journal(dimension, entries); validate(next);
        if (!added.isEmpty()) { access.commit(next); access.open(List.copyOf(added)); }
        return new ExpansionResult(next, limited);
    }

    private static Journal journal(String dimension, List<Entry> entries) { return entries.isEmpty() ? EMPTY : new Journal(dimension, entries); }

    private static void advance(ServerLevel level, ServerPlayer player) throws IOException {
        if (session.owned.size() >= MAX_BLOCKS) { notifyLimit(player); return; }
        BlockPos feet = player.blockPosition(); Direction direction = player.getDirection();
        if (!feet.equals(session.origin) || direction != session.direction) {
            session.origin = feet.immutable(); session.direction = direction; session.columns = columns(feet, direction);
            session.columnCursor = 0; session.scanTop = Integer.MIN_VALUE; session.nextScanTick = owner.getTickCount();
        } else if (session.columnCursor >= session.columns.size()) {
            if (owner.getTickCount() - session.nextScanTick < 0) return;
            session.columnCursor = 0; session.scanTop = Integer.MIN_VALUE;
        }
        List<BlockPos> candidates = new ArrayList<>(); int scanned = 0;
        while (session.columnCursor < session.columns.size() && scanned < SCAN_BUDGET && candidates.size() < EXPAND_BUDGET) {
            BlockPos column = session.columns.get(session.columnCursor);
            if (session.scanTop == Integer.MIN_VALUE) {
                if (session.chunks.size() >= MAX_CHUNKS && !session.chunks.contains(new ChunkPos(column.getX() >> 4, column.getZ() >> 4))) {
                    notifyLimit(player); session.columnCursor++; continue;
                }
                if (!safeColumn(level, column)) {
                    session.columnCursor++;
                    if (!session.boundaryNotified) { session.boundaryNotified = true; player.sendSystemMessage(net.minecraft.network.chat.Component.literal("分海前方尚未載入或無安全支撐；既有水道會保留至到期。")); }
                    continue;
                }
                // loaded 驗證成功後才讀 heightmap；不向新區塊要求生成。
                session.scanTop = Math.min(level.getMaxY() - 1, level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ()) - 1);
                session.scanY = column.getY();
            }
            if (!loaded(level, column)) { session.columnCursor++; session.scanTop = Integer.MIN_VALUE; continue; }
            while (session.scanY <= session.scanTop && scanned < SCAN_BUDGET && candidates.size() < EXPAND_BUDGET) {
                BlockPos pos = new BlockPos(column.getX(), session.scanY++, column.getZ()); scanned++;
                if (level.isInWorldBounds(pos) && !session.owned.containsKey(pos) && level.getBlockState(pos).is(Blocks.WATER)) candidates.add(pos);
            }
            if (session.scanY > session.scanTop) { session.columnCursor++; session.scanTop = Integer.MIN_VALUE; }
        }
        if (session.columnCursor >= session.columns.size()) session.nextScanTick = owner.getTickCount() + 20;
        if (candidates.isEmpty()) return;
        ExpansionResult result = expand(session.journal, session.dimension, candidates, new ExpansionAccess() {
            @Override public void commit(Journal journal) throws IOException { writeJournal(journalFile, journal); session.install(journal); }
            @Override public Entry capture(BlockPos pos) {
                if (!loaded(level, pos)) return null;
                BlockState state = level.getBlockState(pos);
                if (!state.is(Blocks.WATER) || TemporaryObstruction.claims(level, pos) || TemporalRegression.claims(level, pos)
                        || !WorldChangePolicy.allow(player, pos) || TemporaryObstruction.claims(level, pos) || TemporalRegression.claims(level, pos)
                        || level.getBlockState(pos) != state) return null;
                return new Entry(pos.getX(), pos.getY(), pos.getZ(), state.getValue(LiquidBlock.LEVEL), true);
            }
            @Override public void open(List<Entry> entries) throws IOException {
                for (Entry entry : entries) {
                    BlockState state = level.getBlockState(entry.pos());
                    if (!originalWater(state, entry) || !WorldChangePolicy.allow(player, entry.pos()) || level.getBlockState(entry.pos()) != state
                            || !level.setBlock(entry.pos(), Blocks.AIR.defaultBlockState(), UPDATE_FLAGS) || !level.getBlockState(entry.pos()).is(Blocks.AIR))
                        throw new IOException("其他來源拒絕新水域移水；保留WAL以復原");
                }
            }
        });
        if (result.limited()) notifyLimit(player);
    }

    private static void notifyLimit(ServerPlayer player) {
        if (!session.limitNotified) { session.limitNotified = true; player.sendSystemMessage(net.minecraft.network.chat.Component.literal("分海範圍已達上限，既有水道會保留至到期。")); }
    }

    private static boolean originalWater(BlockState state, Entry entry) {
        return state.is(Blocks.WATER) && state.getValue(LiquidBlock.LEVEL) == entry.waterLevel();
    }

    private static void restoreEntries(ServerLevel level, List<Entry> entries) throws IOException {
        Map<Entry, BlockState> finalStates = new LinkedHashMap<>();
        ServerPlayer player = session.playerId == null ? null : owner.getPlayerList().getPlayer(session.playerId);
        for (Entry entry : entries) {
            if (!entry.wantedAir()) continue;
            if (!level.isInWorldBounds(entry.pos()) || !loaded(level, entry.pos())) throw new IOException("復原區塊尚未載入／越界");
            BlockState state = level.getBlockState(entry.pos());
            if (state.is(Blocks.AIR)) {
                // 有原玩家時先問原版／Fabric 保護；crash 載入沒有玩家，不偽造身份。
                if (player != null && !WorldChangePolicy.permission(level, player, entry.pos()))
                    throw new IOException("其他來源拒絕復原自有空氣；保留 journal");
                BlockState original = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, entry.waterLevel());
                if (level.getBlockState(entry.pos()) != state
                        || !level.setBlock(entry.pos(), original, UPDATE_FLAGS)
                        || level.getBlockState(entry.pos()) != original) throw new IOException("原始水方塊復原失敗");
                state = original;
            }
            // 玩家新增方塊、不同 LEVEL 的新水皆原樣保存，不以舊快照覆寫。
            finalStates.put(entry, state);
        }
        if (!finalStates.isEmpty()) flushAndVerify(level, finalStates);
    }

    interface RecoveryAccess {
        boolean ready(Entry entry);
        void restoreAndSave(List<Entry> batch) throws IOException;
        void commit(Journal remaining) throws IOException;
    }
    record RecoveryResult(Journal journal, int cursor, int completed) {}

    /** 分批交易也與 crash fixture 共用；方塊保存成功後才能縮減 journal。 */
    static RecoveryResult recover(Journal before, int cursor, RecoveryAccess access) throws IOException {
        validate(before);
        if (before.entries().isEmpty()) { access.commit(EMPTY); return new RecoveryResult(EMPTY, 0, 0); }
        List<Entry> entries = before.entries(), batch = new ArrayList<>();
        cursor = Math.floorMod(cursor, entries.size());
        for (int n = 0; n < Math.min(SCAN_BUDGET, entries.size()) && batch.size() < RESTORE_BUDGET; n++) {
            Entry entry = entries.get(cursor++ % entries.size());
            if (access.ready(entry)) batch.add(entry);
        }
        if (batch.isEmpty()) return new RecoveryResult(before, Math.floorMod(cursor, entries.size()), 0);
        access.restoreAndSave(List.copyOf(batch));
        Set<BlockPos> restored = new HashSet<>(); batch.forEach(e -> restored.add(e.pos()));
        Journal remaining = journal(before.dimension(), entries.stream().filter(e -> !restored.contains(e.pos())).toList());
        access.commit(remaining);
        return new RecoveryResult(remaining, remaining.entries().isEmpty() ? 0 : Math.floorMod(cursor, remaining.entries().size()), batch.size());
    }

    /** 每次至多 512 格；僅已載入區塊，保護拒絕／離線區塊留 WAL 待自然載入。 */
    public static void restoreAll(MinecraftServer server) {
        if (server != owner || journalFile == null || session == null) return;
        if (!recoveryDue(session.restoring, server.getTickCount(), session.retryTick)) return;
        session.restoring = true; session.retryTick = server.getTickCount() + 1;
        try {
            ServerLevel level = findLevel(server, session.dimension);
            if (level == null) { session.retryTick += 99; return; }
            ServerPlayer player = session.playerId == null ? null : owner.getPlayerList().getPlayer(session.playerId);
            RecoveryResult result = recover(session.journal, session.restoreCursor, new RecoveryAccess() {
                @Override public boolean ready(Entry entry) {
                    if (!entry.wantedAir()) return true;
                    if (!level.isInWorldBounds(entry.pos()) || !loaded(level, entry.pos())) return false;
                    return !level.getBlockState(entry.pos()).is(Blocks.AIR) || player == null || WorldChangePolicy.permission(level, player, entry.pos());
                }
                @Override public void restoreAndSave(List<Entry> batch) throws IOException { restoreEntries(level, batch); }
                @Override public void commit(Journal remaining) throws IOException { writeJournal(journalFile, remaining); session.install(remaining); }
            });
            if (result.journal().entries().isEmpty()) { session = null; available = true; }
            else { session.restoreCursor = result.cursor(); if (result.completed() == 0) session.retryTick += 19; }
        } catch (IOException | RuntimeException error) {
            available = false; session.retryTick = server.getTickCount() + 100;
            LOGGER.error("分海尚未完成復原／保存；保留 journal，稍後重試：{}", journalFile, error);
        }
    }

    /** 解除本人狀態只復原本人開啟的走廊，不動其他玩家的活動。 */
    public static void cancel(ServerPlayer player) {
        if (session != null && player.getUUID().equals(session.playerId)) restoreAll(player.level().getServer());
    }

    /** 只清理執行期引用；磁碟復原紀錄必須由成功的 restoreAll 清空。 */
    public static void clear() {
        owner = null;
        journalFile = null;
        session = null;
        available = false;
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static boolean safeColumn(ServerLevel level, BlockPos column) {
        // 外緣留一格；開始時只讀已載入區塊，不向遠處請求世界生成。
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            BlockPos edge = column.offset(x, 0, z);
            if (!level.isInWorldBounds(edge) || !level.getWorldBorder().isWithinBounds(edge)
                    || !loaded(level, edge)) return false;
        }
        for (int depth = 1; depth <= MAX_SAFE_DROP; depth++) {
            BlockPos below = column.below(depth);
            if (!level.isInWorldBounds(below)) return false;
            BlockState state = level.getBlockState(below);
            if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, below).isEmpty()) return true;
        }
        return false;
    }

    private static void flushAndVerify(ServerLevel level, Map<Entry, BlockState> expected) throws IOException {
        // 公開 API 會同步保存此維度已載入 chunk，並不載入額外區域。不能先清 journal 再等自動存檔。
        level.getChunkSource().save(true);
        Map<ChunkPos, SerializableChunkData> savedChunks = new HashMap<>();
        for (Entry entry : expected.keySet()) {
            ChunkPos chunk = entry.chunk();
            if (!savedChunks.containsKey(chunk)) {
                var tag = level.getChunkSource().chunkMap.read(chunk).join();
                if (tag.isEmpty()) throw new IOException("同步保存後無法讀回分海區塊");
                SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag.get());
                if (data == null || !data.chunkPos().equals(chunk)) throw new IOException("分海區塊存檔格式或位置錯誤");
                savedChunks.put(chunk, data);
            }
            BlockState saved = Blocks.AIR.defaultBlockState();
            for (SerializableChunkData.SectionData section : savedChunks.get(chunk).sectionData()) {
                if (section.y() == (entry.y() >> 4)) {
                    LevelChunkSection blocks = section.chunkSection();
                    if (blocks != null) saved = blocks.getBlockState(entry.x() & 15, entry.y() & 15, entry.z() & 15);
                    break;
                }
            }
            if (saved != expected.get(entry)) throw new IOException("分海方塊尚未成功寫入世界存檔：" + entry.pos());
        }
    }

    static Journal readJournal(Path file) throws IOException {
        if (Files.size(file) > MAX_JOURNAL_BYTES) throw new IOException("分海 journal 超過安全大小");
        JsonElement document = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!document.isJsonObject()) throw invalid("根必須為物件");
        JsonObject root = document.getAsJsonObject();
        if (!root.keySet().equals(Set.of("schemaVersion", "dimension", "entries"))) throw invalid("根欄位缺漏或包含未知資料");
        if (integer(root, "schemaVersion") != SCHEMA_VERSION) throw invalid("未知 schemaVersion");
        if (!root.has("dimension") || !root.get("dimension").isJsonPrimitive()
                || !root.getAsJsonPrimitive("dimension").isString()) throw invalid("dimension 必須是字串");
        if (!root.has("entries") || !root.get("entries").isJsonArray()) throw invalid("entries 必須是陣列");
        List<Entry> entries = new ArrayList<>();
        for (JsonElement item : root.getAsJsonArray("entries")) {
            if (!item.isJsonObject()) throw invalid("每筆方塊紀錄必須是物件");
            JsonObject entry = item.getAsJsonObject();
            if (!entry.keySet().equals(Set.of("x", "y", "z", "waterLevel", "wantedAir"))) throw invalid("方塊欄位缺漏或包含未知資料");
            if (!entry.has("wantedAir") || !entry.get("wantedAir").isJsonPrimitive()
                    || !entry.getAsJsonPrimitive("wantedAir").isBoolean()) throw invalid("wantedAir 必須是布林值");
            entries.add(new Entry(integer(entry, "x"), integer(entry, "y"), integer(entry, "z"),
                integer(entry, "waterLevel"), entry.get("wantedAir").getAsBoolean()));
            if (entries.size() > MAX_BLOCKS) throw invalid("方塊數超過走廊上限");
        }
        Journal journal = new Journal(root.get("dimension").getAsString(), entries);
        validate(journal);
        return journal;
    }

    static void writeJournal(Path file, Journal journal) throws IOException {
        validate(journal);
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("dimension", journal.dimension());
        root.add("entries", GSON.toJsonTree(journal.entries()));
        byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JOURNAL_BYTES) throw new IOException("分海 journal 超過安全大小");
        Files.createDirectories(file.getParent());
        Path pending = Files.createTempFile(file.getParent(), "temporary-water-", ".pending");
        try {
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // 不退回非原子 move；無法原子提交就不准開始改世界。
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally { Files.deleteIfExists(pending); }
    }

    private static void validate(Journal journal) {
        if (journal == null || journal.dimension() == null || journal.entries() == null
                || journal.entries().size() > MAX_BLOCKS) throw invalid("紀錄缺漏或超過方塊數上限");
        if (journal.entries().isEmpty()) {
            if (!journal.dimension().isEmpty()) throw invalid("空紀錄不得保留維度");
            return;
        }
        if (!journal.dimension().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw invalid("dimension 不是有效識別名稱");
        Set<String> positions = new HashSet<>();
        Set<Long> chunks = new HashSet<>();
        for (Entry entry : journal.entries()) {
            if (entry == null || entry.waterLevel() < 0 || entry.waterLevel() > 15
                    || entry.x() < -30000000 || entry.x() >= 30000000
                    || entry.z() < -30000000 || entry.z() >= 30000000
                    || entry.y() < -20000000 || entry.y() >= 20000000) throw invalid("座標或水 LEVEL 超出範圍");
            if (!positions.add(entry.x() + "," + entry.y() + "," + entry.z())) throw invalid("同一座標重複");
            chunks.add(((long)(entry.x() >> 4) << 32) | ((entry.z() >> 4) & 0xffffffffL));
        }
        if (chunks.size() > MAX_CHUNKS) throw invalid("復原紀錄超過安全區塊數");
    }

    private static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(field + " 必須是整數");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException error) { throw invalid(field + " 不是有效整數"); }
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("分海復原紀錄無效：" + reason);
    }
}

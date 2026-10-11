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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;

/** 有界的暫時分海；紀錄先落盤，任何非原版水／自有空氣都不覆寫。 */
public final class TemporaryWaterCorridor {
    static final int WIDTH = 3;
    static final int HEIGHT = 4;
    static final int LENGTH = 12;
    static final int DURATION_TICKS = 400;
    static final int MAX_BLOCKS = WIDTH * HEIGHT * LENGTH;
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_JOURNAL_BYTES = 64 * 1024;
    private static final int MAX_CHUNKS = 4;
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
        final int startedTick;
        boolean restoring;
        int retryTick;
        Session(Journal journal, int tick, boolean restoring) {
            this.journal = journal;
            this.startedTick = tick;
            this.restoring = restoring;
            this.retryTick = tick;
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

    /** 水域從玩家前方一格開始；同一伺服器同時最多一個走廊。 */
    public static boolean start(ServerPlayer player) {
        Objects.requireNonNull(player, "玩家不得為空");
        ServerLevel level = (ServerLevel)player.level();
        MinecraftServer server = level.getServer();
        if (!available || owner != server || session != null || !server.isSameThread()
                || !player.isAlive() || player.isSpectator()) return false;
        BlockPos feet = player.blockPosition();
        Direction forward = player.getDirection();
        if (forward.getAxis().isVertical() || feet.getY() < level.getMinY() + HEIGHT
                || feet.getY() > level.getMaxY() - (HEIGHT - 1)) return false;
        Direction side = forward.getClockWise();
        List<Entry> entries = new ArrayList<>();
        for (int distance = 1; distance <= LENGTH; distance++) {
            for (int lane = -1; lane <= 1; lane++) {
                BlockPos column = feet.relative(forward, distance).relative(side, lane);
                if (!safeColumn(level, column)) return false;
                for (int height = 0; height < HEIGHT; height++) {
                    BlockPos pos = column.above(height);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.WATER)) entries.add(new Entry(pos.getX(), pos.getY(), pos.getZ(),
                        state.getValue(LiquidBlock.LEVEL), true));
                }
            }
        }
        if (entries.isEmpty()) return false;
        Journal planned = new Journal(level.dimension().identifier().toString(), entries);
        try {
            // 整份 write-ahead journal 成功且原子置換後，才允許第一個方塊修改。
            writeJournal(journalFile, planned);
        } catch (IOException | RuntimeException error) {
            available = false;
            LOGGER.error("分海紀錄寫入失敗，未修改世界：{}", journalFile, error);
            return false;
        }
        session = new Session(planned, server.getTickCount(), false);
        session.playerId = player.getUUID();
        try {
            for (Entry entry : entries) {
                BlockPos pos = entry.pos();
                if (!level.getBlockState(pos).is(Blocks.WATER)
                        || !level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_FLAGS)) {
                    throw new IllegalStateException("分海方塊未能安全移開：" + pos);
                }
            }
            return true;
        } catch (RuntimeException error) {
            LOGGER.error("分海未完整建立，立即嘗試復原。", error);
            restoreAll(server);
            return false;
        }
    }

    /** 以伺服器刻計時，玩家離線或切換維度不延長事件。 */
    public static void tick(MinecraftServer server) {
        if (server != owner || session == null) return;
        if (session.restoring || server.getTickCount() - session.startedTick >= DURATION_TICKS) {
            if (server.getTickCount() - session.retryTick >= 0) restoreAll(server);
            return;
        }
        ServerLevel level = findLevel(server, session.journal.dimension());
        if (level == null) { restoreAll(server); return; }
        try {
            List<Entry> next = new ArrayList<>();
            Map<Entry, BlockState> released = new LinkedHashMap<>();
            for (Entry entry : session.journal.entries()) {
                if (!entry.wantedAir()) { next.add(entry); continue; }
                if (!loaded(level, entry.pos())) { restoreAll(server); return; }
                BlockState state = level.getBlockState(entry.pos());
                if (!state.is(Blocks.WATER) && !state.is(Blocks.AIR)) {
                    next.add(entry.release());
                    released.put(entry, state);
                } else next.add(entry);
            }
            if (!released.isEmpty()) {
                // 先確保玩家／其他模組的替代方塊也已保存，再永久撤銷這些座標所有權。
                flushAndVerify(level, released);
                Journal updated = new Journal(session.journal.dimension(), next);
                writeJournal(journalFile, updated);
                session.journal = updated;
            }
            for (Entry entry : session.journal.entries()) {
                if (entry.wantedAir() && level.getBlockState(entry.pos()).is(Blocks.WATER)
                        && !level.setBlock(entry.pos(), Blocks.AIR.defaultBlockState(), UPDATE_FLAGS)) {
                    throw new IllegalStateException("分海回填水未能安全移開");
                }
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.error("分海維持或所有權保存失敗，立即結束並復原。", error);
            restoreAll(server);
        }
    }

    /** STOPPING／到期皆呼叫；復原與保存成功之前絕不清空 journal。 */
    public static void restoreAll(MinecraftServer server) {
        if (server != owner || journalFile == null || session == null) return;
        session.restoring = true;
        session.retryTick = server.getTickCount() + 100;
        try {
            ServerLevel level = findLevel(server, session.journal.dimension());
            if (level == null) throw new IllegalStateException("分海紀錄所屬維度不可用");
            Set<ChunkPos> chunks = new HashSet<>();
            for (Entry entry : session.journal.entries()) {
                if (!entry.wantedAir()) continue;
                if (!level.isInWorldBounds(entry.pos())) throw new IllegalStateException("復原座標超出目前世界高度／邊界");
                chunks.add(entry.chunk());
            }
            // 驗證過的 3×12 區域最多四個 chunk；沒有擴散搜尋，也不生成遺失的區塊。
            for (ChunkPos chunk : chunks) {
                if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
                    var saved = level.getChunkSource().chunkMap.read(chunk).join();
                    if (saved.isEmpty() || !SerializableChunkData.getChunkStatusFromTag(saved.get())
                            .isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.FULL)) {
                        throw new IllegalStateException("分海原區塊尚無完整存檔，不生成新區塊進行復原");
                    }
                    level.getChunk(chunk.x(), chunk.z());
                }
            }
            Map<Entry, BlockState> finalStates = new LinkedHashMap<>();
            for (Entry entry : session.journal.entries()) {
                if (!entry.wantedAir()) continue;
                BlockState state = level.getBlockState(entry.pos());
                if (state.is(Blocks.AIR) || state.is(Blocks.WATER)) {
                    BlockState original = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, entry.waterLevel());
                    if (state != original && !level.setBlock(entry.pos(), original, UPDATE_FLAGS)) {
                        throw new IllegalStateException("原始水方塊復原失敗：" + entry.pos());
                    }
                    state = level.getBlockState(entry.pos());
                    if (state != original) throw new IllegalStateException("其他模組拒絕水方塊復原");
                }
                // 非 WATER/AIR 方塊原樣保存；不刪除玩家建造、植物或其他模組方塊。
                finalStates.put(entry, state);
            }
            if (!finalStates.isEmpty()) flushAndVerify(level, finalStates);
            writeJournal(journalFile, EMPTY);
            session = null;
            available = true;
        } catch (IOException | RuntimeException error) {
            available = false;
            LOGGER.error("分海尚未完成復原／保存；保留 journal，拒絕新分海，稍後重試：{}", journalFile, error);
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
        if (integer(root, "schemaVersion") != SCHEMA_VERSION) throw invalid("未知 schemaVersion");
        if (!root.has("dimension") || !root.get("dimension").isJsonPrimitive()
                || !root.getAsJsonPrimitive("dimension").isString()) throw invalid("dimension 必須是字串");
        if (!root.has("entries") || !root.get("entries").isJsonArray()) throw invalid("entries 必須是陣列");
        List<Entry> entries = new ArrayList<>();
        for (JsonElement item : root.getAsJsonArray("entries")) {
            if (!item.isJsonObject()) throw invalid("每筆方塊紀錄必須是物件");
            JsonObject entry = item.getAsJsonObject();
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
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Entry entry : journal.entries()) {
            if (entry == null || entry.waterLevel() < 0 || entry.waterLevel() > 15
                    || entry.x() < -30000000 || entry.x() >= 30000000
                    || entry.z() < -30000000 || entry.z() >= 30000000
                    || entry.y() < -20000000 || entry.y() >= 20000000) throw invalid("座標或水 LEVEL 超出範圍");
            if (!positions.add(entry.x() + "," + entry.y() + "," + entry.z())) throw invalid("同一座標重複");
            chunks.add(((long)(entry.x() >> 4) << 32) | ((entry.z() >> 4) & 0xffffffffL));
            minX = Math.min(minX, entry.x()); maxX = Math.max(maxX, entry.x());
            minY = Math.min(minY, entry.y()); maxY = Math.max(maxY, entry.y());
            minZ = Math.min(minZ, entry.z()); maxZ = Math.max(maxZ, entry.z());
        }
        long xSpan = (long)maxX - minX + 1, ySpan = (long)maxY - minY + 1, zSpan = (long)maxZ - minZ + 1;
        if (ySpan > HEIGHT || !((xSpan <= WIDTH && zSpan <= LENGTH) || (xSpan <= LENGTH && zSpan <= WIDTH))
                || chunks.size() > MAX_CHUNKS) throw invalid("復原紀錄超出單一走廊的安全尺寸／區塊數");
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

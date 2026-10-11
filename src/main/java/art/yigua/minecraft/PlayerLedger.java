package art.yigua.minecraft;

import art.yigua.core.CastResult;
import com.google.gson.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** 資料隨世界保存，UUID 區分玩家；圖鑑不採共享的書本物品狀態。 */
public final class PlayerLedger {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_COOLDOWN_TICKS = 12000;
    private static final Set<Double> ENVIRONMENTS = Set.of(0.90, 0.92, 0.94, 0.96, 0.98,
        1.0, 1.02, 1.04, 1.06, 1.08, 1.10);
    public static final class State {
        public Set<Integer> discovered = new TreeSet<>();
        public Map<Integer, Integer> counts = new TreeMap<>();
        public CastResult latest;
        /** 尚未起卦時為空；有結果時保存當時材質，換手持工具不會改寫。 */
        public CastingMaterial latestMaterial;
        /** 保存正式起卦當時的環境；舊資料與尚未起卦均採一倍。 */
        public double latestEnvironment = 1;
        public int remainingTicks;
        public boolean welcomed;
        /** 每位玩家分別同意送出與接受；舊存檔也預設個人模式。 */
        public boolean shareSend;
        public boolean shareReceive;
        public boolean taijiClaimed;
        public boolean taijiSelected;
        /** 太極直接選效果另記，不偽造成正式卦象或新增圖鑑。 */
        public String controlledEffect;
        public int schemaVersion = SCHEMA_VERSION;
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<UUID, State> states = new HashMap<>();
    private boolean dirty;

    public PlayerLedger(MinecraftServer server) {
        this(Objects.requireNonNull(server, "伺服器不得為空")
            .getWorldPath(LevelResource.ROOT).resolve("data/yigua/players.json"));
    }

    /** 接收玩家資料檔案的位置；供不啟動遊戲的保存驗收使用。 */
    PlayerLedger(Path file) {
        this.file = Objects.requireNonNull(file, "玩家資料路徑不得為空");
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement document = JsonParser.parseReader(reader);
            if (!document.isJsonObject()) throw invalid("最外層必須是 UUID 對應資料的物件");
            JsonObject root = document.getAsJsonObject();
            for (var entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) throw invalid("玩家資料不得為空，且必須是物件");
                JsonObject saved = entry.getValue().getAsJsonObject();
                // Gson 會略過 primitive 的 null，並可能套用 State 預設值；先查原始格式。
                if (requiredInteger(saved, "schemaVersion") != SCHEMA_VERSION) {
                    throw invalid("不支援此 schemaVersion；需要版本 " + SCHEMA_VERSION);
                }
                requiredInteger(saved, "remainingTicks");
                for (String option : List.of("shareSend", "shareReceive", "taijiClaimed", "taijiSelected")) {
                    if (saved.has(option) && (!saved.get(option).isJsonPrimitive()
                            || !saved.getAsJsonPrimitive(option).isBoolean())) {
                        throw invalid(option + " 必須是布林值");
                    }
                }
                if (saved.has("controlledEffect") && (!saved.get("controlledEffect").isJsonPrimitive()
                        || !saved.getAsJsonPrimitive("controlledEffect").isString())) throw invalid("controlledEffect 必須為已知效果名稱");
                if (!saved.has("welcomed") || !saved.get("welcomed").isJsonPrimitive()
                        || !saved.getAsJsonPrimitive("welcomed").isBoolean()) {
                    throw invalid("welcomed 必須是布林值，不能缺漏或為空");
                }
                if (!saved.has("discovered") || !saved.get("discovered").isJsonArray()) {
                    throw invalid("discovered 必須是陣列，不能缺漏或為空");
                }
                if (!saved.has("counts") || !saved.get("counts").isJsonObject()) {
                    throw invalid("counts 必須是物件，不能缺漏或為空");
                }
                for (JsonElement number : saved.getAsJsonArray("discovered")) {
                    int value = requiredInteger(number, "discovered 卦序");
                    if (value < 1 || value > 64) throw invalid("discovered 卦序必須介於一與六十四");
                }
                for (var count : saved.getAsJsonObject("counts").entrySet()) {
                    if (requiredInteger(count.getValue(), "counts 次數") < 1) throw invalid("counts 次數必須為正整數");
                }
                CastingMaterial material = savedMaterial(saved);
                double environment = savedEnvironment(saved);
                State s = GSON.fromJson(saved, State.class);
                if (s.latest != null && !saved.has("latestMaterial")) {
                    // 舊版資料只有一倍效果；依筮法記為舊版材質，不自動升級。
                    material = CastingMaterial.legacyFor(s.latest.method());
                    dirty = true;
                }
                s.latestMaterial = material;
                s.latestEnvironment = environment;
                if (s.latest != null && !saved.has("latestEnvironment")) dirty = true;
                validate(s);
                UUID uuid = UUID.fromString(entry.getKey());
                if (states.putIfAbsent(uuid, s) != null) throw invalid("同一 UUID 不得重複");
            }
        } catch (IOException | RuntimeException e) {
            // 不以空資料覆蓋毀損存檔；停止載入，讓擁有者保留原始檔案。
            throw new IllegalStateException("一卦玩家資料無法讀取，原始檔案保留：" + file, e);
        }
    }

    private static int requiredInteger(JsonObject saved, String field) {
        return requiredInteger(saved.get(field), field);
    }

    private static CastingMaterial savedMaterial(JsonObject saved) {
        if (!saved.has("latestMaterial")) return null;
        JsonElement value = saved.get("latestMaterial");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid("latestMaterial 必須是已知材質名稱，不能為空或非字串");
        }
        try { return CastingMaterial.valueOf(value.getAsString()); }
        catch (IllegalArgumentException e) { throw invalid("未知起卦材質：" + value.getAsString()); }
    }

    private static double savedEnvironment(JsonObject saved) {
        if (!saved.has("latestEnvironment")) return 1;
        JsonElement value = saved.get("latestEnvironment");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid("latestEnvironment 必須是合法環境數值，不能為空或非數字");
        }
        try {
            // 先比對原始十進位數字，不能讓極小超額被 double 捨入成合法值。
            BigDecimal exact = value.getAsBigDecimal();
            if (ENVIRONMENTS.stream().noneMatch(allowed -> BigDecimal.valueOf(allowed).compareTo(exact) == 0)) {
                throw invalid("latestEnvironment 必須為 0.90 至 1.10 間隔 0.02 的十一個離散值之一");
            }
            return exact.doubleValue();
        } catch (NumberFormatException e) {
            throw invalid("latestEnvironment 必須是有限的合法環境數值");
        }
    }

    private static boolean validEnvironment(double environment) {
        return Double.isFinite(environment) && ENVIRONMENTS.contains(environment);
    }

    private static int requiredInteger(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(field + " 必須是整數，不能缺漏或為空");
        }
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw invalid(field + " 必須是有效範圍內的整數"); }
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("玩家圖鑑格式無效：" + detail);
    }

    private static void validate(State s) {
        if (s == null) throw invalid("玩家資料不得為空");
        if (s.schemaVersion != SCHEMA_VERSION) throw invalid("不支援此 schemaVersion");
        if (s.discovered == null || s.counts == null) throw invalid("圖鑑與次數紀錄不得為空");
        if (!validEnvironment(s.latestEnvironment)) throw invalid("最近環境倍率必須是有限的合法環境數值");
        if (s.controlledEffect != null && !Library.generalProfiles().contains(s.controlledEffect)
                && Arrays.stream(SpecialFortune.Event.values()).noneMatch(event -> event != SpecialFortune.Event.NONE
                    && event.name().toLowerCase(Locale.ROOT).equals(s.controlledEffect))) throw invalid("未知太極指定效果");
        if (s.remainingTicks < 0 || s.remainingTicks > MAX_COOLDOWN_TICKS) {
            throw invalid("剩餘冷卻必須介於零與 " + MAX_COOLDOWN_TICKS);
        }
        if (s.discovered.stream().anyMatch(n -> n == null || n < 1 || n > 64)) {
            throw invalid("圖鑑卦序必須介於一與六十四，且不得為空");
        }
        if (s.counts.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null
                || e.getKey() < 1 || e.getKey() > 64 || e.getValue() < 1)) {
            throw invalid("次數紀錄必須有有效卦序及正整數次數");
        }
        if (!s.discovered.equals(s.counts.keySet())) throw invalid("已發現卦序與次數紀錄不一致");
        if (s.latest == null) {
            if (s.latestMaterial != null) throw invalid("尚未起卦不得有最近材質");
            if (s.latestEnvironment != 1) throw invalid("尚未起卦的環境倍率必須為一");
            if ((s.remainingTicks != 0 && s.controlledEffect == null) || !s.discovered.isEmpty()) {
                throw invalid("有冷卻或正式圖鑑紀錄時必須有最近起卦");
            }
        } else {
            if (!s.discovered.contains(s.latest.primaryNumber())) {
                throw invalid("最近起卦的本卦必須存在圖鑑與次數紀錄");
            }
            if (s.latestMaterial == null || !s.latestMaterial.compatibleWith(s.latest.method())) {
                throw invalid("最近材質不得為空，且必須符合三錢或蓍草方法");
            }
        }
    }

    public State state(UUID uuid) {
        Objects.requireNonNull(uuid, "玩家 UUID 不得為空");
        return states.computeIfAbsent(uuid, ignored -> { dirty = true; return new State(); });
    }
    public void markDirty() { dirty = true; }
    public void tick(UUID uuid) {
        State s = state(uuid);
        if (s.remainingTicks > 0) { s.remainingTicks--; dirty = true; }
    }

    public void record(UUID uuid, CastResult result) {
        Objects.requireNonNull(result, "正式起卦結果不得為空");
        record(uuid, result, CastingMaterial.defaultFor(result.method()));
    }

    public void record(UUID uuid, CastResult result, CastingMaterial material) {
        record(uuid, result, material, 1);
    }

    public void record(UUID uuid, CastResult result, CastingMaterial material, double environment) {
        Objects.requireNonNull(uuid, "玩家 UUID 不得為空");
        Objects.requireNonNull(result, "正式起卦結果不得為空");
        Objects.requireNonNull(material, "正式起卦材質不得為空");
        if (!material.compatibleWith(result.method())) {
            throw new IllegalArgumentException("起卦材質不符合三錢或蓍草方法");
        }
        if (!validEnvironment(environment)) {
            throw new IllegalArgumentException("起卦環境必須為 0.90 至 1.10 間隔 0.02 的十一個離散值之一");
        }
        State s = state(uuid);
        validate(s);
        int count = Math.addExact(s.counts.getOrDefault(result.primaryNumber(), 0), 1);
        s.latest = result;
        s.controlledEffect = null;
        s.taijiSelected = false;
        s.latestMaterial = material;
        s.latestEnvironment = environment;
        s.discovered.add(result.primaryNumber());
        s.counts.put(result.primaryNumber(), count);
        s.remainingTicks = MAX_COOLDOWN_TICKS;
        dirty = true;
        save();
    }

    public void save() {
        if (!dirty) return;
        Path temp = file.resolveSibling("players.json.pending");
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            states.forEach((uuid, state) -> {
                validate(state);
                root.add(uuid.toString(), GSON.toJsonTree(state));
            });
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            dirty = false;
        } catch (IOException e) {
            throw new IllegalStateException("一卦資料寫入失敗：" + file, e);
        }
    }
}

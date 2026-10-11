package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.CastingEngine;
import art.yigua.core.Method;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 不啟動 Minecraft、不依賴 JUnit；所有保存驗收都使用可刪除的暫存目錄。 */
public final class LedgerSelfTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final CastResult QIAN_TO_KUN = CastingEngine.fromLines(Method.COINS, List.of(9, 9, 9, 9, 9, 9));
    private static final CastResult KUN = CastingEngine.fromLines(Method.YARROW, List.of(8, 8, 8, 8, 8, 8));
    private static final double[] ENVIRONMENTS = {0.90, 0.92, 0.94, 0.96, 0.98, 1, 1.02, 1.04, 1.06, 1.08, 1.10};
    private static final double[] INVALID_ENVIRONMENTS = {Double.NaN, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, 0, -1, 0.88, 1.12, 0.91, 0.95, 0.97, 0.99, 1.01, 1.03,
        Math.nextDown(0.90), Math.nextUp(1), Math.nextUp(1.10)};
    private static int checks;

    private LedgerSelfTest() {}

    public static void main(String[] arguments) throws Exception {
        Path temporary = Files.createTempDirectory("yigua-ledger-selftest-");
        try {
            uncastPlayer(temporary.resolve("uncast/players.json"));
            isolationAndPersistence(temporary.resolve("roundtrip/players.json"));
            materialPersistence(temporary.resolve("materials/players.json"));
            environmentPersistence(temporary.resolve("environments/players.json"));
            legacyMaterialMigration(temporary.resolve("migration/players.json"));
            legacyEnvironmentMigration(temporary.resolve("environment-migration"));
            corruptDocuments(temporary.resolve("corrupt"));
            invalidEnvironmentInputs(temporary.resolve("environment-inputs"));
            invalidMemoryCannotOverwrite(temporary.resolve("memory/players.json"));
            invalidEnvironmentMemoryCannotOverwrite(temporary.resolve("environment-memory/players.json"));
            nullInputs(temporary.resolve("nulls/players.json"));
            System.out.println("玩家保存驗收通過：" + checks + " 項檢查，涵蓋 UUID 隔離、材質與環境保存、舊檔遷移、在線冷卻、重讀與毀損保護。");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void uncastPlayer(Path file) throws Exception {
        PlayerLedger ledger = new PlayerLedger(file);
        ledger.state(ALICE).welcomed = true;
        ledger.markDirty();
        ledger.save();
        PlayerLedger.State restored = new PlayerLedger(file).state(ALICE);
        check(restored.welcomed, "僅領取總覽書、尚未起卦的玩家可重讀");
        equal(restored.latest, null, "尚未起卦允許缺少最近結果");
        equal(restored.latestMaterial, null, "尚未起卦不得虛構最近材質");
        equal(restored.latestEnvironment, 1.0, "尚未起卦環境採一倍");
        equal(restored.discovered, Set.of(), "尚未起卦的圖鑑維持空白");
        equal(restored.counts, Map.of(), "尚未起卦的次數維持空白");
        equal(restored.remainingTicks, 0, "尚未起卦沒有冷卻");
    }

    private static void isolationAndPersistence(Path file) throws Exception {
        PlayerLedger ledger = new PlayerLedger(file);
        ledger.record(ALICE, QIAN_TO_KUN);
        equal(ledger.state(ALICE).discovered, Set.of(1), "正式起卦只解鎖本卦，之卦坤不能自動解鎖");
        equal(ledger.state(ALICE).counts, Map.of(1, 1), "正式起卦只增加本卦次數");
        equal(ledger.state(BOB).discovered, Set.of(), "另一 UUID 不取得同玩家圖鑑");
        equal(ledger.state(BOB).latest, null, "另一 UUID 不取得同玩家最近結果");

        ledger.record(ALICE, QIAN_TO_KUN);
        ledger.record(BOB, KUN);
        equal(ledger.state(ALICE).counts, Map.of(1, 2), "重複起卦累加自己的次數");
        equal(ledger.state(BOB).counts, Map.of(2, 1), "另一玩家的紀錄獨立");
        equal(ledger.state(ALICE).latest, QIAN_TO_KUN, "另一玩家起卦不改寫自己的最近結果");
        equal(ledger.state(ALICE).remainingTicks, 12000, "正式起卦初始化十分鐘遊戲刻");

        for (int tick = 0; tick < 73; tick++) ledger.tick(ALICE);
        equal(ledger.state(ALICE).remainingTicks, 11927, "只對在線 UUID 呼叫 tick 才倒數");
        equal(ledger.state(BOB).remainingTicks, 12000, "未呼叫 tick 的離線 UUID 不倒數");
        equal(ledger.state(ALICE).discovered, Set.of(1), "倒數不新增圖鑑紀錄");
        ledger.state(ALICE).welcomed = true;
        ledger.markDirty();
        ledger.save();
        check(!Files.exists(file.resolveSibling("players.json.pending")), "成功保存不留下待置換檔");

        PlayerLedger reloaded = new PlayerLedger(file);
        equal(reloaded.state(ALICE).counts, Map.of(1, 2), "保存重讀維持正式起卦次數");
        equal(reloaded.state(ALICE).latest, QIAN_TO_KUN, "保存重讀完整還原六爻、動爻、之卦與方法");
        equal(reloaded.state(ALICE).latestMaterial, CastingMaterial.IRON, "二參數三錢介面採預設鐵錢");
        equal(reloaded.state(ALICE).latestMaterial.multiplier(), 0.75, "預設鐵錢採新版四分之三倍");
        equal(reloaded.state(ALICE).latestEnvironment, 1.0, "二參數三錢介面採一倍環境");
        equal(reloaded.state(ALICE).discovered, Set.of(1), "保存重讀不把之卦當作已發現");
        equal(reloaded.state(ALICE).remainingTicks, 11927, "重讀不重新開始冷卻");
        check(reloaded.state(ALICE).welcomed, "重讀保持首次贈書狀態");
        equal(reloaded.state(BOB).latest, KUN, "保存重讀維持第二玩家的結果");
        equal(reloaded.state(BOB).latestMaterial, CastingMaterial.BAMBOO, "二參數蓍草介面採預設竹籌");
        equal(reloaded.state(BOB).latestMaterial.multiplier(), 1.5, "預設竹籌採新版一點五倍");
        equal(reloaded.state(BOB).latestEnvironment, 1.0, "二參數蓍草介面採一倍環境");
        equal(reloaded.state(BOB).remainingTicks, 12000, "重讀不扣除離線時間");
        equal(reloaded.state(ALICE).schemaVersion, 1, "保存格式保持第一版");

        reloaded.tick(ALICE);
        reloaded.save();
        PlayerLedger resumed = new PlayerLedger(file);
        equal(resumed.state(ALICE).remainingTicks, 11926, "重新登入後繼續倒數且可再次保存");
        for (int tick = 0; tick < 12020; tick++) resumed.tick(ALICE);
        equal(resumed.state(ALICE).remainingTicks, 0, "倒數歸零後不產生負值");
        equal(resumed.state(BOB).remainingTicks, 12000, "另一 UUID 仍保持自己的剩餘時間");
        resumed.save();
        equal(new PlayerLedger(file).state(ALICE).remainingTicks, 0, "已完成冷卻可正常重讀");
    }

    private record Damage(String label, Consumer<JsonObject> change) {}

    private static void materialPersistence(Path file) throws Exception {
        PlayerLedger ledger = new PlayerLedger(file);
        for (CastingMaterial material : CastingMaterial.values()) {
            UUID uuid = UUID.nameUUIDFromBytes(material.name().getBytes(StandardCharsets.UTF_8));
            CastResult result = castFor(material);
            ledger.record(uuid, result, material);
            equal(ledger.state(uuid).latestMaterial, material, "正式起卦保存當時材質：" + material);
            equal(ledger.state(uuid).latestEnvironment, 1.0, "三參數介面採一倍環境：" + material);
            equal(ledger.state(uuid).remainingTicks, 12000, "材質不得改變正式起卦冷卻");
        }
        PlayerLedger restored = new PlayerLedger(file);
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for (CastingMaterial material : CastingMaterial.values()) {
            UUID uuid = UUID.nameUUIDFromBytes(material.name().getBytes(StandardCharsets.UTF_8));
            equal(restored.state(uuid).latestMaterial, material, "各材質保存重讀不得降級或換成手持工具");
            equal(restored.state(uuid).latest, castFor(material),
                "材質不改寫原有起卦六爻");
            equal(saved.getAsJsonObject(uuid.toString()).get("latestMaterial").getAsString(), material.name(),
                "材質需明確寫入世界存檔");
        }
        restored.record(ALICE, QIAN_TO_KUN, CastingMaterial.WOOD);
        restored.record(ALICE, QIAN_TO_KUN, CastingMaterial.GOLD);
        PlayerLedger reread = new PlayerLedger(file);
        equal(reread.state(ALICE).latestMaterial, CastingMaterial.GOLD, "再次正式起卦才會更新最近材質");
        equal(reread.state(ALICE).counts, Map.of(1, 2), "換材質的正式起卦仍正確累積圖鑑次數");
    }

    private static void environmentPersistence(Path file) throws Exception {
        PlayerLedger ledger = new PlayerLedger(file);
        for (double environment : ENVIRONMENTS) {
            for (CastingMaterial material : CastingMaterial.values()) {
                UUID uuid = environmentUuid(material, environment);
                CastResult result = castFor(material);
                ledger.record(uuid, result, material, environment);
                equal(ledger.state(uuid).latestEnvironment, environment, "正式起卦保存當時環境");
                equal(ledger.state(uuid).latestMaterial, material, "環境不更換材質");
                equal(ledger.state(uuid).latest, result, "環境不改寫卦象");
                equal(ledger.state(uuid).remainingTicks, 12000, "環境不改變冷卻");
            }
        }
        PlayerLedger restored = new PlayerLedger(file);
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for (double environment : ENVIRONMENTS) {
            for (CastingMaterial material : CastingMaterial.values()) {
                UUID uuid = environmentUuid(material, environment);
                equal(restored.state(uuid).latestEnvironment, environment, "十一種環境與十種材質皆可保存重讀");
                equal(restored.state(uuid).latestMaterial, material, "重讀保持環境配對的材質");
                equal(restored.state(uuid).latest, castFor(material),
                    "重讀保持環境配對的六爻");
                equal(restored.state(uuid).counts, Map.of(methodFor(material) == Method.YARROW ? 2 : 1, 1),
                    "環境組合各自只有一次正式紀錄");
                check(saved.getAsJsonObject(uuid.toString()).getAsJsonPrimitive("latestEnvironment").isNumber(),
                    "環境需以數字明確寫入存檔");
                check(saved.getAsJsonObject(uuid.toString()).get("latestEnvironment").getAsBigDecimal()
                    .compareTo(BigDecimal.valueOf(environment)) == 0, "存檔數字保持正確環境值");
            }
        }
        restored.record(ALICE, QIAN_TO_KUN, CastingMaterial.COPPER, 0.96);
        restored.record(ALICE, QIAN_TO_KUN, CastingMaterial.GOLD, 1.04);
        equal(new PlayerLedger(file).state(ALICE).latestEnvironment, 1.04, "再次正式起卦才更新最近環境");
        equal(restored.state(ALICE).counts, Map.of(1, 2), "更換環境仍只累積正式紀錄");
        restored.record(ALICE, QIAN_TO_KUN, CastingMaterial.WOOD);
        equal(new PlayerLedger(file).state(ALICE).latestEnvironment, 1.0, "三參數介面清楚回到一倍環境");
        String[] equivalentDecimals = {"0.900000000000000000", "0.9200", "0.940000", "0.960000000000000000", "0.9800",
            "1.000000000000000000", "1.0200", "1.040000", "1.0600000000", "1.0800", "1.100000000000000000"};
        for (int i = 0; i < ENVIRONMENTS.length; i++) {
            JsonObject exact = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            exact.getAsJsonObject(ALICE.toString()).addProperty("latestEnvironment", new BigDecimal(equivalentDecimals[i]));
            Path numericFile = file.resolveSibling("equivalent-" + i + ".json");
            Files.writeString(numericFile, exact.toString(), StandardCharsets.UTF_8);
            byte[] before = Files.readAllBytes(numericFile);
            equal(new PlayerLedger(numericFile).state(ALICE).latestEnvironment, ENVIRONMENTS[i],
                "合法環境的小數尾零仍可精確識別");
            check(Arrays.equals(before, Files.readAllBytes(numericFile)), "合法小數表示載入不直接重寫原檔");
        }
    }

    private static UUID environmentUuid(CastingMaterial material, double environment) {
        return UUID.nameUUIDFromBytes((material.id() + ":" + environment).getBytes(StandardCharsets.UTF_8));
    }

    private static Method methodFor(CastingMaterial material) {
        return switch (material) {
            case BAMBOO, WOOD_STALKS, GRASS_STALKS, LEGACY_BAMBOO -> Method.YARROW;
            default -> Method.COINS;
        };
    }

    private static CastResult castFor(CastingMaterial material) {
        return methodFor(material) == Method.YARROW ? KUN : QIAN_TO_KUN;
    }

    private static void legacyMaterialMigration(Path file) throws Exception {
        PlayerLedger ledger = new PlayerLedger(file);
        ledger.record(ALICE, QIAN_TO_KUN, CastingMaterial.COPPER);
        ledger.record(BOB, KUN, CastingMaterial.BAMBOO);
        JsonObject old = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        old.getAsJsonObject(ALICE.toString()).remove("latestMaterial");
        old.getAsJsonObject(BOB.toString()).remove("latestMaterial");
        Files.writeString(file, old.toString(), StandardCharsets.UTF_8);
        byte[] oldBytes = Files.readAllBytes(file);
        PlayerLedger migrated = new PlayerLedger(file);
        equal(migrated.state(ALICE).latestMaterial, CastingMaterial.LEGACY, "舊三錢紀錄缺材質時明確標為舊版三錢");
        equal(migrated.state(ALICE).latestMaterial.multiplier(), 1.0, "舊三錢保留一倍，不能降級成新版鐵錢");
        equal(migrated.state(BOB).latestMaterial, CastingMaterial.LEGACY_BAMBOO, "舊蓍草紀錄明確採舊版竹籌");
        equal(migrated.state(BOB).latestMaterial.multiplier(), 1.0, "舊蓍草保留一倍，不能升級成新版竹籌");
        equal(migrated.state(ALICE).latest, QIAN_TO_KUN, "遷移不得重抽卦或改寫動爻");
        equal(migrated.state(ALICE).remainingTicks, 12000, "遷移不更動剩餘冷卻");
        check(Arrays.equals(oldBytes, Files.readAllBytes(file)), "載入遷移只更新記憶體，保存前保留原始檔案");
        migrated.save();
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        equal(saved.getAsJsonObject(ALICE.toString()).get("latestMaterial").getAsString(), "LEGACY", "遷移需標 dirty 讓後續保存寫明舊版材質");
        equal(saved.getAsJsonObject(BOB.toString()).get("latestMaterial").getAsString(), "LEGACY_BAMBOO", "遷移保存需明寫舊版竹籌");
        equal(new PlayerLedger(file).state(ALICE).latestMaterial, CastingMaterial.LEGACY, "遷移後再載入仍維持舊版一倍");
        equal(new PlayerLedger(file).state(BOB).latestMaterial, CastingMaterial.LEGACY_BAMBOO, "蓍草遷移後重讀仍採舊版竹籌");
    }

    private static void legacyEnvironmentMigration(Path directory) throws Exception {
        Path file = directory.resolve("cast/players.json");
        PlayerLedger ledger = new PlayerLedger(file);
        ledger.record(ALICE, QIAN_TO_KUN, CastingMaterial.COPPER, 1.04);
        ledger.record(BOB, KUN, CastingMaterial.BAMBOO, 0.96);
        JsonObject old = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        old.getAsJsonObject(ALICE.toString()).remove("latestEnvironment");
        old.getAsJsonObject(BOB.toString()).remove("latestEnvironment");
        Files.writeString(file, old.toString(), StandardCharsets.UTF_8);
        byte[] oldBytes = Files.readAllBytes(file);
        PlayerLedger migrated = new PlayerLedger(file);
        equal(migrated.state(ALICE).latestEnvironment, 1.0, "舊三錢缺環境時採一倍");
        equal(migrated.state(BOB).latestEnvironment, 1.0, "舊蓍草缺環境時採一倍");
        equal(migrated.state(ALICE).latestMaterial, CastingMaterial.COPPER, "缺環境不降級既有銅錢材質");
        equal(migrated.state(BOB).latestMaterial, CastingMaterial.BAMBOO, "缺環境保持蓍草材質");
        equal(migrated.state(BOB).latestMaterial.multiplier(), 1.5, "既有新版竹籌欄位不降級成舊版竹籌");
        equal(migrated.state(ALICE).latest, QIAN_TO_KUN, "環境遷移保留原卦");
        equal(migrated.state(ALICE).remainingTicks, 12000, "環境遷移保留冷卻");
        check(Arrays.equals(oldBytes, Files.readAllBytes(file)), "載入環境遷移不直接改檔");
        migrated.save();
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        equal(saved.getAsJsonObject(ALICE.toString()).get("latestEnvironment").getAsDouble(), 1.0,
            "舊正式紀錄缺環境需標 dirty 並明寫一倍");
        equal(new PlayerLedger(file).state(BOB).latestEnvironment, 1.0, "環境遷移後可再次重讀");

        Path bothMissing = directory.resolve("both-missing/players.json");
        old.getAsJsonObject(ALICE.toString()).remove("latestMaterial");
        old.getAsJsonObject(BOB.toString()).remove("latestMaterial");
        Files.createDirectories(bothMissing.getParent());
        Files.writeString(bothMissing, old.toString(), StandardCharsets.UTF_8);
        PlayerLedger both = new PlayerLedger(bothMissing);
        equal(both.state(ALICE).latestMaterial, CastingMaterial.LEGACY, "同時缺材質與環境仍維持舊三錢遷移");
        equal(both.state(BOB).latestMaterial, CastingMaterial.LEGACY_BAMBOO, "同時缺欄仍維持舊蓍草一倍遷移");
        equal(both.state(ALICE).latestEnvironment, 1.0, "同時缺欄的舊三錢環境採一倍");
        both.save();
        equal(new PlayerLedger(bothMissing).state(ALICE).latestMaterial, CastingMaterial.LEGACY,
            "同時缺欄的遷移保存後不變成新版鐵錢或銅錢");

        Path uncast = directory.resolve("uncast/players.json");
        PlayerLedger empty = new PlayerLedger(uncast);
        empty.state(ALICE).welcomed = true;
        empty.save();
        JsonObject oldEmpty = JsonParser.parseString(Files.readString(uncast)).getAsJsonObject();
        oldEmpty.getAsJsonObject(ALICE.toString()).remove("latestEnvironment");
        Files.writeString(uncast, oldEmpty.toString(), StandardCharsets.UTF_8);
        PlayerLedger emptyRestored = new PlayerLedger(uncast);
        equal(emptyRestored.state(ALICE).latestEnvironment, 1.0, "舊空紀錄允許缺環境並預設一倍");
        equal(emptyRestored.state(ALICE).latest, null, "舊空紀錄不虛構起卦");
        emptyRestored.markDirty();
        emptyRestored.save();
        equal(new PlayerLedger(uncast).state(ALICE).latestEnvironment, 1.0, "舊空紀錄可正常保存一倍環境");
    }

    private static void corruptDocuments(Path directory) throws Exception {
        Path baseline = directory.resolve("baseline/players.json");
        PlayerLedger valid = new PlayerLedger(baseline);
        valid.record(ALICE, QIAN_TO_KUN);
        String snapshot = Files.readString(baseline, StandardCharsets.UTF_8);
        List<Damage> damages = List.of(
            new Damage("未知格式版本", state -> state.addProperty("schemaVersion", 2)),
            new Damage("缺少格式版本", state -> state.remove("schemaVersion")),
            new Damage("空格式版本", state -> state.add("schemaVersion", JsonNull.INSTANCE)),
            new Damage("字串格式版本", state -> state.addProperty("schemaVersion", "1")),
            new Damage("未知材質", state -> state.addProperty("latestMaterial", "DIAMOND")),
            new Damage("空材質", state -> state.add("latestMaterial", JsonNull.INSTANCE)),
            new Damage("小寫材質", state -> state.addProperty("latestMaterial", "gold")),
            new Damage("數字材質", state -> state.addProperty("latestMaterial", 2)),
            new Damage("物件材質", state -> state.add("latestMaterial", new JsonObject())),
            new Damage("三錢使用竹籌材質", state -> state.addProperty("latestMaterial", "BAMBOO")),
            new Damage("空環境", state -> state.add("latestEnvironment", JsonNull.INSTANCE)),
            new Damage("字串環境", state -> state.addProperty("latestEnvironment", "1")),
            new Damage("布林環境", state -> state.addProperty("latestEnvironment", true)),
            new Damage("物件環境", state -> state.add("latestEnvironment", new JsonObject())),
            new Damage("陣列環境", state -> state.add("latestEnvironment", JsonParser.parseString("[1]"))),
            new Damage("非數環境", state -> state.addProperty("latestEnvironment", Double.NaN)),
            new Damage("正無窮環境", state -> state.addProperty("latestEnvironment", Double.POSITIVE_INFINITY)),
            new Damage("負無窮環境", state -> state.addProperty("latestEnvironment", Double.NEGATIVE_INFINITY)),
            new Damage("低於環境範圍", state -> state.addProperty("latestEnvironment", 0.88)),
            new Damage("超過環境範圍", state -> state.addProperty("latestEnvironment", 1.12)),
            new Damage("環境非離散步階", state -> state.addProperty("latestEnvironment", 0.97)),
            new Damage("環境極小步階超額", state -> state.addProperty("latestEnvironment", new BigDecimal("1.020000000000000000000001"))),
            new Damage("環境極小上限超額", state -> state.addProperty("latestEnvironment", new BigDecimal("1.100000000000000000000001"))),
            new Damage("環境極小下限超額", state -> state.addProperty("latestEnvironment", new BigDecimal("0.899999999999999999999999"))),
            new Damage("數字環境超過浮點範圍", state -> state.addProperty("latestEnvironment", new BigDecimal("1e309"))),
            new Damage("負冷卻", state -> state.addProperty("remainingTicks", -1)),
            new Damage("超過上限冷卻", state -> state.addProperty("remainingTicks", 12001)),
            new Damage("空冷卻", state -> state.add("remainingTicks", JsonNull.INSTANCE)),
            new Damage("缺少冷卻", state -> state.remove("remainingTicks")),
            new Damage("小數冷卻", state -> state.addProperty("remainingTicks", 0.5)),
            new Damage("空贈書狀態", state -> state.add("welcomed", JsonNull.INSTANCE)),
            new Damage("空圖鑑", state -> state.add("discovered", JsonNull.INSTANCE)),
            new Damage("空圖鑑卦序", state -> state.getAsJsonArray("discovered").add(JsonNull.INSTANCE)),
            new Damage("空次數紀錄", state -> state.add("counts", JsonNull.INSTANCE)),
            new Damage("零次數", state -> state.getAsJsonObject("counts").addProperty("1", 0)),
            new Damage("空次數", state -> state.getAsJsonObject("counts").add("1", JsonNull.INSTANCE)),
            new Damage("缺少對應次數", state -> state.getAsJsonObject("counts").remove("1")),
            new Damage("多出未解鎖次數", state -> state.getAsJsonObject("counts").addProperty("2", 1)),
            new Damage("非法卦序", state -> state.getAsJsonObject("counts").addProperty("65", 1)),
            new Damage("正式紀錄無最近結果", state -> state.remove("latest")),
            new Damage("最近卦與六爻不符", state -> state.getAsJsonObject("latest").addProperty("primaryNumber", 2)),
            new Damage("最近本卦不在圖鑑", state -> {
                state.add("latest", JsonParser.parseString("{\"method\":\"YARROW\",\"lineValues\":[8,8,8,8,8,8],\"primaryNumber\":2,\"changedNumber\":0,\"changingLines\":[],\"special\":\"\"}"));
            }),
            new Damage("空紀錄卻有冷卻", state -> {
                state.add("discovered", JsonParser.parseString("[]"));
                state.add("counts", new JsonObject());
                state.remove("latest");
                state.addProperty("remainingTicks", 1);
            }),
            new Damage("尚未起卦卻有材質", state -> {
                state.add("discovered", JsonParser.parseString("[]"));
                state.add("counts", new JsonObject());
                state.remove("latest");
                state.addProperty("remainingTicks", 0);
            }),
            new Damage("尚未起卦卻有環境加成", state -> {
                state.add("discovered", JsonParser.parseString("[]"));
                state.add("counts", new JsonObject());
                state.remove("latest");
                state.remove("latestMaterial");
                state.addProperty("remainingTicks", 0);
                state.addProperty("latestEnvironment", 1.02);
            })
        );
        int index = 0;
        for (Damage damage : damages) {
            JsonObject document = JsonParser.parseString(snapshot).getAsJsonObject();
            damage.change().accept(document.getAsJsonObject(ALICE.toString()));
            assertRejectedUnchanged(directory.resolve("case-" + index++ + "/players.json"), document.toString(), damage.label());
        }
        for (CastingMaterial material : CastingMaterial.values()) {
            if (methodFor(material) != Method.YARROW || material == CastingMaterial.BAMBOO) continue;
            JsonObject document = JsonParser.parseString(snapshot).getAsJsonObject();
            document.getAsJsonObject(ALICE.toString()).addProperty("latestMaterial", material.name());
            assertRejectedUnchanged(directory.resolve("coins-" + material.id() + "/players.json"), document.toString(),
                "三錢存檔不能使用" + material);
        }
        Path yarrowBaseline = directory.resolve("yarrow-baseline/players.json");
        new PlayerLedger(yarrowBaseline).record(ALICE, KUN, CastingMaterial.BAMBOO);
        String yarrowSnapshot = Files.readString(yarrowBaseline);
        for (CastingMaterial material : CastingMaterial.values()) {
            if (methodFor(material) == Method.YARROW) continue;
            JsonObject document = JsonParser.parseString(yarrowSnapshot).getAsJsonObject();
            document.getAsJsonObject(ALICE.toString()).addProperty("latestMaterial", material.name());
            assertRejectedUnchanged(directory.resolve("yarrow-" + material.id() + "/players.json"), document.toString(), "蓍草存檔不能使用" + material);
        }
        assertRejectedUnchanged(directory.resolve("syntax/players.json"), "{ damaged json", "毀損 JSON 語法");
        assertRejectedUnchanged(directory.resolve("null-document/players.json"), "null", "空文件");
        assertRejectedUnchanged(directory.resolve("array-document/players.json"), "[]", "錯誤文件容器");
        assertRejectedUnchanged(directory.resolve("null-state/players.json"), "{\"" + ALICE + "\":null}", "空玩家資料");
        JsonObject badUuid = JsonParser.parseString(snapshot).getAsJsonObject();
        badUuid.add("invalid-uuid", badUuid.remove(ALICE.toString()));
        assertRejectedUnchanged(directory.resolve("uuid/players.json"), badUuid.toString(), "非法玩家 UUID");
    }

    private static void assertRejectedUnchanged(Path file, String contents, String label) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        Throwable failure = rejects(IllegalStateException.class, () -> new PlayerLedger(file), label + "必須拒絕載入");
        check(failure.getMessage().contains("原始檔案保留"), label + "應清楚說明保留原檔");
        check(Arrays.equals(before, Files.readAllBytes(file)), label + "不能改寫原檔");
        check(!Files.exists(file.resolveSibling("players.json.pending")), label + "不能生成空白替換檔");
    }

    private static void invalidEnvironmentInputs(Path directory) throws Exception {
        Path emptyFile = directory.resolve("empty/players.json");
        PlayerLedger empty = new PlayerLedger(emptyFile);
        for (double environment : INVALID_ENVIRONMENTS) {
            rejects(IllegalArgumentException.class, () -> empty.record(ALICE, QIAN_TO_KUN, CastingMaterial.GOLD, environment),
                "非法環境必須在建立玩家狀態前拒絕：" + environment);
            empty.save();
            check(!Files.exists(emptyFile), "非法環境不創造空玩家存檔");
            check(!Files.exists(emptyFile.resolveSibling("players.json.pending")), "非法環境不生成待替換檔");
        }
        Path existingFile = directory.resolve("existing/players.json");
        PlayerLedger existing = new PlayerLedger(existingFile);
        existing.record(ALICE, QIAN_TO_KUN, CastingMaterial.COPPER, 0.98);
        byte[] original = Files.readAllBytes(existingFile);
        for (double environment : INVALID_ENVIRONMENTS) {
            rejects(IllegalArgumentException.class, () -> existing.record(ALICE, KUN, CastingMaterial.BAMBOO, environment),
                "已有正式紀錄也拒絕非法環境");
            equal(existing.state(ALICE).latest, QIAN_TO_KUN, "非法環境不改寫最近起卦");
            equal(existing.state(ALICE).latestMaterial, CastingMaterial.COPPER, "非法環境不改寫材質");
            equal(existing.state(ALICE).latestEnvironment, 0.98, "非法環境不改寫當時環境");
            equal(existing.state(ALICE).counts, Map.of(1, 1), "非法環境不增加圖鑑次數");
            equal(existing.state(ALICE).remainingTicks, 12000, "非法環境不改寫冷卻");
            existing.save();
            check(Arrays.equals(original, Files.readAllBytes(existingFile)), "非法輸入保留既有檔案");
            check(!Files.exists(existingFile.resolveSibling("players.json.pending")), "非法輸入不生成替換檔");
        }
        rejects(IllegalArgumentException.class, () -> existing.record(BOB, KUN, CastingMaterial.BAMBOO, 1.01),
            "既有世界也不得因非法環境新增另一玩家");
        existing.save();
        check(Arrays.equals(original, Files.readAllBytes(existingFile)), "另一玩家非法輸入也保留原檔");
        check(!JsonParser.parseString(Files.readString(existingFile)).getAsJsonObject().has(BOB.toString()),
            "另一玩家非法輸入沒有建立玩家狀態");
    }

    private static void invalidMemoryCannotOverwrite(Path file) throws Exception {
        PlayerLedger ledger = new PlayerLedger(file);
        ledger.record(ALICE, QIAN_TO_KUN);
        byte[] original = Files.readAllBytes(file);
        ledger.state(ALICE).latestMaterial = null;
        ledger.markDirty();
        rejects(IllegalStateException.class, ledger::save, "執行中的最近材質不可為空");
        check(Arrays.equals(original, Files.readAllBytes(file)), "空材質不得覆蓋有效原檔");
        check(!Files.exists(file.resolveSibling("players.json.pending")), "空材質不得生成替換檔");
        ledger.state(ALICE).latestMaterial = CastingMaterial.BAMBOO;
        rejects(IllegalStateException.class, ledger::save, "執行中的材質與筮法矛盾也須拒絕");
        check(Arrays.equals(original, Files.readAllBytes(file)), "矛盾材質不得覆蓋有效原檔");
        check(!Files.exists(file.resolveSibling("players.json.pending")), "矛盾材質不得生成替換檔");
        ledger.state(ALICE).latestMaterial = CastingMaterial.IRON;
        ledger.state(ALICE).counts.clear();
        ledger.markDirty();
        rejects(IllegalStateException.class, ledger::save, "執行中不一致的圖鑑也必須拒絕保存");
        check(Arrays.equals(original, Files.readAllBytes(file)), "不一致記憶體狀態不得覆蓋有效原檔");
        check(!Files.exists(file.resolveSibling("players.json.pending")), "保存驗證失敗不得生成替換檔");
    }

    private static void invalidEnvironmentMemoryCannotOverwrite(Path file) throws Exception {
        PlayerLedger ledger = new PlayerLedger(file);
        ledger.record(ALICE, QIAN_TO_KUN, CastingMaterial.IRON, 1.02);
        byte[] original = Files.readAllBytes(file);
        for (double environment : INVALID_ENVIRONMENTS) {
            ledger.state(ALICE).latestEnvironment = environment;
            ledger.markDirty();
            rejects(IllegalStateException.class, ledger::save, "記憶體非法環境也須拒絕保存：" + environment);
            check(Arrays.equals(original, Files.readAllBytes(file)), "記憶體非法環境不覆蓋有效原檔");
            check(!Files.exists(file.resolveSibling("players.json.pending")), "記憶體非法環境不產生替換檔");
        }
        ledger.state(ALICE).latestEnvironment = 1.02;
        ledger.save();
        equal(new PlayerLedger(file).state(ALICE).latestEnvironment, 1.02, "修復記憶體後可以正常保存");
    }

    private static void nullInputs(Path file) throws Exception {
        PlayerLedger ledger = new PlayerLedger(file);
        rejects(NullPointerException.class, () -> new PlayerLedger((Path) null), "資料路徑為空應清楚拒絕");
        rejects(NullPointerException.class, () -> ledger.state(null), "UUID 為空應拒絕");
        rejects(NullPointerException.class, () -> ledger.tick(null), "倒數 UUID 為空應拒絕");
        rejects(NullPointerException.class, () -> ledger.record(null, QIAN_TO_KUN), "正式起卦 UUID 為空應拒絕");
        rejects(NullPointerException.class, () -> ledger.record(ALICE, null), "正式起卦結果為空應拒絕");
        rejects(NullPointerException.class, () -> ledger.record(ALICE, QIAN_TO_KUN, null), "正式起卦材質為空應拒絕");
        rejects(NullPointerException.class, () -> ledger.record(null, QIAN_TO_KUN, CastingMaterial.IRON, 1), "四參數 UUID 為空應拒絕");
        rejects(NullPointerException.class, () -> ledger.record(ALICE, null, CastingMaterial.IRON, 1), "四參數起卦結果為空應拒絕");
        rejects(NullPointerException.class, () -> ledger.record(ALICE, QIAN_TO_KUN, null, 1), "四參數材質為空應拒絕");
        for (CastingMaterial material : CastingMaterial.values()) {
            if (methodFor(material) == Method.YARROW) {
                rejects(IllegalArgumentException.class, () -> ledger.record(ALICE, QIAN_TO_KUN, material), "三錢不能記成籌材質：" + material);
            } else {
                rejects(IllegalArgumentException.class, () -> ledger.record(ALICE, KUN, material), "蓍草不能記成錢材質：" + material);
            }
        }
        ledger.save();
        check(!Files.exists(file), "無效輸入不能產生玩家紀錄");
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static Throwable rejects(Class<? extends Throwable> expected, CheckedAction action, String label) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (!expected.isInstance(failure)) throw new AssertionError(label + "：非預期錯誤類型", failure);
            check(true, label);
            return failure;
        }
        throw new AssertionError(label + "：未拒絕");
    }

    private static void equal(Object actual, Object expected, String label) {
        check(java.util.Objects.equals(actual, expected), label + "：預期 " + expected + "，實際 " + actual);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}

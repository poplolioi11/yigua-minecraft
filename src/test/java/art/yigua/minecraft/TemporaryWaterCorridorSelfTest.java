package art.yigua.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** journal 的保存與毀損保護驗收；遊戲內方塊行為另由隔離整合測試驗證。 */
public final class TemporaryWaterCorridorSelfTest {
    private static int checks;
    private TemporaryWaterCorridorSelfTest() {}

    public static void main(String[] arguments) throws Exception {
        Path folder = Files.createTempDirectory("yigua-water-selftest-");
        try {
            Path file = folder.resolve("data/yigua/temporary-water.json");
            List<TemporaryWaterCorridor.Entry> water = new ArrayList<>();
            for (int x = 15; x < 18; x++) for (int y = 60; y < 64; y++) for (int z = 15; z < 27; z++) {
                water.add(new TemporaryWaterCorridor.Entry(x, y, z, water.size() % 16, true));
            }
            var complete = new TemporaryWaterCorridor.Journal("minecraft:overworld", water);
            TemporaryWaterCorridor.writeJournal(file, complete);
            equal(TemporaryWaterCorridor.readJournal(file), complete, "144 格、四區塊、所有水 LEVEL 原子保存後完整重讀");
            try (var paths = Files.list(file.getParent())) {
                check(paths.filter(p -> p.toString().endsWith(".pending")).findAny().isEmpty(),
                    "成功提交不留下 pending 檔");
            }
            List<TemporaryWaterCorridor.Entry> released = new ArrayList<>(water);
            released.set(0, released.getFirst().release());
            var partial = new TemporaryWaterCorridor.Journal("minecraft:overworld", released);
            TemporaryWaterCorridor.writeJournal(file, partial);
            equal(TemporaryWaterCorridor.readJournal(file), partial, "永久撤銷的方塊所有權也保存並重讀");
            String valid = Files.readString(file, StandardCharsets.UTF_8);
            for (Consumer<JsonObject> mutation : List.<Consumer<JsonObject>>of(
                    root -> root.addProperty("schemaVersion", 2),
                    root -> root.addProperty("dimension", "not a dimension"),
                    root -> root.addProperty("dimension", ""),
                    root -> root.remove("entries"),
                    root -> root.getAsJsonArray("entries").add(root.getAsJsonArray("entries").get(0).deepCopy()),
                    root -> first(root).addProperty("waterLevel", 16),
                    root -> first(root).addProperty("waterLevel", -1),
                    root -> first(root).addProperty("waterLevel", 1.1),
                    root -> first(root).addProperty("waterLevel", "1"),
                    root -> first(root).addProperty("wantedAir", "true"),
                    root -> first(root).remove("wantedAir"),
                    root -> first(root).addProperty("x", 30000000),
                    root -> first(root).addProperty("x", -30000001),
                    root -> first(root).addProperty("x", 5000),
                    root -> first(root).addProperty("y", 1000),
                    root -> first(root).addProperty("z", 1000))) {
                JsonObject root = JsonParser.parseString(valid).getAsJsonObject();
                mutation.accept(root);
                String damaged = root.toString();
                Files.writeString(file, damaged);
                rejects(() -> TemporaryWaterCorridor.readJournal(file), "拒絕損壞／越界紀錄");
                equal(Files.readString(file), damaged, "讀取失敗不覆寫原始紀錄");
            }
            String oversized = " ".repeat(65537);
            Files.writeString(file, oversized);
            rejects(() -> TemporaryWaterCorridor.readJournal(file), "拒絕超大紀錄且不解析內容");
            equal(Files.readString(file), oversized, "超大紀錄保留");
            TemporaryWaterCorridor.writeJournal(file, complete);
            valid = Files.readString(file);
            var invalid = new TemporaryWaterCorridor.Journal("minecraft:overworld", List.of(
                new TemporaryWaterCorridor.Entry(0, 60, 0, 0, true),
                new TemporaryWaterCorridor.Entry(40, 60, 0, 0, true)));
            rejects(() -> TemporaryWaterCorridor.writeJournal(file, invalid), "無效記憶體紀錄不得覆寫磁碟");
            equal(Files.readString(file), valid, "原先有效的 write-ahead 紀錄保留");
            var empty = new TemporaryWaterCorridor.Journal("", List.of());
            TemporaryWaterCorridor.writeJournal(file, empty);
            equal(TemporaryWaterCorridor.readJournal(file), empty, "成功復原後的空 journal 可重讀");
            Path blocked = folder.resolve("blocked");
            Files.writeString(blocked, "existing file");
            rejects(() -> TemporaryWaterCorridor.writeJournal(blocked.resolve("data/journal.json"), complete),
                "路徑寫入失敗回傳錯誤，呼叫者不能修改世界");
            equal(Files.readString(blocked), "existing file", "寫入失敗不破壞路徑中的原檔");
            System.out.println("分海 journal 驗收通過：" + checks + " 項保存／毀損保護檢查。");
        } finally {
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static JsonObject first(JsonObject root) { return root.getAsJsonArray("entries").get(0).getAsJsonObject(); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String description) throws Exception {
        try { action.run(); }
        catch (java.io.IOException | IllegalStateException error) { checks++; return; }
        throw new AssertionError(description);
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
    private static void equal(Object actual, Object expected, String description) {
        check(java.util.Objects.equals(actual, expected), description + "：" + actual);
    }
}

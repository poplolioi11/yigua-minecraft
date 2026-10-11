package art.yigua.minecraft.client;

import art.yigua.core.Method;
import art.yigua.minecraft.client.RitualPreferences.EntryMode;
import art.yigua.minecraft.client.RitualPreferences.RitualMode;
import art.yigua.minecraft.client.RitualPreferences.UiMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 實物拖曳須保留確切策數；投錢只產生一次請求；檢查舊偏好遷移與保存失敗。 */
public final class RitualInteractionSelfTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        testStalks();
        testCoins();
        testPreferences();
        System.out.println("RitualInteractionSelfTest: " + checks + " checks passed");
    }
    private static RitualInteraction model(int count) {
        RitualInteraction result = new RitualInteraction();
        result.reset(count);
        result.layout(new RitualInteraction.Rect(10, 30, 98, 154), new RitualInteraction.Rect(120, 30, 98, 154));
        return result;
    }
    private static void testStalks() {
        check(RitualInteraction.isPrimaryButton(1));
        check(!RitualInteraction.isPrimaryButton(0) && !RitualInteraction.isPrimaryButton(2));
        for (int count : new int[] {49, 44, 40, 36, 32, 24}) {
            RitualInteraction model = model(count);
            check(model.leftCount() == 0 && model.rightCount() == count && !model.validSplit());
            for (int left = 1; left <= count; left++) {
                var stick = model.sticks().stream().filter(value -> !value.left()).findFirst().orElseThrow();
                check(model.press(stick.bounds().x() + 1, stick.bounds().y() + 1, false));
                check(model.carrying() && model.selectedCount() == 1);
                check(model.move(40, 80));
                check(model.leftCount() == left - 1);
                check(model.release(40, 80));
                check(model.leftCount() == left && model.rightCount() == count - left);
                check(model.validSplit() == (left <= count - 2));
            }
            check(!model.release(40, 80));
            model.setLeftCount(9);
            check(model.leftCount() == 9 && model.rightCount() == count - 9);
            var stick = model.sticks().getFirst();
            model.press(stick.bounds().x() + 1, stick.bounds().y() + 1, false);
            model.release(400, 400);
            check(model.leftCount() == 9);
            model.press(stick.bounds().x() + 1, stick.bounds().y() + 1, false);
            model.cancelGesture();
            check(!model.carrying() && model.leftCount() == 9);
            model.reset(40);
            check(model.selectedCount() == 0 && model.leftCount() == 0 && model.remaining() == 40);
        }
        RitualInteraction model = model(49);
        check(!model.press(110, 80, false));
        rejects(() -> model.reset(50));
        rejects(() -> model.setLeftCount(50));
        check(model.remaining() == 49 && model.leftCount() == 0);
        check(model.press(120, 30, true));
        model.move(218, 74);
        check(model.selectedCount() == 14 && model.selecting());
        model.release(218, 74);
        check(model.leftCount() == 0 && model.selectedCount() == 14 && !model.selecting());
        var selected = model.sticks().stream().filter(RitualInteraction.Stick::selected).findFirst().orElseThrow();
        check(model.press(selected.bounds().x() + 1, selected.bounds().y() + 1, false));
        model.release(30, 100);
        check(model.leftCount() == 14 && model.rightCount() == 35);
        selected = model.sticks().stream().filter(RitualInteraction.Stick::selected).findFirst().orElseThrow();
        model.press(selected.bounds().x() + 1, selected.bounds().y() + 1, false);
        model.release(200, 100);
        check(model.leftCount() == 0 && model.rightCount() == 49);
        model.press(120, 30, true);
        model.release(218, 184);
        check(model.selectedCount() == 49);
        selected = model.sticks().getFirst();
        model.press(selected.bounds().x() + 1, selected.bounds().y() + 1, false);
        model.release(50, 100);
        check(model.leftCount() == 49 && !model.validSplit());
    }
    private static void testCoins() {
        var model = new RitualInteraction.CoinThrow();
        var coin = new RitualInteraction.Rect(10, 10, 24, 24);
        var target = new RitualInteraction.Rect(80, 20, 100, 100);
        check(!model.press(coin, 0, 0));
        check(!model.release(target, 90, 40));
        for (int toss = 0; toss < 18; toss++) {
            check(model.press(coin, 20, 20));
            check(!model.press(coin, 20, 20));
            check(model.move(90, 40) && model.carrying());
            check(model.release(target, 90, 40));
            check(!model.release(target, 90, 40));
        }
        model.press(coin, 20, 20);
        check(!model.release(target, 40, 40) && !model.carrying());
        model.press(coin, 20, 20);
        model.cancel();
        check(!model.release(target, 90, 40));
    }
    private static void testPreferences() throws IOException {
        var defaults = RitualPreferences.defaults();
        check(defaults.entry() == EntryMode.BUTTON && defaults.yarrow() == RitualMode.AUTO
            && defaults.coins() == RitualMode.AUTO && defaults.ui() == UiMode.INTERACTIVE && defaults.revealAnimation());
        var old = RitualPreferences.parse("{\"mode\":\"direct\",\"manualYarrow\":true,\"sequentialCoins\":false}");
        check(old.entry() == EntryMode.DIRECT && old.yarrow() == RitualMode.TRADITIONAL && old.coins() == RitualMode.AUTO);
        var newModes = RitualPreferences.parse("{\"manualYarrow\":true,\"yarrowMode\":\"instant\",\"coinsMode\":\"traditional\"}");
        check(newModes.yarrow() == RitualMode.INSTANT && newModes.coins() == RitualMode.TRADITIONAL);
        check(defaults.withMethod(Method.COINS, RitualMode.TRADITIONAL).yarrow() == RitualMode.AUTO);
        check(defaults.withMethod(Method.YARROW, RitualMode.INSTANT).coins() == RitualMode.AUTO);
        check(defaults.withBoth(RitualMode.TRADITIONAL).coins() == RitualMode.TRADITIONAL
            && defaults.withBoth(RitualMode.TRADITIONAL).yarrow() == RitualMode.TRADITIONAL);
        check(RitualMode.TRADITIONAL.next() == RitualMode.AUTO && RitualMode.AUTO.next() == RitualMode.INSTANT
            && RitualMode.INSTANT.next() == RitualMode.TRADITIONAL);
        for (String invalid : new String[] {"[]", "null", "{\"mode\":3}", "{\"mode\":\"invalid\"}",
                "{\"yarrowMode\":false}", "{\"coinsMode\":\"random\"}", "{\"ritualUi\":\"unknown\"}",
                "{\"manualYarrow\":\"true\"}", "{\"sequentialCoins\":0}", "{\"revealAnimation\":\"false\"}"}) {
            rejects(() -> RitualPreferences.parse(invalid));
        }
        Path directory = Files.createTempDirectory("yigua-ritual-selftest-");
        try {
            Path config = directory.resolve("settings.json");
            for (EntryMode entry : EntryMode.values()) for (RitualMode yarrow : RitualMode.values())
                for (RitualMode coins : RitualMode.values()) for (UiMode ui : UiMode.values())
                    for (boolean animation : new boolean[] {true, false}) {
                        var prefs = new RitualPreferences(entry, yarrow, coins, ui, animation);
                        check(RitualPreferences.parse(prefs.json()).equals(prefs));
                        prefs.save(config);
                        check(RitualPreferences.parse(Files.readString(config)).equals(prefs));
                    }
            String before = Files.readString(config);
            try { defaults.save(config.resolve("blocked.json")); throw new AssertionError("應拒絕檔案當目錄"); }
            catch (IOException expected) { checks++; }
            check(Files.readString(config).equals(before));
            Path occupied = directory.resolve("occupied");
            Files.createDirectories(occupied);
            Files.writeString(occupied.resolve("keep"), "keep");
            try { defaults.save(occupied); throw new AssertionError("應拒絕替換非空資料夾"); }
            catch (IOException expected) { checks++; }
            check(Files.readString(occupied.resolve("keep")).equals("keep"));
            try (var files = Files.list(directory)) {
                check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void check(boolean valid) {
        checks++;
        if (!valid) throw new AssertionError("檢查 " + checks + " 失敗");
    }
    private static void rejects(Runnable operation) {
        checks++;
        try { operation.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("非法輸入未拒絕");
    }
}

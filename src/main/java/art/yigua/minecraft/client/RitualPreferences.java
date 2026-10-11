package art.yigua.minecraft.client;

import art.yigua.core.Method;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** 本機偏好：使用物品的入口、兩種筮法的進度方式與傳統操作介面分開保存。 */
public record RitualPreferences(EntryMode entry, RitualMode yarrow, RitualMode coins, UiMode ui, boolean revealAnimation) {
    public enum EntryMode {
        BUTTON("button"), DIRECT("direct");
        private final String id;
        EntryMode(String id) { this.id = id; }
        public String id() { return this.id; }
    }

    public enum RitualMode {
        TRADITIONAL("traditional", "傳統"), AUTO("auto", "自動"), INSTANT("instant", "馬上");
        private final String id;
        private final String label;
        RitualMode(String id, String label) { this.id = id; this.label = label; }
        public String id() { return this.id; }
        public String label() { return this.label; }
        public RitualMode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public enum UiMode {
        TEXT("text", "文字"), INTERACTIVE("interactive", "互動拖曳");
        private final String id;
        private final String label;
        UiMode(String id, String label) { this.id = id; this.label = label; }
        public String id() { return this.id; }
        public String label() { return this.label; }
        public UiMode next() { return this == TEXT ? INTERACTIVE : TEXT; }
    }

    public RitualPreferences {
        Objects.requireNonNull(entry);
        Objects.requireNonNull(yarrow);
        Objects.requireNonNull(coins);
        Objects.requireNonNull(ui);
    }

    public static RitualPreferences defaults() {
        return new RitualPreferences(EntryMode.BUTTON, RitualMode.AUTO, RitualMode.AUTO, UiMode.INTERACTIVE, true);
    }

    public RitualMode forMethod(Method method) { return method == Method.YARROW ? yarrow : coins; }
    public RitualPreferences withEntry(EntryMode next) { return new RitualPreferences(next, yarrow, coins, ui, revealAnimation); }
    public RitualPreferences withUi(UiMode next) { return new RitualPreferences(entry, yarrow, coins, next, revealAnimation); }
    public RitualPreferences withBoth(RitualMode next) { return new RitualPreferences(entry, next, next, ui, revealAnimation); }
    public RitualPreferences withAnimation(boolean next) { return new RitualPreferences(entry, yarrow, coins, ui, next); }
    public RitualPreferences withMethod(Method method, RitualMode next) {
        return method == Method.YARROW ? new RitualPreferences(entry, next, coins, ui, revealAnimation)
            : new RitualPreferences(entry, yarrow, next, ui, revealAnimation);
    }

    public static RitualPreferences parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        boolean oldYarrow = readBoolean(object, "manualYarrow");
        boolean oldCoins = readBoolean(object, "sequentialCoins");
        return new RitualPreferences(
            entry(readString(object, "mode", "button")),
            ritual(readString(object, "yarrowMode", oldYarrow ? "traditional" : "auto")),
            ritual(readString(object, "coinsMode", oldCoins ? "traditional" : "auto")),
            ui(readString(object, "ritualUi", "interactive")),
            readBoolean(object, "revealAnimation", true));
    }

    public String json() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", entry.id());
        object.addProperty("yarrowMode", yarrow.id());
        object.addProperty("coinsMode", coins.id());
        object.addProperty("ritualUi", ui.id());
        object.addProperty("revealAnimation", revealAnimation);
        // 舊版仍能讀懂傳統開關；新版的 enum 欄位具有優先權。
        object.addProperty("manualYarrow", yarrow == RitualMode.TRADITIONAL);
        object.addProperty("sequentialCoins", coins == RitualMode.TRADITIONAL);
        return object.toString() + "\n";
    }

    /** 先完整寫入暫存檔，成功替換後呼叫端才能更新畫面中的偏好。 */
    public void save(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".yigua-client-", ".tmp");
        try {
            Files.writeString(temporary, json());
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static EntryMode entry(String id) {
        for (EntryMode value : EntryMode.values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException("起卦入口必須是 button 或 direct");
    }
    private static RitualMode ritual(String id) {
        for (RitualMode value : RitualMode.values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException("起卦方式必須是 traditional、auto 或 instant");
    }
    private static UiMode ui(String id) {
        for (UiMode value : UiMode.values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException("傳統介面必須是 text 或 interactive");
    }
    private static String readString(JsonObject object, String key, String fallback) {
        if (!object.has(key)) return fallback;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException(key + " 必須是字串");
        }
        return object.get(key).getAsString();
    }
    private static boolean readBoolean(JsonObject object, String key) {
        return readBoolean(object, key, false);
    }
    private static boolean readBoolean(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException(key + " 必須是布林值");
        }
        return object.get(key).getAsBoolean();
    }
}

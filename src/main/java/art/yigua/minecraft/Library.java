package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.CastingEngine;
import com.google.gson.*;
import java.io.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 古籍與遊戲規則分開載入；遊戲不解析經文字串來猜吉凶。 */
public final class Library {
    public record HexText(int number, String name, List<String> lines,
        List<String> lineSources, String specialLabel, String specialText, String sourceNote) {}
    public record Rule(String id, int number, int line, String profile, double amount,
        int durationTicks, int priority, String rationale, String origText) {}
    private static final Map<Integer, HexText> TEXTS = new HashMap<>();
    private static final Map<Integer, Rule> BASE = new HashMap<>();
    private static final List<Rule> LINES = new ArrayList<>();
    private static final Map<String, Rule> SPECIAL = new HashMap<>();
    private static final List<String> GENERAL_PROFILES = List.of("travel", "caution", "focus", "fatigue",
        "strength", "restraint", "guard", "frail", "quick", "slowhand", "luck", "illluck",
        "lightness", "heaviness", "landing", "fallrisk", "airloss", "vitality", "depletion",
        "leap", "heavyfeet", "steady", "exposed", "fireward", "embers", "reach", "nearreach",
        "breath", "deepwork", "drownedwork", "closecombat", "direction");
    private static final Set<String> PROFILES = allProfiles();

    private Library() {}

    private static Set<String> allProfiles() {
        Set<String> profiles = new HashSet<>(GENERAL_PROFILES);
        profiles.add("neutral");
        return Set.copyOf(profiles);
    }

    public static void load() {
        JsonObject text = read("/data/yigua/texts/wuying-lines.json");
        for (JsonElement e : text.getAsJsonArray("hexagrams")) {
            JsonObject o = e.getAsJsonObject();
            int n = o.get("number").getAsInt();
            List<String> lines = strings(o.getAsJsonArray("lines"));
            List<String> sources = strings(o.getAsJsonArray("lineSources"));
            JsonObject special = o.has("special") ? o.getAsJsonObject("special") : null;
            if (n < 1 || n > 64 || lines.size() != 6 || sources.size() != 6
                    || !o.get("name").getAsString().equals(CastingEngine.nameForNumber(n))) {
                throw new IllegalStateException("古籍資料格式錯誤：" + n);
            }
            if (TEXTS.put(n, new HexText(n, o.get("name").getAsString(), lines, sources,
                    special == null ? "" : special.get("label").getAsString(),
                    special == null ? "" : special.get("text").getAsString(),
                    o.has("sourceNote") ? o.get("sourceNote").getAsString() : "")) != null) {
                throw new IllegalStateException("重複卦序：" + n);
            }
        }
        if (TEXTS.size() != 64) throw new IllegalStateException("古籍必須包含完整六十四卦");
        JsonObject effects = read("/data/yigua/rules/effects.json");
        JsonArray yichuan = read("/data/yigua/texts/yichuan-guaci.json").getAsJsonArray("hexagrams");
        if (yichuan.size() != 64) throw new IllegalStateException("伊川來源必須包含六十四卦");
        for (JsonElement e : effects.getAsJsonArray("hexagrams")) {
            JsonObject o = e.getAsJsonObject();
            Rule r = parse(o, false);
            JsonObject reference = yichuan.get(r.number() - 1).getAsJsonObject();
            StringJoiner guaci = new StringJoiner("；");
            StringBuilder commentary = new StringBuilder(reference.get("xu").getAsString());
            for (JsonElement segment : reference.getAsJsonArray("segments")) {
                guaci.add(segment.getAsJsonObject().get("jing").getAsString());
                commentary.append(segment.getAsJsonObject().get("zhuan").getAsString());
            }
            String excerpt = o.get("source_excerpt").getAsString();
            String evidence = "《伊川易傳》卦辭：" + guaci + "\n程傳節錄：" + excerpt + "\n四庫本初錄稿，未經全文二校。";
            if (reference.get("no").getAsInt() != r.number() || excerpt.isBlank()
                    || !commentary.toString().contains(excerpt) || !evidence.equals(r.origText())
                    || !reference.getAsJsonArray("image_links").get(0).getAsString().equals(o.get("source_url").getAsString())
                    || !reference.get("pages").getAsString().equals(o.get("source_pages").getAsString())
                    || !o.get("source_status").getAsString().equals("初錄稿，未經全文二校")) {
                throw new IllegalStateException("伊川節錄或卦辭與來源不符：" + r.number());
            }
            if (BASE.put(r.number(), r) != null) throw new IllegalStateException("重複基礎效果");
        }
        for (JsonElement e : effects.getAsJsonArray("line_overrides")) {
            JsonObject o = e.getAsJsonObject();
            Rule r = parse(o, true);
            if (r.line() < 1 || r.line() > 6 || !r.origText().equals(hex(r.number()).lines().get(r.line() - 1))) {
                throw new IllegalStateException("逐爻覆寫必須逐字對應原文");
            }
            LINES.add(r);
        }
        for (JsonElement e : effects.getAsJsonArray("special_rules")) {
            JsonObject o = e.getAsJsonObject();
            Rule r = parse(o, false);
            if (!r.origText().equals(hex(r.number()).specialText())) throw new IllegalStateException("用辭不符");
            SPECIAL.put(o.get("label").getAsString(), r);
        }
        if (BASE.size() != 64) throw new IllegalStateException("每卦都必須有基礎遊戲效果");
    }

    private static Rule parse(JsonObject o, boolean line) {
        int n = o.get("number").getAsInt();
        String profile = o.get("profile").getAsString();
        double amount = o.get("amount").getAsDouble();
        int duration = o.get("duration_ticks").getAsInt();
        // 規則保留一倍基準；正數表示增益，重力與摔傷屬性由套用端反向調整。
        double expected = baseAmount(profile);
        if (n < 1 || n > 64 || !PROFILES.contains(profile) || !Double.isFinite(amount)
                || Math.abs(amount - expected) > 0.000001 || duration != 12000) {
            throw new IllegalStateException("效果規則超出首版固定強度與時間：" + n);
        }
        return new Rule(o.has("id") ? o.get("id").getAsString() : "hexagram_" + n,
            n, line ? o.get("line").getAsInt() : 0, profile, amount, duration,
            o.has("priority") ? o.get("priority").getAsInt() : 0,
            o.get("rationale").getAsString(), o.has("orig_text") ? o.get("orig_text").getAsString() : "");
    }

    private static JsonObject read(String path) {
        try (InputStream stream = Library.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("找不到 " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) { throw new IllegalStateException("無法載入一卦資料", e); }
    }

    private static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>();
        values.forEach(v -> result.add(v.getAsString()));
        return List.copyOf(result);
    }

    public static HexText hex(int number) {
        HexText h = TEXTS.get(number);
        if (h == null) throw new IllegalArgumentException("卦序必須為一至六十四");
        return h;
    }

    public static Rule rule(CastResult result) {
        if (!result.special().isEmpty() && SPECIAL.containsKey(result.special())) return SPECIAL.get(result.special());
        return LINES.stream().filter(r -> r.number() == result.primaryNumber()
                && result.changingLines().contains(r.line()))
            .max(Comparator.comparingInt(Rule::priority).thenComparingInt(Rule::line))
            .orElse(BASE.get(result.primaryNumber()));
    }

    /** 語義正負：增益為正；中性為零；未知識別名稱不得進入註冊或稀有事件。 */
    public static double baseAmount(String profile) {
        return switch (profile) {
            case "travel", "focus", "quick", "lightness", "landing", "leap", "steady", "fireward", "deepwork", "direction" -> 0.1;
            case "caution", "fatigue", "slowhand", "heaviness", "fallrisk", "airloss", "heavyfeet", "exposed", "embers", "drownedwork" -> -0.1;
            case "strength", "guard", "luck", "breath" -> 1;
            case "restraint", "frail", "illluck" -> -1;
            case "vitality" -> 2;
            case "depletion" -> -2;
            case "reach", "closecombat" -> 0.5;
            case "nearreach" -> -0.5;
            case "neutral" -> 0;
            default -> Double.NaN;
        };
    }

    public static Set<String> profiles() { return PROFILES; }
    public static List<String> generalProfiles() { return GENERAL_PROFILES; }

    public static String profileName(String profile) {
        return switch (profile) {
            case "travel" -> "行旅"; case "caution" -> "行路受阻";
            case "focus" -> "專注"; case "fatigue" -> "休整";
            case "strength" -> "健行"; case "restraint" -> "收斂";
            case "guard" -> "守護"; case "frail" -> "謹慎";
            case "quick" -> "振作"; case "slowhand" -> "遲滯";
            case "luck" -> "得宜"; case "illluck" -> "失宜";
            case "lightness" -> "輕身"; case "heaviness" -> "沉重";
            case "landing" -> "安落"; case "fallrisk" -> "失足";
            case "airloss" -> "涉水受阻";
            case "vitality" -> "生機"; case "depletion" -> "衰耗";
            case "leap" -> "躍升"; case "heavyfeet" -> "重足";
            case "steady" -> "穩立"; case "exposed" -> "易退";
            case "fireward" -> "避灼"; case "embers" -> "餘燼";
            case "reach" -> "延展"; case "nearreach" -> "近取";
            case "breath" -> "深息"; case "deepwork" -> "潛作";
            case "drownedwork" -> "潛阻"; case "closecombat" -> "近接";
            case "direction" -> "順方"; default -> "平和";
        };
    }

    public static String effectSummary(Rule r) {
        Objects.requireNonNull(r, "規則不可為 null");
        String magnitude = magnitude(r.amount(), false);
        String percent = magnitude(r.amount(), true) + "%";
        String value = switch (r.profile()) {
            case "travel", "caution" -> "移動速度" + (r.amount() > 0 ? "增加" : "降低") + percent;
            case "focus", "fatigue" -> "挖掘速度" + (r.amount() > 0 ? "增加" : "降低") + percent;
            case "strength", "restraint" -> "攻擊傷害" + (r.amount() > 0 ? "+" : "－") + magnitude;
            case "guard", "frail" -> "護甲" + (r.amount() > 0 ? "+" : "－") + magnitude;
            case "quick", "slowhand" -> "攻擊恢復速度" + (r.amount() > 0 ? "增加" : "降低") + percent;
            case "luck", "illluck" -> "幸運" + (r.amount() > 0 ? "+" : "－") + magnitude + "（限含幸運判定的戰利品）";
            case "lightness", "heaviness" -> "下落加速度" + (r.amount() > 0 ? "降低" : "增加") + percent + "（也影響跳躍）";
            case "landing", "fallrisk" -> "摔落傷害" + (r.amount() > 0 ? "降低" : "增加") + percent;
            case "airloss" -> "水下氧氣額外消耗";
            case "vitality", "depletion" -> "生命上限" + (r.amount() > 0 ? "+" : "－") + magnitude + "（不補血）";
            case "leap", "heavyfeet" -> "跳躍強度" + (r.amount() > 0 ? "增加" : "降低") + percent;
            case "steady", "exposed" -> "擊退抗性" + (r.amount() > 0 ? "+" : "－") + magnitude;
            case "fireward", "embers" -> "燃燒時間" + (r.amount() > 0 ? "降低" : "增加") + percent + "（非免火）";
            case "reach", "nearreach" -> "方塊互動距離" + (r.amount() > 0 ? "+" : "－") + magnitude + "格（非視野）";
            case "breath" -> "水下氧氣加成+" + magnitude;
            case "deepwork", "drownedwork" -> "水下挖掘速度" + (r.amount() > 0 ? "增加" : "降低") + percent;
            case "closecombat" -> "實體互動距離+" + magnitude + "格（非視野）";
            case "direction" -> "移動西南+" + percent + "、東北－" + percent + "、其他0%";
            default -> "基礎效果為中性";
        };
        return profileName(r.profile()) + "：" + value;
    }

    private static String magnitude(double amount, boolean percent) {
        // 十二位有效數足以保留材質數值，並去掉浮點運算造成的尾數。
        BigDecimal value = BigDecimal.valueOf(Math.abs(amount))
            .round(new MathContext(12, RoundingMode.HALF_UP));
        if (percent) value = value.movePointRight(2);
        return value.stripTrailingZeros().toPlainString();
    }
}

package art.yigua.minecraft;

import art.yigua.core.*;
import java.util.*;

/** 對照古籍已核對的動爻案例，檢查遊戲規則選擇。 */
public final class LibrarySelfTest {
    private static int checks;

    public static void main(String[] args) {
        Library.load();
        Set<String> naturallyAvailable = new HashSet<>();
        for (int number = 1; number <= 64; number++) {
            var result = cast(number);
            var rule = Library.rule(result);
            require(rule.number() == number && rule.line() == 0, "無動爻應採本卦規則");
            require(Library.hex(number).lines().size() == 6, "六爻完整");
            require(rule.durationTicks() == 12000, "效果時間");
            require(Double.compare(rule.amount(), baseAmount(rule.profile())) == 0, "資料保持一倍基準");
            naturallyAvailable.add(rule.profile());
            for (int line = 1; line <= 6; line++) {
                Library.Rule moving = Library.rule(cast(number, line));
                naturallyAvailable.add(moving.profile());
                if (moving.line() > 0) {
                    require(moving.origText().equals(Library.hex(number).lines().get(line - 1)), "自然動爻效果須保留實際原爻原文");
                }
            }
        }
        naturallyAvailable.remove("neutral");
        require(Library.generalProfiles().size() == 32 && new HashSet<>(Library.generalProfiles()).size() == 32,
            "一般效果必須有32種獨立profile");
        require(naturallyAvailable.equals(new HashSet<>(Library.generalProfiles())), "32種一般效果全部可由自然卦與動爻取得");
        require(Library.profiles().size() == 33 && Library.profiles().contains("neutral"), "一般規則表另保留中性，不混入隱藏事件");
        profile(27, "travel", 6);   // 由頤厲吉利涉大川
        profile(39, "caution", 3); // 往蹇來反
        profile(1, "restraint", 6);// 亢龍有悔
        profile(60, "guard", 1);   // 不出戶庭无咎
        profile(60, "frail", 2);   // 不出門庭凶
        profile(64, "travel", 3);  // 未濟征凶利涉大川
        profile(25, "illluck", 3);
        profile(10, "landing", 4);
        profile(28, "airloss", 6);
        profile(27, "vitality"); profile(47, "depletion");
        profile(35, "leap"); profile(52, "heavyfeet");
        profile(32, "steady"); profile(36, "exposed");
        profile(49, "fireward"); profile(30, "embers");
        profile(19, "reach"); profile(33, "nearreach");
        profile(59, "breath"); profile(48, "deepwork");
        profile(64, "drownedwork"); profile(8, "closecombat"); profile(39, "direction");
        profile(60, "frail", 1, 2);// 同優先序取較高動爻
        require(Library.rule(CastingEngine.fromLines(Method.COINS, List.of(9,9,9,9,9,9))).profile().equals("guard"), "用九優先於亢龍");
        require(Library.rule(CastingEngine.fromLines(Method.YARROW, List.of(6,6,6,6,6,6))).profile().equals("neutral"), "用六");
        require(Library.hex(1).specialText().equals("見羣龍无首吉"), "保留武英殿字形");
        materials();
        require(Library.effectSummary(new Library.Rule("浮點尾數", 1, 0, "travel", 0.1 * 0.75,
            12000, 0, "測試", "")).equals("行旅：移動速度增加7.5%"), "百分比去除浮點尾數");
        rejectsNull(() -> CastingMaterial.defaultFor(null), "預設材質拒絕空筮法");
        rejectsNull(() -> CastingMaterial.legacyFor(null), "舊存檔材質拒絕空筮法");
        rejectsNull(() -> Library.effectSummary(null), "效果說明拒絕空規則");
        System.out.println("古籍、效果選擇與材質驗收通過：" + checks
            + " 項檢查、64 卦、9 個逐爻案例、32種自然一般效果、同卦多動爻、用九用六及 10 種材質 × 33 種規則。");
    }

    private static void materials() {
        CastingMaterial[] materials = {
            CastingMaterial.WOOD, CastingMaterial.IRON, CastingMaterial.COPPER,
            CastingMaterial.GREEN_COPPER, CastingMaterial.GOLD, CastingMaterial.BAMBOO,
            CastingMaterial.WOOD_STALKS, CastingMaterial.GRASS_STALKS, CastingMaterial.LEGACY_BAMBOO,
            CastingMaterial.LEGACY
        };
        double[] multipliers = {0.5, 0.75, 1, 1.25, 1.5, 1.5, 1, 0.5, 1, 1};
        double[] percentageAmounts = {0.05, 0.075, 0.1, 0.125, 0.15, 0.15, 0.1, 0.05, 0.1, 0.1};
        String[] percentages = {"5", "7.5", "10", "12.5", "15", "15", "10", "5", "10", "10"};
        String[] attributes = {"0.5", "0.75", "1", "1.25", "1.5", "1.5", "1", "0.5", "1", "1"};
        String[] ids = {"wood", "iron", "copper", "green_copper", "gold", "bamboo",
            "wood_stalks", "grass_stalks", "legacy_bamboo", "legacy"};
        String[] names = {"木錢", "鐵錢", "褐銅錢", "綠銅錢", "金錢", "竹籌", "木籌", "草籌", "舊版竹籌", "舊版三錢"};
        Set<CastingMaterial> yarrowMaterials = Set.of(CastingMaterial.BAMBOO, CastingMaterial.WOOD_STALKS,
            CastingMaterial.GRASS_STALKS, CastingMaterial.LEGACY_BAMBOO);
        List<String> profiles = new ArrayList<>(Library.generalProfiles());
        profiles.add("neutral");
        require(CastingMaterial.values().length == materials.length, "完整十種材質");
        for (int i = 0; i < materials.length; i++) {
            CastingMaterial material = materials[i];
            require(Double.compare(material.multiplier(), multipliers[i]) == 0, "材質倍率：" + names[i]);
            require(material.id().equals(ids[i]), "穩定材質識別值：" + names[i]);
            require(material.displayName().equals(names[i]), "繁體中文材質名稱");
            require(material.compatibleWith(Method.COINS) == !yarrowMaterials.contains(material), "三錢法材質相容");
            require(material.compatibleWith(Method.YARROW) == yarrowMaterials.contains(material), "蓍草法材質相容");
            rejectsNull(() -> material.compatibleWith(null), "相容檢查拒絕空筮法");
            rejectsNull(() -> material.scaleRule(null), "材質拒絕空規則");
            for (String profile : profiles) {
                Library.Rule original = new Library.Rule("驗收_" + profile, 64, 6, profile,
                    baseAmount(profile), 12000, 71, "遊戲規則說明", "經文原貌");
                Library.Rule scaled = material.scaleRule(original);
                double expectedAmount = switch (profile) {
                    case "travel", "focus", "quick", "lightness", "landing", "leap", "steady", "fireward", "deepwork", "direction" -> percentageAmounts[i];
                    case "caution", "fatigue", "slowhand", "heaviness", "fallrisk", "airloss", "heavyfeet", "exposed", "embers", "drownedwork" -> -percentageAmounts[i];
                    case "strength", "guard", "luck", "breath" -> multipliers[i];
                    case "restraint", "frail", "illluck" -> -multipliers[i];
                    case "vitality" -> 2 * multipliers[i];
                    case "depletion" -> -2 * multipliers[i];
                    case "reach", "closecombat" -> 0.5 * multipliers[i];
                    case "nearreach" -> -0.5 * multipliers[i];
                    default -> 0;
                };
                require(scaled != original, "縮放產生新規則");
                require(Double.compare(scaled.amount(), expectedAmount) == 0, "精確正負與中性倍率：" + names[i] + profile);
                require(Double.compare(original.amount(), baseAmount(profile)) == 0, "原規則不突變");
                require(scaled.id().equals(original.id()), "保留規則識別值");
                require(scaled.number() == original.number(), "保留卦序");
                require(scaled.line() == original.line(), "保留動爻");
                require(scaled.profile().equals(original.profile()), "保留效果類型");
                require(scaled.durationTicks() == 12000, "材質不延長十分鐘");
                require(scaled.priority() == original.priority(), "保留優先序");
                require(scaled.rationale().equals(original.rationale()), "保留遊戲說明");
                require(scaled.origText().equals(original.origText()), "保留古籍原文");
                String additive = decimal(percentageAmounts[i]);
                String health = decimal(2 * multipliers[i]);
                String range = decimal(0.5 * multipliers[i]);
                String expectedSummary = switch (profile) {
                    case "travel" -> "行旅：移動速度增加" + percentages[i] + "%";
                    case "caution" -> "行路受阻：移動速度降低" + percentages[i] + "%";
                    case "focus" -> "專注：挖掘速度增加" + percentages[i] + "%";
                    case "fatigue" -> "休整：挖掘速度降低" + percentages[i] + "%";
                    case "strength" -> "健行：攻擊傷害+" + attributes[i];
                    case "restraint" -> "收斂：攻擊傷害－" + attributes[i];
                    case "guard" -> "守護：護甲+" + attributes[i];
                    case "frail" -> "謹慎：護甲－" + attributes[i];
                    case "quick" -> "振作：攻擊恢復速度增加" + percentages[i] + "%";
                    case "slowhand" -> "遲滯：攻擊恢復速度降低" + percentages[i] + "%";
                    case "luck" -> "得宜：幸運+" + attributes[i] + "（限含幸運判定的戰利品）";
                    case "illluck" -> "失宜：幸運－" + attributes[i] + "（限含幸運判定的戰利品）";
                    case "lightness" -> "輕身：下落加速度降低" + percentages[i] + "%（也影響跳躍）";
                    case "heaviness" -> "沉重：下落加速度增加" + percentages[i] + "%（也影響跳躍）";
                    case "landing" -> "安落：摔落傷害降低" + percentages[i] + "%";
                    case "fallrisk" -> "失足：摔落傷害增加" + percentages[i] + "%";
                    case "airloss" -> "涉水受阻：水下氧氣額外消耗";
                    case "vitality" -> "生機：生命上限+" + health + "（不補血）";
                    case "depletion" -> "衰耗：生命上限－" + health + "（不補血）";
                    case "leap" -> "躍升：跳躍強度增加" + percentages[i] + "%";
                    case "heavyfeet" -> "重足：跳躍強度降低" + percentages[i] + "%";
                    case "steady" -> "穩立：擊退抗性+" + additive;
                    case "exposed" -> "易退：擊退抗性－" + additive;
                    case "fireward" -> "避灼：燃燒時間降低" + percentages[i] + "%（非免火）";
                    case "embers" -> "餘燼：燃燒時間增加" + percentages[i] + "%（非免火）";
                    case "reach" -> "延展：方塊互動距離+" + range + "格（非視野）";
                    case "nearreach" -> "近取：方塊互動距離－" + range + "格（非視野）";
                    case "breath" -> "深息：水下氧氣加成+" + attributes[i];
                    case "deepwork" -> "潛作：水下挖掘速度增加" + percentages[i] + "%";
                    case "drownedwork" -> "潛阻：水下挖掘速度降低" + percentages[i] + "%";
                    case "closecombat" -> "近接：實體互動距離+" + range + "格（非視野）";
                    case "direction" -> "順方：移動西南+" + percentages[i] + "%、東北－" + percentages[i] + "%、其他0%";
                    default -> "平和：基礎效果為中性";
                };
                require(Library.effectSummary(scaled).equals(expectedSummary), "效果說明反映真實數值：" + names[i] + profile);
            }
        }
        require(CastingMaterial.defaultFor(Method.COINS) == CastingMaterial.IRON, "新三錢法預設鐵錢");
        require(CastingMaterial.defaultFor(Method.COINS).multiplier() == 0.75, "預設鐵錢採新版四分之三倍");
        require(CastingMaterial.defaultFor(Method.YARROW) == CastingMaterial.BAMBOO, "新蓍草法預設竹籌");
        require(CastingMaterial.defaultFor(Method.YARROW).multiplier() == 1.5, "預設竹籌採新版一點五倍");
        require(CastingMaterial.legacyFor(Method.COINS) == CastingMaterial.LEGACY, "舊三錢存檔保持一倍");
        require(CastingMaterial.legacyFor(Method.YARROW) == CastingMaterial.LEGACY_BAMBOO, "舊蓍草存檔明確採舊版竹籌");
        require(CastingMaterial.legacyFor(Method.YARROW).multiplier() == 1, "舊蓍草存檔保持一倍，不升級新版竹籌");
        Library.Rule unusualDuration = new Library.Rule("保留時間", 1, 0, "travel", 0.1, 37, 0, "測試", "");
        require(CastingMaterial.GOLD.scaleRule(unusualDuration).durationTicks() == 37, "僅縮放數值，不重寫其他時間");
    }

    private static double baseAmount(String profile) {
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
            default -> throw new AssertionError("未知驗收效果：" + profile);
        };
    }

    private static String decimal(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static void rejectsNull(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (NullPointerException expected) {
            checks++;
        }
    }

    private static CastResult cast(int number, int... changing) {
        int mask = CastingEngine.maskForNumber(number);
        List<Integer> values = new ArrayList<>();
        for (int i=0; i<6; i++) values.add((mask & (1<<i)) != 0 ? 7 : 8);
        for (int line : changing) values.set(line-1, values.get(line-1) == 7 ? 9 : 6);
        return CastingEngine.fromLines(Method.COINS, values);
    }
    private static void profile(int number, String expected, int... moving) {
        require(Library.rule(cast(number, moving)).profile().equals(expected), "第" + number + "卦動爻效果");
    }
    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}

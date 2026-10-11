package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.CastingEngine;
import art.yigua.core.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 具體六爻機率、稀有優先序與同屬性數值的有界驗收。 */
public final class RareFortuneSelfTest {
    private static int checks;

    public static void main(String[] args) {
        Library.load();
        specifiedFractions();
        eventPriorityAndSymmetry();
        materialScaling();
        allConcreteOutcomes();
        limitsAndValidation();
        System.out.println("稀有卦驗收通過：" + checks + " 項檢查；8192 個筮法／六爻組合、機率總和、稀有事件、材質與上限。");
    }

    private static void specifiedFractions() {
        fraction(resolve(Method.COINS, 9, 9, 9, 9, 9, 9), 1, 262144);
        fraction(resolve(Method.YARROW, 9, 9, 9, 9, 9, 9), 729, 16777216);
        fraction(resolve(Method.YARROW, 6, 6, 6, 6, 6, 6), 1, 16777216);
        fraction(resolve(Method.YARROW, 6, 6, 6, 9, 9, 9), 27, 16777216);
        fraction(resolve(Method.YARROW, 9, 9, 9, 6, 6, 6), 27, 16777216);
        fraction(resolve(Method.COINS, 7, 7, 7, 7, 7, 7), 729, 262144);
        fraction(resolve(Method.YARROW, 7, 7, 7, 7, 7, 7), 15625, 16777216);
        fraction(resolve(Method.YARROW, 8, 8, 8, 8, 8, 8), 117649, 16777216);
    }

    private static void eventPriorityAndSymmetry() {
        for (Method method : Method.values()) {
            RareFortune.Outcome good = resolve(method, 6, 6, 6, 9, 9, 9);
            RareFortune.Outcome bad = resolve(method, 9, 9, 9, 6, 6, 6);
            require(good.name().equals("否極泰來") && bad.name().equals("泰極否來"), "否泰六爻事件優先於一般皆變");
            require(profiles(good).equals(Set.of("travel", "focus", "guard")), "否極泰來三項正向效果");
            require(profiles(bad).equals(Set.of("caution", "fatigue", "frail")), "泰極否來三項負向效果");
            fraction(bad, good.numerator(), good.denominator());
            require(good.rarityFactor() == bad.rarityFactor(), "鏡像事件使用相同稀有倍率");
            for (int index = 0; index < good.rules().size(); index++) {
                require(good.rules().get(index).amount() == -bad.rules().get(index).amount(), "同屬性的正負幅度對稱");
                double goodExpected = (double) good.numerator() / good.denominator() * good.rules().get(index).amount();
                double badExpected = (double) bad.numerator() / bad.denominator() * bad.rules().get(index).amount();
                require(goodExpected == -badExpected, "只比較同屬性的 signed amount 期望，不混同屬性效用");
            }
            RareFortune.Outcome nine = resolve(method, 9, 9, 9, 9, 9, 9);
            RareFortune.Outcome six = resolve(method, 6, 6, 6, 6, 6, 6);
            require(nine.name().equals("群龍無首") && profiles(nine).equals(Set.of("travel", "guard")), "用九專屬正向組合");
            require(six.name().equals("利永貞") && profiles(six).equals(Set.of("focus", "guard")), "用六專屬正向組合");
        }
        require(resolve(Method.COINS, 9, 9, 9, 9, 9, 9).rarityFactor() == 3, "三錢完整皆變組合倍率 3");
        require(resolve(Method.YARROW, 9, 9, 9, 9, 9, 9).rarityFactor() == 2.5, "蓍草全九倍率 2.5");
        require(resolve(Method.YARROW, 6, 6, 6, 6, 6, 6).rarityFactor() == 4, "蓍草全六倍率上限 4");
        require(resolve(Method.YARROW, 6, 6, 6, 9, 9, 9).rarityFactor() == 3, "蓍草否泰倍率 3");
    }

    private static void materialScaling() {
        CastResult rareCast = cast(Method.COINS, 6, 6, 6, 9, 9, 9);
        CastResult normalCast = cast(Method.COINS, 7, 7, 7, 7, 7, 7);
        Library.Rule original = Library.rule(normalCast);
        RareFortune.Outcome baseline = RareFortune.resolve(rareCast, CastingMaterial.COPPER);
        for (CastingMaterial material : CastingMaterial.values()) {
            if (!material.compatibleWith(Method.COINS)) continue;
            RareFortune.Outcome rare = RareFortune.resolve(rareCast, material);
            fraction(rare, baseline.numerator(), baseline.denominator());
            require(rare.rarityFactor() == baseline.rarityFactor(), "材質不改變機率或稀有倍率");
            for (int i = 0; i < rare.rules().size(); i++) {
                close(rare.rules().get(i).amount(), baseline.rules().get(i).amount() * material.multiplier(), "材質只縮放效果數值");
                require(rare.rules().get(i).durationTicks() == 12000, "材質不得刷新效果時間");
            }
            RareFortune.Outcome normal = RareFortune.resolve(normalCast, material);
            require(!normal.rare() && normal.name().equals("常規") && normal.rarityFactor() == 1, "常規不套稀有倍率");
            require(normal.rules().equals(List.of(material.scaleRule(original))), "常規保留原規則及材質縮放");
        }
        require(Library.rule(normalCast).equals(original), "稀有模組不得變動 Library 或原文規則");
        close(RareFortune.resolve(rareCast, CastingMaterial.GOLD).rules().get(2).amount(), 4.5, "金錢否泰護甲為 3 倍稀有再乘 1.5 倍材質");
    }

    private static void allConcreteOutcomes() {
        boolean sawNeutral = false, sawGuard = false, sawFrail = false;
        Set<Double> yarrowBands = new HashSet<>();
        for (Method method : Method.values()) {
            long denominator = method == Method.COINS ? 262144 : 16777216;
            long weightSum = 0, rareWeightSum = 0;
            int rareCount = 0;
            for (int code = 0; code < 4096; code++) {
                int remaining = code;
                int[] values = new int[6];
                long expectedNumerator = 1;
                boolean allMoving = true;
                for (int index = 0; index < 6; index++) {
                    values[index] = 6 + (remaining & 3);
                    remaining >>= 2;
                    expectedNumerator *= method == Method.COINS
                        ? new int[] {1, 3, 3, 1}[values[index] - 6]
                        : new int[] {1, 5, 7, 3}[values[index] - 6];
                    allMoving &= values[index] == 6 || values[index] == 9;
                }
                CastResult result = cast(method, values);
                RareFortune.Outcome outcome = RareFortune.resolve(result, CastingMaterial.legacyFor(method));
                fraction(outcome, expectedNumerator, denominator);
                weightSum += outcome.numerator();
                require(outcome.rare() == allMoving, "僅六爻全部皆變是稀有事件");
                require(profiles(outcome).size() == outcome.rules().size(), "同一稀有事件不得重複效果屬性");
                if (!allMoving) continue;
                rareCount++;
                rareWeightSum += outcome.numerator();
                require(outcome.rarityFactor() >= 2 && outcome.rarityFactor() <= 4
                    && outcome.rarityFactor() * 2 == Math.rint(outcome.rarityFactor() * 2), "倍率限有限半級範圍");
                if (method == Method.YARROW) yarrowBands.add(outcome.rarityFactor());
                for (Library.Rule rule : outcome.rules()) {
                    require(rule.id().startsWith("rare_") && rule.durationTicks() == 12000 && rule.origText().isEmpty()
                        && rule.rationale().contains("並非經文本意"), "稀有事件清楚區分遊戲與經文");
                    require(Math.abs(rule.amount()) <= Math.abs(Library.baseAmount(rule.profile())) * 4,
                        "一倍材質稀有幅度依各屬性基準封頂");
                }
                if (!outcome.name().equals("六爻皆變")) continue;
                Library.Rule base = Library.rule(result);
                require(outcome.rules().size() == 2, "一般六爻皆變使用兩項效果");
                if (base.amount() == 0) {
                    require(profiles(outcome).equals(Set.of("guard", "focus")), "中性六爻皆變採守護與專注");
                    sawNeutral = true;
                } else {
                    require(outcome.rules().get(0).profile().equals(base.profile()), "一般皆變保留原本非中性屬性");
                    require(outcome.rules().stream().allMatch(rule -> Math.signum(rule.amount()) == Math.signum(base.amount())), "一般皆變依原效果正負選向");
                    if (base.profile().equals("guard")) {
                        require(profiles(outcome).equals(Set.of("guard", "focus")), "原守護替補專注，避免重複");
                        sawGuard = true;
                    } else if (base.profile().equals("frail")) {
                        require(profiles(outcome).equals(Set.of("frail", "fatigue")), "原謹慎替補休整，避免重複");
                        sawFrail = true;
                    }
                }
            }
            require(weightSum == denominator, "4096 完整六爻組合的機率合計恰為一");
            require(rareCount == 64 && rareWeightSum * 4096 == denominator, "所有皆變合計機率為 1/4096，須與單一排列機率區分");
        }
        require(sawNeutral && sawGuard && sawFrail, "中性、正向守護與負向謹慎分支均有真實資料案例");
        require(yarrowBands.equals(Set.of(2.5, 3.0, 3.5, 4.0)), "蓍草的實際稀有半級分布");
    }

    private static void limitsAndValidation() {
        require(RareFortune.rarityFactor(1, 2) == 2, "高機率輸入仍限最低稀有倍率 2");
        require(RareFortune.rarityFactor(1, Long.MAX_VALUE) == 4, "極低機率輸入限最高稀有倍率 4");
        expect(IllegalArgumentException.class, () -> RareFortune.rarityFactor(0, 8), "零機率拒絕");
        expect(IllegalArgumentException.class, () -> RareFortune.rarityFactor(2, 1), "超過一機率拒絕");
        expect(NullPointerException.class, () -> RareFortune.resolve(null, CastingMaterial.IRON), "空結果拒絕");
        expect(NullPointerException.class, () -> RareFortune.resolve(cast(Method.COINS, 7, 7, 7, 7, 7, 7), null), "空材質拒絕");
        expect(IllegalArgumentException.class, () -> RareFortune.resolve(cast(Method.COINS, 7, 7, 7, 7, 7, 7), CastingMaterial.BAMBOO), "錯配筮法與材質拒絕");
        RareFortune.Outcome result = resolve(Method.COINS, 9, 9, 9, 9, 9, 9);
        expect(UnsupportedOperationException.class, () -> result.rules().clear(), "規則清單不可被呼叫者變更");
        List<Library.Rule> input = new ArrayList<>(result.rules());
        RareFortune.Outcome copied = new RareFortune.Outcome(result.name(), true, input,
            result.numerator(), result.denominator(), result.rarityFactor());
        input.clear();
        require(copied.rules().size() == 2, "Outcome 防禦複製規則清單");
        expect(IllegalArgumentException.class, () -> new RareFortune.Outcome("常規", false, copied.rules(), 2, 4, 1), "未約分機率拒絕");
        expect(IllegalArgumentException.class, () -> new RareFortune.Outcome("稀有", true, copied.rules(), 1, 4, 4.5), "超出已註冊倍率範圍拒絕");
    }

    private static RareFortune.Outcome resolve(Method method, int... values) {
        return RareFortune.resolve(cast(method, values), CastingMaterial.defaultFor(method));
    }
    private static CastResult cast(Method method, int... values) {
        List<Integer> lines = new ArrayList<>(values.length);
        for (int value : values) lines.add(value);
        return CastingEngine.fromLines(method, lines);
    }
    private static Set<String> profiles(RareFortune.Outcome outcome) {
        Set<String> result = new HashSet<>();
        outcome.rules().forEach(rule -> result.add(rule.profile()));
        return result;
    }
    private static void fraction(RareFortune.Outcome outcome, long numerator, long denominator) {
        require(outcome.numerator() == numerator && outcome.denominator() == denominator, "完整六爻確切機率 " + numerator + "/" + denominator);
    }
    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-12, message);
    }
    private static void expect(Class<? extends Throwable> type, Runnable action, String message) {
        try { action.run(); }
        catch (Throwable failure) { require(type.isInstance(failure), message); return; }
        throw new AssertionError(message);
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

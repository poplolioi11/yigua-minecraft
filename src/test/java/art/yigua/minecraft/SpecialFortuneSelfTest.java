package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.CastingEngine;
import art.yigua.core.Method;
import java.util.List;

/** 驗證獨立稀有閘門，避免可控制的手動爻值直接保證極端獎懲。 */
public final class SpecialFortuneSelfTest {
    private static int checks;
    public static void main(String[] args) {
        Library.load();
        for (Method method : Method.values()) {
            CastResult good = CastingEngine.fromLines(method, List.of(6, 6, 6, 9, 9, 9));
            CastResult bad = CastingEngine.fromLines(method, List.of(9, 9, 9, 6, 6, 6));
            CastResult nine = CastingEngine.fromLines(method, List.of(9, 9, 9, 9, 9, 9));
            counts(good, 64, 0, 16, 0, 8, 0, 0, 0, 0);
            counts(bad, 0, 1, 0, 0, 0, 0, 0, 0, 8);
            counts(nine, 0, 0, 16, 16, 0, 0, 0, 0, 0);
            counts(allMoving(method, 53), 0, 0, 16, 0, 0, 8, 0, 0, 0);
            counts(allMoving(method, 13), 0, 0, 16, 0, 0, 0, 8, 0, 0);
            counts(allMoving(method, 59), 0, 0, 16, 0, 0, 0, 0, 1, 0);
            // 公平三錢下，一個否→泰排列加獨立神光門檻：1 / 16,777,216。
            if (method == Method.COINS) {
                RareFortune.Outcome fortune = RareFortune.resolve(good, CastingMaterial.COPPER);
                require(fortune.numerator() == 1 && fortune.denominator() * 64 == 16777216,
                    "神光完整標準三錢機率；人工選分堆不能套用此無條件機率");
            }
            for (int code = 0; code < 4096; code++) {
                int value = code;
                java.util.ArrayList<Integer> lines = new java.util.ArrayList<>(6);
                for (int index = 0; index < 6; index++) { lines.add(6 + (value & 3)); value >>= 2; }
                CastResult cast = CastingEngine.fromLines(method, lines);
                RareFortune.Outcome fortune = RareFortune.resolve(cast, CastingMaterial.defaultFor(method));
                if (cast.changingLines().size() < 6) {
                    require(SpecialFortune.select(cast, fortune, 0) == SpecialFortune.Event.NONE,
                        "普通與部分動爻結果不觸發極端事件");
                }
                require(SpecialFortune.select(cast, fortune, 121) == SpecialFortune.Event.NONE,
                    "稀有六爻亦不保證事件，門檻外全部正常");
                require(SpecialFortune.select(cast, fortune, 4095) == SpecialFortune.Event.NONE,
                    "抽樣上界不得觸發");
            }
        }
        CastResult good = CastingEngine.fromLines(Method.COINS, List.of(6, 6, 6, 9, 9, 9));
        RareFortune.Outcome outcome = RareFortune.resolve(good, CastingMaterial.COPPER);
        reject(() -> SpecialFortune.select(good, outcome, -1));
        reject(() -> SpecialFortune.select(good, outcome, 4096));
        System.out.println("神秘事件驗收通過：" + checks + " 項；獨立低機率閘門、普通結果排除、兩種筮法與完整六爻組合。");
    }

    private static CastResult allMoving(Method method, int number) {
        int mask = CastingEngine.maskForNumber(number);
        java.util.ArrayList<Integer> values = new java.util.ArrayList<>();
        for (int line = 0; line < 6; line++) values.add((mask & (1 << line)) == 0 ? 6 : 9);
        return CastingEngine.fromLines(method, values);
    }

    private static void counts(CastResult cast, int... expected) {
        RareFortune.Outcome fortune = RareFortune.resolve(cast, CastingMaterial.legacyFor(cast.method()));
        int[] counts = new int[SpecialFortune.Event.values().length];
        for (int draw = 0; draw < SpecialFortune.DRAW_SIZE; draw++) {
            counts[SpecialFortune.select(cast, fortune, draw).ordinal()]++;
        }
        require(expected.length == counts.length - 1, "每種事件有明確的門檻計數");
        for (int index = 1; index < counts.length; index++) {
            require(counts[index] == expected[index - 1], "神秘事件閘門精確計數：" + SpecialFortune.Event.values()[index]);
        }
        require(java.util.Arrays.stream(counts).sum() == 4096, "事件互斥且機率總和為一");
    }
    private static void reject(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("不合法抽樣應拒絕");
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

package art.yigua.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/** 不依賴 Minecraft 或第三方測試框架；以 main 執行即可驗收計算核心。 */
public final class CoreSelfTest {
    private static int checks;

    private CoreSelfTest() {}

    public static void main(String[] arguments) {
        probabilityModels();
        kingWenBijection();
        knownReadings();
        exhaustiveSixLines();
        randomDispatch();
        immutableResults();
        invalidInputs();
        System.out.println("計算核心驗收通過：" + checks + " 項檢查，涵蓋 4096 種六爻組合。");
    }

    private static void probabilityModels() {
        int[] coins = new int[4];
        for (int outcome = 0; outcome < 8; outcome++) {
            coins[CastingEngine.coinLine(outcome) - 6]++;
        }
        check(Arrays.equals(coins, new int[] {1, 3, 3, 1}), "三錢精確權重應為 1／3／3／1");
        equal(CastingEngine.coinLine(0), 6, "三枚陰面為老陰");
        equal(CastingEngine.coinLine(7), 9, "三枚陽面為老陽");
        equal(CastingEngine.coinLine(1), 7, "一枚陽面為少陽");
        equal(CastingEngine.coinLine(3), 8, "兩枚陽面為少陰");

        int[] yarrow = new int[4];
        for (int outcome = 0; outcome < 16; outcome++) {
            yarrow[CastingEngine.yarrowLine(outcome) - 6]++;
        }
        check(Arrays.equals(yarrow, new int[] {1, 5, 7, 3}), "蓍草精確權重應為 1／5／7／3");
        equal(CastingEngine.yarrowLine(0), 6, "四十九減九、八、八得二十四策");
        equal(CastingEngine.yarrowLine(15), 9, "四十九減五、四、四得三十六策");
    }

    private static void kingWenBijection() {
        Set<Integer> numbers = new HashSet<>();
        Set<Integer> masks = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int mask = 0; mask < 64; mask++) {
            int number = CastingEngine.numberForMask(mask);
            check(number >= 1 && number <= 64, "卦序須介於 1 與 64");
            equal(CastingEngine.maskForNumber(number), mask, "卦象往返不應改變");
            numbers.add(number);
        }
        for (int number = 1; number <= 64; number++) {
            int mask = CastingEngine.maskForNumber(number);
            equal(CastingEngine.numberForMask(mask), number, "卦序往返不應改變");
            masks.add(mask);
            names.add(CastingEngine.nameForNumber(number));
        }
        equal(numbers.size(), 64, "所有卦序皆出現且不重複");
        equal(masks.size(), 64, "所有六位元卦象皆出現且不重複");
        equal(names.size(), 64, "所有卦名皆出現且不重複");

        // 八個純卦及幾個上下卦互換的例子，避免只測到錯誤資料的自我往返。
        int[][] anchors = {
            {1, 0b111111}, {2, 0b000000}, {29, 0b010010}, {30, 0b101101},
            {51, 0b001001}, {52, 0b100100}, {57, 0b110110}, {58, 0b011011},
            {3, 0b010001}, {4, 0b100010}, {5, 0b010111}, {6, 0b111010},
            {11, 0b000111}, {12, 0b111000}, {63, 0b010101}, {64, 0b101010}
        };
        for (int[] anchor : anchors) {
            equal(CastingEngine.maskForNumber(anchor[0]), anchor[1], "已知文王卦序與上下卦");
        }
        equal(CastingEngine.nameForNumber(12), "否", "第十二卦卦名");
        equal(CastingEngine.nameForNumber(11), "泰", "第十一卦卦名");
        equal(CastingEngine.nameForNumber(63), "既濟", "第六十三卦卦名");
        equal(CastingEngine.nameForNumber(64), "未濟", "第六十四卦卦名");
    }

    private static void knownReadings() {
        CastResult piToTai = CastingEngine.fromLines(Method.YARROW, List.of(8, 8, 8, 9, 9, 9));
        equal(piToTai.primaryNumber(), 12, "否卦本卦");
        // 三個上爻由陽變陰後是坤，不是泰：這組爻值不能把下卦也翻轉。
        equal(piToTai.changedNumber(), 2, "否卦上三爻動，變為坤卦");
        equal(piToTai.changingLines(), List.of(4, 5, 6), "否卦上三爻動");

        CastResult allMovingPi = CastingEngine.fromLines(Method.YARROW, List.of(6, 6, 6, 9, 9, 9));
        equal(allMovingPi.primaryNumber(), 12, "否卦六爻皆動的本卦");
        equal(allMovingPi.changedNumber(), 11, "否卦六爻皆動才變為泰卦");
        equal(allMovingPi.changingLines(), List.of(1, 2, 3, 4, 5, 6), "六爻皆動");
        equal(allMovingPi.special(), "", "六爻皆動但非全六或全九，無用六或用九");

        CastResult nine = CastingEngine.fromLines(Method.COINS, List.of(9, 9, 9, 9, 9, 9));
        equal(nine.primaryNumber(), 1, "全九本卦乾");
        equal(nine.changedNumber(), 2, "全九之坤");
        equal(nine.special(), "用九", "全九讀法");

        CastResult six = CastingEngine.fromLines(Method.COINS, List.of(6, 6, 6, 6, 6, 6));
        equal(six.primaryNumber(), 2, "全六本卦坤");
        equal(six.changedNumber(), 1, "全六之乾");
        equal(six.special(), "用六", "全六讀法");

        CastResult stable = CastingEngine.fromLines(Method.COINS, List.of(7, 8, 7, 8, 7, 8));
        equal(stable.primaryNumber(), 63, "上下爻序不應反置");
        equal(stable.changedNumber(), 0, "無動爻時無變卦");
        equal(stable.changingLines(), List.of(), "無動爻清單");
        equal(stable.special(), "", "無特殊讀法");
    }

    private static void exhaustiveSixLines() {
        for (int encoded = 0; encoded < 4096; encoded++) {
            int remaining = encoded;
            List<Integer> values = new ArrayList<>(6);
            List<Integer> moving = new ArrayList<>(6);
            int original = 0;
            int transformed = 0;
            boolean allSix = true;
            boolean allNine = true;
            for (int index = 0; index < 6; index++) {
                int value = 6 + remaining % 4;
                remaining /= 4;
                values.add(value);
                if (value == 7 || value == 9) {
                    original += 1 << index;
                }
                if (value == 6 || value == 7) {
                    transformed += 1 << index;
                }
                if (value == 6 || value == 9) {
                    moving.add(index + 1);
                }
                allSix &= value == 6;
                allNine &= value == 9;
            }
            for (Method method : Method.values()) {
                CastResult result = CastingEngine.fromLines(method, values);
                equal(result.method(), method, "起卦方法須保留");
                equal(result.lineValues(), values, "爻值及初爻至上爻的順序須保留");
                equal(CastingEngine.maskForNumber(result.primaryNumber()), original, "本卦陰陽須符合爻值");
                equal(result.changingLines(), moving, "只有六與九是動爻");
                if (moving.isEmpty()) {
                    equal(result.changedNumber(), 0, "靜卦沒有變卦");
                } else {
                    equal(CastingEngine.maskForNumber(result.changedNumber()), transformed, "只翻轉動爻的陰陽");
                    check(result.primaryNumber() != result.changedNumber(), "有動爻時本卦與變卦不同");
                }
                equal(result.special(), allNine ? "用九" : allSix ? "用六" : "", "特殊讀法限定全九或全六");
            }
        }
    }

    private static void randomDispatch() {
        SequenceRandom coins = new SequenceRandom(8, new int[] {0, 1, 2, 3, 4, 7});
        CastResult coinResult = CastingEngine.cast(Method.COINS, coins);
        equal(coinResult.lineValues(), List.of(6, 7, 7, 8, 7, 9), "三錢亂數抽樣順序");
        equal(coins.calls, 6, "每卦恰做六次三錢抽樣");

        SequenceRandom yarrow = new SequenceRandom(16, new int[] {0, 1, 3, 4, 7, 15});
        CastResult yarrowResult = CastingEngine.cast(Method.YARROW, yarrow);
        equal(yarrowResult.lineValues(), List.of(6, 7, 8, 7, 9, 9), "蓍草亂數抽樣順序");
        equal(yarrow.calls, 6, "每卦恰做六次蓍草抽樣");
    }

    private static void immutableResults() {
        List<Integer> source = new ArrayList<>(List.of(6, 7, 8, 9, 7, 8));
        CastResult result = CastingEngine.fromLines(Method.COINS, source);
        source.set(0, 7);
        source.clear();
        equal(result.lineValues(), List.of(6, 7, 8, 9, 7, 8), "呼叫端修改輸入不應改變結果");
        expect(UnsupportedOperationException.class, () -> result.lineValues().set(0, 7), "爻值清單唯讀");
        expect(UnsupportedOperationException.class, () -> result.changingLines().add(2), "動爻清單唯讀");

        List<Integer> directValues = new ArrayList<>(result.lineValues());
        List<Integer> directMoving = new ArrayList<>(result.changingLines());
        CastResult direct = new CastResult(Method.COINS, directValues, result.primaryNumber(),
            result.changedNumber(), directMoving, result.special());
        directValues.clear();
        directMoving.clear();
        equal(direct, result, "公開結果建構子同樣須防禦複製");
    }

    private static void invalidInputs() {
        for (int invalid : new int[] {-1, 8, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            expect(IllegalArgumentException.class, () -> CastingEngine.coinLine(invalid), "非法三錢抽樣值");
        }
        for (int invalid : new int[] {-1, 16, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            expect(IllegalArgumentException.class, () -> CastingEngine.yarrowLine(invalid), "非法蓍草抽樣值");
        }
        for (int invalid : new int[] {-1, 64, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            expect(IllegalArgumentException.class, () -> CastingEngine.numberForMask(invalid), "非法卦象");
        }
        for (int invalid : new int[] {0, 65, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            expect(IllegalArgumentException.class, () -> CastingEngine.maskForNumber(invalid), "非法卦序");
            expect(IllegalArgumentException.class, () -> CastingEngine.nameForNumber(invalid), "非法卦名查詢");
        }
        expect(IllegalArgumentException.class, () -> CastingEngine.fromLines(Method.COINS, List.of()), "缺少六爻");
        expect(IllegalArgumentException.class, () -> CastingEngine.fromLines(Method.COINS, List.of(6, 7, 8, 9, 7)), "爻數不足");
        expect(IllegalArgumentException.class, () -> CastingEngine.fromLines(Method.COINS, List.of(6, 7, 8, 9, 7, 8, 9)), "爻數過多");
        expect(IllegalArgumentException.class, () -> CastingEngine.fromLines(Method.COINS, List.of(5, 7, 8, 9, 7, 8)), "爻值過小");
        expect(IllegalArgumentException.class, () -> CastingEngine.fromLines(Method.COINS, List.of(6, 7, 8, 9, 7, 10)), "爻值過大");
        expect(NullPointerException.class, () -> CastingEngine.fromLines(null, List.of(6, 7, 8, 9, 7, 8)), "缺少起卦方法");
        expect(NullPointerException.class, () -> CastingEngine.fromLines(Method.COINS, null), "缺少爻值清單");
        expect(NullPointerException.class, () -> CastingEngine.fromLines(Method.COINS, Arrays.asList(6, 7, null, 9, 7, 8)), "爻值不得為空");
        expect(NullPointerException.class, () -> CastingEngine.cast(Method.COINS, null), "缺少亂數來源");
        expect(NullPointerException.class, () -> CastingEngine.cast(null, new SequenceRandom(8, new int[6])), "亂數起卦缺少方法");

        List<Integer> nine = List.of(9, 9, 9, 9, 9, 9);
        List<Integer> sixMoving = List.of(1, 2, 3, 4, 5, 6);
        expect(IllegalArgumentException.class, () -> new CastResult(Method.COINS, nine, 2, 2, sixMoving, "用九"), "本卦與爻值矛盾");
        expect(IllegalArgumentException.class, () -> new CastResult(Method.COINS, nine, 1, 0, sixMoving, "用九"), "變卦與動爻矛盾");
        expect(IllegalArgumentException.class, () -> new CastResult(Method.COINS, nine, 1, 2, List.of(), "用九"), "動爻清單與爻值矛盾");
        expect(IllegalArgumentException.class, () -> new CastResult(Method.COINS, nine, 1, 2, sixMoving, "用六"), "特殊讀法與爻值矛盾");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object actual, Object expected, String message) {
        check(expected.equals(actual), message + "；預期=" + expected + "，實際=" + actual);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action, String message) {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError(message + "；錯誤例外類別=" + failure.getClass().getName(), failure);
        }
        throw new AssertionError(message + "；應拒絕輸入卻未拋出例外");
    }

    private static final class SequenceRandom implements RandomGenerator {
        private final int expectedBound;
        private final int[] sequence;
        private int calls;

        private SequenceRandom(int expectedBound, int[] sequence) {
            this.expectedBound = expectedBound;
            this.sequence = sequence.clone();
        }

        @Override
        public int nextInt(int bound) {
            equal(bound, expectedBound, "亂數上界必須符合方法");
            check(calls < sequence.length, "亂數呼叫次數不可超過六次");
            return sequence[calls++];
        }

        @Override
        public long nextLong() {
            throw new AssertionError("起卦不得繞過有界抽樣");
        }
    }
}

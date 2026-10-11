package art.yigua.minecraft;

import art.yigua.core.CastingEngine;
import art.yigua.core.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 手動分堆的完整可行路徑、十八步成卦、三錢面值與非法操作驗收。 */
public final class ManualCastingSelfTest {
    private static int checks;
    private static long paths;

    private ManualCastingSelfTest() {}

    public static void main(String[] args) {
        allYarrowPaths();
        manualEighteenChanges();
        invalidYarrowPreservesState();
        coins();
        immutableProgress();
        System.out.println("手動起卦驗收通過：" + checks + " 項檢查、" + paths
            + " 條完整三變分堆路徑；十八變、十八次投錢與非法輸入均驗證。");
    }

    private static void allYarrowPaths() {
        Set<Integer> reachable = new HashSet<>();
        Set<Integer> firstRemovals = new HashSet<>();
        Set<Integer> laterRemovals = new HashSet<>();
        for (int first = 1; first <= 47; first++) {
            ManualCasting.YarrowChange a = ManualCasting.yarrowChange(49, 1, first);
            inspectChange(a);
            firstRemovals.add(a.removed());
            check(a.lineValue() == 0, "前兩變不得提前成爻");
            for (int second = 1; second <= a.remainingAfter() - 2; second++) {
                ManualCasting.YarrowChange b = ManualCasting.yarrowChange(a.remainingAfter(), 2, second);
                inspectChange(b);
                laterRemovals.add(b.removed());
                check(b.lineValue() == 0, "第二變不得提前成爻");
                for (int third = 1; third <= b.remainingAfter() - 2; third++) {
                    ManualCasting.YarrowChange c = ManualCasting.yarrowChange(b.remainingAfter(), 3, third);
                    inspectChange(c);
                    laterRemovals.add(c.removed());
                    reachable.add(c.lineValue());
                    check(c.remainingAfter() == c.lineValue() * 4, "三變後四策為一，成爻值須吻合");
                    check(c.lineValue() >= 6 && c.lineValue() <= 9, "每條可行分堆路徑須得到六至九");
                    paths++;
                }
            }
        }
        check(firstRemovals.equals(Set.of(5, 9)), "第一變只歸奇五或九策");
        check(laterRemovals.equals(Set.of(4, 8)), "第二、三變只歸奇四或八策");
        check(reachable.equals(Set.of(6, 7, 8, 9)), "人工分堆可得到四種爻值");
        ManualCasting.YarrowChange divisible = ManualCasting.yarrowChange(49, 1, 4);
        check(divisible.leftRemainder() == 4 && divisible.rightRemainder() == 4,
            "整除四時保留四策，不能誤作零");
    }

    private static void inspectChange(ManualCasting.YarrowChange c) {
        check(c.left() + c.rightBeforeHang() == c.remainingBefore(), "分二守恆");
        check(c.rightAfterHang() == c.rightBeforeHang() - 1 && c.rightAfterHang() >= 1, "從右堆掛一且保留至少一根");
        check(c.leftRemainder() >= 1 && c.leftRemainder() <= 4
            && c.rightRemainder() >= 1 && c.rightRemainder() <= 4, "左右歸奇餘數介於一至四");
        check((c.left() - c.leftRemainder()) % 4 == 0
            && (c.rightAfterHang() - c.rightRemainder()) % 4 == 0, "左右以四揲策");
        check(c.removed() == 1 + c.leftRemainder() + c.rightRemainder(), "歸奇包含掛一與左右餘策");
        check(c.remainingBefore() == c.removed() + c.remainingAfter(), "歸奇後策數守恆");
        check(c.remainingAfter() % 4 == 0, "歸奇後策數為四的倍數");
    }

    private static void manualEighteenChanges() {
        List<Integer> target = List.of(6, 7, 8, 9, 6, 9);
        ManualCasting.Steps steps = new ManualCasting.Steps(Method.YARROW);
        int operations = 0;
        for (int line = 0; line < target.size(); line++) {
            check(steps.line() == line + 1 && steps.remaining() == 49 && steps.change() == 1,
                "每個新爻由初次變與四十九策開始");
            int[] choices = choicesFor(target.get(line));
            for (int change = 0; change < 3; change++) {
                check(steps.change() == change + 1, "三變依序進行");
                ManualCasting.YarrowChange result = steps.split(choices[change]);
                operations++;
                check(result.change() == change + 1, "步驟結果保留當次變次");
                check(steps.lines().size() == line + (change == 2 ? 1 : 0), "僅第三變收入爻值");
                if (change < 2) expectFailure(steps::result);
            }
            check(steps.lines().get(line).equals(target.get(line)), "實際人工分堆決定爻值，不重新隨機");
        }
        check(operations == 18 && steps.complete(), "十八變完整成卦");
        check(steps.result().equals(CastingEngine.fromLines(Method.YARROW, target)), "人工六爻正確交給共用卦象核心");
        Snapshot completed = snapshot(steps);
        expectFailure(() -> steps.split(1));
        check(snapshot(steps).equals(completed), "完成後重複分堆不改狀態");
        expectFailure(() -> steps.lines().add(7));
        expectFailure(() -> steps.coins().add(2));
        for (int value : List.of(6, 7, 8, 9)) {
            ManualCasting.Steps homogeneous = new ManualCasting.Steps(Method.YARROW);
            for (int line = 0; line < 6; line++) for (int choice : choicesFor(value)) homogeneous.split(choice);
            check(homogeneous.result().lineValues().equals(List.of(value, value, value, value, value, value)),
                "不同的人工分堆可重現各種完整六爻");
            if (value == 6) check(homogeneous.result().special().equals("用六"), "全老陰仍產生用六");
            if (value == 9) check(homogeneous.result().special().equals("用九"), "全老陽仍產生用九");
        }
    }

    private static int[] choicesFor(int value) {
        return switch (value) {
            case 6 -> new int[] {4, 4, 4};
            case 7 -> new int[] {4, 4, 1};
            case 8 -> new int[] {1, 4, 1};
            case 9 -> new int[] {1, 1, 1};
            default -> throw new AssertionError(value);
        };
    }

    private static void invalidYarrowPreservesState() {
        ManualCasting.Steps steps = new ManualCasting.Steps(Method.YARROW);
        for (int line = 0; line < 6; line++) {
            for (int change = 1; change <= 3; change++) {
                for (int invalid : new int[] {Integer.MIN_VALUE, -1, 0, steps.remaining() - 1,
                        steps.remaining(), Integer.MAX_VALUE}) {
                    Snapshot before = snapshot(steps);
                    expectFailure(() -> steps.split(invalid));
                    check(snapshot(steps).equals(before), "非法分堆不能修改進度");
                }
                Snapshot before = snapshot(steps);
                expectFailure(() -> steps.toss(2));
                check(snapshot(steps).equals(before), "竹籌不能接受投錢操作");
                steps.split(1);
            }
        }
        for (int[] invalid : new int[][] {
            {49, 0, 1}, {49, 4, 1}, {48, 1, 1}, {49, 2, 1}, {49, 3, 1},
            {36, 2, 1}, {44, 3, 1}, {-1, 1, 1}, {Integer.MAX_VALUE, 1, 1}
        }) expectFailure(() -> ManualCasting.yarrowChange(invalid[0], invalid[1], invalid[2]));
    }

    private static void coins() {
        int[] frequencies = new int[4];
        for (int mask = 0; mask < 8; mask++) {
            List<Integer> faces = new ArrayList<>();
            for (int coin = 0; coin < 3; coin++) faces.add((mask & (1 << coin)) == 0 ? 2 : 3);
            int value = ManualCasting.coinLine(faces);
            check(value == CastingEngine.coinLine(mask), "三次公平面值與既有八種三錢結果一致");
            frequencies[value - 6]++;
        }
        check(Arrays.equals(frequencies, new int[] {1, 3, 3, 1}), "三枚獨立公平錢保留一比三比三比一");
        for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(2), List.of(2, 3),
                List.of(2, 2, 2, 2), List.of(1, 2, 3), List.of(2, 3, 4))) {
            expectFailure(() -> ManualCasting.coinLine(invalid));
        }
        expectFailure(() -> ManualCasting.coinLine(null));
        expectFailure(() -> ManualCasting.coinLine(Arrays.asList(2, null, 3)));
        ManualCasting.Steps steps = new ManualCasting.Steps(Method.COINS);
        List<Integer> expected = List.of(6, 7, 8, 9, 7, 8);
        int throwsCount = 0;
        for (int line = 0; line < 6; line++) {
            check(steps.line() == line + 1 && steps.coins().isEmpty(), "每爻開始前沒有殘留錢值");
            int threes = expected.get(line) - 6;
            for (int coin = 0; coin < 3; coin++) {
                for (int invalid : new int[] {Integer.MIN_VALUE, 0, 1, 4, Integer.MAX_VALUE}) {
                    Snapshot before = snapshot(steps);
                    expectFailure(() -> steps.toss(invalid));
                    check(snapshot(steps).equals(before), "非法面值不改狀態");
                }
                Snapshot before = snapshot(steps);
                expectFailure(() -> steps.split(1));
                check(snapshot(steps).equals(before), "三錢不能接受分堆操作");
                int face = coin < threes ? 3 : 2;
                ManualCasting.CoinToss toss = steps.toss(face);
                throwsCount++;
                check(toss.face() == face && toss.coins().size() == coin + 1, "逐枚保留投錢面值");
                check(toss.lineCompleted() == (coin == 2), "只有第三枚錢成爻");
                if (coin < 2) {
                    check(steps.coins().size() == coin + 1, "未成爻前保留當前錢值");
                    expectFailure(steps::result);
                } else {
                    check(toss.sum() == expected.get(line) && steps.lines().get(line).equals(expected.get(line)),
                        "三次和決定該爻值，不再抽樣");
                    check(steps.coins().isEmpty(), "成爻後清空當前錢值供下一爻使用");
                }
                expectFailure(() -> toss.coins().add(2));
            }
        }
        check(throwsCount == 18 && steps.complete(), "十八次逐枚投錢成卦");
        check(steps.result().equals(CastingEngine.fromLines(Method.COINS, expected)), "人工投錢六爻正確交給卦象核心");
        Snapshot completed = snapshot(steps);
        expectFailure(() -> steps.toss(2));
        check(snapshot(steps).equals(completed), "完成後不接受更多投錢");
    }

    private static void immutableProgress() {
        List<Integer> lines = new ArrayList<>(List.of(7, 8));
        List<Integer> coins = new ArrayList<>(List.of(2));
        ManualCasting.Progress progress = new ManualCasting.Progress("coins", true, 3, 2, 0,
            lines, coins, true, "繼續投錢");
        lines.clear(); coins.add(3);
        check(progress.lines().equals(List.of(7, 8)) && progress.coins().equals(List.of(2)), "進度對清單做防禦複製");
        expectFailure(() -> progress.lines().add(6));
        expectFailure(() -> progress.coins().add(3));
    }

    private record Snapshot(int line, int change, int remaining, List<Integer> lines, List<Integer> coins, boolean complete) {}

    private static Snapshot snapshot(ManualCasting.Steps steps) {
        return new Snapshot(steps.line(), steps.change(), steps.remaining(), steps.lines(), steps.coins(), steps.complete());
    }

    private static void expectFailure(Runnable operation) {
        checks++;
        try { operation.run(); } catch (IllegalArgumentException | IllegalStateException
                | UnsupportedOperationException | NullPointerException expected) { return; }
        throw new AssertionError("預期拒絕非法操作");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

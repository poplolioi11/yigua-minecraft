package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.CastingEngine;
import art.yigua.core.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 完整枚舉動爻追加：原爻對應、確定結果、去重、上限與後段清除。 */
public final class MovingFortuneSelfTest {
    private static int checks;
    public static void main(String[] args) {
        Library.load();
        boolean[] positions = new boolean[6];
        for (Method method : Method.values()) {
            for (int code = 0; code < 4096; code++) {
                int remaining = code;
                List<Integer> lines = new ArrayList<>();
                for (int index = 0; index < 6; index++) { lines.add(6 + (remaining & 3)); remaining >>= 2; }
                CastResult cast = CastingEngine.fromLines(method, lines);
                if (cast.changingLines().size() == 6) continue;
                CastingMaterial material = CastingMaterial.legacyFor(method);
                var outcome = RareFortune.resolve(cast, material);
                require(!outcome.rare() && outcome.rarityFactor() == 1, "動爻追加不是稀有倍數");
                require(outcome.equals(RareFortune.resolve(cast, material)), "相同實際六爻不得重抽追加結果");
                require(outcome.rules().getFirst().equals(material.scaleRule(Library.rule(cast))), "原爻覆寫與優先序完整保留");
                int size = outcome.rules().size();
                if (cast.changingLines().isEmpty()) require(size == 1, "不動爻沒有追加");
                else require(size >= 2 && size <= (cast.changingLines().size() <= 2 ? 2 : 3), "部分動爻確實追加且不無限疊加");
                var profiles = new HashSet<String>();
                for (var rule : outcome.rules()) {
                    require(profiles.add(rule.profile()), "相同類型不得重複疊加");
                    require(rule.durationTicks() == 12000 && Double.isFinite(rule.amount()), "效果有界且不延長時間");
                    if (!rule.id().startsWith("moving_")) continue;
                    require(cast.changingLines().contains(rule.line()), "僅實際動爻能產生追加");
                    require(rule.origText().equals(Library.hex(cast.primaryNumber()).lines().get(rule.line() - 1)), "追加保留對應原爻完整文字");
                    require(rule.rationale().contains("遊戲") && rule.rationale().contains("不表示"), "遊戲追加與傳統解釋分開");
                    positions[rule.line() - 1] = true;
                }
                if (cast.changedNumber() != 0) {
                    var plan = FortunePhases.resolve(cast, material, 1);
                    var expectedFirst = outcome.rules().stream().map(rule -> new Library.Rule(
                        rule.id(), rule.number(), rule.line(), rule.profile(), rule.amount(),
                        FortunePhases.HALF_TICKS, rule.priority(), rule.rationale(), rule.origText())).toList();
                    require(plan.primary().equals(expectedFirst), "前段完整使用動爻追加且持續五分鐘");
                    require(plan.changed().stream().noneMatch(rule -> rule.id().startsWith("moving_")), "穩定變卦後段不保留前段追加");
                }
            }
        }
        for (boolean position : positions) require(position, "六個爻位皆能實際影響效果");
        System.out.println("動爻追加驗收通過：" + checks + " 項；完整六爻枚舉、原文對應、追加上限、確定性與五分鐘換段。");
    }
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

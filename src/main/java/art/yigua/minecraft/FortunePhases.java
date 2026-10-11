package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.CastingEngine;
import java.util.ArrayList;
import java.util.List;

/** 一次起卦固定的兩段效果，切換不重抽、不刷新圖鑑與冷卻。 */
public final class FortunePhases {
    public static final int HALF_TICKS = 6000;
    public static final int TOTAL_TICKS = 12000;
    public record Plan(List<Library.Rule> primary, List<Library.Rule> changed) {
        public Plan { primary = List.copyOf(primary); changed = List.copyOf(changed); }
        public List<Library.Rule> current(int remaining) {
            return !changed.isEmpty() && remaining <= HALF_TICKS ? changed : primary;
        }
    }
    private FortunePhases() {}

    public static Plan resolve(CastResult result, CastingMaterial material, double environment) {
        RareFortune.Outcome fortune = RareFortune.resolve(result, material);
        int firstDuration = result.changedNumber() == 0 ? TOTAL_TICKS : HALF_TICKS;
        List<Library.Rule> primary = fortune.rules().stream()
            .map(rule -> duration(EnvironmentContext.scale(rule, environment), firstDuration)).toList();
        if (result.changedNumber() == 0) return new Plan(primary, List.of());
        int mask = CastingEngine.maskForNumber(result.changedNumber());
        List<Integer> stable = new ArrayList<>(6);
        for (int index = 0; index < 6; index++) stable.add((mask & (1 << index)) == 0 ? 8 : 7);
        CastResult changed = CastingEngine.fromLines(result.method(), stable);
        Library.Rule changedRule = material.scaleRule(Library.rule(changed));
        if (fortune.rare()) changedRule = EnvironmentContext.scale(changedRule, fortune.rarityFactor());
        changedRule = duration(EnvironmentContext.scale(changedRule, environment), HALF_TICKS);
        return new Plan(primary, List.of(changedRule));
    }

    private static Library.Rule duration(Library.Rule rule, int ticks) {
        return new Library.Rule(rule.id(), rule.number(), rule.line(), rule.profile(), rule.amount(),
            ticks, rule.priority(), rule.rationale(), rule.origText());
    }
}

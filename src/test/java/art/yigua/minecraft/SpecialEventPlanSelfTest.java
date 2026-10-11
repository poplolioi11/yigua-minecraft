package art.yigua.minecraft;

/** 動畫時序與跨來源增益所有權的確定性驗收。 */
public final class SpecialEventPlanSelfTest {
    public static void main(String[] args) {
        int checks = 0;
        require(SpecialEventPlan.boltCount() == 6, "天雷應有六道影像"); checks++;
        int previous = -1;
        for (int index = 0; index < SpecialEventPlan.boltCount(); index++) {
            int age = SpecialEventPlan.boltAge(index);
            require(age > previous && age < SpecialEventPlan.THUNDER_VISUAL_TICKS, "雷光時間順序與上限");
            previous = age; checks++;
        }
        require(SpecialEventPlan.thunderDamageAge() > previous && SpecialEventPlan.thunderDamageAge() < SpecialEventPlan.THUNDER_VISUAL_TICKS,
            "六道演出後在有效期間只結算一次傷害"); checks++;
        double height = 32;
        for (int age = -2; age <= 150; age++) {
            double next = SpecialEventPlan.beamBottom(age);
            require(next >= 0 && next <= 32 && next <= height, "神光下緣必須緩降且不穿地");
            height = next; checks++;
        }
        require(SpecialEventPlan.beamBottom(0) == 32 && SpecialEventPlan.beamBottom(100) == 0, "從天至地"); checks++;
        require(SpecialEventPlan.ownedDuration(300, 0, 300), "可辨認自有時長"); checks++;
        require(!SpecialEventPlan.ownedDuration(301, 1, 300), "外部提高等級時保留"); checks++;
        require(!SpecialEventPlan.ownedDuration(1200, 0, 300), "外部延長時保留"); checks++;
        require(!SpecialEventPlan.ownedDuration(50, 0, 300), "外部縮短或替換時保留"); checks++;
        require(!SpecialEventPlan.ownedDuration(-1, 0, 300), "永久狀態保留"); checks++;
        require(!SpecialEventPlan.ownedDuration(1, 0, 0), "已過期不得移除其他來源"); checks++;
        require(SpecialEventPlan.AURA_TICKS == 20 * 120, "正向稀有效果持續兩分鐘"); checks++;
        require(SpecialEventPlan.OBSTRUCTION_TICKS == 20 * 60, "障路獨立一分鐘"); checks++;
        require(SpecialEventPlan.thunderDamageAge() == 77, "延長正向狀態不改天雷時序"); checks++;
        int coveredUntil = 0, stages = 0, fiveParticles = 0, flowerParticles = 0;
        for (int age = 0; age < SpecialEventPlan.AURA_TICKS; age++) {
            if (SpecialEventPlan.refreshStage(age)) {
                int duration = SpecialEventPlan.stageDuration(age);
                require(duration > 0 && duration <= 1200 && age + duration <= SpecialEventPlan.AURA_TICKS,
                    "短封包期限與稀有總期限都必須有界"); checks++;
                if (age > 0) { require(age < coveredUntil - 20, "續播必須在前段淡出之前"); checks++; }
                coveredUntil = age + duration; stages++;
            }
            require(age < coveredUntil, "兩分鐘期間不得出現視覺斷流"); checks++;
            var five = SpecialEventPlan.auraParticles(false, age, 1.8);
            var flowers = SpecialEventPlan.auraParticles(true, age, 1.8);
            boolean emits = SpecialEventPlan.emitAuraParticles(age);
            require(five.size() == (emits ? 15 : 0) && flowers.size() == (emits ? 21 : 0), "每輪粒子預算固定"); checks++;
            for (var point : java.util.stream.Stream.concat(five.stream(), flowers.stream()).toList()) {
                require(Double.isFinite(point.x()) && Double.isFinite(point.y()) && Double.isFinite(point.z())
                    && Math.abs(point.x()) <= 1 && Math.abs(point.z()) <= 1 && point.y() >= 0 && point.y() <= 2.5,
                    "循環粒子不得離開玩家附近或帶有非法座標"); checks++;
            }
            fiveParticles += five.size(); flowerParticles += flowers.size();
        }
        require(stages == 6 && coveredUntil == SpecialEventPlan.AURA_TICKS, "六次分段在兩分鐘準時結束"); checks++;
        require(fiveParticles == 9000 && flowerParticles == 12600, "每秒最多75或105粒子，不逐刻無限累積"); checks++;
        for (int age : new int[] {-1, 2400, 2401, Integer.MAX_VALUE}) {
            require(SpecialEventPlan.stageDuration(age) == 0 && !SpecialEventPlan.emitAuraParticles(age)
                && SpecialEventPlan.auraParticles(false, age, 1.8).isEmpty(), "期限外不續播或發粒子"); checks++;
        }
        require(!SpecialEventPlan.auraParticles(false, 0, 1.8).equals(SpecialEventPlan.auraParticles(false, 404, 1.8)),
            "五氣在二十秒之後仍有不同軌跡"); checks++;
        require(!SpecialEventPlan.auraParticles(true, 0, 1.8).equals(SpecialEventPlan.auraParticles(true, 2004, 1.8)),
            "光花在第二分鐘仍持續旋轉"); checks++;
        Object ours = new Object(), replacement = new Object();
        require(SpecialEventPlan.owns(ours, ours, 2000, 0, 2000), "可撤回本人授予的精確實例"); checks++;
        require(!SpecialEventPlan.owns(replacement, ours, 2000, 0, 2000), "外部相同等級與時長的新實例也要保留"); checks++;
        require(!SpecialEventPlan.owns(ours, ours, 4000, 0, 2000), "外部延長同一實例仍要保留"); checks++;
        require(!SpecialEventPlan.owns(ours, ours, 2000, 1, 2000), "外部提高同一實例強度仍要保留"); checks++;
        require(!SpecialEventPlan.owns(null, ours, 2000, 0, 2000), "被清除後不可誤認為本人狀態"); checks++;
        require(!SpecialEventPlan.owns(ours, ours, 1, 0, 0), "到期後不回收其他來源"); checks++;
        System.out.println("稀有時序驗收通過：" + checks + " 項。");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

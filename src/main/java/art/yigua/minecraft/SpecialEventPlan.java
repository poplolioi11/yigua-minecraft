package art.yigua.minecraft;

/** 有界的稀有動畫時序；追加雷光不追加致命傷害抽樣。 */
public final class SpecialEventPlan {
    public static final int AURA_TICKS = 2400;
    public static final int OBSTRUCTION_TICKS = 1200;
    public static final int AURA_STAGE_TICKS = 600;
    public static final int AURA_REFRESH_TICKS = 400;
    public static final int PARTICLE_INTERVAL = 4;
    public static final int DIVINE_VISUAL_TICKS = 120;
    public static final int THUNDER_VISUAL_TICKS = 160;
    private static final int[] BOLT_TICKS = {0, 10, 20, 35, 50, 65};
    private SpecialEventPlan() {}

    public record AuraParticle(double x, double y, double z, int color) {}
    private static final int[] FIVE_COLORS = {0x86D9C5, 0xF49A88, 0xEBD681, 0xD9EBF4, 0x98ACDF};

    /** 短封包分段延續視覺，維持既有封包的 1200 刻上限。 */
    public static int stageDuration(int age) {
        return age < 0 || age >= AURA_TICKS ? 0 : Math.min(AURA_STAGE_TICKS, AURA_TICKS - age);
    }
    public static boolean refreshStage(int age) {
        return age >= 0 && age < AURA_TICKS && age % AURA_REFRESH_TICKS == 0;
    }
    public static boolean emitAuraParticles(int age) {
        return age >= 0 && age < AURA_TICKS && age % PARTICLE_INTERVAL == 0;
    }

    /** 五條螺旋氣流或頭頂三朵六瓣光花；每輪最多 21 顆粒子。 */
    public static java.util.List<AuraParticle> auraParticles(boolean flowers, int age, double height) {
        if (!emitAuraParticles(age)) return java.util.List.of();
        var points = new java.util.ArrayList<AuraParticle>(21);
        if (!flowers) {
            for (int color = 0; color < FIVE_COLORS.length; color++) {
                for (int trail = 0; trail < 3; trail++) {
                    double angle = age * 0.055 + color * Math.PI * 2 / 5 - trail * 0.22;
                    double rise = ((age + color * 24 + trail * 6) % 120) / 120.0;
                    double radius = 0.65 + 0.2 * Math.sin(rise * Math.PI);
                    points.add(new AuraParticle(Math.cos(angle) * radius, 0.15 + rise * (height + 0.5),
                        Math.sin(angle) * radius, FIVE_COLORS[color]));
                }
            }
        } else {
            for (int flower = 0; flower < 3; flower++) {
                double orbit = age * 0.014 + flower * Math.PI * 2 / 3;
                double centerX = Math.cos(orbit) * 0.42, centerZ = Math.sin(orbit) * 0.42;
                double y = height + 0.4 + Math.sin(age * 0.05 + flower) * 0.08;
                for (int petal = 0; petal < 6; petal++) {
                    double angle = petal * Math.PI / 3 + age * 0.025;
                    points.add(new AuraParticle(centerX + Math.cos(angle) * 0.22, y,
                        centerZ + Math.sin(angle) * 0.22, FIVE_COLORS[flower]));
                }
                points.add(new AuraParticle(centerX, y + 0.03, centerZ, 0xFFE1A0));
            }
        }
        return java.util.List.copyOf(points);
    }

    public static int boltCount() { return BOLT_TICKS.length; }
    public static int boltAge(int index) { return BOLT_TICKS[index]; }
    public static int thunderDamageAge() { return BOLT_TICKS[BOLT_TICKS.length - 1] + 12; }
    public static double beamBottom(int age) {
        double t = Math.clamp(age / 100.0, 0, 1);
        return 32 * (1 - t * t * (3 - 2 * t));
    }
    /** 保留由他處延長、改強度或替換的同名狀態。 */
    public static boolean ownedDuration(int remaining, int amplifier, int expectedRemaining) {
        return amplifier == 0 && expectedRemaining > 0
            && Math.abs((long)remaining - expectedRemaining) <= 1;
    }
    public static boolean owns(Object active, Object granted, int remaining, int amplifier, int expectedRemaining) {
        return active != null && active == granted && ownedDuration(remaining, amplifier, expectedRemaining);
    }
}

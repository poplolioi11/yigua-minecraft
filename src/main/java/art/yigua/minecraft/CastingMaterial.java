package art.yigua.minecraft;

import art.yigua.core.Method;
import java.math.BigDecimal;
import java.util.Objects;

/** 材質只調整遊戲效果強度，不改變筮法、卦象機率或持續時間。 */
public enum CastingMaterial {
    WOOD(0.5, "木錢", "wood"),
    IRON(0.75, "鐵錢", "iron"),
    COPPER(1, "褐銅錢", "copper"),
    GREEN_COPPER(1.25, "綠銅錢", "green_copper"),
    GOLD(1.5, "金錢", "gold"),
    BAMBOO(1.5, "竹籌", "bamboo"),
    WOOD_STALKS(1, "木籌", "wood_stalks"),
    GRASS_STALKS(0.5, "草籌", "grass_stalks"),
    LEGACY_BAMBOO(1, "舊版竹籌", "legacy_bamboo"),
    LEGACY(1, "舊版三錢", "legacy");

    private final double multiplier;
    private final BigDecimal decimalMultiplier;
    private final String displayName;
    private final String id;

    CastingMaterial(double multiplier, String displayName, String id) {
        this.multiplier = multiplier;
        this.decimalMultiplier = BigDecimal.valueOf(multiplier);
        this.displayName = displayName;
        this.id = id;
    }

    public double multiplier() { return multiplier; }
    public String displayName() { return displayName; }
    public String id() { return id; }

    public boolean compatibleWith(Method method) {
        Objects.requireNonNull(method, "筮法不可為 null");
        Method required = switch (this) {
            case BAMBOO, WOOD_STALKS, GRASS_STALKS, LEGACY_BAMBOO -> Method.YARROW;
            default -> Method.COINS;
        };
        return method == required;
    }

    /** 以十進位相乘，避免 0.1 × 0.75 留下不必要的二進位誤差。 */
    public Library.Rule scaleRule(Library.Rule rule) {
        Objects.requireNonNull(rule, "規則不可為 null");
        double amount = BigDecimal.valueOf(rule.amount()).multiply(decimalMultiplier).doubleValue();
        return new Library.Rule(rule.id(), rule.number(), rule.line(), rule.profile(), amount,
            rule.durationTicks(), rule.priority(), rule.rationale(), rule.origText());
    }

    public static CastingMaterial defaultFor(Method method) {
        return switch (Objects.requireNonNull(method, "筮法不可為 null")) {
            case COINS -> IRON;
            case YARROW -> BAMBOO;
        };
    }

    public static CastingMaterial legacyFor(Method method) {
        return switch (Objects.requireNonNull(method, "筮法不可為 null")) {
            case COINS -> LEGACY;
            case YARROW -> LEGACY_BAMBOO;
        };
    }
}

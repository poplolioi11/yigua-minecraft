package art.yigua.minecraft;

import art.yigua.core.CastResult;
import art.yigua.core.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 以完整六爻組合判斷遊戲稀有事件，並保留該組合的確切機率。
 * 稀有倍率只限制數值幅度，不表示移速、挖掘、護甲等不同屬性的效用相等。
 */
public final class RareFortune {
    public record Outcome(String name, boolean rare, List<Library.Rule> rules,
            long numerator, long denominator, double rarityFactor) {
        public Outcome {
            Objects.requireNonNull(name, "事件名稱不得為空");
            rules = List.copyOf(Objects.requireNonNull(rules, "事件規則不得為空"));
            if (name.isBlank() || rules.isEmpty()) throw new IllegalArgumentException("事件必須有名稱與規則");
            validateProbability(numerator, denominator);
            if (gcd(numerator, denominator) != 1) throw new IllegalArgumentException("機率必須約分");
            if (!Double.isFinite(rarityFactor) || (!rare && rarityFactor != 1)
                    || (rare && (rarityFactor < 2 || rarityFactor > 4 || rarityFactor * 2 != Math.rint(rarityFactor * 2)))) {
                throw new IllegalArgumentException("稀有倍率必須是 2 至 4 的半級，常規倍率必須是 1");
            }
        }
    }

    private static final String RATIONALE = "這是完整六爻皆變的遊戲稀有事件加成，並非經文本意或現實吉凶判定。";

    private RareFortune() {}

    public static Outcome resolve(CastResult result, CastingMaterial material) {
        Objects.requireNonNull(result, "起卦結果不得為空");
        Objects.requireNonNull(material, "材質不得為空");
        if (!material.compatibleWith(result.method())) throw new IllegalArgumentException("材質與筮法不相容");
        long numerator = 1;
        long denominator = 1;
        for (int value : result.lineValues()) {
            numerator *= weight(result.method(), value);
            denominator *= result.method() == Method.COINS ? 8 : 16;
        }
        long divisor = gcd(numerator, denominator);
        numerator /= divisor;
        denominator /= divisor;
        Library.Rule base = Objects.requireNonNull(Library.rule(result), "必須先載入遊戲規則");
        if (result.changingLines().size() != 6) {
            return new Outcome(result.changingLines().isEmpty() ? "常規" : "動爻追加", false,
                MovingFortune.resolve(result, base, material), numerator, denominator, 1);
        }

        String name;
        String id;
        List<String> profiles;
        if (result.primaryNumber() == 12 && result.changedNumber() == 11) {
            name = "否極泰來"; id = "pi_to_tai";
            profiles = List.of("travel", "focus", "guard");
        } else if (result.primaryNumber() == 11 && result.changedNumber() == 12) {
            name = "泰極否來"; id = "tai_to_pi";
            profiles = List.of("caution", "fatigue", "frail");
        } else if (result.primaryNumber() == 1 && result.special().equals("用九")) {
            name = "群龍無首"; id = "qun_long_wu_shou";
            profiles = List.of("travel", "guard");
        } else if (result.primaryNumber() == 2 && result.special().equals("用六")) {
            name = "利永貞"; id = "li_yong_zhen";
            profiles = List.of("focus", "guard");
        } else {
            name = "六爻皆變"; id = "all_moving";
            boolean positive = base.amount() >= 0;
            String first = base.amount() == 0 ? "guard" : base.profile();
            String second = first.equals("guard") || first.equals("frail")
                ? (positive ? "focus" : "fatigue") : (positive ? "guard" : "frail");
            profiles = List.of(first, second);
        }
        double factor = rarityFactor(numerator, denominator);
        List<Library.Rule> rules = new ArrayList<>(profiles.size());
        for (String profile : profiles) {
            double amount = BigDecimal.valueOf(profileAmount(profile))
                .multiply(BigDecimal.valueOf(factor)).doubleValue();
            Library.Rule rare = new Library.Rule("rare_" + id + "_" + profile,
                result.primaryNumber(), 0, profile, amount, 12000, base.priority(), RATIONALE, "");
            rules.add(material.scaleRule(rare));
        }
        return new Outcome(name, true, rules, numerator, denominator, factor);
    }

    static double rarityFactor(long numerator, long denominator) {
        validateProbability(numerator, denominator);
        double informationPerLine = Math.log((double) denominator / numerator) / Math.log(2) / 6;
        return Math.clamp(Math.round(informationPerLine * 2) / 2.0, 2, 4);
    }

    private static int weight(Method method, int value) {
        return switch (method) {
            case COINS -> switch (value) {
                case 6, 9 -> 1;
                case 7, 8 -> 3;
                default -> throw new IllegalArgumentException("爻值必須是 6、7、8、9");
            };
            case YARROW -> switch (value) {
                case 6 -> 1; case 7 -> 5; case 8 -> 7; case 9 -> 3;
                default -> throw new IllegalArgumentException("爻值必須是 6、7、8、9");
            };
        };
    }

    private static double profileAmount(String profile) {
        double amount = Library.baseAmount(profile);
        if (!Double.isFinite(amount) || amount == 0) throw new IllegalArgumentException("稀有事件必須使用非中性效果");
        return amount;
    }

    private static void validateProbability(long numerator, long denominator) {
        if (numerator <= 0 || denominator <= 0 || numerator > denominator) {
            throw new IllegalArgumentException("機率必須介於零（不含）至一");
        }
    }

    private static long gcd(long first, long second) {
        while (second != 0) {
            long remainder = first % second;
            first = second;
            second = remainder;
        }
        return first;
    }
}

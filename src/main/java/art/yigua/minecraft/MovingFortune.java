package art.yigua.minecraft;

import art.yigua.core.CastResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 動爻附加的明示遊戲規則；不是把 384 爻自動解讀成傳統吉凶。 */
public final class MovingFortune {
    private static final String[][] POSITIVE = {
        {"leap", "steady"}, {"vitality", "breath"}, {"deepwork", "closecombat"},
        {"reach", "fireward"}, {"luck", "quick"}, {"lightness", "landing"}
    };
    private static final String[][] NEGATIVE = {
        {"heavyfeet", "exposed"}, {"depletion", "airloss"}, {"drownedwork", "restraint"},
        {"nearreach", "embers"}, {"illluck", "slowhand"}, {"heaviness", "fallrisk"}
    };
    private MovingFortune() {}

    public static List<Library.Rule> resolve(CastResult cast, Library.Rule base, CastingMaterial material) {
        if (cast.changingLines().size() >= 6) throw new IllegalArgumentException("六爻皆變應進入稀有規則");
        List<Library.Rule> rules = new ArrayList<>();
        rules.add(material.scaleRule(base));
        if (cast.changingLines().isEmpty()) return List.copyOf(rules);
        boolean positive = base.amount() >= 0;
        String[][] choices = positive ? POSITIVE : NEGATIVE;
        int limit = cast.changingLines().size() <= 2 ? 1 : 2;
        // 完整六爻決定輪序，不再投錢、不再分堆、不再抽亂數。
        int fingerprint = cast.primaryNumber();
        for (int value : cast.lineValues()) fingerprint = fingerprint * 31 + value;
        int start = Math.floorMod(fingerprint, cast.changingLines().size());
        Set<String> profiles = new HashSet<>();
        profiles.add(base.profile());
        for (int offset = 0; offset < cast.changingLines().size() && rules.size() <= limit; offset++) {
            int line = cast.changingLines().get((start + offset) % cast.changingLines().size());
            int value = cast.lineValues().get(line - 1);
            String profile = choices[line - 1][value == 6 ? 0 : 1];
            if (!profiles.add(profile)) continue;
            rules.add(addition(cast, line, value, profile, material));
        }
        if (rules.size() == 1) {
            int line = cast.changingLines().get(start);
            String fallback = positive ? "guard" : "frail";
            if (base.profile().equals(fallback)) fallback = positive ? "focus" : "fatigue";
            rules.add(addition(cast, line, cast.lineValues().get(line - 1), fallback, material));
        }
        return List.copyOf(rules);
    }

    private static Library.Rule addition(CastResult cast, int line, int value, String profile, CastingMaterial material) {
        return material.scaleRule(new Library.Rule("moving_" + cast.primaryNumber() + "_" + line + "_" + value + "_" + profile,
            cast.primaryNumber(), line, profile, Library.baseAmount(profile), 12000, 0,
            "動爻追加：依實際爻位、老陰／老陽與本次主要效果編排的遊戲變化；不表示此爻具有固定傳統吉凶。",
            Library.hex(cast.primaryNumber()).lines().get(line - 1)));
    }
}

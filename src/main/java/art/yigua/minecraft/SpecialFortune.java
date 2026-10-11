package art.yigua.minecraft;

import art.yigua.core.CastResult;

/** 書外的遊戲事件另抽一次；手動可選分堆，也不能保證取得極端獎懲。 */
public final class SpecialFortune {
    public enum Event { NONE, DIVINE, THUNDER, UNDYING, FIVE_QI, FLOWERS, BIRDS, RAPPORT, SEA }
    public static final int DRAW_SIZE = 4096;
    private SpecialFortune() {}

    public static Event select(CastResult cast, RareFortune.Outcome fortune, int draw) {
        if (draw < 0 || draw >= DRAW_SIZE) throw new IllegalArgumentException("事件抽樣超出範圍");
        if (!fortune.rare() || cast.changingLines().size() != 6) return Event.NONE;
        if (fortune.rules().stream().allMatch(rule -> rule.amount() < 0)) {
            return draw == 0 ? Event.THUNDER : Event.NONE;
        }
        if (fortune.rules().stream().anyMatch(rule -> rule.amount() < 0)) return Event.NONE;
        if (cast.primaryNumber() == 12 && cast.changedNumber() == 11 && draw < 64) return Event.DIVINE;
        if (draw >= 64 && draw < 80) return Event.UNDYING;
        // 以下為幻想玩法，名稱及機率不冒充經傳的傳統解卦規則。
        if (cast.primaryNumber() == 1 && draw >= 80 && draw < 96) return Event.FIVE_QI;
        if (cast.primaryNumber() == 12 && cast.changedNumber() == 11 && draw >= 96 && draw < 104) return Event.FLOWERS;
        if (cast.primaryNumber() == 53 && draw >= 104 && draw < 112) return Event.BIRDS;
        if (cast.primaryNumber() == 13 && draw >= 112 && draw < 120) return Event.RAPPORT;
        if (cast.primaryNumber() == 59 && draw == 120) return Event.SEA;
        return Event.NONE;
    }
}

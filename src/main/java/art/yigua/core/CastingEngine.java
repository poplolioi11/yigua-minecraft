package art.yigua.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 與遊戲平台無關的起卦核心。
 * 六爻輸入一律由初爻至上爻；六位元卦象中的 bit 0 是初爻，陽為 1、陰為 0。
 */
public final class CastingEngine {
    // 公共領域的文王卦序；索引 0 對應第一卦。這裡只存卦象事實，不存網站程式。
    private static final int[] MASKS = {
        63, 0, 17, 34, 23, 58, 2, 16,
        55, 59, 7, 56, 61, 47, 4, 8,
        25, 38, 3, 48, 41, 37, 32, 1,
        57, 39, 33, 30, 18, 45, 28, 14,
        60, 15, 40, 5, 53, 43, 20, 10,
        35, 49, 31, 62, 24, 6, 26, 22,
        29, 46, 9, 36, 52, 11, 13, 44,
        54, 27, 50, 19, 51, 12, 21, 42
    };

    private static final String[] NAMES = {
        "乾", "坤", "屯", "蒙", "需", "訟", "師", "比",
        "小畜", "履", "泰", "否", "同人", "大有", "謙", "豫",
        "隨", "蠱", "臨", "觀", "噬嗑", "賁", "剝", "復",
        "無妄", "大畜", "頤", "大過", "坎", "離", "咸", "恆",
        "遯", "大壯", "晉", "明夷", "家人", "睽", "蹇", "解",
        "損", "益", "夬", "姤", "萃", "升", "困", "井",
        "革", "鼎", "震", "艮", "漸", "歸妹", "豐", "旅",
        "巽", "兌", "渙", "節", "中孚", "小過", "既濟", "未濟"
    };

    private static final int[] NUMBERS = new int[64];

    static {
        if (MASKS.length != 64 || NAMES.length != 64) {
            throw new ExceptionInInitializerError("六十四卦資料不完整");
        }
        for (int index = 0; index < MASKS.length; index++) {
            int mask = MASKS[index];
            if (mask < 0 || mask >= 64 || NUMBERS[mask] != 0) {
                throw new ExceptionInInitializerError("六十四卦的卦象重複或無效");
            }
            NUMBERS[mask] = index + 1;
        }
    }

    private CastingEngine() {}

    /** 一次產生六爻。呼叫端提供亂數來源，便於遊戲伺服器控制及可重現測試。 */
    public static CastResult cast(Method method, RandomGenerator random) {
        Objects.requireNonNull(method, "起卦方法不得為空");
        Objects.requireNonNull(random, "亂數來源不得為空");
        List<Integer> values = new ArrayList<>(6);
        for (int line = 0; line < 6; line++) {
            values.add(switch (method) {
                case COINS -> coinLine(random.nextInt(8));
                case YARROW -> yarrowLine(random.nextInt(16));
            });
        }
        return fromLines(method, values);
    }

    /** 以既有六爻還原本卦、動爻及變卦；輸入順序必須由下往上。 */
    public static CastResult fromLines(Method method, List<Integer> lineValues) {
        Objects.requireNonNull(method, "起卦方法不得為空");
        Objects.requireNonNull(lineValues, "六爻不得為空");
        List<Integer> values = List.copyOf(lineValues);
        if (values.size() != 6) {
            throw new IllegalArgumentException("每卦必須恰有六爻");
        }
        int primaryMask = 0;
        int changedMask = 0;
        List<Integer> changingLines = new ArrayList<>(6);
        for (int index = 0; index < values.size(); index++) {
            int value = values.get(index);
            if (value < 6 || value > 9) {
                throw new IllegalArgumentException("爻值必須為六、七、八或九");
            }
            int yang = value & 1;
            boolean moving = value == 6 || value == 9;
            primaryMask |= yang << index;
            changedMask |= (moving ? yang ^ 1 : yang) << index;
            if (moving) {
                changingLines.add(index + 1);
            }
        }
        String special = values.stream().allMatch(value -> value == 9)
            ? "用九"
            : values.stream().allMatch(value -> value == 6) ? "用六" : "";
        return new CastResult(
            method,
            values,
            numberForMask(primaryMask),
            changingLines.isEmpty() ? 0 : numberForMask(changedMask),
            changingLines,
            special
        );
    }

    /** 三枚等可能的錢各取二或三，八種結果產生六／七／八／九的 1／3／3／1 權重。 */
    public static int coinLine(int outcome) {
        if (outcome < 0 || outcome >= 8) {
            throw new IllegalArgumentException("三錢抽樣值必須介於 0 與 7");
        }
        return 6 + Integer.bitCount(outcome);
    }

    /**
     * 蓍草三變的理想化餘數模型：五十策去一，以四十九策起算。
     * 第一變移除九或五的權重為 1／3；後兩變移除八或四各半。
     * 十六種等可能的餘數類別得到六／七／八／九的 1／5／7／3 權重。
     * 這不是把每次分堆的位置視為均勻，也不是讓六爻等機率出現。
     */
    public static int yarrowLine(int outcome) {
        if (outcome < 0 || outcome >= 16) {
            throw new IllegalArgumentException("蓍草抽樣值必須介於 0 與 15");
        }
        int firstRemoval = outcome / 4 == 0 ? 9 : 5;
        int secondRemoval = (outcome & 2) == 0 ? 8 : 4;
        int thirdRemoval = (outcome & 1) == 0 ? 8 : 4;
        return (49 - firstRemoval - secondRemoval - thirdRemoval) / 4;
    }

    public static int numberForMask(int mask) {
        if (mask < 0 || mask >= 64) {
            throw new IllegalArgumentException("六爻卦象必須介於 0 與 63");
        }
        return NUMBERS[mask];
    }

    public static int maskForNumber(int number) {
        requireNumber(number);
        return MASKS[number - 1];
    }

    public static String nameForNumber(int number) {
        requireNumber(number);
        return NAMES[number - 1];
    }

    private static void requireNumber(int number) {
        if (number < 1 || number > 64) {
            throw new IllegalArgumentException("文王卦序必須介於 1 與 64");
        }
    }
}

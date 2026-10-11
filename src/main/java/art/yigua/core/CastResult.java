package art.yigua.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 可保存的起卦結果；六爻由下往上，動爻使用 1–6 編號。
 * 無動爻時 changedNumber 為 0，無特殊讀法時 special 為空字串。
 * 兩個清單會做防禦複製，且公開建構子會拒絕與爻值矛盾的結果。
 */
public record CastResult(
    Method method,
    List<Integer> lineValues,
    int primaryNumber,
    int changedNumber,
    List<Integer> changingLines,
    String special
) {
    public CastResult {
        Objects.requireNonNull(method, "起卦方法不得為空");
        lineValues = List.copyOf(Objects.requireNonNull(lineValues, "六爻不得為空"));
        changingLines = List.copyOf(Objects.requireNonNull(changingLines, "動爻清單不得為空"));
        Objects.requireNonNull(special, "特殊讀法不得為空值");
        if (lineValues.size() != 6) {
            throw new IllegalArgumentException("每卦必須恰有六爻");
        }
        int primaryMask = 0;
        int changedMask = 0;
        boolean allSix = true;
        boolean allNine = true;
        List<Integer> expectedChanging = new ArrayList<>(6);
        for (int index = 0; index < 6; index++) {
            int value = lineValues.get(index);
            if (value < 6 || value > 9) {
                throw new IllegalArgumentException("爻值必須為六、七、八或九");
            }
            boolean moving = value == 6 || value == 9;
            int yang = value & 1;
            primaryMask |= yang << index;
            changedMask |= (moving ? yang ^ 1 : yang) << index;
            allSix &= value == 6;
            allNine &= value == 9;
            if (moving) {
                expectedChanging.add(index + 1);
            }
        }
        int expectedChanged = expectedChanging.isEmpty() ? 0 : CastingEngine.numberForMask(changedMask);
        String expectedSpecial = allNine ? "用九" : allSix ? "用六" : "";
        if (primaryNumber != CastingEngine.numberForMask(primaryMask)
                || changedNumber != expectedChanged
                || !changingLines.equals(expectedChanging)
                || !special.equals(expectedSpecial)) {
            throw new IllegalArgumentException("卦序、動爻或特殊讀法與六爻不一致");
        }
    }
}

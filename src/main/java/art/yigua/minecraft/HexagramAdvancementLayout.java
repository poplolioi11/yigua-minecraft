package art.yigua.minecraft;

import net.minecraft.advancements.AdvancementNode;
import net.minecraft.resources.Identifier;

/** 六十四卦只調整顯示位置；不改動條件、玩家進度或其他分頁。 */
public final class HexagramAdvancementLayout {
    private HexagramAdvancementLayout() {}

    public static boolean isRoot(Identifier id) {
        return id.getNamespace().equals("yigua") && id.getPath().equals("hexagrams/root");
    }

    public static int number(Identifier id) {
        if (!id.getNamespace().equals("yigua")) return 0;
        String path = id.getPath();
        if (path.length() != 12 || !path.startsWith("hexagrams/")) return 0;
        char tens = path.charAt(10), ones = path.charAt(11);
        if (tens < '0' || tens > '9' || ones < '0' || ones > '9') return 0;
        int number = (tens - '0') * 10 + ones - '0';
        return number >= 1 && number <= 64 ? number : 0;
    }

    public static boolean apply(AdvancementNode root) {
        if (!root.isRoot() || !isRoot(root.holder().id())) return false;
        root.setLocation(0, -1);
        for (AdvancementNode child : root.children()) {
            int number = number(child.holder().id());
            if (number != 0) child.setLocation((number - 1) % 8, (number - 1) / 8);
        }
        return true;
    }
}

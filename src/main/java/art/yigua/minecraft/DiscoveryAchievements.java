package art.yigua.minecraft;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;

/** 使用原版成就保存，不另外修改玩家成就檔或其他模組的進度。 */
public final class DiscoveryAchievements {
    private DiscoveryAchievements() {}

    public static void welcome(ServerPlayer player) {
        grant(player, "hexagrams/root");
        grant(player, "effects/root");
    }

    public static void hexagram(ServerPlayer player, int number) {
        if (number < 1 || number > 64) throw new IllegalArgumentException("卦序超出範圍");
        welcome(player);
        grant(player, "hexagrams/" + String.format(java.util.Locale.ROOT, "%02d", number));
    }

    public static void effect(ServerPlayer player, String profile) {
        if (Library.profiles().contains(profile) && !profile.equals("neutral")) {
            welcome(player);
            grant(player, "effects/" + profile);
        }
    }

    static void secret(ServerPlayer player, String event) {
        welcome(player);
        grant(player, "effects/secret_" + event);
    }

    private static void grant(ServerPlayer player, String path) {
        AdvancementHolder advancement = player.level().getServer().getAdvancements().get(YiguaMod.id(path));
        if (advancement != null) player.getAdvancements().award(advancement, "obtained");
    }
}

package art.yigua.minecraft;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** 世界異象的許可探測；不破壞方塊、不發 AFTER/CANCELED，也不產生掉落物。 */
public final class WorldChangePolicy {
    private static boolean enabled;

    private WorldChangePolicy() {}

    /** 由伺服器設定明確開啟；關閉只阻止新變更，既有 write-ahead 紀錄仍須復原。 */
    public static void configure(boolean value) { enabled = value; }

    public static boolean enabled() { return enabled; }

    public static boolean allow(ServerPlayer player, BlockPos pos) {
        if (!enabled || player == null || !player.isAlive() || player.isRemoved()
                || player.isSpectator() || player.hasDisconnected()) return false;
        return permission((ServerLevel) player.level(), player, pos);
    }

    static boolean permission(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || player.level().getServer() != level.getServer()
                || !level.getServer().isSameThread() || !level.isInWorldBounds(pos)
                || !level.getWorldBorder().isWithinBounds(pos)
                || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null
                || !player.mayInteract(level, pos)) return false;
        // Fabric 此版本的 Before 契約要求 listener 不改世界；任一 false 即停止。
        // 只呼叫 permission callbacks，不呼叫原版 destroyBlock 或 AFTER 掉落途徑。
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(
            level, player, pos, level.getBlockState(pos), level.getBlockEntity(pos));
    }
}

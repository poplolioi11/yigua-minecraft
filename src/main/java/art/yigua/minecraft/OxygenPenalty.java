package art.yigua.minecraft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.level.block.Blocks;

/** 只在原本確實耗掉一單位氧氣時累積額外消耗，保留水肺、導管及水泡柱保護。 */
public final class OxygenPenalty {
    private record State(int air, double fraction, Object level, int tick) {}
    private static final Map<UUID, State> STATES = new HashMap<>();
    private OxygenPenalty() {}

    public static void tick(ServerPlayer player, double strength) {
        if (strength <= 0 || !player.isAlive() || player.getAbilities().invulnerable
                || !player.isEyeInFluid(FluidTags.WATER) || player.canBreatheUnderwater()
                || MobEffectUtil.hasWaterBreathing(player)
                || player.level().getBlockState(BlockPos.containing(player.getX(), player.getEyeY(), player.getZ()))
                    .is(Blocks.BUBBLE_COLUMN)) {
            cancel(player.getUUID());
            return;
        }
        int air = player.getAirSupply();
        State previous = STATES.get(player.getUUID());
        double fraction = 0;
        if (previous != null && previous.level == player.level() && player.tickCount == previous.tick + 1) {
            fraction = previous.fraction;
            // 不改寫其他模組採用的特殊耗氧步幅；原版呼吸附魔省氧時亦不收額外氧氣。
            if (previous.air - air == 1) fraction += strength;
        }
        int extra = (int)Math.floor(fraction + 1e-8);
        if (extra > 0) {
            player.setAirSupply(Math.max(-19, air - extra));
            fraction -= extra;
        }
        STATES.put(player.getUUID(), new State(player.getAirSupply(), fraction, player.level(), player.tickCount));
    }

    public static void cancel(UUID id) { STATES.remove(id); }
    public static void clear() { STATES.clear(); }
}

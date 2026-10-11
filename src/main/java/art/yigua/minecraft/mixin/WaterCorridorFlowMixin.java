package art.yigua.minecraft.mixin;

import art.yigua.minecraft.TemporaryWaterCorridor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 有效分海只隔開原版自然回灌；不攔玩家放水、外來方塊或其他流體。 */
@Mixin(value = FlowingFluid.class, remap = false)
public abstract class WaterCorridorFlowMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void yigua$keepOwnedWaterCorridor(LevelAccessor level, BlockPos pos, BlockState state,
                                            Direction direction, FluidState target, CallbackInfo callback) {
        if ((target.getType() == Fluids.WATER || target.getType() == Fluids.FLOWING_WATER)
                && level instanceof ServerLevel serverLevel
                && TemporaryWaterCorridor.blocksNaturalWaterFlow(serverLevel, pos)) callback.cancel();
    }
}

package art.yigua.minecraft.mixin;

import art.yigua.minecraft.HexagramAdvancementLayout;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.TreeNodePosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原生排版完成後，只覆寫本模組六十四卦的固定顯示座標。 */
@Mixin(value = TreeNodePosition.class, remap = false)
public abstract class HexagramAdvancementLayoutMixin {
    @Inject(method = "run", at = @At("TAIL"))
    private static void yigua$positionHexagrams(AdvancementNode root, CallbackInfo callback) {
        HexagramAdvancementLayout.apply(root);
    }
}

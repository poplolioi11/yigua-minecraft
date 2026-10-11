package art.yigua.minecraft.mixin;

import art.yigua.minecraft.HexagramAdvancementLayout;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 首次開頁從乾、坤開始；後續拖曳位置沿用原版保存行為。 */
@Mixin(value = AdvancementTab.class, remap = false)
public abstract class HexagramAdvancementTabMixin {
    @Shadow private boolean centered;
    @Shadow private double scrollX;
    @Shadow private double scrollY;
    @Shadow public abstract AdvancementHolder getRootAdvancement();

    @Inject(method = "extractContents", at = @At("HEAD"))
    private void yigua$startAtFirstRow(GuiGraphicsExtractor graphics, int left, int top,
                                      CallbackInfo callback) {
        if (!centered && HexagramAdvancementLayout.isRoot(getRootAdvancement().id())) {
            scrollX = 5;
            scrollY = 32;
            centered = true;
        }
    }
}

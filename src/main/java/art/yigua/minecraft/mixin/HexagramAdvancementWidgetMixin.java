package art.yigua.minecraft.mixin;

import art.yigua.minecraft.HexagramAdvancementLayout;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 六十四卦是收藏網格，父節點連線不代表必須依序解鎖。 */
@Mixin(value = AdvancementWidget.class, remap = false)
public abstract class HexagramAdvancementWidgetMixin {
    @Shadow @Final private AdvancementNode advancementNode;

    @Inject(method = "extractConnectivity", at = @At("HEAD"), cancellable = true)
    private void yigua$omitGridConnections(GuiGraphicsExtractor graphics, int x, int y,
                                           boolean background, CallbackInfo callback) {
        if (advancementNode.isRoot() && HexagramAdvancementLayout.isRoot(advancementNode.holder().id())) {
            callback.cancel();
        }
    }
}

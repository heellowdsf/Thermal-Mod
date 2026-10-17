package com.thermalvision.client.mixin;

import com.thermalvision.client.ThermalVisionClient;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 第一人称手臂热成像 / First-person arm thermal effect
 *
 * 第一人称手臂不走 RenderLayer 系统，满亮度光照 + 着色器暖色提亮 = 热源效果。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class HandMixin {

    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private int thermalvision$handLight(int light) {
        return ThermalVisionClient.isActive() ? 15728880 : light;
    }
}

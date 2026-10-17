package com.thermalvision.client.mixin;

import com.thermalvision.client.ThermalVisionClient;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 热成像穿透雾效
 *
 * 红外波长(8-14μm)可穿透烟雾微粒，推远雾距离即可实现。
 */
@Mixin(FogRenderer.class)
public abstract class FogMixin {

    @ModifyArg(method = "setupFog",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogStart(F)V"),
            index = 0, require = 0)
    private static float thermalvision$noFogStart(float original) {
        return ThermalVisionClient.isActive() ? 9999.0f : original;
    }

    @ModifyArg(method = "setupFog",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogEnd(F)V"),
            index = 0, require = 0)
    private static float thermalvision$noFogEnd(float original) {
        return ThermalVisionClient.isActive() ? 10000.0f : original;
    }
}

package com.thermalvision.client.mixin;

import com.thermalvision.client.ThermalBufferSource;
import com.thermalvision.client.ThermalVisionClient;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 盔甲层热成像 / Armor layer thermal overlay
 *
 * 拦截 HumanoidArmorLayer 的 MultiBufferSource，替换为热成像 buffer。
 * heat=0.55 让盔甲比裸露皮肤(1.0)暗，可区分穿甲/不穿甲目标。
 *
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class ArmorLayerMixin {

    @ModifyVariable(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private MultiBufferSource thermalvision$wrapArmor(MultiBufferSource original) {
        if (!ThermalVisionClient.isActive() || !ThermalVisionClient.renderingWorld)
            return original;
        // 距离衰减由 EntityThermalContext 提供（实体渲染前设置）
        float heat = 0.55f * com.thermalvision.client.EntityThermalContext.currentAttenuation();
        return new ThermalBufferSource(original, heat);
    }
}

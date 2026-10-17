package com.thermalvision.client.mixin;

import com.thermalvision.client.ItemRenderState;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 标记物品渲染中，ThermalBufferSource 据此跳过热成像处理
 *
 * 无论 YSM 还是原版，手持物品最终都经过 ItemRenderer.render()。
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void thermalvision$beforeItem(ItemStack stack, ItemDisplayContext displayContext,
                                          boolean leftHand, PoseStack poseStack,
                                          MultiBufferSource bufferSource, int combinedLight,
                                          int combinedOverlay, BakedModel model,
                                          CallbackInfo ci) {
        ItemRenderState.push();
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void thermalvision$afterItem(ItemStack stack, ItemDisplayContext displayContext,
                                         boolean leftHand, PoseStack poseStack,
                                         MultiBufferSource bufferSource, int combinedLight,
                                         int combinedOverlay, BakedModel model,
                                         CallbackInfo ci) {
        ItemRenderState.pop();
    }
}

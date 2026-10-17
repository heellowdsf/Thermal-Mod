package com.thermalvision.client.mixin;

import com.thermalvision.client.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * YSM 兼容
 *
 * 问题:
 *   YSM 用 @WrapWithCondition 拦截 entityRenderer.render()，
 *   阻止原版 LivingEntityRenderer 执行，导致 ThermalFeatureRenderer 不触发。
 *
 * 解决 / Solution:
 *   在 EntityRenderDispatcher.render() RETURN 注入，对未被 ThermalFeatureRenderer
 *   处理的实体，用 ThermalBufferSource 重新调用 render()。
 *   YSM 会再次拦截并用自己的模型渲染——但这次 buffer 是热成像版本。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    // 防止无限递归
    @Unique
    private static boolean thermalvision$thermalPass = false;

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private <E extends Entity> void thermalvision$afterRender(
            E entity, double x, double y, double z, float yRot, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            CallbackInfo ci) {

        if (thermalvision$thermalPass) return;
        if (!ThermalVisionClient.isActive() || !ThermalVisionClient.renderingWorld) return;
        if (!(entity instanceof LivingEntity living) || living instanceof ArmorStand) return;

        // 不再跳过"已处理"实体——改为对所有 living 实体在此统一重渲染整个模型。
        // 这样女巫帽子、鸡腿、所有额外 RenderLayer 都会被染绿，因为之前变成一个雷霆大方块了
        // 不会出现主模型染绿、额外层却被原版彩色渲染而糊成大方块的问题。
        // 环境衰减：距离、雨、水
        var cam = net.minecraft.client.Minecraft.getInstance().gameRenderer
                .getMainCamera().getPosition();
        double ddx = living.getX() - cam.x, ddy = living.getY() - cam.y, ddz = living.getZ() - cam.z;
        float att = EnvironmentState.entityAttenuation(ddx*ddx + ddy*ddy + ddz*ddz, living.isUnderWater());

        // 衰减到极低时跳过
        if (att < 0.04f) return;

        // 综合热量：距离/天气衰减 × 每实体水冷却（潜水降温等）
        float heat = att * HeatMap.getEntityHeat(living);
        if (heat < 0.04f) return;
        // 存给盔甲层读取，保证一致
        EntityThermalContext.set(heat);

        // 取实体主纹理供热成像着色器做 alpha 裁形状（解决女巫帽子/鸡腿大方块）
        try {
            net.minecraft.client.renderer.entity.EntityRenderer<? super E> renderer =
                    ((EntityRenderDispatcher) (Object) this).getRenderer(entity);
            EntityThermalContext.setTexture(renderer.getTextureLocation(entity));
        } catch (Exception e) {
            EntityThermalContext.setTexture(null);
        }

        // 绿色基值随综合热量缩放
        ThermalBufferSource thermalBuffer = new ThermalBufferSource(bufferSource, heat, true);

        thermalvision$thermalPass = true;
        ItemRenderState.reset();  // 清零物品渲染深度，防跨实体污染
        try {
            //noinspection unchecked
            ((EntityRenderDispatcher) (Object) this).render(
                    entity, x, y, z, yRot, partialTick,
                    poseStack, thermalBuffer, packedLight);
        } catch (Throwable t) {
            // 兜底：热成像重渲染万一出错（如格式不匹配、光影管线冲突），
            // 只忽略本实体，绝不让游戏崩溃。
        } finally {
            thermalvision$thermalPass = false;
        }
    }
}

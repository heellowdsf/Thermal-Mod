package com.thermalvision.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;

/**
 * 热成像实体高亮层
 *
 * 用白色纹理 + 绿色顶点色渲染模型，着色器检测绿色后转为白色热源。
 *
 * YSM 兼容：renderedIds 记录本帧被本层处理的实体。
 * YSM 接管渲染的实体不会经过此层，EntityRenderDispatcherMixin 会为其补充热标记。
 *
 */
public class ThermalFeatureRenderer<T extends LivingEntity, M extends EntityModel<T>>
        extends RenderLayer<T, M> {

    // 本帧已处理的实体 ID
    private static final IntSet renderedIds = new IntOpenHashSet();

    public ThermalFeatureRenderer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    /** 每帧开始时清空（GameRendererMixin 调用 */
    public static void beginFrame() {
        renderedIds.clear();
    }

    /** 检查实体是否已被本层处理 */
    public static boolean wasRendered(int entityId) {
        return renderedIds.contains(entityId);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        // 已停用：实体热成像渲染改由 EntityRenderDispatcherMixin 统一处理整个模型
        // （包括女巫帽子、鸡腿/喙、所有额外 RenderLayer），避免本层只渲染主模型导致
        // 额外层被漏掉而糊成大方块。保留本类仅为兼容图层注册结构。
    }
}

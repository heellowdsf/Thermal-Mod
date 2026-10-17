package com.thermalvision.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.thermalvision.client.ItemRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * 包装 MultiBufferSource，将顶点颜色替换为绿色热标记
 * Wraps MultiBufferSource, overriding vertex colors to green thermal markers.
 *
 * 两种模式 / Two modes:
 *   filterNonBody=false: 全部变绿（ArmorLayerMixin 用）
 *   filterNonBody=true:  建模变绿，物品走原始渲染（YSM 兼容
 */
public class ThermalBufferSource implements MultiBufferSource {

    private final MultiBufferSource delegate;
    private final float heat;
    private final boolean filterNonBody;
    // 手臂模式：总是把几何体染成白热（皮肤裁形 / 整臂纯绿），无视 ItemRenderState 和盔甲判据。
    // 第一人称持枪手臂是在枪的物品渲染内部画的（rendering()=true），普通路径会放行不染色；
    // 手臂模式绕过那些判断，保证手臂始终白热。
    private final boolean armMode;

    public ThermalBufferSource(MultiBufferSource delegate, float heat) {
        this(delegate, heat, false);
    }

    public ThermalBufferSource(MultiBufferSource delegate, float heat, boolean filterNonBody) {
        this.delegate = delegate;
        this.heat = heat;
        this.filterNonBody = filterNonBody;
        this.armMode = false;
    }

    private ThermalBufferSource(MultiBufferSource delegate, float heat, boolean filterNonBody, boolean armMode) {
        this.delegate = delegate;
        this.heat = heat;
        this.filterNonBody = filterNonBody;
        this.armMode = armMode;
    }

    /** 第一人称手臂专用：始终白热。*/
    public static ThermalBufferSource forArm(MultiBufferSource delegate, float heat) {
        return new ThermalBufferSource(delegate, heat, false, true);
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        String name = renderType.toString();

        // 丢弃阴影
        if (name.contains("shadow")) {
            return NoOpVertexConsumer.INSTANCE;
        }

        // 文字/名字标签：顶点格式没有法线，放行原始渲染（不染绿、不崩）。
        var elements = renderType.format().getElements();
        boolean hasNormal = elements.contains(com.mojang.blaze3d.vertex.DefaultVertexFormat.ELEMENT_NORMAL);
        if (!hasNormal) {
            return delegate.getBuffer(renderType);
        }

        // 手臂模式：始终白热。皮肤纹理可解析就走实体裁形（保留手臂轮廓），否则整臂纯绿。
        // 不检查 ItemRenderState、不走盔甲丢弃分支——手臂无论在不在物品渲染上下文里都发热。
        if (armMode) {
            if (name.contains("minecraft:textures")
                    && renderType.format() == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) {
                ResourceLocation tex = parseTexture(name);
                if (tex != null) {
                    return new ThermalVertexConsumer(
                            delegate.getBuffer(ThermalRenderType.getThermalEntity(tex)), heat);
                }
            }
            return new ThermalVertexConsumer(
                    delegate.getBuffer(ThermalRenderType.getThermalLayer()), heat);
        }

        // 附魔光效层（glint）的处理放到下面各分支内部：必须在「手持物放行」判断【之后】，
        // 这样【手持】附魔物的 glint 仍走手持物放行（正常世界渲染，不动），只丢弃【穿戴】
        // 在身体/盔甲上的 glint。若在此处统一丢弃，会误伤手持附魔物。

        if (filterNonBody) {
            // 手持物（2D 物品 / 3D 方块 / 盾牌等）：渲染期间计数器 >0，放行原始渲染。
            // 注意：手持附魔物的 glint 也在此放行（正常渲染，不丢弃）。
            if (ItemRenderState.rendering()) {
                return delegate.getBuffer(renderType);
            }
            // 穿戴在身体上的附魔光效层（glint）：丢弃 → 修「附魔盔甲/身体上有流动白色条纹」。
            // glint 是流动亮条纹，叠加在绿色标记之上，会让像素 R/B 时高时低 → 后处理色键
            // （R≈0 且 B≈0）时通时断 → 表面跟着光带一亮一暗流动。像丢弃 shadow 一样彻底不画它，
            // 附魔与否表现一致。已确认非手持物（上面 rendering() 已放行手持物）。
            if (name.contains("glint")) {
                return NoOpVertexConsumer.INSTANCE;
            }
            // vanilla 实体：自定义着色器（alpha 裁形 + 纯绿）。关键修正——
            // 用【该渲染层自己的纹理】做 alpha，而不是全局 currentTexture。
            // 村民的外袍是单独一层、用袍子纹理；若用主纹理 alpha 去裁袍子几何，会因纹理不一致
            // 把袍子裁掉（漏白→显示成灰）。从 renderType.toString() 解析出本层纹理即可各自正确。
            boolean vanillaTexture = name.contains("minecraft:textures");
            if (vanillaTexture
                    && renderType.format() == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) {
                ResourceLocation tex = parseTexture(name);
                if (tex != null) {
                    VertexConsumer original = delegate.getBuffer(ThermalRenderType.getThermalEntity(tex));
                    return new ThermalVertexConsumer(original, heat);
                }
            }
            // YSM 等模型：白色纹理层纯绿，不依赖纹理 alpha/RGB，整模型完整变白。
            VertexConsumer original = delegate.getBuffer(ThermalRenderType.getThermalLayer());
            return new ThermalVertexConsumer(original, heat);
        }

        // 默认模式（盔甲层 ArmorLayerMixin 用）。

        // 盔甲的附魔光效层（glint）：丢弃 → 修「附魔盔甲上有流动白色条纹」。
        // 盔甲的 glint 一定是穿戴的（非手持），直接丢即可。理由同上：glint 叠加在绿色标记上
        // 会让 R/B 时高时低破坏色键，导致表面跟着流动光带一亮一暗。
        if (name.contains("glint")) {
            return NoOpVertexConsumer.INSTANCE;
        }

        // 盔甲按【自己的纹理 alpha】裁出真实轮廓 → 修「盔甲大一圈 + 整体变暗」。
        // 判据收紧为 models/armor/ ——这是第二重保险：附魔 glint 层用的纹理是
        // misc/enchanted_glint_entity.png（在 misc/ 目录，不在 models/armor/），所以
        // 即便上面的 glint 名字判据万一在某些映射下失效，glint 也不会命中这里被误染绿。
        // 只有真正的盔甲纹理（models/armor/*_layer_*.png）才走染绿裁形。
        if (name.contains("models/armor/")
                && renderType.format() == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) {
            ResourceLocation tex = parseTexture(name);
            if (tex != null) {
                VertexConsumer original = delegate.getBuffer(ThermalRenderType.getThermalEntity(tex));
                return new ThermalVertexConsumer(original, heat);
            }
        }

        // 到这里说明既不是盔甲纹理、也不是已处理的 glint（异常/未知层，如某些模组的特殊层）。
        // 为避免像 glint 那样制造色键干扰，未知的带纹理层一律【丢弃】而不是染绿或放行。
        if (name.contains("minecraft:textures") || name.contains("glint")) {
            return NoOpVertexConsumer.INSTANCE;
        }

        // 兜底：无法解析纹理时（异常情况）退回白纹理纯绿，保证盔甲至少可见、不崩。
        VertexConsumer original = delegate.getBuffer(ThermalRenderType.getThermalLayer());
        return new ThermalVertexConsumer(original, heat);
    }

    // 从 RenderType.toString() 里解析出 minecraft: 命名空间的纹理路径。
    // 1.20.1 的 toString 形如 ...TextureStateShard[Optional[minecraft:textures/.../x.png]]...
    // 解析失败返回 null（调用方会退回白纹理纯绿，安全）。
    private static ResourceLocation parseTexture(String renderTypeName) {
        int key = renderTypeName.indexOf("minecraft:textures/");
        if (key < 0) return null;
        int end = renderTypeName.indexOf(".png", key);
        if (end < 0) return null;
        String full = renderTypeName.substring(key, end + 4);   // minecraft:textures/.../x.png
        int colon = full.indexOf(':');
        try {
            return new ResourceLocation(full.substring(0, colon), full.substring(colon + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 空 VertexConsumer，丢弃所有顶点数据
     */
    enum NoOpVertexConsumer implements VertexConsumer {
        INSTANCE;

        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
        @Override public void vertex(float x, float y, float z,
                                     float r, float g, float b, float a,
                                     float texU, float texV, int overlayUV, int lightmapUV,
                                     float nx, float ny, float nz) {}
    }
}

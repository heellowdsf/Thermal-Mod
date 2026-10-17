package com.thermalvision.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * 热成像专用 RenderType
 *
 * 关键属性:
 *   1. ENTITY_TRANSLUCENT_EMISSIVE — 自发光，无方向光照
 *   2. VIEW_OFFSET_Z_LAYERING — 深度偏移防 Z-fighting
 *   3. NO_CULL — 双面渲染，无缺面
 */
public class ThermalRenderType extends RenderType {

    // 继承 RenderType 所需的虚拟构造函数
    private ThermalRenderType() {
        super("thermalvision_dummy", DefaultVertexFormat.POSITION,
                VertexFormat.Mode.QUADS, 0, false, false, () -> {}, () -> {});
    }

    private static final ResourceLocation WHITE_TEXTURE =
            new ResourceLocation("thermalvision", "textures/entity/white.png");

    private static final RenderType THERMAL_LAYER = create(
            "thermalvision_thermal",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            256,
            false, true,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(WHITE_TEXTURE, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false)
    );

    public static RenderType getThermalLayer() {
        return THERMAL_LAYER;
    }

    // 着色器供应器：返回我们的自定义着色器；万一未加载（null），退回 vanilla 的
    // entity cutout 着色器兜底，确保供应器永不返回 null → Oculus 批量渲染不会 NPE 崩溃。
    private static final RenderStateShard.ShaderStateShard THERMAL_ENTITY_SHADER =
            new RenderStateShard.ShaderStateShard(() -> {
                net.minecraft.client.renderer.ShaderInstance s = ThermalShaders.getThermalEntityShader();
                return s != null ? s
                        : net.minecraft.client.renderer.GameRenderer.getRendertypeEntityCutoutShader();
            });

    // 按实体真实纹理缓存渲染层
    private static final java.util.Map<ResourceLocation, RenderType> TEX_LAYERS =
            new java.util.HashMap<>();

    /**
     * 用实体真实纹理 + 自定义热成像着色器的渲染层。着色器只用纹理 alpha 裁形状、
     * 输出纯绿（无视 RGB）：形状正确、动物无花色、不可被皮肤绕过。
     */
    public static RenderType getThermalEntity(ResourceLocation texture) {
        return TEX_LAYERS.computeIfAbsent(texture, tex -> create(
                "thermalvision_entity",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                false, true,
                CompositeState.builder()
                        .setShaderState(THERMAL_ENTITY_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, false, false))
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        // 【已补上】深度偏移，防 Z-fighting —— 修「生物热成像抽搐/闪烁」。
                        //
                        // 绿色热标记是在原版实体渲染完之后，在【完全相同的位置】把同一套模型
                        // 再画一遍。两次绘制的深度值逐像素相等，落在深度测试的边界上，于是
                        // 哪一次「赢」取决于浮点舍入 —— 这就是 Z-fighting。
                        // 在测试时发现美西螈有bug
						// 我推测的可能原因：
                        // 为什么偏偏美西螈最明显：静止的生物每帧深度值一样，Z-fighting 图案
                        // 是固定的，看起来只是有点脏，而美西螈一直在游动，每帧深度都在变，
                        // 图案就跟着帧率乱跳 → 看起来就是「闪来闪去、抽搐」。
                        //
                        // VIEW_OFFSET_Z_LAYERING 把几何体在视图空间里朝相机方向轻微缩放
                        // （×0.99975，原版给盔甲外层/信标光束防 Z-fighting 用的就是它），
                        // 让绿色标记稳定地赢下深度测试，闪烁消失。模型缩小量肉眼不可见。
                        //
                        // 注意：本类顶部注释本来就把「深度偏移防 Z-fighting」列为关键属性，
                        // 下面的 getThermalLayer() 白纹理层也确实设了 —— 只有这条按纹理
                        // 缓存的路径漏了，而所有原版生物走的正是这条路径。
                        //
                        // [ADDED] Depth offset to prevent Z-fighting — fixes entity flicker.
                        // The green marker re-draws the SAME model at the SAME position right
                        // after the vanilla render, so both draws produce per-pixel EQUAL depth
                        // and which one wins comes down to float rounding — Z-fighting. It shows
                        // up worst on axolotls because they swim constantly: a still mob has a
                        // fixed (merely dirty-looking) pattern, while a moving one re-rolls it
                        // every frame → visible flicker. VIEW_OFFSET_Z_LAYERING scales geometry
                        // very slightly toward the camera in view space (x0.99975 — the same
                        // shard vanilla uses for outer armor and beacon beams), so the marker
                        // wins the depth test consistently. The size change is imperceptible.
                        // Note the class header already lists depth offset as a key property and
                        // getThermalLayer() below does set it — only this per-texture path was
                        // missing it, and every vanilla mob goes through this path.
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .createCompositeState(false)
        ));
    }
}

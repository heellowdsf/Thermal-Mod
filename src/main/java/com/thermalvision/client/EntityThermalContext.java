package com.thermalvision.client;

/**
 * 当前渲染实体的衰减系数
 *
 * 渲染管线是单线程的：实体渲染时，身体层(ThermalFeatureRenderer)和盔甲层
 * (ArmorLayerMixin)依次执行。身体层先算好该实体的距离/天气衰减并存这里，
 * 盔甲层读取同一个值，保证两者一致变暗（远处身体暗了盔甲不会还亮）。
 *
 */
public class EntityThermalContext {
    private static float attenuation = 1.0f;
    private static net.minecraft.resources.ResourceLocation texture = null;

    public static void set(float att) { attenuation = att; }
    public static float currentAttenuation() { return attenuation; }

    // 当前实体主纹理（dispatcher 在重渲染前设置），供热成像着色器做 alpha 裁形状
    public static void setTexture(net.minecraft.resources.ResourceLocation tex) { texture = tex; }
    public static net.minecraft.resources.ResourceLocation currentTexture() { return texture; }
}

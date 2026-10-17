package com.thermalvision.client;

/**
 * 物品渲染深度计数器，由 ItemRendererMixin 控制。
 *
 * 用计数器而非布尔值，是为了正确处理嵌套的 ItemRenderer.render 调用——
 * 手持方块（3D 模型）、盾牌（BEWLR 特殊渲染器）会嵌套调用 render，
 * 布尔值会被内层的 RETURN 过早置 false，导致玩家身体被误判而失去热量。
 * 计数器进入 +1、退出 -1，只有 depth>0 才算"物品渲染中"，嵌套也平衡。
 *
 */
public class ItemRenderState {
    private static int depth = 0;

    public static void push() { depth++; }
    public static void pop() { if (depth > 0) depth--; }
    public static boolean rendering() { return depth > 0; }

    /** 重置（每个实体热成像重渲染前清零，防跨实体污染）*/
    public static void reset() { depth = 0; }
}

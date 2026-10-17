package com.thermalvision.client.embeddium;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 把方块顶点颜色强制为纯绿 (0,G,0)、满亮度，供灰度着色器识别为白热。
 */
public class GreenTintConsumer implements VertexConsumer {

    private final VertexConsumer delegate;
    private final int g;       // 0-255
    private final float gf;    // 0.0-1.0

    public GreenTintConsumer(VertexConsumer delegate, int green) {
        this.delegate = delegate;
        this.g = green;
        this.gf = green / 255f;
    }

    @Override public VertexConsumer vertex(double x, double y, double z) { delegate.vertex(x, y, z); return this; }
    @Override public VertexConsumer color(int r, int gg, int b, int a) { delegate.color(0, g, 0, 255); return this; }
    @Override public VertexConsumer uv(float u, float v) { delegate.uv(u, v); return this; }
    @Override public VertexConsumer overlayCoords(int u, int v) { delegate.overlayCoords(u, v); return this; }
    @Override public VertexConsumer uv2(int u, int v) { delegate.uv2(15728880); return this; }   // fullbright
    @Override public VertexConsumer normal(float x, float y, float z) { delegate.normal(x, y, z); return this; }
    @Override public void endVertex() { delegate.endVertex(); }
    @Override public void defaultColor(int r, int gg, int b, int a) { delegate.defaultColor(0, g, 0, 255); }
    @Override public void unsetDefaultColor() { delegate.unsetDefaultColor(); }

    @Override
    public void vertex(float x, float y, float z,
                       float red, float green, float blue, float alpha,
                       float texU, float texV, int overlayUV, int lightmapUV,
                       float nx, float ny, float nz) {
        delegate.vertex(x, y, z, 0f, gf, 0f, 1.0f,
                texU, texV, overlayUV, 15728880, nx, ny, nz);
    }
}

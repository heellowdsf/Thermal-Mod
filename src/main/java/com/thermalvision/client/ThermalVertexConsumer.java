package com.thermalvision.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 顶点颜色覆盖：强制所有顶点为绿色(0, G, 0)，满亮度光照
 */
public class ThermalVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;
    private final int gr;   // green 0-255
    private final float gf; // green 0.0-1.0

    public ThermalVertexConsumer(VertexConsumer delegate, float heat) {
        this.delegate = delegate;
        this.gr = (int) (heat * 255);
        this.gf = heat;
    }

    @Override public VertexConsumer vertex(double x, double y, double z) { delegate.vertex(x, y, z); return this; }
    @Override public VertexConsumer color(int r, int g, int b, int a) { delegate.color(0, gr, 0, 255); return this; }
    @Override public VertexConsumer uv(float u, float v) { delegate.uv(u, v); return this; }
    @Override public VertexConsumer overlayCoords(int u, int v) { delegate.overlayCoords(u, v); return this; }
    @Override public VertexConsumer uv2(int u, int v) { delegate.uv2(15728880); return this; } // fullbright
    @Override public VertexConsumer normal(float x, float y, float z) { delegate.normal(x, y, z); return this; }
    @Override public void endVertex() { delegate.endVertex(); }
    @Override public void defaultColor(int r, int g, int b, int a) { delegate.defaultColor(0, gr, 0, 255); }
    @Override public void unsetDefaultColor() { delegate.unsetDefaultColor(); }

    @Override
    public void vertex(float x, float y, float z,
                       float red, float green, float blue, float alpha,
                       float texU, float texV, int overlayUV, int lightmapUV,
                       float normalX, float normalY, float normalZ) {
        delegate.vertex(x, y, z, 0f, gf, 0f, 1.0f,
                texU, texV, overlayUV, 15728880, normalX, normalY, normalZ);
    }
}

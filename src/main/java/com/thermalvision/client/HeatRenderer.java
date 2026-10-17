package com.thermalvision.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 方块热量渲染 / Block heat rendering
 *
 * 两部分 / Two parts:
 *   1. 热源方块本身：渲染成纯绿立方体标记 (0,G,0)，让灰度着色器识别为白热源。
 *      这样火源（岩浆/火/篝火/岩浆块/火把）可靠发白，不靠着色器猜屏幕颜色，
 *      彻底避免夕阳、红橙黄羊毛等被误判。强热源 G 高（更白），弱热源 G 低。
 *   2. 扩散热量：在被烘热的固体方块上方画柔和发光四边形（余热效果）。
 *
 */
public class HeatRenderer {

    private static final BufferBuilder buffer = new BufferBuilder(4096);
    private static final ResourceLocation HEAT_TEXTURE =
            new ResourceLocation("thermalvision", "textures/entity/heat_glow.png");
    private static final float HALF = 0.55f;

    public static void render(PoseStack poseStack, Camera camera) {
        if (!ThermalVisionClient.isActive()) return;

        Vec3 cam = camera.getPosition();
        Matrix4f mat = poseStack.last().pose();
        double camX = cam.x, camY = cam.y, camZ = cam.z;

        // 1. 热源方块绿色白热标记
        renderSourceMarkers(mat, camX, camY, camZ);

        // 2. 扩散余热发光四边形
        renderDiffusedGlow(mat, camX, camY, camZ);
    }

    /**
     * 把热源方块渲染成纯绿标记，着色器识别 (R≈0,B≈0) → 白热。
     * 标记盒子大小贴合方块的实际形状（VoxelShape）：火把是小条、火是薄层、
     * 岩浆/岩浆块是满方块。这样不会把小火把渲染成大方块。
     */
    private static void renderSourceMarkers(Matrix4f mat, double camX, double camY, double camZ) {
        // Embeddium 在场时，热源由 ThermalChunkMeshAppender 以真实形状渲染，
        // 这里的立方体标记降级方案跳过，避免重复渲染。
        if (com.thermalvision.client.EmbeddiumHeatHook.isEmbeddiumPresent()) return;

        int count = HeatMap.getSourceCount();
        if (count == 0) return;

        net.minecraft.client.multiplayer.ClientLevel level =
                net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;

        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int drawn = 0;

        for (int i = 0; i < count; i++) {
            long packed = HeatMap.getSourceKey(i);
            float h = HeatMap.getSourceVal(i);   // 源强度 0~1

            int bx = BlockPos.getX(packed);
            int by = BlockPos.getY(packed);
            int bz = BlockPos.getZ(packed);

            float rx = (float)(bx - camX);
            float ry = (float)(by - camY);
            float rz = (float)(bz - camZ);
            if (rx*rx + ry*ry + rz*rz > 4096) continue; // 64格内

            // 用方块实际形状的包围盒决定标记尺寸（火把小、火薄、岩浆满）
            net.minecraft.core.BlockPos bp = new net.minecraft.core.BlockPos(bx, by, bz);
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(bp);
            net.minecraft.world.phys.AABB box;
            try {
                net.minecraft.world.phys.shapes.VoxelShape shape = st.getShape(level, bp);
                if (shape.isEmpty()) {
                    // 火/液体等无碰撞形状的，用满方块
                    box = new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1);
                } else {
                    box = shape.bounds();
                }
            } catch (Exception e) {
                box = new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1);
            }

            // 绿色强度对应温度
            int g = (int)(Math.min(1f, h) * 255);

            // 略微膨胀避免 z-fighting
            float x0 = rx + (float) box.minX - 0.01f, x1 = rx + (float) box.maxX + 0.01f;
            float y0 = ry + (float) box.minY - 0.01f, y1 = ry + (float) box.maxY + 0.01f;
            float z0 = rz + (float) box.minZ - 0.01f, z1 = rz + (float) box.maxZ + 0.01f;

            // 六面立方体，纯绿 (0,g,0)
            // 上
            buffer.vertex(mat, x0, y1, z0).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x0, y1, z1).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y1, z1).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y1, z0).color(0, g, 0, 255).endVertex();
            // 下
            buffer.vertex(mat, x0, y0, z1).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x0, y0, z0).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y0, z0).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y0, z1).color(0, g, 0, 255).endVertex();
            // 北
            buffer.vertex(mat, x1, y0, z0).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x0, y0, z0).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x0, y1, z0).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y1, z0).color(0, g, 0, 255).endVertex();
            // 南
            buffer.vertex(mat, x0, y0, z1).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y0, z1).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y1, z1).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x0, y1, z1).color(0, g, 0, 255).endVertex();
            // 西
            buffer.vertex(mat, x0, y0, z0).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x0, y0, z1).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x0, y1, z1).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x0, y1, z0).color(0, g, 0, 255).endVertex();
            // 东
            buffer.vertex(mat, x1, y0, z1).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y0, z0).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y1, z0).color(0, g, 0, 255).endVertex();
            buffer.vertex(mat, x1, y1, z1).color(0, g, 0, 255).endVertex();
            drawn++;
        }

        if (drawn == 0) { buffer.end(); return; }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.disableBlend();
    }

    /**
     * 扩散余热：被烘热的固体上方画柔和发光四边形。
     */
    private static void renderDiffusedGlow(Matrix4f mat, double camX, double camY, double camZ) {
        int count = HeatMap.getSnapCount();
        if (count == 0) return;

        // 需要方块的真实形状来决定发光高度（避免薄方块如地毯把光悬在空中）。
        net.minecraft.client.multiplayer.ClientLevel level =
                net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;
        net.minecraft.core.BlockPos.MutableBlockPos bp = new net.minecraft.core.BlockPos.MutableBlockPos();

        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int drawn = 0;

        for (int i = 0; i < count; i++) {
            long packed = HeatMap.getSnapKey(i);
            float h = HeatMap.getSnapVal(i);

            int bx = BlockPos.getX(packed);
            int by = BlockPos.getY(packed);
            int bz = BlockPos.getZ(packed);

            float rx = (float)(bx - camX);
            float ry = (float)(by - camY);
            float rz = (float)(bz - camZ);
            if (rx*rx + ry*ry + rz*rz > 625) continue;

            // 发光高度 = 方块实际顶面（地毯≈0.06、台阶≈0.5、满方块=1.0），而不是固定 +1.0。
            // 这样余热光贴在被烘热方块的表面上，薄方块不再悬空。
            float topY;
            try {
                bp.set(bx, by, bz);
                net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(bp).getShape(level, bp);
                topY = shape.isEmpty() ? 1.0f : (float) shape.bounds().maxY;
            } catch (Exception e) {
                topY = 1.0f;
            }

            float cx = rx + 0.5f;
            float y  = ry + topY + 0.02f;   // 略高于顶面避免
            float cz = rz + 0.5f;

            // 纯绿 (0,G,0)：与热源标记一致，让灰度着色器识别为白热。
            // 之前用白色 (255,255,245)，新的 HSV 背景会把白色当成普通低饱和物体→中灰，
            // 余热就不亮了。改成纯绿，走实体白热通道，余热重新变亮白。
            int a = 100 + (int)(h * 155);
            int r = 0, g = 200 + (int)(h * 55), b = 0;

            buffer.vertex(mat, cx - HALF, y, cz - HALF).uv(0, 0).color(r, g, b, a).endVertex();
            buffer.vertex(mat, cx - HALF, y, cz + HALF).uv(0, 1).color(r, g, b, a).endVertex();
            buffer.vertex(mat, cx + HALF, y, cz + HALF).uv(1, 1).color(r, g, b, a).endVertex();
            buffer.vertex(mat, cx + HALF, y, cz - HALF).uv(1, 0).color(r, g, b, a).endVertex();
            drawn++;
        }

        if (drawn == 0) { buffer.end(); return; }

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, HEAT_TEXTURE);
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}

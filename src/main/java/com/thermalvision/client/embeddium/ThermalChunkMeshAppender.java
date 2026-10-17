package com.thermalvision.client.embeddium;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.thermalvision.client.HeatMap;
import com.thermalvision.client.ThermalVisionClient;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.model.data.ModelData;
import org.embeddedt.embeddium.api.ChunkMeshEvent;
import org.embeddedt.embeddium.api.MeshAppender;

/**
 * Embeddium 集成：热源方块以真实形状染绿融入区块网格
 *
 * 监听 ChunkMeshEvent，为每个区块 section 注册 MeshAppender。Embeddium 在 worker
 * 线程网格化该 section 时调用 appender，我们用 vanilla 的 renderBatched/renderLiquid
 * 把热源方块的真实几何（火把那一根、岩浆的流动液面）渲染到 Embeddium 提供的
 * VertexConsumer——顶点融入区块最终网格。颜色经 GreenTintConsumer 强制纯绿 (0,G,0)，
 * 灰度着色器据此识别为白热。无延迟、无额外开销、Embeddium 原生支持，形状天然正确。
 *
 *
 * 此类只在确认 Embeddium 已加载后才被触碰（见 EmbeddiumHeatHook），
 * 避免没装 Embeddium 时因找不到 org.embeddedt.* 类而崩溃。
 */
public final class ThermalChunkMeshAppender {

    /** 注册事件监听器*/
    public static void register() {
        ChunkMeshEvent.BUS.addListener(ThermalChunkMeshAppender::onChunkMesh);
    }

    private static void onChunkMesh(ChunkMeshEvent event) {
        // 仅染绿开关开启时介入（关闭时立即停，避免重建又把方块染绿导致绿色残留）
        if (!ThermalVisionClient.isMeshTintActive()) return;

        // 不预先过滤 section——直接注册 appender，在 worker 线程里逐方块判断热源。
        // 之前用 HeatMap 快照过滤会因快照异步更新/为空而漏掉已存在的火把（必须放/破方块才生效）。
        // appender 内部读真实区块方块判断，无需预先知道哪个 section 有热源。
        event.addMeshAppender(ThermalChunkMeshAppender::renderSection);
    }

    /** 在 worker 线程调用：渲染该 section 的热源方块真实几何 */
    private static void renderSection(MeshAppender.Context ctx) {
        BlockAndTintGetter view = ctx.blockRenderView();   // 线程安全的区块视图
        SectionPos origin = ctx.sectionOrigin();
        var provider = ctx.vertexConsumerProvider();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();
        PoseStack poseStack = new PoseStack();
		
        //int baseX = origin.minBlockX();
        int baseX = origin.minBlockX();
        int baseY = origin.minBlockY();
        int baseZ = origin.minBlockZ();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < 16; dx++) {
            for (int dy = 0; dy < 16; dy++) {
                for (int dz = 0; dz < 16; dz++) {
                    int wx = baseX + dx, wy = baseY + dy, wz = baseZ + dz;
                    pos.set(wx, wy, wz);

                    BlockState state = view.getBlockState(pos);
                    if (state.isAir()) continue;

                    float heat = HeatMap.blockHeat(state.getBlock());
                    if (heat <= 0f) continue;

                    int g = (int) (Math.min(1f, heat) * 255);

                    // renderBatched / renderLiquid 写入的顶点坐标是相对于 section 原点的
                    // （区块网格上下文），与 vanilla 区块编译一致。
                    poseStack.pushPose();
                    poseStack.translate(dx, dy, dz);

                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        // 岩浆等液体：用流体正确的渲染层（岩浆是 solid，不是 translucent！）。
                        // renderLiquid 不接收 PoseStack——它内部用 pos 的世界坐标自行定位，
                        // 所以这里不能靠 poseStack.translate，直接传 pos 让它自己算。
                        RenderType fluidLayer = net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderLayer(fluid);
                        VertexConsumer base = provider.apply(fluidLayer);
                        VertexConsumer green = new GreenTintConsumer(base, g);
                        try {
                            dispatcher.renderLiquid(pos, view, green, state, fluid);
                        } catch (Exception ignored) {}
                    }

                    // BakedModel 几何（火把/火/岩浆块/营火/熔炉等），液体方块通常也无模型几何
                    if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
                        RenderType layer = chooseLayer(state);
                        VertexConsumer base = provider.apply(layer);
                        VertexConsumer green = new GreenTintConsumer(base, g);
                        try {
                            dispatcher.renderBatched(state, pos, view, poseStack, green,
                                    false, random, ModelData.EMPTY, layer);
                        } catch (Exception ignored) {}
                    }

                    poseStack.popPose();
                }
            }
        }
    }

    /** 选合适的渲染层*/
    private static RenderType chooseLayer(BlockState state) {
        try {
            RenderType rt = net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(state);
            return rt != null ? rt : RenderType.cutout();
        } catch (Exception e) {
            return RenderType.cutout();
        }
    }

    private ThermalChunkMeshAppender() {}
}















































































































































































































































































































// ?













































































































//:)

























































































































































































//:) :)

















































































































































































































































































//:) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :):) :)

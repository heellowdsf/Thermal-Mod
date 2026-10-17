package com.thermalvision.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.thermalvision.client.ThermalVisionClient;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 热成像期间不渲染天空穹
 *
 * ── 物理依据──
 * 长波红外里晴空向外太空辐射，表观温度远低于地面，是画面里最冷、最均匀的区域；
 * 太阳/月亮/星星/晚霞都是可见光现象，热像仪根本看不到它们的「明暗形状」。
 * 尤其月亮：夜面本身是冷的，绝不该在热像里呈现成一个亮白圆盘。
 *
 * ── 修的 bug ──
 *   · 月亮变大煎蛋：月亮贴图是画面里最亮的东西，被当成强热源。
 *   · 黄昏被误判成热源：晚霞的红橙渐变让天空各处呈现不同温度

 *
 * 为什么用这个办法，而不是用深度缓冲判天空
 * 靠深度缓冲区分天空需要给后处理链额外绑定一张深度贴图。那条链路一旦绑定失败，
 * 着色器会读到全 0 的深度 → 整屏被判成天空 → 世界全黑、生物也不再是白色。
 * 这里的做法没有任何新依赖：天空不画，那片像素就保持 renderLevel 开头 clear 的雾色，
 * 是一整片均匀色 → 走原有映射后是一片均匀灰，既没有煎蛋月亮，也没有局部误判热源。
 * 万一这个注入没生效（require = 0，不会崩），天空照常渲染，其它一切不受影响 —— 
 * 失败时只是「没修好」，绝不会把画面弄坏。
 *
 *
 * 仅 THERMAL 模式生效；夜视仪需要看见月光和星空，不受影响。
 */
@Mixin(LevelRenderer.class)
public abstract class SkyMixin {

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void thermalvision$skipSky(PoseStack poseStack, Matrix4f projectionMatrix,
                                       float partialTick, Camera camera, boolean isFoggy,
                                       Runnable skyFogSetup, CallbackInfo ci) {
        if (ThermalVisionClient.isActive()) {
            ci.cancel();
        }
    }
}

package com.thermalvision.client.mixin;

import com.thermalvision.client.ThermalVisionClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 热成像穿透粒子
 *
 * 红外可穿透烟雾粉尘，两层拦截：
 *   1. add() — 阻止新粒子进入引擎（兼容 Embeddium/Sodium）
 *   2. render() — 兜底，阻止已存在粒子的渲染
 *
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleMixin {

    // 第一层：阻止粒子进入
    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
    private void thermalvision$blockParticleAdd(Particle particle, CallbackInfo ci) {
        if (ThermalVisionClient.isActive()) ci.cancel();
    }

    // 第二层：阻止渲染
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void thermalvision$noParticles(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                            LightTexture lightTexture, Camera camera, float partialTick,
                                            CallbackInfo ci) {
        if (ThermalVisionClient.isActive()) ci.cancel();
    }
}

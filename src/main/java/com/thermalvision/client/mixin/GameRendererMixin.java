package com.thermalvision.client.mixin;

import com.thermalvision.ThermalVisionMod;
import com.thermalvision.client.ThermalFeatureRenderer;
import com.thermalvision.client.ThermalVisionClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow @Final Minecraft minecraft;

    @Unique private PostChain thermalvision$postOverworld;
    @Unique private PostChain thermalvision$postNether;
    @Unique private PostChain thermalvision$postNightVision;
    @Unique private boolean thermalvision$shadersReady = false;
    @Unique private int thermalvision$frame = 0;

    @Unique
    private void thermalvision$ensureLoaded() {
        if (thermalvision$shadersReady) return;
        thermalvision$shadersReady = true;
        thermalvision$postOverworld = thermalvision$loadShader(
                new ResourceLocation("thermalvision", "shaders/post/thermal.json"));
        thermalvision$postNether = thermalvision$loadShader(
                new ResourceLocation("thermalvision", "shaders/post/thermal_nether.json"));
        thermalvision$postNightVision = thermalvision$loadShader(
                new ResourceLocation("thermalvision", "shaders/post/nightvision.json"));
    }

    @Unique
    private PostChain thermalvision$loadShader(ResourceLocation id) {
        try {
            PostChain chain = new PostChain(
                    minecraft.getTextureManager(), minecraft.getResourceManager(),
                    minecraft.getMainRenderTarget(), id);
            chain.resize(minecraft.getWindow().getWidth(),
                         minecraft.getWindow().getHeight());
            return chain;
        } catch (Exception e) {
            ThermalVisionMod.LOGGER.error("Shader load failed: {}", id, e);
            return null;
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void thermalvision$startLevel(float partialTick, long finishTimeNano,
                                           com.mojang.blaze3d.vertex.PoseStack poseStack,
                                           CallbackInfo ci) {
        ThermalVisionClient.renderingWorld = true;
        ThermalFeatureRenderer.beginFrame();
        // 检测挂起期间 Iris 是否自己重建过管线（走地狱门等维度切换会触发），
        // 若是则销毁孤儿管线并对新管线重新挂起。非挂起状态下是空操作。
        com.thermalvision.client.IrisCompat.validate();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 0)
    private void thermalvision$apply(float partialTick, long finishTimeNano,
                                      com.mojang.blaze3d.vertex.PoseStack poseStack,
                                      CallbackInfo ci) {
        if (!ThermalVisionClient.isAnyActive()) {
            ThermalVisionClient.renderingWorld = false;
            ThermalVisionClient.tickPendingClose();
            return;
        }

        thermalvision$ensureLoaded();
        ThermalVisionClient.tickPendingClose();
        PostChain post = null;
        if (ThermalVisionClient.isActive()) {
            boolean nether = minecraft.level != null &&
                    minecraft.level.dimension() == Level.NETHER;
            post = nether ? thermalvision$postNether : thermalvision$postOverworld;
        } else if (ThermalVisionClient.isNightVisionActive()) {
            post = thermalvision$postNightVision;
        }

        if (post != null) {
            thermalvision$frame++;
            float time = (thermalvision$frame % 10000) * 0.001f;
            boolean thermalOverworld = ThermalVisionClient.isActive()
                    && post == thermalvision$postOverworld;
            float rain = com.thermalvision.client.EnvironmentState.getRainStrength();
            float snowy = com.thermalvision.client.EnvironmentState.getSnowy();

            try {
                for (PostPass pass : ((PostChainAccessor) post).thermalvision$getPasses()) {
                    var effect = pass.getEffect();
                    effect.safeGetUniform("Time").set(time);
                    if (thermalOverworld) {
                        effect.safeGetUniform("RainStrength").set(rain);
                        effect.safeGetUniform("Snowy").set(snowy);
                    }
                }
            } catch (Exception ignored) {}
            post.process(partialTick);
            minecraft.getMainRenderTarget().bindWrite(false);
        }
        ThermalVisionClient.renderingWorld = false;
    }

    @Inject(method = "resize", at = @At("TAIL"), require = 0)
    private void thermalvision$resize(int w, int h, CallbackInfo ci) {
        if (thermalvision$postOverworld != null) thermalvision$postOverworld.resize(w, h);
        if (thermalvision$postNether != null) thermalvision$postNether.resize(w, h);
        if (thermalvision$postNightVision != null) thermalvision$postNightVision.resize(w, h);
    }

    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private void thermalvision$onClose(CallbackInfo ci) {
        thermalvision$closeAll();
    }

    @Unique
    private void thermalvision$closeAll() {
        // 兜底：关闭游戏时若仍处于挂起状态，把管线交还 Iris 走正常销毁流程
        com.thermalvision.client.IrisCompat.releaseBeforeLevelChange();
        if (thermalvision$postOverworld != null) { thermalvision$postOverworld.close(); thermalvision$postOverworld = null; }
        if (thermalvision$postNether != null) { thermalvision$postNether.close(); thermalvision$postNether = null; }
        if (thermalvision$postNightVision != null) { thermalvision$postNightVision.close(); thermalvision$postNightVision = null; }
        thermalvision$shadersReady = false;
    }
}

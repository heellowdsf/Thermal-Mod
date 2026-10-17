package com.thermalvision.client;

import com.thermalvision.ThermalVisionMod;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

/**
 * 注册自定义热成像实体着色器
 *
 * 该着色器只用纹理 alpha 裁形状、颜色输出纯绿（无视纹理 RGB）
 */
@Mod.EventBusSubscriber(modid = ThermalVisionMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ThermalShaders {

    private static ShaderInstance thermalEntityShader;

    public static ShaderInstance getThermalEntityShader() {
        return thermalEntityShader;
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            new ResourceLocation(ThermalVisionMod.MOD_ID, "thermal_entity"),
                            com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY),
                    shader -> thermalEntityShader = shader
            );
            ThermalVisionMod.LOGGER.info("[ThermalVision] Thermal entity shader registered.");
        } catch (IOException e) {
            ThermalVisionMod.LOGGER.error("[ThermalVision] Failed to register thermal entity shader.", e);
        }
    }
}

package com.thermalvision.client.mixin;

import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 调用 ParticleEngine.clearParticles()*/
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Invoker("clearParticles")
    void thermalvision$clearParticles();
}

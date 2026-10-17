package com.thermalvision.client;

import com.thermalvision.ThermalVisionMod;
import net.minecraftforge.fml.ModList;

/**
 * Embeddium 软依赖隔离层
 *
 * 先用ModList检测Embeddium是否加载，确认后才调用引用 org.embeddedt的代码
 * 由于VM在首次执行到某方法时才会链接其引用的类，把对Embeddium API的引用关在
 * ThermalChunkMeshAppender 里、只在检测通过后调用，就能保证没装 Embeddium 时
 * 这些类永远不被加载，mod不会因NoClassDefFoundError崩溃
 *
 */
public final class EmbeddiumHeatHook {

    private static boolean embeddiumPresent = false;
    private static boolean initialized = false;

    /** 是否检测到 Embeddium */
    public static boolean isEmbeddiumPresent() {
        return embeddiumPresent;
    }

    /**
     * 客户端启动时调用一次 / called once on client setup.
     * 检测 Embeddium，若存在则注册区块网格集成。
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        embeddiumPresent = ModList.get().isLoaded("embeddium");
        if (!embeddiumPresent) {
            ThermalVisionMod.LOGGER.info("[ThermalVision] Embeddium not found — using fallback block-heat markers.");
            return;
        }

        try {
            com.thermalvision.client.embeddium.ThermalChunkMeshAppender.register();
            ThermalVisionMod.LOGGER.info("[ThermalVision] Embeddium detected — real-shape heat-source rendering enabled.");
        } catch (Throwable t) {
            embeddiumPresent = false;
            ThermalVisionMod.LOGGER.error("[ThermalVision] Failed to hook Embeddium, falling back to markers.", t);
        }
    }

    private EmbeddiumHeatHook() {}
}

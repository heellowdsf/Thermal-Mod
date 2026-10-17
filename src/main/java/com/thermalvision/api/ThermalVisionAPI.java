package com.thermalvision.api;

import com.thermalvision.client.HeatMap;
import com.thermalvision.client.ThermalVisionClient;
import net.minecraft.world.level.block.Block;

/**
 * 热成像公开 API
 */
public final class ThermalVisionAPI {

    private ThermalVisionAPI() {}

    /** API 版本；破坏性改动时递增。/ API version; incremented on breaking changes. */
    public static final int API_VERSION = 1;
    /** 视觉模式 / vision mode. */
    public enum Mode { OFF, THERMAL, NIGHT_VISION }
    /** 当前模式 / current mode. */
    public static Mode getMode() {
        switch (ThermalVisionClient.getMode()) {
            case THERMAL:      return Mode.THERMAL;
            case NIGHTVISION:  return Mode.NIGHT_VISION;
            default:           return Mode.OFF;
        }
    }

    public static boolean isThermalActive()      { return ThermalVisionClient.isActive(); }
    public static boolean isNightVisionActive()  { return ThermalVisionClient.isNightVisionActive(); }
    public static boolean isAnyActive()          { return ThermalVisionClient.isAnyActive(); }
    public static void openThermal() { ThermalVisionClient.openThermal(); }
    public static void openNightVision() { ThermalVisionClient.openNightVision(); }
    public static void close() { ThermalVisionClient.closeVision(); }
    public static void toggleThermal() {
        if (ThermalVisionClient.isActive()) ThermalVisionClient.closeVision();
        else ThermalVisionClient.openThermal();
    }
    public static void setRequireGogglesItem(boolean required) {
        ThermalVisionClient.setRequireGogglesItem(required);
    }
    public static boolean isRequireGogglesItem() {
        return ThermalVisionClient.isRequireGogglesItem();
    }
    public static void registerHeatSource(Block block, float heat) {
        HeatMap.registerHeatSource(block, heat);
    }

    /** 移除自定义*/
    public static void removeHeatSource(Block block) {
        HeatMap.unregisterHeatSource(block);
    }

    /** 查询某方块当前的热量（含自定义覆盖），0 表示不发热。*/
    public static float getBlockHeat(Block block) {
        return HeatMap.blockHeat(block);
    }

    //模式变化监听
    /** 模式变化回调 */
    public interface ModeListener {
        void onModeChanged(Mode from, Mode to);
    }

    public static void addModeListener(ModeListener listener) {
        if (listener == null) return;
        ThermalVisionClient.addModeListener((from, to) ->
                listener.onModeChanged(map(from), map(to)));
    }

    // 整合包是进程存续期一直挂
    private static Mode map(ThermalVisionClient.VisionMode m) {
        switch (m) {
            case THERMAL:     return Mode.THERMAL;
            case NIGHTVISION: return Mode.NIGHT_VISION;
            default:          return Mode.OFF;
        }
    }
}

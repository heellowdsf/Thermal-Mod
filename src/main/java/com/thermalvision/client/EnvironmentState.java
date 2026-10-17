package com.thermalvision.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

/**
 * 环境状态采样
 *
 * 每 tick 采样玩家所在的生物群系温度、天气、是否在水下，
 * 这些参数驱动热成像的真实环境表现：
 *   - 雪地：背景冷，实体高对比锐利
 *   - 雨天：信号散射，对比度下降，远处衰减
 *   - 水下：红外被强吸收，能见距离骤降
 *
 * 所有值都做平滑插值，避免穿越群系边界 / 天气突变时画面跳变。
 *
 */
public class EnvironmentState {

    // 平滑后的当前值
    private static float rainStrength = 0f;     // 降雨强度 0~1
    private static float snowy = 0f;            // 寒冷度 0~1
    private static float underwater = 0f;       // 水下程度 0~1

    // 平滑速度
    private static final float LERP = 0.08f;

    // 是否已初始化（开启后第一帧直接跳到真实值，不从默认值慢慢过渡）
    private static boolean initialized = false;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;

        BlockPos pos = player.blockPosition();
        Biome biome = level.getBiome(pos).value();

        // 生物群系温度
        // MC 范围约 -0.5(雪)~2.0(沙漠)，平原 0.8
        float targetTemp = biome.getBaseTemperature();

        // 寒冷度
        // 温度 <0.15 视为寒冷群系；正在下雪额外加成
        float targetSnowy = 0f;
        if (targetTemp < 0.15f) {
            targetSnowy = Math.min(1f, (0.15f - targetTemp) / 0.5f + 0.4f);
        }
        // 正在下雪（寒冷群系 + 降水）
        boolean precipitating = level.isRaining();
        if (precipitating && targetTemp <= 0.15f) {
            targetSnowy = Math.max(targetSnowy, 0.7f);
        }

        // 降雨强度
        // 只在温暖群系算"雨"（寒冷群系的降水是雪，走 snowy 通道）
        float targetRain = 0f;
        if (precipitating && targetTemp > 0.15f) {
            targetRain = level.getRainLevel(1.0f);
            // 雷暴增强
            if (level.isThundering()) {
                targetRain = Math.min(1f, targetRain * 1.35f + 0.15f);
            }
        }

        // 水下
        // isUnderWater(): 眼睛在水方块中
        float targetUnderwater = player.isUnderWater() ? 1f : 0f;

        // 第一帧直接跳到真实值
        if (!initialized) {
            initialized = true;
            rainStrength = targetRain;
            snowy = targetSnowy;
            underwater = targetUnderwater;
            return;
        }

        // === 平滑插值
        rainStrength += (targetRain - rainStrength) * LERP;
        snowy += (targetSnowy - snowy) * LERP;
        // 水下进出要快一点
        underwater += (targetUnderwater - underwater) * 0.20f;
    }

    public static void reset() {
        rainStrength = 0f;
        snowy = 0f;
        underwater = 0f;
        initialized = false;   // 下次开启重新
    }

    public static float getRainStrength() { return rainStrength; }
    public static float getSnowy()        { return snowy; }
    public static float getUnderwater()   { return underwater; }

    /**
     * 实体热信号衰减系数
     *
     * 综合距离、雨、水的影响，返回 0~1 的乘数作用于实体绿色强度。
     *
     * @param distSq 实体到相机距离平方
     * @param entityInWater 实体是否在水中
     */
    public static float entityAttenuation(double distSq, boolean entityInWater) {
        float att = 1f;
        double dist = Math.sqrt(distSq);

        // 距离衰减
        // 现实：红外辐射随距离被大气吸收/扩散，越远越弱。
        // 晴天：180 格内无衰减，180 格后越远越弱（约 360 格淡到最低）。
        // 雨天：雨幕散射使有效距离缩短到 100 格内无衰减，100 格后较明显衰减，
        //       雨越大远处淡得越彻底（可直接淡到与环境同色）。
        float nearDist, farDist, floor;
        if (rainStrength > 0.01f) {
            // 雨天：近 90 格无衰减，远端随雨势缩短（小雨~260，暴雨~180）
            nearDist = 90f;
            farDist  = 260f - rainStrength * 80f;
            // 雨越大 floor 越低（暴雨时远处直接淡到接近 0 = 环境色）
            floor = 0.10f * (1f - rainStrength);
        } else {
            // 晴天：近 120 格无衰减，远 360 格，保留少量微光
            nearDist = 120f;
            farDist  = 360f;
            floor = 0.15f;
        }

        float fade = (float) (1.0 - (dist - nearDist) / (farDist - nearDist));
        fade = Math.max(0.0f, Math.min(1.0f, fade));
        // 平方曲线，远处淡得更自然
        fade = fade * fade;
        att *= floor + fade * (1f - floor);

        // 雨天整体再轻微压低信号（湿度吸收），但幅度很小，近处仍明显
        att *= 1f - rainStrength * 0.06f;

        // 水的阻隔
        // 注意：水的冷却效果由 HeatMap.entityCooling 平滑处理（每实体渐变），
        // 这里不做硬切换——否则实体在水面上下浮动时 isUnderWater() 每 tick 抖动，
        // 会导致 att 在 0.12↔1.0 间突变而疯狂闪烁。
        // 仅当玩家自己在水下时，整体压暗远处（水吸收红外），且用平滑 underwater 值。
        if (underwater > 0.01f) {
            float waterDim = 1f - underwater * 0.5f * Math.min(1f, (float)(dist / 16.0));
            att *= Math.max(0.2f, waterDim);
        }

        return Math.max(0f, Math.min(1f, att));
    }
}

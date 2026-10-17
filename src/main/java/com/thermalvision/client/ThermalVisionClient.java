package com.thermalvision.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.thermalvision.ThermalVisionMod;
import net.minecraft.world.level.Level;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端核心：视觉模式切换、按键绑定、事件处理
 */
public class ThermalVisionClient {

    public enum VisionMode { OFF, THERMAL, NIGHTVISION }
    private static VisionMode mode = VisionMode.OFF;
    public static boolean renderingWorld = false;
    static KeyMapping key;

    // 网格染绿开关：控制Embeddium appender是否把热源方块染绿
    // 正确处理关闭：关闭时立刻设 false，触发的重建产生正常网格，不再是绿色
    // 但 mode 保持 THERMAL 几帧让全屏着色器把"还没重建完的残留绿色"转成白色，避免一闪
    // 若关闭仍用 mode 判断，重建会又染一次绿，导致绿色永久残留。
    private static boolean meshTintActive = false;
    // 延迟关闭倒计时
    private static int pendingCloseFrames = 0;
    public static boolean isActive() { return mode == VisionMode.THERMAL; }
    public static boolean isNightVisionActive() { return mode == VisionMode.NIGHTVISION; }
    public static boolean isAnyActive() { return mode != VisionMode.OFF; }
    public static VisionMode getMode() { return mode; }

    // 附魔光效强度抑制
    // 热成像开启时把「辅助功能中附魔光效闪烁强度」Options.glintStrength 设为 0，
    // 记住原值，关闭时还原。这样附魔盔甲/物品那条流动光带在热成像里根本不会绘制
    // 它是可见光反射、不是温度，本就不该出现。这是纯游戏设置层的开关，不碰渲染管线，
    // 这比在 mixin 里拦截干净可靠得多。
    private static Double savedGlintStrength = null;   // null就是当前未覆盖

    private static void suppressGlint() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        if (savedGlintStrength == null) {              // 只在首次覆盖时保存原值
            savedGlintStrength = mc.options.glintStrength().get();
        }
        mc.options.glintStrength().set(0.0);}

    /** 还原玩家原来的附魔光效 */
    private static void restoreGlint() {
        if (savedGlintStrength != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options != null) mc.options.glintStrength().set(savedGlintStrength);
            savedGlintStrength = null;
        }
    }

    //Embeddium appender 是否应染绿（关闭时立即关，避免重建又染绿）
    public static boolean isMeshTintActive() { return meshTintActive; }

    /**
     * 每渲染帧调用：推进延迟关闭。倒计时结束时真正关闭热成像。
     */
    public static void tickPendingClose() {
        if (pendingCloseFrames > 0) {
            pendingCloseFrames--;
            if (pendingCloseFrames == 0) {
                finishClose();
            }
        }
    }

    /**
     * 关闭热成像唯一出口：清状态+恢复光影管线。
     *
     * 光影恢复必须放在这里（而不是按键那一刻），因为延迟关闭期间 mode 仍是 THERMAL，
     * 后处理链还在跑,若提前恢复光影，那几帧就会用色键去处理一张已经被光影包
     * 上色的画面，出现明显闪烁。等 mode 真正变 OFF 再恢复，画面是干净的。
     */
    private static void finishClose() {
        mode = VisionMode.OFF;
        HeatMap.clear();
        IrisCompat.restore();
        restoreGlint();// 还原附魔光效强度
        fireModeChanged(VisionMode.THERMAL, VisionMode.OFF);
    }

    //供按键与 API 共用的开关逻辑
    // 开启热成像（不检查护目镜，调用方自行决定）
    public static void openThermal() {
        Minecraft mc = Minecraft.getInstance();
        VisionMode prev = mode;
        mode = VisionMode.THERMAL;
        meshTintActive = true;
        pendingCloseFrames = 0;
        suppressGlint();
        EnvironmentState.reset();
        EnvironmentState.tick();
        if (mc.level != null && mc.player != null)
            HeatMap.tick(mc.level, mc.player);
        boolean rebuiltByShaderSuspend = IrisCompat.suspend();
        if (!rebuiltByShaderSuspend) ForgeEvents.rebuildChunksForHeat();
        if (mc.particleEngine != null)
            ((com.thermalvision.client.mixin.ParticleEngineAccessor) mc.particleEngine)
                    .thermalvision$clearParticles();
        fireModeChanged(prev, mode);
    }

    //开启夜视仪
    public static void openNightVision() {
        VisionMode prev = mode;
        mode = VisionMode.NIGHTVISION;
        fireModeChanged(prev, mode);
    }

    //关闭当前视觉模式（热成像走延迟关闭防绿残留,夜视仪立即关）
    public static void closeVision() {
        if (mode == VisionMode.THERMAL) {
            EnvironmentState.reset();
            meshTintActive = false;
            ForgeEvents.rebuildChunksForHeat();
            if (EmbeddiumHeatHook.isEmbeddiumPresent()) {
                pendingCloseFrames = 8;
            } else {
                finishClose();
            }
        } else if (mode == VisionMode.NIGHTVISION) {
            VisionMode prev = mode;
            mode = VisionMode.OFF;
            fireModeChanged(prev, mode);
        }
    }

    // API支持：激活门槛+状态监听
    //是否要求背包里有护目镜才能激活。整合包/开发者可通过 API 关掉，做成纯按键或自定义门槛
    private static volatile boolean requireGogglesItem = true;
    public static void setRequireGogglesItem(boolean v) { requireGogglesItem = v; }
    public static boolean isRequireGogglesItem() { return requireGogglesItem; }
    /** 模式变化监听器（开发者/整合包挂钩*/
    public interface ModeListener { void onModeChanged(VisionMode from, VisionMode to); }
    private static final java.util.List<ModeListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static void addModeListener(ModeListener l) { if (l != null) listeners.add(l); }
    public static void removeModeListener(ModeListener l) { listeners.remove(l); }
    private static void fireModeChanged(VisionMode from, VisionMode to) {
        if (from == to) return;
        for (ModeListener l : listeners) {
            try { l.onModeChanged(from, to); } catch (Exception e) {
                ThermalVisionMod.LOGGER.warn("[ThermalVision] mode listener threw", e);
            }
        }
    }

    private static boolean hasThermalGoggles(LocalPlayer player) {
        if (!requireGogglesItem) return true;   // API 可关闭物品门槛
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(ThermalVisionMod.THERMAL_GOGGLES.get())) return true;
        }
        return false;
    }

    private static boolean hasNightVisionGoggles(LocalPlayer player) {
        if (!requireGogglesItem) return true;   // API 可关闭物品门槛
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(ThermalVisionMod.NIGHT_VISION_GOGGLES.get())) return true;
        }
        return false;
    }

    //MOD BUS
    @Mod.EventBusSubscriber(modid = ThermalVisionMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModEvents {

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            key = new KeyMapping(
                    "key.thermalvision.toggle",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_X,
                    "category.thermalvision");
            event.register(key);
        }

        @SubscribeEvent
        public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
            // 检测 Embeddium 并注册区块网格热源集成（没装则降级到标记）
            event.enqueueWork(EmbeddiumHeatHook::init);
            // 检测 Oculus/Iris 并解析快速挂起所需的反射句柄（没装则全部空操作）
            event.enqueueWork(IrisCompat::init);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        @SubscribeEvent
        public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
            // 为所有 LivingEntity 渲染器添加热成像层
            for (EntityType type : ForgeRegistries.ENTITY_TYPES) {
                EntityRenderer renderer = event.getRenderer(type);
                if (renderer instanceof LivingEntityRenderer living) {
                    living.addLayer(new ThermalFeatureRenderer(living));
                }
            }
            for (String skin : event.getSkins()) {
                LivingEntityRenderer playerRenderer = event.getSkin(skin);
                if (playerRenderer != null) {
                    playerRenderer.addLayer(new ThermalFeatureRenderer(playerRenderer));
                }
            }
        }
    }

    //FORGE BUS 事件（运行时）
    @Mod.EventBusSubscriber(modid = ThermalVisionMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ForgeEvents {

        /**
         * 标记玩家周围一定范围的区块 section 为"脏"，触发重新网格化，
         * 让热源方块的染绿状态更新（开/关热成像时调用）。
         * 用 setSectionDirty（与放/破方块相同的触发机制）而非 allChanged()，
         * 只重建附近区块而非全世界，避免明显卡顿。
         * 只在 Embeddium 在场时需要——降级标记方案每帧实时绘制。
         */
        private static void rebuildChunksForHeat() {
            if (!EmbeddiumHeatHook.isEmbeddiumPresent()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.levelRenderer == null || mc.player == null) return;

            // 玩家所在 section 坐标（方块坐标 >> 4）/ player's section coords (block >> 4)
            int csx = mc.player.blockPosition().getX() >> 4;
            int csy = mc.player.blockPosition().getY() >> 4;
            int csz = mc.player.blockPosition().getZ() >> 4;

            // 重建半径只需覆盖热源扫描范围（scanBlocks 只扫玩家 ±10 格）。之前用 8 section
            // (±128 格) 会重建 17×17×9≈2601 个区块，而其中只有 ±10 格内的才有热源可染绿，
            // 其余全是无谓重建——正是「开热成像明显卡顿」和「关闭后绿色残留（重建太多没做完）」
            // 的根源。缩到 2 section(±32 格) 刚好覆盖 ±10 格扫描（含边界余量），
            // 区块数降到 5×5×5=125，减少约 20 倍，染绿效果不损失，开/关都更顺、残留更少。
            int rH = 2, rV = 2;
            for (int dx = -rH; dx <= rH; dx++) {
                for (int dz = -rH; dz <= rH; dz++) {
                    for (int dy = -rV; dy <= rV; dy++) {
                        mc.levelRenderer.setSectionDirty(csx + dx, csy + dy, csz + dz);
                    }
                }
            }
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();

            while (key != null && key.consumeClick()) {
                if (mode != VisionMode.OFF) {
                    // 关闭 / Turn OFF
                    VisionMode was = mode;
                    closeVision();
                    if (mc.player != null)
                        mc.player.displayClientMessage(Component.literal(
                                was == VisionMode.THERMAL
                                        ? "§c[Thermal] OFF"
                                        : "§c[NVG] OFF"), true);
                } else {
                    // 开启（热成像优先）
                    if (hasThermalGoggles(mc.player)) {
                        openThermal();
                        if (mc.player != null)
                            mc.player.displayClientMessage(
                                    Component.literal("§a[Thermal] ON"), true);
                    } else if (hasNightVisionGoggles(mc.player)) {
                        openNightVision();
                        if (mc.player != null)
                            mc.player.displayClientMessage(
                                    Component.literal("§a[NVG] ON"), true);
                    }
                }
            }

            // 设备移出背包自动关闭
            if (mode == VisionMode.THERMAL && !hasThermalGoggles(mc.player)) {
                // 同样先关染绿再重建，避免绿色残留
                meshTintActive = false;
                rebuildChunksForHeat();
                pendingCloseFrames = 0;
                finishClose();
            }
            if (mode == VisionMode.NIGHTVISION && !hasNightVisionGoggles(mc.player)) {
                mode = VisionMode.OFF;
            }

            // 仅热成像激活时 tick 热力图 + 环境状态
            if (mode == VisionMode.THERMAL && mc.level != null && mc.player != null) {
                HeatMap.tick(mc.level, mc.player);
                EnvironmentState.tick();
            }
        }

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                HeatRenderer.render(event.getPoseStack(), event.getCamera());
            }
        }

        /**
         * 退出世界 / 断开连接：必须把挂起的光影管线交还给 Iris。
         * Iris 只销毁 PipelineManager 那张 map 里的管线；我们挂起时把它换了出来，
         * 不还回去它就永远不会被销毁，framebuffer 和纹理显存直接泄漏。
         */
        @SubscribeEvent
        public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            IrisCompat.releaseBeforeLevelChange();
            mode = VisionMode.OFF;
            meshTintActive = false;
            pendingCloseFrames = 0;
            HeatMap.clear();
            EnvironmentState.reset();
            restoreGlint();          // 退出世界务必还原，避免把玩家的光效设置写坏
        }
    }
}

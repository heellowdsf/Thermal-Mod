package com.thermalvision.client;

import com.thermalvision.ThermalVisionMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 这里我需要详细说明！！！因为有点复杂 
 * Oculus / Iris 光影兼容层 —— 热成像期间挂起光影管线，且不触发 reload。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 1、为什么热成像和光影天然冲突
 * ══════════════════════════════════════════════════════════════════════════
 * 本mod的热成像是屏幕空间色键：实体经自定义 core shader 输出纯绿 (0,G,0)，
 * thermal_grayscale.fsh 靠 R≈0 && B≈0 && G>0.02 判定热源。这要求颜色管线从顶点
 * 输出到后处理读取全程不被改动。
 *
 * 而光影包做的正好相反：Oculus 会把所有 ShaderInstance 换成包里的 gbuffers_*，
 * 绿色被阴影/雾/tonemap/bloom 处理之后 R、B 不再是 0，色键彻底失效；
 * LightTextureMixin 的满亮度也被包自己的光照模型盖过，
 * SkyMixin / FogMixin / ParticleMixin 同样被接管。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 2、为什么不用官方 API IrisApi.setShadersEnabledAndApply
 * ══════════════════════════════════════════════════════════════════════════
 * 它内部走 Iris.reload()：
 *     irisConfig.initialize();
 *     destroyEverything();    // 销毁管线 + 卸载整个 shaderpack
 *     loadShaderpack();       // 从磁盘重新解析、预处理整个包
 *     preparePipeline(...);   // 主线程同步重编译全部 program
 * 读盘重新解析 + 重编译几十上百个 program，这就是那 2 秒卡帧
 * 对临时关一下光影而言这些工作一件都不需要做。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 3、本类的做法：只换引用，不销毁
 * ══════════════════════════════════════════════════════════════════════════
 * PipelineManager 的状态只有两处：
 *     private WorldRenderingPipeline pipeline;
 *     private final Map<NamespacedId, WorldRenderingPipeline> pipelinesPerDimension;
 * preparePipeline() 每帧在 renderLevel 开头调用，命中 map 即 pipeline = map.get(dim)。
 *
 * 所以只要把当前维度的 map 条目换成一个 VanillaRenderingPipeline，并把原来的
 * IrisRenderingPipeline 对象存在自己手里（不销毁），开销就是一次 HashMap put +
 * 一次字段赋值。恢复时塞回去即可。没有销毁、没有读盘、没有重编译
 *
 * 换完立刻生效的正是热成像最依赖的部分 —— MixinShaderInstance 与 MixinGameRenderer
 * 判断是否覆盖 core shader 用的都是：
 *     pipeline instanceof ShaderRenderingPipeline
 * 一旦变成 VanillaRenderingPipeline 就恒为 false，于是 thermal_entity core shader
 * 恢复执行、纯绿原样进帧缓冲、满亮度光照贴图重新有效、天空/雾/粒子拦截全部回归
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 4、唯一代价：区块必须重新网格化
 * ══════════════════════════════════════════════════════════════════════════
 * VanillaRenderingPipeline 的构造函数会 setUseExtendedVertexFormat(false)。
 * Oculus 的扩展格式 XHFP 前 20 字节其实和 Embeddium 的 CompactChunkVertex 逐字节
 * 相同（同样的 POSITION_MATERIAL_MESH / COLOR_SHADE / BLOCK_TEXTURE / LIGHT_TEXTURE
 * 与偏移），本来有机会免重建，但两边的属性绑定点错开了一位：
 *     Embeddium: POSITION=0, COLOR=1, TEXTURE=2, LIGHT=3
 *     Oculus   : POSITION=1, COLOR=2, TEXTURE=3, LIGHT=4
 * Embeddium 的默认区块着色器在 location 0 取位置，而扩展 VAO 把位置绑在 location 1
 * 所以必须 allChanged() 重新网格化。
 *
 * 但这对本mod是免费的 —— 开关镜本来就要重建区块给热源方块染绿
 * （原先的 rebuildChunksForHeat()），allChanged() 是替换它而不是额外开销
 * 而且 Embeddium 的重新网格化在 worker 线程异步进行，主线程不阻塞
 * 表现为区块陆续刷出而非冻屏 —— 与 reload 的同步 GLSL 编译不是一个量级
 *
 * 恢复时 IrisChunkProgramOverrides 会重建 6 个地形 program（约 100~300ms）
 * 这是整个方案残留的唯一主线程开销
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 5、安全性
 * ══════════════════════════════════════════════════════════════════════════
 * 全程反射，编译期零依赖，没装 Oculus 时所有方法都是空操作
 * 任何一个句柄解析失败 - fastPathAvailable = false，按 ALLOW_SLOW_FALLBACK
 * 决定是否退回官方 API（有卡顿）或直接放弃（热成像效果会不正确，但不会崩）
 *
 * 已对 Oculus 1.20.1 源码逐项核对：三个字段名、VanillaRenderingPipeline 同包
 * WorldRenderingSettings.INSTANCE 为 public static final
 * Iris.getPipelineManager()/getCurrentDimension() 为 public static
 * WorldRenderingPipeline.destroy() 存在。
 */
public final class IrisCompat {

    /**
     * 快路径不可用时（例如 Oculus 改了内部结构）是否允许退回官方 API
     * 官方 API 会触发完整 reload，有 1~3 秒硬卡顿；设为 false 则宁可放弃挂起
     * 此时热成像在光影开启下效果不正确，但不会卡、不会崩
     */
    public static boolean ALLOW_SLOW_FALLBACK = true;

    private static boolean initialized = false;
    private static boolean fastPathAvailable = false;
    private static boolean apiAvailable = false;
    private static boolean warnedUser = false;

    //快路径反射句柄
    private static Method mGetPipelineManager;      // Iris.getPipelineManager()
    private static Method mGetCurrentDimension;     // Iris.getCurrentDimension()
    private static Field fPipeline;                 // PipelineManager.pipeline
    private static Field fPipelinesPerDimension;    // PipelineManager.pipelinesPerDimension
    private static Field fVersionCounter;           // PipelineManager.versionCounterForSodiumShaderReload
    private static Class<?> vanillaPipelineClass;   // VanillaRenderingPipeline
    private static Method mDestroyPipeline;         // WorldRenderingPipeline.destroy()
    private static Object worldRenderingSettings;   // WorldRenderingSettings.INSTANCE

    // 慢路径（官方 API）句柄
    private static Object apiInstance;
    private static Method mGetConfig;
    private static Method mSetShadersEnabledAndApply;

    // 挂起状态
    private static Object savedPipeline;              // 被换下的 IrisRenderingPipeline
    private static Object savedDimension;             // 对应维度 NamespacedId
    private static Object installedVanilla;           // 我们塞进去的那个 vanilla 实例
    private static Map<Field, Object> savedSettings;  // WorldRenderingSettings 快照
    private static boolean fastSuspended = false;
    private static boolean slowSuspended = false;

    private IrisCompat() {}

    public static boolean isFastPathAvailable() { return fastPathAvailable; }
    public static boolean isSuspended() { return fastSuspended || slowSuspended; }

    // 初始化

    /** 客户端启动时调用一次*/
    public static void init() {
        if (initialized) return;
        initialized = true;

        if (!ModList.get().isLoaded("oculus") && !ModList.get().isLoaded("iris")) {
            ThermalVisionMod.LOGGER.info("[ThermalVision] No Oculus/Iris — nothing to do.");
            return;
        }

        initFastPath();
        initSlowPath();

        if (!fastPathAvailable) {
            ThermalVisionMod.LOGGER.warn(
                    "[ThermalVision] Oculus internals not matched; fast suspension unavailable. "
                            + "Falling back to {}.",
                    (ALLOW_SLOW_FALLBACK && apiAvailable) ? "the official API (causes a stall)" : "no suspension");
        }
    }

    private static void initFastPath() {
        for (String irisName : new String[]{
                "net.irisshaders.iris.Iris",
                "net.coderbot.iris.Iris"}) {
            try {
                Class<?> iris = Class.forName(irisName);
                mGetPipelineManager = iris.getMethod("getPipelineManager");
                mGetCurrentDimension = iris.getMethod("getCurrentDimension");

                Object mgr = mGetPipelineManager.invoke(null);
                Class<?> mgrClass = mgr.getClass();

                fPipeline = mgrClass.getDeclaredField("pipeline");
                fPipelinesPerDimension = mgrClass.getDeclaredField("pipelinesPerDimension");
                fVersionCounter = mgrClass.getDeclaredField("versionCounterForSodiumShaderReload");
                fPipeline.setAccessible(true);
                fPipelinesPerDimension.setAccessible(true);
                fVersionCounter.setAccessible(true);

                // VanillaRenderingPipeline 与 PipelineManager 同包，用包名推导
                // 同时兼容 net.irisshaders.* 与 net.coderbot.* 两套命名
                vanillaPipelineClass = Class.forName(
                        mgrClass.getPackageName() + ".VanillaRenderingPipeline");
                // destroy() 来自 WorldRenderingPipeline 接口，用于维度切换时防显存泄漏
                mDestroyPipeline = Class.forName(
                        mgrClass.getPackageName() + ".WorldRenderingPipeline").getMethod("destroy");

                if (!resolveWorldRenderingSettings()) {
                    ThermalVisionMod.LOGGER.warn(
                            "[ThermalVision] WorldRenderingSettings not found — fast path disabled.");
                    return;
                }

                fastPathAvailable = true;
                ThermalVisionMod.LOGGER.info(
                        "[ThermalVision] Fast pipeline suspension ready ({}).", irisName);
                return;
            } catch (Throwable t) {
                // 换下一个候选包名
            }
        }
    }

    /** 定位 WorldRenderingSettings.INSTANCE（新版）或 BlockRenderingSettings.INSTANCE（旧 Oculus） */
    private static boolean resolveWorldRenderingSettings() {
        for (String name : new String[]{
                "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings",
                "net.coderbot.iris.block_rendering.BlockRenderingSettings"}) {
            try {
                worldRenderingSettings = Class.forName(name).getField("INSTANCE").get(null);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void initSlowPath() {
        for (String cls : new String[]{
                "net.irisshaders.iris.api.v0.IrisApi",
                "net.coderbot.iris.api.v0.IrisApi"}) {
            try {
                Class<?> api = Class.forName(cls);
                apiInstance = api.getMethod("getInstance").invoke(null);
                mGetConfig = api.getMethod("getConfig");
                mSetShadersEnabledAndApply = mGetConfig.getReturnType()
                        .getMethod("setShadersEnabledAndApply", boolean.class);
                apiAvailable = true;
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    // 查询

    /** 当前是否真的有光影包在渲染（挂起期间返回 false，语义正确） */
    public static boolean isShaderPackActive() {
        if (!fastPathAvailable) return false;
        try {
            Object current = fPipeline.get(mGetPipelineManager.invoke(null));
            return current != null && !vanillaPipelineClass.isInstance(current);
        } catch (Throwable t) {
            return false;
        }
    }

    // 挂起

    /**
     * 开启热成像时调用：挂起光影管线。
     *
     * @return true 表示本方法已经触发过全量区块重建，调用方应跳过自己的
     *         rebuildChunksForHeat()；false 表示什么都没做（没装光影或没开包）
     *         调用方按原逻辑走
     */
    public static boolean suspend() {
        if (isSuspended()) return true;
        if (!isShaderPackActive()) return false;

        if (fastPathAvailable && fastSuspend()) return true;

        if (ALLOW_SLOW_FALLBACK && apiAvailable) {
            try {
                mSetShadersEnabledAndApply.invoke(mGetConfig.invoke(apiInstance), false);
                slowSuspended = true;
                ThermalVisionMod.LOGGER.info("[ThermalVision] Shader pack disabled via API (slow path).");
                return true;   // reload 内部已重建全部区块
            } catch (Throwable t) {
                ThermalVisionMod.LOGGER.error("[ThermalVision] Slow-path suspend failed.", t);
            }
        }

        warnUserOnce();
        return false;
    }

    private static boolean fastSuspend() {
        try {
            Object mgr = mGetPipelineManager.invoke(null);
            Object dim = mGetCurrentDimension.invoke(null);

            @SuppressWarnings("unchecked")
            Map<Object, Object> perDim = (Map<Object, Object>) fPipelinesPerDimension.get(mgr);
            Object current = perDim.get(dim);
            if (current == null || vanillaPipelineClass.isInstance(current)) return false;

            // 先快照设置：VanillaRenderingPipeline 的构造函数会清掉 blockStateIds /
            // blockTypeIds / entityIds / useExtendedVertexFormat 等一整批字段
            // 这些值只在 IrisRenderingPipeline 构造时填充，恢复时必须原样塞回去
            savedSettings = snapshotSettings();

            Object vanilla = vanillaPipelineClass.getDeclaredConstructor().newInstance();

            savedPipeline = current;
            savedDimension = dim;
            installedVanilla = vanilla;
            perDim.put(dim, vanilla);
            fPipeline.set(mgr, vanilla);

            // IrisChunkProgramOverrides 用 shadersCreated 缓存地形 program
            // 递增版本号才会触发它 deleteShaders() 重建
            bumpSodiumShaderVersion(mgr);

            fastSuspended = true;
            remesh();

            ThermalVisionMod.LOGGER.info("[ThermalVision] Pipeline suspended (no reload).");
            return true;
        } catch (Throwable t) {
            ThermalVisionMod.LOGGER.error("[ThermalVision] Fast suspend failed.", t);
            fastSuspended = false;
            savedPipeline = null;
            savedDimension = null;
            installedVanilla = null;
            savedSettings = null;
            return false;
        }
    }

    // 恢复
    /** 关闭热成像时调用：恢复玩家原本的光影管线。 */
    public static void restore() {
        if (slowSuspended) {
            slowSuspended = false;
            try {
                mSetShadersEnabledAndApply.invoke(mGetConfig.invoke(apiInstance), true);
            } catch (Throwable t) {
                ThermalVisionMod.LOGGER.error("[ThermalVision] Slow-path restore failed.", t);
            }
            return;
        }

        if (!fastSuspended) return;
        fastSuspended = false;

        try {
            Object mgr = mGetPipelineManager.invoke(null);
            @SuppressWarnings("unchecked")
            Map<Object, Object> perDim = (Map<Object, Object>) fPipelinesPerDimension.get(mgr);

            if (perDim.get(savedDimension) == installedVanilla) {
                // 正常情况：把原管线塞回去
                restoreSettings(savedSettings);
                perDim.put(savedDimension, savedPipeline);
                fPipeline.set(mgr, savedPipeline);
                bumpSodiumShaderVersion(mgr);
                remesh();
                ThermalVisionMod.LOGGER.info("[ThermalVision] Pipeline restored (no reload).");
            } else {
                // 挂起期间 Iris 自己重建过管线（维度切换等）。
                // 此时 WorldRenderingSettings 属于新管线，不能用旧快照覆盖；
                // 旧管线已不在 map 里，Iris 不会销毁它，必须我们自己销毁防显存泄漏。
                destroySaved();
                ThermalVisionMod.LOGGER.info("[ThermalVision] Stale pipeline released (rebuilt meanwhile).");
            }
        } catch (Throwable t) {
            ThermalVisionMod.LOGGER.error("[ThermalVision] Fast restore failed.", t);
        } finally {
            clearState();
        }
    }

    /**
     * 每渲染帧调用（热成像激活时）：检测挂起期间 Iris 是否自己重建了管线。
     *
     * Oculus 在 Minecraft.updateLevelInEngines 里，一旦维度变化就会 destroyPipeline()
     * 销毁 map 中的所有管线并重建。我们挂起的那个不在 map 里，不处理就会永久泄漏
     * 它的 framebuffer 和纹理显存；同时新维度会重新生成一个 IrisRenderingPipeline
     * 光影会在热成像开着的时候突然回来。这里检测到就销毁旧的、对新的重新挂起
     */
    public static void validate() {
        if (!fastSuspended) return;
        try {
            Object mgr = mGetPipelineManager.invoke(null);
            Object dim = mGetCurrentDimension.invoke(null);

            @SuppressWarnings("unchecked")
            Map<Object, Object> perDim = (Map<Object, Object>) fPipelinesPerDimension.get(mgr);

            boolean sameDimension = dim != null && dim.equals(savedDimension);
            boolean ourVanillaStillThere = perDim.get(savedDimension) == installedVanilla;
            if (sameDimension && ourVanillaStillThere) return;   // 一切正常

            // 管线被 Iris 重建过：销毁孤儿、清状态、对新管线重新挂起
            destroySaved();
            clearState();
            fastSuspended = false;
            fastSuspend();
        } catch (Throwable t) {
            ThermalVisionMod.LOGGER.error("[ThermalVision] Pipeline validate failed.", t);
        }
    }

    /**
     * 退出世界 / 断开连接前必须调用
     * Iris 会销毁 map 里的所有管线；我们挂起的那个不在 map 里
     * 不塞回去就会泄漏 framebuffer 和纹理显存
     */
    public static void releaseBeforeLevelChange() {
        if (slowSuspended) {
            restore();
            return;
        }
        if (!fastSuspended) return;

        try {
            Object mgr = mGetPipelineManager.invoke(null);
            @SuppressWarnings("unchecked")
            Map<Object, Object> perDim = (Map<Object, Object>) fPipelinesPerDimension.get(mgr);

            if (perDim.get(savedDimension) == installedVanilla) {
                // 塞回 map，交给 Iris 按正常流程销毁
                restoreSettings(savedSettings);
                perDim.put(savedDimension, savedPipeline);
                fPipeline.set(mgr, savedPipeline);
            } else {
                destroySaved();
            }
        } catch (Throwable ignored) {
        } finally {
            fastSuspended = false;
            clearState();
        }
    }

    // 辅助

    /** 快照 WorldRenderingSettings 的全部实例字段（逐字段反射，跨版本稳） */
    private static Map<Field, Object> snapshotSettings() throws IllegalAccessException {
        Map<Field, Object> snap = new LinkedHashMap<>();
        for (Field f : worldRenderingSettings.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            snap.put(f, f.get(worldRenderingSettings));
        }
        return snap;
    }

    private static void restoreSettings(Map<Field, Object> snap) {
        if (snap == null) return;
        snap.forEach((f, v) -> {
            try {
                f.set(worldRenderingSettings, v);
            } catch (Throwable ignored) {
            }
        });
    }

    private static void destroySaved() {
        if (savedPipeline == null || mDestroyPipeline == null) return;
        try {
            mDestroyPipeline.invoke(savedPipeline);
        } catch (Throwable t) {
            ThermalVisionMod.LOGGER.warn("[ThermalVision] Could not destroy orphaned pipeline.", t);
        }
    }

    private static void clearState() {
        savedPipeline = null;
        savedDimension = null;
        installedVanilla = null;
        savedSettings = null;
    }

    private static void bumpSodiumShaderVersion(Object mgr) {
        try {
            fVersionCounter.setInt(mgr, fVersionCounter.getInt(mgr) + 1);
        } catch (Throwable ignored) {
        }
    }

    private static void remesh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
    }

    /** 无法挂起光影时提示玩家一次，避免他以为是 mod 坏了 */
    private static void warnUserOnce() {
        if (warnedUser) return;
        warnedUser = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "§e[Thermal] §7无法挂起光影包，热成像效果可能不正确 / "
                            + "could not suspend shader pack"), false);
        }
    }
}

package com.thermalvision.client.mixin;

import com.thermalvision.client.ThermalVisionClient;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 热成像期间强制满亮度光照贴图 / Force a fullbright lightmap while thermal vision is active.
 *
 * ── 为什么这是那些光照 bug 的正解 ──
 * 热像仪探测长波红外（温度），看不见可见光：火把、月光、阴影不会改变热像画面。
 * 但本 mod 的热成像是「屏幕空间后处理」，只能读到已经打完光的画面，画面亮度里混着
 * 太阳/月亮/火把/阴影。着色器里的 valueHint 用的就是画面亮度，于是把光照当成了温度：
 *   · 火把照亮附近方块  → 方块在热像里「变热」
 *   · 光源闪烁/移动    → 阴影跟着抽搐
 *   · 同一个方块       → 白天和夜里表现不一致
 *
 * 修法：热成像开启时把光照贴图的每个像素强制写成白色（满亮度）。世界渲染时就不再有
 * 任何来自太阳/月亮/火把/阴影的亮度差异，画面亮度只剩下「材质本身的反照率」。
 *
 * 这带来一个很关键的连带好处：着色器里那句 valueHint 原本是「泄漏光照」的 bug 来源，
 * 现在它读到的亮度已经与光照无关、只反映材质，于是同一句代码变成了「区分材质」的
 * 正确依据。所以着色器完全不用为了防光照而削弱材质对比 —— 材质区分度 100% 保留。
 *
 * 为什么用 @ModifyArg 而不是 @Shadow 私有字段
 * 直接 @Shadow 访问 LightTexture 的私有字段（lightPixels / lightTexture）有个致命问题：
 * 一旦字段名与实际版本对不上，mixin 会在加载期直接崩游戏，而 require = 0 挡不住这种失败。
 * 这里改成只拦截原版自己那次写像素的调用，把颜色参数换成白色：
 *   · 不访问任何私有字段 → 没有 @Shadow 崩溃风险
 *   · require = 0：万一这次调用找不到（比如别的 mod 重写了该方法），静默不生效，不崩、不报错
 *   · 不 cancel 原版方法 → 上传贴图、脏标记等全交给原版处理，行为最贴近原生
 * 失败时的最坏结果只是「没修好」，绝不会把画面弄坏或让游戏崩溃。
 *
 *
 * 仅 THERMAL 模式生效：普通游戏、以及需要保留低光放大的夜视仪，完全不受影响。
 * 原版每 tick 会把光照贴图标脏并重算，所以开关热成像后最多一 tick 内即生效/复原
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {

    @ModifyArg(
            method = "updateLightTexture",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/platform/NativeImage;setPixelRGBA(III)V"),
            index = 2,
            require = 0)
    private int thermalvision$fullbrightPixel(int original) {
        return ThermalVisionClient.isActive() ? 0xFFFFFFFF : original;
    }
}

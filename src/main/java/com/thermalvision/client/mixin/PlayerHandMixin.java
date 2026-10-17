package com.thermalvision.client.mixin;

import com.thermalvision.client.ThermalBufferSource;
import com.thermalvision.client.ThermalVisionClient;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 第一人称手臂发热 
 *
 * 让第一人称的玩家手臂（空手时、以及持枪/持物时的那只手臂）在热成像里像其它活物一样
 * 呈白热，而手里的枪/物品不发热。
 *
 * 为什么拦 PlayerRenderer.renderRightHand / renderLeftHand：
 *   · 原版第一人称手：ItemInHandRenderer.renderArmWithItem → renderPlayerArm →
 *     PlayerRenderer.renderRightHand/renderLeftHand（用玩家皮肤画手臂）
 *   · TACZ 持枪手臂：TACZ 的 RightHandRender/LeftHandRender 最终也调用
 *     RenderHelper.renderFirstPersonArm → renderer.renderRightHand/renderLeftHand
 *   两条路径都汇聚到这两个方法，而枪是 TACZ 用自己的 bedrock 模型单独画的、不经过这里
 *   所以在这里把手臂的 MultiBufferSource 换成热成像 buffer，手臂就白热，枪保持原样
 *   手发热、枪不发热。一次覆盖原版手 + 任何把手臂交给 PlayerRenderer 画的枪械 mod
 *
 *
 * filterNonBody=true → 手臂走实体白热通道（按皮肤 alpha 裁形，或整臂纯绿），不会掉进盔甲
 * 的「未知纹理丢弃」分支。heat=1.0 → 玩家自己的手臂，满白热。
 *
 * 仅热成像模式生效；关闭 / 夜视仪时原样渲染。
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerHandMixin {

    @ModifyVariable(
            method = {
                    "renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V",
                    "renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V"
            },
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private MultiBufferSource thermalvision$wrapArm(MultiBufferSource original) {
        if (!ThermalVisionClient.isActive()) return original;
        return ThermalBufferSource.forArm(original, 1.0f);
    }
}

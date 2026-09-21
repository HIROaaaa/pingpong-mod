package com.whale.pingpong.mixin;

import com.whale.pingpong.client.PingPongClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截左键：手里拿着球拍时，把「左键按下」转成一次蓄力开始（需求 1）。
 *
 * <h2>五期 M6：取消原版攻击动作</h2>
 * 用户实测原话：「现在左键会先攻击一下然后再蓄力，这个原版攻击的动作不能有」。
 * 之前这里只发通知、不取消原版逻辑，于是左键**先播一次原版挥臂**（还带着攻击判定），
 * 再进入我们的蓄力 —— 两个动作叠在一起，看起来就是"先抡一拳再说"。
 *
 * 现在手持球拍时直接 {@code cancel}：
 * <ul>
 *   <li>不再有原版挥手动画（动作全部交给 M6 的模型骨骼系统）；</li>
 *   <li>不再对实体/方块产生攻击判定（拿着球拍本来就不该打人）；</li>
 *   <li>玩家视角完全不动（我们的挥拍从不改视线朝向）；</li>
 *   <li>蓄力照常：{@code onAttackPressed()} 在 cancel 之前已经调用了，
 *       而出球（{@code PingPongClient.handleSwing}）只看按键状态、不看返回值。</li>
 * </ul>
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

	@Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
	private void pingpong$onAttack(CallbackInfoReturnable<Boolean> cir) {
		MinecraftClient client = (MinecraftClient) (Object) this;
		if (client.player == null || client.currentScreen != null) {
			return;
		}
		ItemStack stack = client.player.getMainHandStack();
		if (!(stack.getItem() instanceof com.whale.pingpong.item.PingPongPaddleItem)) {
			return;
		}
		PingPongClient.onAttackPressed();
		// 取消原版攻击：不做攻击判定、不播原版挥手动画。返回 false 表示"这一下没有攻击"。
		cir.setReturnValue(false);
		cir.cancel();
	}
}

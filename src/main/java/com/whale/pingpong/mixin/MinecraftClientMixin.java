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
 * 这里【不】取消原版逻辑，所以：
 * - 玩家视角完全不动；
 * - 手臂 / 手持球拍的挥动动画由原版照常播放（我们额外叠加自己的球拍动画）；
 * - 我们只是多告诉客户端状态「开始蓄力了」，松手时才真正发包挥拍（见 PingPongClient）。
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

	@Inject(method = "doAttack", at = @At("HEAD"))
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
	}
}

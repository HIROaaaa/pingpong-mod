package com.whale.pingpong.mixin;

import com.whale.pingpong.client.PingPongClientState;
import com.whale.pingpong.item.ModItems;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截左键：手里拿着球拍时，额外发一个挥拍包给服务端。
 *
 * 这里【不】取消原版逻辑，所以：
 * - 玩家视角完全不动（原版挥动也不会转视角）；
 * - 手臂 / 手持球拍的挥动动画由原版照常播放；
 * - 我们只是多告诉服务端「我挥拍了，拍面角度是这样」。
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

	@Inject(method = "doAttack", at = @At("HEAD"))
	private void pingpong$onAttack(CallbackInfoReturnable<Boolean> cir) {
		MinecraftClient client = (MinecraftClient) (Object) this;
		if (client.player == null || client.currentScreen != null) {
			return;
		}
		if (!client.player.getMainHandStack().isOf(ModItems.PINGPONG_PADDLE)) {
			return;
		}

		PingPongClientState.startSwing();
		com.whale.pingpong.net.ModNetworking.sendSwing(
				PingPongClientState.tilt(), PingPongClientState.sideTilt());
	}
}

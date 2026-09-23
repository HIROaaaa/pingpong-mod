package com.whale.pingpong.mixin;

import com.whale.pingpong.client.PingPongClientState;
import com.whale.pingpong.item.ModItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截鼠标滚轮：手持球拍时，滚轮不再切换物品栏，而是调节拍面角度。
 *
 * - 滚轮上 = 球拍后仰（上旋）
 * - 滚轮下 = 球拍前倾（下旋）
 * - Alt + 滚轮 = 左右偏斜（侧旋）
 * - 潜行时放行，仍然可以正常切物品栏
 *
 * 每次调节都会在 action bar 上打出当前角度百分比 —— 光看手里的模型不够直观，
 * 必须让「角度改了」这件事一眼可见。
 */
@Mixin(Mouse.class)
public class MouseMixin {

	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void pingpong$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.currentScreen != null) {
			return;
		}
		if (!client.player.getMainHandStack().isOf(ModItems.PINGPONG_PADDLE)) {
			return;
		}
		// 潜行时交还给原版（切物品栏）
		if (client.player.isSneaking()) {
			return;
		}
		if (vertical == 0.0) {
			return;
		}

		boolean alt = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_ALT)
				|| InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
		boolean ctrl = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_CONTROL)
				|| InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
		// 三个维度：普通滚轮=俯仰、Alt=侧偏、Ctrl=水平旋转（第三个方向，2026-09-23 补）
		int axis = ctrl ? PingPongClientState.AXIS_SPIN
				: alt ? PingPongClientState.AXIS_SIDE
				: PingPongClientState.AXIS_TILT;
		PingPongClientState.onScroll(vertical, axis);

		int percent = (int) Math.round(PingPongClientState.axisValue(axis) * 100.0);
		String key = axis == PingPongClientState.AXIS_SPIN ? "hud.pingpong.scroll.spin"
				: axis == PingPongClientState.AXIS_SIDE ? "hud.pingpong.scroll.side"
				: "hud.pingpong.scroll.tilt";
		client.inGameHud.setOverlayMessage(Text.translatable(key, percent), false);

		// 吃掉这次滚动，避免同时切换快捷栏
		ci.cancel();
	}
}

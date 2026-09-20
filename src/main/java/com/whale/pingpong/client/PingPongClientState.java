package com.whale.pingpong.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

/**
 * 客户端本地的「球拍状态」：拍面俯仰 / 侧偏 / 挥拍进度。
 *
 * 这些值是玩家用鼠标滚轮（+ 左 Alt）实时调的，属于输入状态，不需要服务端同步；
 * 真正需要服务端知道的只有「击球那一刻的角度」，所以只在挥拍包里带上。
 */
public final class PingPongClientState {

	/** 滚轮每一格改变多少角度比例 */
	public static final double SCROLL_STEP = 0.1;
	/** 挥拍动画持续多少 tick（仅用于 HUD 提示） */
	private static final int SWING_TICKS = 8;

	/** 拍面俯仰：+1 = 极限后仰（上旋），-1 = 极限前倾（下旋） */
	private static double tilt = 0.0;
	/** 拍面侧偏：+1 / -1 = 左右极限（侧旋） */
	private static double sideTilt = 0.0;
	private static int swingTicks = 0;

	private PingPongClientState() {
	}

	public static double tilt() {
		return tilt;
	}

	public static double sideTilt() {
		return sideTilt;
	}

	public static boolean isSwinging() {
		return swingTicks > 0;
	}

	/** 鼠标滚轮回调：alt 为 true 时调侧偏，否则调俯仰。 */
	public static void onScroll(double vertical, boolean alt) {
		double delta = Math.signum(vertical) * SCROLL_STEP;
		if (alt) {
			sideTilt = MathHelper.clamp(sideTilt + delta, -1.0, 1.0);
		} else {
			tilt = MathHelper.clamp(tilt + delta, -1.0, 1.0);
		}
	}

	/** 开始一次挥拍（用于 HUD 与自定义动画）。 */
	public static void startSwing() {
		swingTicks = SWING_TICKS;
	}

	public static void reset() {
		tilt = 0.0;
		sideTilt = 0.0;
	}

	/** 每客户端 tick 调一次。 */
	public static void tick(MinecraftClient client) {
		if (swingTicks > 0) {
			swingTicks--;
		}
		// 手里没拿球拍时，角度慢慢归零，下次拿出来就是平拍
		if (client.player == null || !client.player.getMainHandStack().isOf(
				com.whale.pingpong.item.ModItems.PINGPONG_PADDLE)) {
			tilt *= 0.8;
			sideTilt *= 0.8;
			if (Math.abs(tilt) < 0.01) {
				tilt = 0.0;
			}
			if (Math.abs(sideTilt) < 0.01) {
				sideTilt = 0.0;
			}
		}
	}
}

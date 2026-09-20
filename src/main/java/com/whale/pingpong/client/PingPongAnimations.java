package com.whale.pingpong.client;

import com.whale.pingpong.util.PlayerHand;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * 球拍姿态 → 矩阵变换。第一人称与第三人称共用同一套公式，保证「自己看到的」和「别人看到的」一致。
 *
 * 挥拍动作按真实乒乓球的三段式（需求 3）：
 * <pre>
 * 0.00 ~ 0.35 后摆：拍子往回带、手腕抬起来（蓄势）
 * 0.35 ~ 0.75 前挥：快速从后方向前扫（这是真正击球的瞬间）
 * 0.75 ~ 1.00 随挥：继续向前送出去，然后收住
 * </pre>
 * 正手从身体右后方向左前方扫、反手从左下向正前方推 —— 两边轨迹的横向符号相反。
 */
public final class PingPongAnimations {

	/** 拍面俯仰的视觉最大角度（度） */
	private static final float TILT_VISUAL_DEGREES = 62.0F;
	/** 拍面侧偏的视觉最大角度（度） */
	private static final float SIDE_VISUAL_DEGREES = 52.0F;

	private PingPongAnimations() {
	}

	/**
	 * 把拍面角度 + 一次挥拍动作应用到一个<b>已经处于「手持物坐标系」</b>的矩阵栈上。
	 *
	 * @param progress 挥拍进度 0~1，0 表示没在挥拍
	 * @param hand     正手 / 反手
	 */
	public static void apply(MatrixStack matrices, double tilt, double sideTilt, float progress, PlayerHand hand) {
		float swing = MathHelper.clamp(progress, 0.0F, 1.0F);
		int handedSign = hand == PlayerHand.FOREHAND ? 1 : -1;

		// --- 拍面角度：常态就能看出朝向（需求 0） ---
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) tilt * TILT_VISUAL_DEGREES));
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) sideTilt * SIDE_VISUAL_DEGREES));

		if (swing <= 0.0F) {
			return;
		}

		// --- 三段式挥拍 ---
		float windup;   // 后摆量
		float forward;  // 前挥量
		float follow;   // 随挥量
		if (swing < 0.35F) {
			windup = swing / 0.35F;
			forward = 0.0F;
			follow = 0.0F;
		} else if (swing < 0.75F) {
			windup = 1.0F - (swing - 0.35F) / 0.40F;
			forward = (swing - 0.35F) / 0.40F;
			follow = 0.0F;
		} else {
			windup = 0.0F;
			forward = 1.0F;
			follow = (swing - 0.75F) / 0.25F;
		}

		// 后摆：拍子往身体外侧后方带，手腕抬高
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-26.0F * windup * handedSign));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-22.0F * windup * handedSign));
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-14.0F * windup));

		// 前挥：横向扫过去 + 手腕下压
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((34.0F * forward - 22.0F * windup) * handedSign));
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((20.0F * forward - 26.0F * windup) * handedSign));
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(18.0F * forward));

		// 随挥：继续向前送一点，然后收回
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(10.0F * follow));
		matrices.translate(0.0F, -0.02F * swing, -0.11F * (forward + follow));
	}
}

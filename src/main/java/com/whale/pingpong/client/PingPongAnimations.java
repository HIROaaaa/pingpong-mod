package com.whale.pingpong.client;

import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * 球拍姿态 → 矩阵变换。第一人称与第三人称共用同一套公式，保证「自己看到的」和「别人看到的」一致。
 *
 * <h2>四类击球各有自己的动作（需求 13/17/21）</h2>
 * <pre>
 *   DRIVE 攻球：拍面基本不变，水平从后往前扫 ——"平着推过去"
 *   LOOP  拉弧圈：引拍到身体下方、拍面朝下，然后从下往上刷 ——"兜球"
 *   PUSH  搓球：手臂放低、拍面朝上托住球，向前下方推（需求 17 原话：
 *               「手臂放低到球台台面上一点，然后用球拍向前推」）
 *   CHOP  削球：拍面高举到肩上，然后从上往下劈（需求 21 原话：「把球拍从上往下劈」）
 * </pre>
 * 每个动作都是三段式（引拍 → 触球 → 随挥），随挥幅度随力度增长。
 * 正手与反手的横向符号相反（{@code handedSign}），所以两者的动作不会看起来一模一样。
 */
public final class PingPongAnimations {

	/** 拍面俯仰的视觉最大角度（度） */
	private static final float TILT_VISUAL_DEGREES = 62.0F;
	/** 拍面侧偏的视觉最大角度（度） */
	private static final float SIDE_VISUAL_DEGREES = 52.0F;
	/** 台内搓球时的躯干前倾角（度）：需求 19「身体需要往台内的方向前倾」 */
	private static final float IN_TABLE_LEAN_DEGREES = 15.0F;
	/** 台内时手臂额外向台内送出的距离（格） */
	private static final float IN_TABLE_LEAN_REACH = 0.08F;

	private PingPongAnimations() {
	}

	/** 向后兼容的重载：没给击球类型时走旧的通用挥拍。 */
	public static void apply(MatrixStack matrices, double tilt, double sideTilt, float progress, PlayerHand hand) {
		apply(matrices, tilt, sideTilt, progress, hand, null, false);
	}

	public static void apply(MatrixStack matrices, double tilt, double sideTilt, float progress, PlayerHand hand,
							 StrokeType stroke) {
		apply(matrices, tilt, sideTilt, progress, hand, stroke, false);
	}

	/**
	 * 把拍面角度 + 一次挥拍动作应用到一个<b>已经处于「手持物坐标系」</b>的矩阵栈上。
	 *
	 * @param progress 挥拍进度 0~1，0 表示没在挥拍
	 * @param hand     正手 / 反手
	 * @param stroke   击球类型（决定动作形态）；null 时退回通用三段式
	 * @param inTable  是否站在台内（需求 19）：台内搓球时躯干前倾、手臂往台内伸
	 */
	public static void apply(MatrixStack matrices, double tilt, double sideTilt, float progress, PlayerHand hand,
							 StrokeType stroke, boolean inTable) {
		float swing = MathHelper.clamp(progress, 0.0F, 1.0F);
		int handedSign = hand == PlayerHand.FOREHAND ? 1 : -1;

		// 【需求 19】台内：躯干前倾 + 手臂往台内送。改的是模型姿态，玩家视角一点不动。
		if (inTable) {
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(IN_TABLE_LEAN_DEGREES));
			matrices.translate(0.0F, 0.0F, -IN_TABLE_LEAN_REACH);
		}

		// --- 拍面角度：常态就能看出朝向（需求 0） ---
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) tilt * TILT_VISUAL_DEGREES));
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) sideTilt * SIDE_VISUAL_DEGREES));

		if (swing <= 0.0F) {
			return;
		}

		// --- 三段式拆分：引拍 → 触球 → 随挥 ---
		float windup;
		float forward;
		float follow;
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

		if (stroke == null) {
			applyGeneric(matrices, windup, forward, follow, handedSign);
			return;
		}

		// 【Java 8 兼容】传统 switch 语句（原来是箭头式 case + 多标签 case）
		switch (stroke) {
			case LOOP_FOREHAND:
			case LOOP_BACKHAND:
				applyLoop(matrices, windup, forward, follow, handedSign);
				break;
			case PUSH_FOREHAND:
			case PUSH_BACKHAND:
				applyPush(matrices, windup, forward, follow, handedSign);
				break;
			case CHOP_FOREHAND:
			case CHOP_BACKHAND:
				applyChop(matrices, windup, forward, follow, handedSign);
				break;
			default:
				applyDrive(matrices, windup, forward, follow, handedSign);
				break;
		}
	}

	/** 旧的通用三段式挥拍（兜底，保持向后兼容）。 */
	private static void applyGeneric(MatrixStack m, float windup, float forward, float follow, int sign) {
		m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-26.0F * windup * sign));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-22.0F * windup * sign));
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-14.0F * windup));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((34.0F * forward - 22.0F * windup) * sign));
		m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((20.0F * forward - 26.0F * windup) * sign));
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(18.0F * forward));
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(10.0F * follow));
		m.translate(0.0F, -0.02F * (forward + follow), -0.11F * (forward + follow));
	}

	/** 攻球：水平从后往前扫，拍面几乎不变。 */
	private static void applyDrive(MatrixStack m, float windup, float forward, float follow, int sign) {
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-18.0F * windup * sign));
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-8.0F * windup));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(30.0F * forward * sign));
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(10.0F * forward));
		m.translate(0.0F, -0.01F * (forward + follow), -0.10F * (forward + follow));
	}

	/** 拉弧圈：引拍沉到身体下方、拍面朝下，然后由下往上刷。 */
	private static void applyLoop(MatrixStack m, float windup, float forward, float follow, int sign) {
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(34.0F * windup));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-14.0F * windup * sign));
		m.translate(0.0F, -0.12F * windup, 0.02F * windup);
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-52.0F * forward));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(16.0F * forward * sign));
		m.translate(0.0F, 0.10F * forward, 0.0F);
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-18.0F * follow));
	}

	/** 搓球：手臂放低、拍面托住球，向前下方推（需求 17）。 */
	private static void applyPush(MatrixStack m, float windup, float forward, float follow, int sign) {
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-16.0F * windup));
		m.translate(0.0F, -0.05F * windup, -0.03F * windup);
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(6.0F * forward));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(10.0F * forward * sign));
		m.translate(0.0F, -0.06F * (forward + follow), -0.09F * (forward + follow));
	}

	/** 削球：高举拍面，然后从上往下劈（需求 21）。 */
	private static void applyChop(MatrixStack m, float windup, float forward, float follow, int sign) {
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-46.0F * windup));
		m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-12.0F * windup * sign));
		m.translate(0.0F, 0.14F * windup, -0.02F * windup);
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(74.0F * forward));
		m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(14.0F * forward * sign));
		m.translate(0.0F, -0.16F * forward, -0.06F * forward);
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(16.0F * follow));
	}
}

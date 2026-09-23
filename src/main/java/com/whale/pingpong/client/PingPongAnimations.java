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

	/**
	 * 只做「引拍」那一段的挥拍进度（供**蓄力期间**使用）。
	 *
	 * 【为什么需要它】用户 2026-09-23 反馈：「拉球蓄力的时候第一时间没有动作，但是松开蓄力
	 * 开始打球的时候球拍才呈现出往下拉的动作」。真因：`PingPongClientState.startSwing()`
	 * 只在**松手**时调用，蓄力期间 `swingProgress()` 恒为 0 —— 于是
	 * {@link #apply} 里 `swing <= 0` 直接 return，蓄力时**一点姿势反馈都没有**。
	 *
	 * 【第二次修正：上限必须落在"引拍段"之内】第一版把上限写成 0.9，但 {@link #apply} 的
	 * 三段式分界是 **0.35 起进入前挥**：蓄力超过 35% 之后进度就跨进了前挥段 ——
	 * 于是**削球蓄力时会做出"往下砸"的动作**（用户反馈「削球还是蓄力会往下拉」），
	 * 拉球蓄力后半段也提前进入前挥。现在把上限压到 **0.32**（略低于 0.35），
	 * 保证蓄力期间**全程都只在引拍段**内推进，绝不出现在"蓄力却已经在挥"的错乱。
	 *
	 * @param chargeRatio 蓄力比例 0~1
	 */
	public static float chargeWindupProgress(double chargeRatio) {
		return (float) MathHelper.clamp(chargeRatio * CHARGE_WINDUP_MAX, 0.0, CHARGE_WINDUP_MAX);
	}

	/**
	 * 蓄力期间引拍进度的上限。必须**小于** {@link #apply} 里"引拍→前挥"的分界 0.35，
	 * 否则蓄力越久越会提前播到前挥（表现为"蓄力时球拍就往下砸/往前挥了"）。
	 */
	private static final float CHARGE_WINDUP_MAX = 0.32F;

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

	/**
	 * 搓球（PUSH）：用户 2026-09-23 两次给了方向要求 ——
	 * 「正手搓球的动作应该是手向斜前方（右手拿拍就是右前）伸过去再搓，反手搓球应该是在身前搓」
	 * 以及第二次纠正「反手搓球胳膊根本没往胸前伸」。
	 *
	 * 所以正反手在**横向上差得很远**（这是上一版没做到位的地方：反手横移只给了 0.02 格，几乎看不出来）：
	 *   · 正手（sign=+1）：往**右前方**送 —— Y 轴 26°、横移 0.10 格，拍在身体斜前方；
	 *   · 反手（sign=−1）：胳膊**明显往胸前收** —— Y 轴 34°、横移 **0.14 格**（上一版仅 0.02），
	 *     再往前下方递出去搓。用户原话是"往胸前伸"，所以这里的横向位移必须看得见。
	 * 两者引拍都只是轻微后收（−10°），不做"往下引拍"。
	 */
	private static void applyPush(MatrixStack m, float windup, float forward, float follow, int sign) {
		boolean forehand = sign > 0;
		// 引拍：轻微后收；（反手仍保持往胸前收的趋势，别把手甩到外面去）
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-10.0F * windup));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((forehand ? 4.0F : 12.0F) * windup * sign));
		m.translate(forehand ? 0.04F * windup : 0.05F * windup, 0.02F * windup, -0.02F * windup);
		// 前挥：正手往斜前方送；反手往胸前收着向前下方递
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(6.0F * forward));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((forehand ? 26.0F : 34.0F) * forward * sign));
		m.translate(
				(forehand ? 0.10F : 0.14F) * (forward + follow),
				-0.05F * (forward + follow),
				-0.12F * (forward + follow));
	}

	/**
	 * 削球（CHOP）：按**真实技术描述**重做（2026-09-23 用户提示「你找一下现实中削球动作的描述」）。
	 *
	 * 查证来源与要点：
	 *   · [百度百科·削球]：正手「向右后上方引拍与肩同高」→「上臂带动前臂由右上向左前下方加速切削」，
	 *     在身体右侧约 40cm 处触球；反手「向左上方引拍约与肩高、拍柄向下」→「从左上方向右前下方挥动」，
	 *     在胸前偏左 30cm 处击球，顺势挥至右侧下。两者共同点：**挥拍呈圆弧路线**、动作幅度大。
	 *   · [PingSkills 教练答]：正手「球拍从右耳后方开始，向下切，收在左膝」；
	 *     反手「从左耳后方开始，收在右膝」。
	 *
	 * 落到本项目（sign：正手 +1 / 反手 −1；Y 轴旋转的正向 = 往反手侧/左，负向 = 往正手侧/右）：
	 *   · 正手：引拍往**右后上**（Y 负、X 负=举高）→ 前挥往**左前下**（Y 正、X 正=下砸），横移大
	 *   · 反手：引拍往**左后上**（Y 正、X 负）→ 前挥往**右前下**（Y 负、X 正），横移大
	 * 两种手型的 X/Y 符号都相反，所以圆弧的绕行方向天然相反 —— 这正是"正反手方向相反"。
	 */
	private static void applyChop(MatrixStack m, float windup, float forward, float follow, int sign) {
		// 引拍：往侧后方上方举拍（与肩同高）
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-44.0F * windup));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-18.0F * windup * sign));  // 正手往右、反手往左
		m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-10.0F * windup * sign));
		m.translate(0.10F * windup * sign, 0.14F * windup, -0.02F * windup);
		// 前挥：往另一侧的前下方切 —— 横向大幅扫过（圆弧的"画弧"部分）+ 明显下砸
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(70.0F * forward));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(30.0F * forward * sign));  // 与引拍反向
		m.translate(-0.16F * forward * sign, -0.15F * forward, -0.08F * forward);
		// 随挥：继续往侧下方收（真实动作里收在对侧膝旁）
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(14.0F * follow));
		m.translate(-0.08F * follow * sign, -0.05F * follow, 0.0F);
	}
}

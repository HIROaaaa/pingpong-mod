package com.whale.pingpong.util;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 手臂姿态的**纯数学**：由「正反手 + 击球类型 + 相位进度」算出球拍相对眼睛的位置与朝向。
 *
 * <h2>为什么要单独抽出来（用户第 4 条反馈）</h2>
 * 用户原话：「击球点也要相应更改，点位要跟着动画里球拍的位置更改，不要固定在一个地方」。
 * 之前击球点是 {@link TableGeometry#paddlePoint} 里一组**固定偏移**（前 0.55、侧向 ±0.38、上 0.45），
 * 而动画已经把手臂摆到别处了 —— 于是"看到的拍子"和"判定用的拍子"是两回事，
 * 玩家会觉得"明明够到了却没打到"。
 *
 * 现在把姿态算成**同一个函数**：客户端用它摆动画、服务端用它定判定点，
 * 两边只传参数（手型 + 击球类型 + 力度），不传位置 —— 服务端权威保住了，也不会不同步。
 *
 * <h2>模型几何（Yarn 1.20.1 实测）</h2>
 * 手臂只能**绕肩旋转**，手恒在过肩的竖直面内；臂长 11 模型像素 = 0.6875 格，
 * 右肩相对躯干中心 x = −5 像素。球拍再往手外延伸约 0.25 格（真实球拍全长 25cm）。
 * 横向位置只能靠**躯干转体**给 —— 这正是真人打球要转腰的原因。
 */
public final class ArmPose {

	/** 臂长（格）：11 模型像素 / 16 */
	public static final double ARM_LENGTH = 11.0 / 16.0;
	/** 球拍从手心再延伸出去的长度（格）：真实球拍全长约 25cm，扣掉握在手里的部分 */
	public static final double PADDLE_LENGTH = 0.25;
	/** 右肩相对躯干中心的横向偏移（格）：5 像素 / 16 */
	private static final double SHOULDER_OFFSET = 5.0 / 16.0;

	private ArmPose() {
	}

	/**
	 * 一个相位的姿态角度（度）。pitch 以 0 = 手臂自然下垂为原点：
	 * <b>正值 = 手往身后抬，负值 = 手往身前伸</b>。
	 */
	public static final class Angles {
		public final double pitch;
		public final double yaw;
		public final double roll;
		public final double bodyYaw;
		public final double bodyPitch;
		/** 相位进度（用于插值，调用方保留） */
		public final double progress;

		public Angles(double pitch, double yaw, double roll, double bodyYaw, double bodyPitch, double progress) {
			this.pitch = pitch;
			this.yaw = yaw;
			this.roll = roll;
			this.bodyYaw = bodyYaw;
			this.bodyPitch = bodyPitch;
			this.progress = progress;
		}
	}

	/**
	 * 按「击球类型 + 手型 + 相位」给出角度。
	 *
	 * @param stroke   击球类型（决定动作形态：攻球平扫 / 弧圈下沉再上拉 / 搓球低推 / 削球高劈）
	 * @param forehand 是否正手
	 * @param windup   引拍进度 0~1（蓄力时 = 蓄力比例）
	 * @param forward  前挥进度 0~1
	 * @param follow   随挥进度 0~1
	 */
	public static Angles anglesFor(StrokeType stroke, boolean forehand,
								   double windup, double forward, double follow) {
		double handed = forehand ? 1.0 : -1.0;

		// 基准：持拍待机 —— 手臂抬到身前约 30°
		double pitch = -30.0;
		double yaw = 0.0;
		double roll = 10.0 * handed;
		double bodyYaw = 0.0;
		double bodyPitch = 0.0;

		// 引拍：90° 往身后上方拉
		double windupPitch = 90.0;
		double windupRoll = -12.0;
		double windupBodyYaw = -24.0;
		double windupBodyPitch = -7.0;

		// 前挥：扫到身前下方
		double forwardPitch = -55.0;
		double forwardBodyYaw = 30.0;
		double forwardBodyPitch = 10.0;

		// 随挥
		double followPitch = -12.0;

		// ---- 击球类型的形态差异（用户第 2、3 条反馈都落在这里）----
		if (stroke != null) {
			switch (stroke) {
				case LOOP_FOREHAND:
				case LOOP_BACKHAND:
					// 【反手拉球：从腹部往下引拍，再往上拉（用户原话）】
					// 正手弧圈是"从下往上兜"，反手是"从腹部往下沉一下再往上拉" ——
					// 两者引拍的**起点高度**不同，所以引拍俯仰的偏移量分开给。
					if (forehand) {
						windupPitch = 96.0;      // 正手：从下往上兜，幅度大
						windupBodyPitch = -10.0;
						forwardPitch = -62.0;    // 往前上方兜出去
					} else {
						// 【反手拉球：2026-09-22 按用户新描述重做】
						// 用户原话：「反手应该让手从胸前开始往正下方拉，现在反手拉球动作和正手一样」。
						// 旧参数是「手在身后抬起来（78°）→ 前挥往上收（−78°）」，用户判定"和正手一样"
						// （确实都是"往后抬再往前上收"的同构动作，只是幅度略差）。
						//
						// 现在改成**从前上方向下扫**：
						//   引拍 → 手抬到**身前上方**（球拍举在胸前高度，增量 −43）
						//   前挥 → 手往**正下方**扫下去（增量 +11，扫到躯干下方）
						// 这样正手是"从下往上兜"、反手是"从上往下拉"，两者形态与方向都不同。
						//
						// 【关于"从胸前开始"】几何上手臂只能绕肩旋转，"手停在胸口高度"是不可达的
						// （手到肩的距离恒为 11 像素 = 0.69 格，要么抬到身前上方、要么垂到下方）。
						// 所以取"可达到的最近似位置"：手抬到身前上方且贴身 —— 视觉上就是"拍子举在胸前"。
						//
						// 【第一版搞反了】我先解出 windupPitch=+18 / forwardPitch=+96，paddle_point_check
						// 一跑就 FAIL —— 球拍跑到了**身后** 0.73 格。原因是 pitch 正值 = 手往身后抬，
						// 而"胸前/身前"必须是负值。现在这组是拿解算器按目标位置反解出来的。
						windupPitch = -43.0;
						windupBodyPitch = -3.0;
						windupRoll = -18.0;
						forwardPitch = 11.0;
					}
					break;
				case PUSH_FOREHAND:
				case PUSH_BACKHAND:
					// 【搓球：手臂放低到台面上一点，往前下方推（用户原话：要像现实中搓球）】
					// 引拍几乎不往后拉，而是**压低**；前挥是小幅度往前推，不甩臂。
					windupPitch = 18.0;
					windupRoll = -6.0;
					windupBodyYaw = -8.0;
					windupBodyPitch = 6.0;       // 身体略往前压（贴近台面）
					forwardPitch = -34.0;        // 只往前推一点，不抡
					forwardBodyYaw = 12.0;
					forwardBodyPitch = 8.0;
					break;
				case CHOP_FOREHAND:
				case CHOP_BACKHAND:
					// 削球：举到肩上再往下劈
					windupPitch = 118.0;
					windupBodyPitch = -12.0;
					forwardPitch = -74.0;
					break;
				default:
					// 攻球：水平平扫
					windupPitch = 84.0;
					forwardPitch = -50.0;
					break;
			}
		}

		double w = MathHelper.clamp(windup, 0.0, 1.0);
		double f = MathHelper.clamp(forward, 0.0, 1.0);
		double fo = MathHelper.clamp(follow, 0.0, 1.0);

		double outPitch = pitch + windupPitch * w + forwardPitch * f + followPitch * fo;
		double outYaw = yaw;
		double outRoll = roll + windupRoll * w * handed;
		double outBodyYaw = (windupBodyYaw * w + forwardBodyYaw * f) * handed;
		double outBodyPitch = windupBodyPitch * w + forwardBodyPitch * f;

		return new Angles(outPitch, outYaw, outRoll, outBodyYaw, outBodyPitch, w + f + fo);
	}

	/**
	 * 球拍中心相对**眼睛**的位置（格）。判定与动画共用这一个函数。
	 *
	 * @param angles 姿态角度
	 * @param forehand 是否正手（决定肩在哪一侧）
	 */
	public static Vec3d paddleOffset(Angles angles, boolean forehand) {
		double handed = forehand ? 1.0 : -1.0;

		// 手相对躯干中心：手臂绕肩旋转后，再叠上躯干转体
		double pitch = Math.toRadians(angles.pitch);
		// 竖直/前后分量（模型坐标：y 向下为正）
		double handY = ARM_LENGTH * Math.cos(pitch);
		double handZ = ARM_LENGTH * Math.sin(pitch);
		double handX = -SHOULDER_OFFSET * handed;

		// 躯干转体：把手臂整体绕竖直轴转（横向位移就来自这里）
		double bodyYaw = Math.toRadians(angles.bodyYaw);
		double cosB = Math.cos(bodyYaw);
		double sinB = Math.sin(bodyYaw);
		double rotX = handX * cosB + handZ * sinB;
		double rotZ = -handX * sinB + handZ * cosB;

		// 躯干前倾/后仰
		double bodyPitch = Math.toRadians(angles.bodyPitch);
		double y2 = handY * Math.cos(bodyPitch) - rotZ * Math.sin(bodyPitch);
		double z2 = handY * Math.sin(bodyPitch) + rotZ * Math.cos(bodyPitch);

		// 球拍再往手外延伸：沿手臂方向继续出去
		double dirY = Math.sin(pitch + bodyPitch);
		double dirZ = Math.cos(pitch + bodyPitch);
		double paddleOut = PADDLE_LENGTH;

		// 游戏坐标：玩家面朝 -Z（forward），模型 z 后为正 → 游戏 z 取反
		double gameX = rotX;
		double gameZ = -(z2 + dirZ * paddleOut);
		double gameY = -(y2 + dirY * paddleOut);

		return new Vec3d(gameX, gameY, gameZ);
	}

	/** 球拍中心的世界坐标：眼睛位置 + 按视线与球台朝向旋转后的偏移。 */
	public static Vec3d paddleWorld(Vec3d eyePos, Vec3d forward, Vec3d outward, Angles angles, boolean forehand) {
		Vec3d flat = new Vec3d(forward.x, 0.0, forward.z);
		flat = flat.lengthSquared() < 1.0e-8 ? new Vec3d(0.0, 0.0, 1.0) : flat.normalize();
		// 以"朝向球台"为基准方向，而不是玩家视线 —— 与需求 6（击球点由球台与站位决定）一致
		Vec3d toward = outward.lengthSquared() < 1.0e-8 ? flat : outward.multiply(-1.0);
		Vec3d right = new Vec3d(toward.x, 0.0, toward.z).crossProduct(new Vec3d(0.0, 1.0, 0.0)).normalize();

		Vec3d offset = paddleOffset(angles, forehand);
		return eyePos
				.add(toward.multiply(-offset.z))    // 模型 z 为"后"，取反成朝前
				.add(right.multiply(offset.x))
				.add(0.0, offset.y, 0.0);
	}
}

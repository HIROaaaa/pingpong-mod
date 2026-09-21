package com.whale.pingpong.client;

import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * 乒乓球动作→**玩家模型骨骼**的姿态（五期 M6，第二轮按实测反馈重做）。
 *
 * <h2>为什么动作要动模型而不是只转手里的拍子</h2>
 * 之前 {@link PingPongAnimations} 只对「手持物坐标系」做矩阵变换 —— 只有那把拍子在动，
 * 玩家的胳膊、身体纹丝不动。用户要的是「胳膊身体可以弯曲的那种动作，和那些动作优化 mod 一样」，
 * 所以动作必须落到**骨骼**上（见 {@code mixin/PlayerEntityModelMixin}）。
 *
 * <h2>第一版为什么"只是手臂微微动了一下"（用户实测反馈）</h2>
 * 三个原因，都在这一版里改掉了：
 * <ol>
 *   <li><b>幅度太小</b>：第一版给的角度全是 8°~30° 量级，而玩家模型的手臂本身就有 90° 级的
 *       原版摆动 —— 这点增量在视觉上等于没动。现在整体放大到 <b>45°~85°</b> 量级；</li>
 *   <li><b>只有挥拍那 8 tick 有动作</b>：用户按住左键蓄力时手臂不动，松手瞬间才有一下。
 *       现在<b>蓄力阶段就推进引拍</b>（见下面 phase 的分支），引拍到不到位取决于蓄力进度，
 *       松手后从"已经引好拍"的位置进入前挥 —— 这正是用户要的
 *       「在蓄力拉球的时候就有手往后引拍的动作」；</li>
 *   <li><b>正反手不是镜像</b>：第一版只把侧向分量乘了 ±1，俯仰（前后）方向两只手一样，
 *       所以看起来"正反手反了/没区别"。现在 yaw 与 roll 都按手型镜像。</li>
 * </ol>
 *
 * <h2>三段式与缓动</h2>
 * <pre>
 *   蓄力（按住左键）：引拍进度 = 蓄力比例，手往后上方拉，蓄满时引拍到最大
 *   松手瞬间：        进入挥拍，从当前（引好拍的）姿态出发前挥 —— 不做瞬间跳变
 *   挥拍 8 tick：     前挥（快，0.55）→ 随挥（缓，0.22）
 * </pre>
 */
public final class PingPongModelPose {

	// ------------------------------------------------------------------
	// 目标姿态字段（每次由 update() 重算）
	// ------------------------------------------------------------------
	private static float armPitch;
	private static float armYaw;
	private static float armRoll;
	private static float bodyPitch;
	private static float bodyYaw;
	private static float headYaw;
	private static float offArmPitch;
	private static boolean active;

	// 当前值（缓动后的实际输出）
	private static float curArmPitch;
	private static float curArmYaw;
	private static float curArmRoll;
	private static float curBodyPitch;
	private static float curBodyYaw;
	private static float curHeadYaw;
	private static float curOffArmPitch;

	// ------------------------------------------------------------------
	// 动作幅度常量（度）
	//
	// 【符号与方向：由 tools/pose_solver.js 反解后定下，不再靠手感猜】
	// 模型部件：pitch 绕 X 轴 —— **正值 = 手往身后抬，负值 = 手往身前伸**；
	// yaw 绕 Y 轴、roll 绕 Z 轴。手臂只有"绕肩旋转"一个自由度，手恒在过肩的竖直面内，
	// 所以**横向扫动必须靠躯干转体（bodyYaw）**来给，这也是真人打球会转腰的原因。
	// handedSign = 正手 +1 / 反手 −1，作用在 yaw/roll/bodyYaw 上 → 两只手镜像。
	// 实测若发现左右整体反了，把 HANDED_FLIP 改成 -1 一次翻过来，不必逐个改数字。
	//
	// 【为什么幅度要这么大】用户第一轮反馈「只是手臂微微动了一下」：第一版给的是 8°~30°，
	// 而原版走路摆手本身就有 40° 量级 —— 那点增量视觉上等于没动。
	// 现在待机抬臂 65°、引拍到 +60°、前挥到 −88°，**前后摆幅 148°**，一眼就能看出来。
	// ------------------------------------------------------------------

	/** 手型符号总开关：实测左右反了就改成 -1.0F */
	private static final float HANDED_FLIP = 1.0F;

	/**
	 * 持拍待机：手臂抬到身前约 30°（球拍举在身前但不高举 —— 抬太高反而不像"准备击球"）。
	 * 【标定基准】pitch 以 0° = 手臂自然垂在身侧为原点：
	 *   负值 → 手往身前抬；正值 → 手往身后抬。
	 */
	private static final float READY_ARM_PITCH = -30.0F;
	/** 待机的轻微外展（乘手型符号） */
	private static final float READY_ARM_ROLL = 10.0F;

	/**
	 * 引拍：手臂往后上方拉（−30° → +60°，**整整 90° 的引拍行程**）。
	 * 这是**蓄力阶段就在推进**的动作 —— 用户要的「蓄力拉球时就有手往后引拍的效果」。
	 */
	private static final float WINDUP_PITCH = 90.0F;
	/** 引拍时的收臂（乘手型符号） */
	private static final float WINDUP_ROLL = -12.0F;
	/** 引拍时的转体（乘手型符号）：正手引拍时肩膀往后转 */
	private static final float WINDUP_BODY_YAW = -24.0F;
	/** 引拍时躯干略后仰 */
	private static final float WINDUP_BODY_PITCH = -7.0F;

	/** 前挥：手臂猛往前伸（+60° → −85°，**扫过 145°**） */
	private static final float FORWARD_PITCH = -55.0F;
	/** 前挥时的转体（乘手型符号） */
	private static final float FORWARD_BODY_YAW = 30.0F;
	/** 前挥时躯干前压 */
	private static final float FORWARD_BODY_PITCH = 10.0F;

	/** 随挥：继续走一点然后收住 */
	private static final float FOLLOW_PITCH = -12.0F;

	/** 击球类型的形态差异（叠在基础三段式上） */
	private static final float LOOP_DROP = -18.0F;      // 拉弧圈：引拍更低更沉（兜球）
	private static final float CHOP_LIFT = 28.0F;       // 削球：引拍更高（举到肩上）
	private static final float PUSH_LOW = 14.0F;        // 搓球：手臂压低往前推

	/** 台内前倾（需求 19）：身体往台内压 */
	private static final float IN_TABLE_LEAN = 15.0F;

	// 缓动系数：引拍慢（做出蓄势感） / 触球快（出拍干脆） / 随挥缓（收得住）
	private static final float EASE_WINDUP = 0.30F;
	private static final float EASE_FORWARD = 0.62F;
	private static final float EASE_FOLLOW = 0.26F;
	private static final float EASE_IDLE = 0.18F;

	private PingPongModelPose() {
	}

	// ------------------------------------------------------------------
	// 每帧更新
	// ------------------------------------------------------------------

	/**
	 * 由客户端每帧调用（渲染前），根据「手里拿没拿球拍 + 在蓄力还是在挥拍」算出目标姿态。
	 */
	public static void update(AbstractClientPlayerEntity player) {
		boolean holding = player.getMainHandStack().isOf(com.whale.pingpong.item.ModItems.PINGPONG_PADDLE);
		if (!holding) {
			active = false;
			targetAll(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
			return;
		}
		active = true;

		boolean local = player == net.minecraft.client.MinecraftClient.getInstance().player;
		PaddlePoseCache.Pose cached = PaddlePoseCache.get(player.getUuid());
		PlayerHand hand = local ? PingPongClientState.hand() : cached.hand;
		float swing = local ? PingPongClientState.swingProgress() : PaddlePoseCache.swingProgressOf(cached);
		StrokeType stroke = local ? PingPongClientState.lastStroke() : cached.stroke;
		double tiltForPose = local ? PingPongClientState.tilt() : cached.tilt;
		float charge = local ? (float) PingPongClientState.chargeRatio() : 0.0F;

		float handed = (hand == PlayerHand.FOREHAND ? 1.0F : -1.0F) * HANDED_FLIP;

		// ---- 姿态 = 待机基础 + 当前阶段的动作 ----
		float pitch = READY_ARM_PITCH;
		float yaw = 0.0F;
		float roll = READY_ARM_ROLL * handed;
		float bodyPitch = 0.0F;
		float bodyYaw = 0.0F;
		float headYaw = 0.0F;
		float offArm = 0.0F;

		// 球拍俯仰也让手臂跟着变，这样"拍面角度"在第三人称一眼可见（需求 0）
		pitch -= (float) tiltForPose * 12.0F;

		if (swing <= 0.0F && charge > 0.0F) {
			// ---- 蓄力阶段：推进引拍（用户要的"蓄力时就有往后引拍的动作"）----
			float w = MathHelper.clamp(charge, 0.0F, 1.0F);
			// 用 sqrt 让引拍在蓄力前半程就走得比较明显（线性的话前半程几乎看不出来）
			float windup = (float) Math.sqrt(w);
			StrokeType s = previewStrokeFor(hand, charge);

			pitch += (WINDUP_PITCH + strokeWindupPitch(s)) * windup;
			roll += WINDUP_ROLL * windup * handed;
			bodyYaw += WINDUP_BODY_YAW * windup * handed;
			bodyPitch += WINDUP_BODY_PITCH * windup;
			headYaw += -WINDUP_BODY_YAW * 0.45F * windup * handed;
			offArm += 30.0F * windup;
		} else if (swing > 0.0F) {
			// ---- 挥拍阶段：从"已经引好拍"的位置进入前挥 → 随挥 ----
			// 三段式比例与 PingPongAnimations 一致（0.35 / 0.75），两个视角的动作才对得上。
			float windup;
			float forward;
			float follow;
			if (swing < 0.35F) {
				windup = 1.0F;          // 松手瞬间仍保持引拍姿态
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
			StrokeType s = stroke == null ? StrokeType.DRIVE_FOREHAND : stroke;

			pitch += (WINDUP_PITCH + strokeWindupPitch(s)) * windup
					+ FORWARD_PITCH * forward + FOLLOW_PITCH * follow;
			roll += (WINDUP_ROLL * windup) * handed;
			bodyYaw += (WINDUP_BODY_YAW * windup + FORWARD_BODY_YAW * forward) * handed;
			bodyPitch += WINDUP_BODY_PITCH * windup + FORWARD_BODY_PITCH * forward;
			headYaw += (-WINDUP_BODY_YAW * windup * 0.45F - FORWARD_BODY_YAW * 0.35F * forward) * handed;
			offArm += 30.0F * windup + 18.0F * forward;
		}

		targetAll(pitch, yaw, roll, bodyPitch, bodyYaw, headYaw, offArm);
	}

	/** 击球类型的引拍形态差异（弧圈沉、削球高、搓球低、攻球平）。 */
	private static float strokeWindupPitch(StrokeType stroke) {
		if (stroke == null) {
			return 0.0F;
		}
		switch (stroke) {
			case LOOP_FOREHAND:
			case LOOP_BACKHAND:
				return LOOP_DROP;
			case CHOP_FOREHAND:
			case CHOP_BACKHAND:
				return CHOP_LIFT;
			case PUSH_FOREHAND:
			case PUSH_BACKHAND:
				return PUSH_LOW;
			default:
				return 0.0F;
		}
	}

	/** 蓄力时预览：这一拍最终会打成哪一类（与 {@code StrokeType.select} 同规则）。 */
	private static StrokeType previewStrokeFor(PlayerHand hand, float charge) {
		return StrokeType.select(hand == PlayerHand.BACKHAND, false, charge);
	}

	private static void targetAll(float pitch, float yaw, float roll, float body, float bodyTurn,
								  float head, float offArm) {
		armPitch = pitch;
		armYaw = yaw;
		armRoll = roll;
		bodyPitch = body;
		bodyYaw = bodyTurn;
		headYaw = head;
		offArmPitch = offArm;
	}

	/** 把当前姿态缓动一步并写进模型部件。 */
	public static void apply(ModelPart rightArm, ModelPart leftArm, ModelPart body, ModelPart head,
							 boolean offHandLeft, float ease) {
		float k = MathHelper.clamp(ease, 0.0F, 1.0F);
		if (!active && Math.abs(curArmPitch) < 0.01F && Math.abs(curArmYaw) < 0.01F
				&& Math.abs(curArmRoll) < 0.01F && Math.abs(curBodyPitch) < 0.01F
				&& Math.abs(curBodyYaw) < 0.01F && Math.abs(curHeadYaw) < 0.01F
				&& Math.abs(curOffArmPitch) < 0.01F) {
			return;   // 完全回到原版姿态后就不再碰模型（空手走路保持原样）
		}

		curArmPitch = ease(curArmPitch, armPitch, k);
		curArmYaw = ease(curArmYaw, armYaw, k);
		curArmRoll = ease(curArmRoll, armRoll, k);
		curBodyPitch = ease(curBodyPitch, bodyPitch, k);
		curBodyYaw = ease(curBodyYaw, bodyYaw, k);
		curHeadYaw = ease(curHeadYaw, headYaw, k);
		curOffArmPitch = ease(curOffArmPitch, offArmPitch, k);

		ModelPart paddleArm = offHandLeft ? leftArm : rightArm;
		ModelPart otherArm = offHandLeft ? rightArm : leftArm;

		paddleArm.pitch += toRadians(curArmPitch);
		paddleArm.yaw += toRadians(curArmYaw);
		paddleArm.roll += toRadians(curArmRoll);

		otherArm.pitch += toRadians(curOffArmPitch * 0.5F);
		otherArm.roll -= toRadians(curOffArmPitch * 0.3F);

		body.pitch += toRadians(curBodyPitch);
		body.yaw += toRadians(curBodyYaw);
		head.yaw += toRadians(curHeadYaw - curBodyYaw * 0.5F);
	}

	private static float ease(float current, float target, float k) {
		return current + (target - current) * k;
	}

	private static float toRadians(float degrees) {
		return degrees * 0.017453292F;
	}

	/** 当前这一帧的缓动系数（引拍慢 / 触球快 / 随挥缓）。 */
	public static float easeFor(float swingProgress) {
		if (!active) {
			return EASE_IDLE;
		}
		if (swingProgress <= 0.0F) {
			return EASE_WINDUP;   // 蓄力中：引拍要慢慢来，才像"蓄势"
		}
		if (swingProgress < 0.35F) {
			return EASE_FORWARD;
		}
		if (swingProgress < 0.75F) {
			return EASE_FORWARD;
		}
		return EASE_FOLLOW;
	}
}

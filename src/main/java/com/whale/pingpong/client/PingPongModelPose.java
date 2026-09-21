package com.whale.pingpong.client;

import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * 乒乓球动作→**玩家模型骨骼**的姿态（五期 M6）。
 *
 * <h2>为什么要动模型而不是只转手里的拍子</h2>
 * 之前 {@link PingPongAnimations} 只对「手持物坐标系」做矩阵变换 —— 也就是只有那把拍子在动，
 * 玩家的胳膊、身体纹丝不动，看起来像"球拍自己在飘"。用户的原话是要「顺滑的动作」、
 * 「胳膊身体可以弯曲的那种动作，和那些动作优化 mod 一样」，所以动作必须落到**骨骼**上。
 *
 * <h2>弯曲是怎么做出来的</h2>
 * 原版玩家模型（{@code BipedEntityModel}）只有「上臂」一整段，**没有肘关节**。
 * 这里不去改模型网格（那要重构 ModelPart 与 UV，风险高），而是用三联旋转合成"弯"的感觉：
 * <pre>
 *   上臂 pitch/yaw/roll   —— 肩关节（大臂方向）
 *   上臂 roll 的额外分量   —— 内收/外展（手肘离开身体的夹角）
 *   躯干 + 头的反向补偿    —— 让"弯腰找球"看起来是身体在动，而不是手臂单独抽搐
 * </pre>
 * 手臂抬起时 roll/pitch 的组合天然读作"手肘弯着"；这比整条手臂绕肩旋转自然得多，
 * 也正是动作优化类 mod 观感的主要来源。
 *
 * <h2>平滑</h2>
 * 每一帧的当前姿态用指数缓动追目标姿态（{@link #ease}），而不是把目标值直接赋上去 ——
 * 这既去掉了原版那种正弦挥手，也让引拍→触球→随挥之间连续过渡。
 * 缓动速度分三段：**引拍慢、触球快、随挥缓**（用户要的"顺滑"关键就在这个速度差上）。
 */
public final class PingPongModelPose {

	// ------------------------------------------------------------------
	// 目标姿态字段（每次由 update() 重算）
	// ------------------------------------------------------------------

	/** 持拍手臂的肩关节旋转（弧度/度，直接写进 ModelPart） */
	private static float armPitch;
	private static float armYaw;
	private static float armRoll;
	/** 躯干：前倾 / 转动 */
	private static float bodyPitch;
	private static float bodyYaw;
	/** 头部：随动作轻微跟随（抵消一点躯干转动，避免"头跟着身体甩"） */
	private static float headYaw;
	/** 另一只手臂（非持拍手）的摆动量，做平衡用 */
	private static float offArmPitch;
	/** 是否处于"持拍"状态：false 时把一切缓动回 0（空手不该有打球动作） */
	private static boolean active;

	// ------------------------------------------------------------------
	// 当前值（缓动后的实际输出）
	// ------------------------------------------------------------------
	private static float curArmPitch;
	private static float curArmYaw;
	private static float curArmRoll;
	private static float curBodyPitch;
	private static float curBodyYaw;
	private static float curHeadYaw;
	private static float curOffArmPitch;

	// ------------------------------------------------------------------
	// 动作幅度常量（度）
	// ------------------------------------------------------------------

	/** 待机时上臂抬起的角度：球拍举在身前，而不是垂在腿边 */
	private static final float READY_ARM_PITCH = -22.0F;
	/** 待机时上臂的内收角（正手向外、反手向内由 handedSign 决定） */
	private static final float READY_ARM_ROLL = 14.0F;
	/** 拉弧圈引拍时下沉的幅度（用户要的"兜球"） */
	private static final float LOOP_WINDUP_DROP = 36.0F;
	/** 削球引拍时高举的幅度（用户要的"从上往下劈"） */
	private static final float CHOP_WINDUP_LIFT = 42.0F;
	/** 台内前倾（需求 19）：身体往台内压 */
	private static final float IN_TABLE_LEAN = 15.0F;

	/** 缓动系数：引拍段（慢，做出"蓄势"感） */
	private static final float EASE_WINDUP = 0.18F;
	/** 缓动系数：触球段（快，出拍要干脆） */
	private static final float EASE_FORWARD = 0.55F;
	/** 缓动系数：随挥段（缓，收得住） */
	private static final float EASE_FOLLOW = 0.22F;
	/** 缓动系数：回到待机 */
	private static final float EASE_IDLE = 0.15F;

	private PingPongModelPose() {
	}

	// ------------------------------------------------------------------
	// 每 tick / 每帧更新
	// ------------------------------------------------------------------

	/**
	 * 由客户端每帧调用（在渲染前），根据"手里拿没拿球拍 + 正在做哪个动作"算出目标姿态。
	 *
	 * @param player 要算姿态的玩家（本地玩家用实时输入，其他玩家用同步过来的缓存值）
	 */
	public static void update(AbstractClientPlayerEntity player) {
		// 【为什么每个玩家都要算】多人联机时别人也得看到你的动作 —— 他们的手臂姿态由
		// PaddlePoseCache 里同步过来的拍形/击球类型驱动，和本地玩家走同一套公式。
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

		// --- 三段式拆分（与 PingPongAnimations 同一套比例，动作才连得上）---
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

		float handedSign = hand == PlayerHand.FOREHAND ? 1.0F : -1.0F;

		// 起始值 = 持拍待机姿势
		float pitch = READY_ARM_PITCH;
		float yaw = 0.0F;
		float roll = READY_ARM_ROLL * handedSign;
		float body = 0.0F;
		float bodyTurn = 0.0F;
		float head = 0.0F;
		float offArm = 0.0F;

		// 拍面俯仰也让手臂跟着变，这样"拍面角度"在第三人称一眼可见（需求 0）
		pitch -= (float) tiltForPose * 10.0F;

		StrokeType s = stroke == null ? StrokeType.DRIVE_FOREHAND : stroke;
		switch (s) {
			case LOOP_FOREHAND:
			case LOOP_BACKHAND:
				// 拉弧圈：引拍沉到身体下方，然后自下往上刷
				pitch += LOOP_WINDUP_DROP * windup - 34.0F * forward - 12.0F * follow;
				yaw += (-16.0F * windup + 26.0F * forward) * handedSign;
				body += 6.0F * windup - 8.0F * forward;
				bodyTurn += (-10.0F * windup + 16.0F * forward) * handedSign;
				offArm += 14.0F * windup;
				break;
			case PUSH_FOREHAND:
			case PUSH_BACKHAND:
				// 搓球：手臂放低、向前下方推（需求 17 原话：手臂放低到台面上一点再向前推）
				pitch += 10.0F * windup - 12.0F * forward;
				yaw += (-8.0F * windup + 18.0F * forward) * handedSign;
				body += 8.0F * windup + 6.0F * forward;
				offArm += 10.0F * windup;
				break;
			case CHOP_FOREHAND:
			case CHOP_BACKHAND:
				// 削球：高举拍面，从上往下劈（需求 21）
				pitch += -CHOP_WINDUP_LIFT * windup + 58.0F * forward + 14.0F * follow;
				yaw += (-10.0F * windup + 12.0F * forward) * handedSign;
				body += -10.0F * windup + 12.0F * forward;
				bodyTurn += (8.0F * windup - 10.0F * forward) * handedSign;
				offArm += 18.0F * windup;
				break;
			default:
				// 攻球：水平从后往前扫
				pitch += -14.0F * windup - 6.0F * forward;
				yaw += (-20.0F * windup + 32.0F * forward + 10.0F * follow) * handedSign;
				bodyTurn += (-12.0F * windup + 18.0F * forward) * handedSign;
				head += (6.0F * windup - 8.0F * forward) * handedSign;
				offArm += 12.0F * windup;
				break;
		}

		targetAll(pitch, yaw, roll, body, bodyTurn, head, offArm);
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

	/**
	 * 把当前姿态缓动一步，并写进模型部件。
	 *
	 * <p>【为什么写在这里而不是直接赋值】目标姿态是"这个动作的终点"，
	 * 直接赋值会让动作瞬间跳过去（原版挥手的生硬感就来自这里）。缓动让每帧只走一部分，
	 * 于是引拍是"慢慢抬起来"、出拍是"啪地打出去"、随挥是"缓缓收住"。
	 *
	 * @param model 正在渲染的玩家模型部件集合
	 */
	public static void apply(ModelPart rightArm, ModelPart leftArm, ModelPart body, ModelPart head,
							 boolean offHandLeft, float ease) {
		float k = MathHelper.clamp(ease, 0.0F, 1.0F);
		if (!active && Math.abs(curArmPitch) < 0.01F && Math.abs(curArmYaw) < 0.01F
				&& Math.abs(curArmRoll) < 0.01F && Math.abs(curBodyPitch) < 0.01F
				&& Math.abs(curBodyYaw) < 0.01F && Math.abs(curHeadYaw) < 0.01F
				&& Math.abs(curOffArmPitch) < 0.01F) {
			return;   // 已经完全回到原版姿态，不必再碰模型（空手走路保持原样）
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

		// 持拍手臂：在"抬起的手"基础上叠加动作（+ pitch 是向下、− 是向上；yaw/roll 见模型约定）
		paddleArm.pitch += toRadians(curArmPitch);
		paddleArm.yaw += toRadians(curArmYaw);
		paddleArm.roll += toRadians(curArmRoll);

		// 另一只手臂：轻微反向摆动，做身体平衡（真实打球时非持拍手不会僵着）
		otherArm.pitch += toRadians(curOffArmPitch * 0.6F);
		otherArm.roll -= toRadians(curOffArmPitch * 0.35F);

		// 躯干与头：前倾 / 转体 / 头部跟随
		body.pitch += toRadians(curBodyPitch);
		body.yaw += toRadians(curBodyYaw);
		head.yaw += toRadians(curHeadYaw - curBodyYaw * 0.5F);
	}

	/** 指数缓动：每帧朝目标走 k 的比例。 */
	private static float ease(float current, float target, float k) {
		return current + (target - current) * k;
	}

	private static float toRadians(float degrees) {
		return degrees * 0.017453292F;
	}

	/**
	 * 当前这一帧该用的缓动系数（引拍慢 / 触球快 / 随挥缓）。
	 * 由渲染钩子按挥拍进度取用，这样速度差是"动作本身的属性"，不随帧率漂。
	 */
	public static float easeFor(float swingProgress) {
		if (!active) {
			return EASE_IDLE;
		}
		if (swingProgress <= 0.0F) {
			return EASE_IDLE;
		}
		if (swingProgress < 0.35F) {
			return EASE_WINDUP;
		}
		if (swingProgress < 0.75F) {
			return EASE_FORWARD;
		}
		return EASE_FOLLOW;
	}
}

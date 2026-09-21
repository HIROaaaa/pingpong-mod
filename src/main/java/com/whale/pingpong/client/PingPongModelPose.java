package com.whale.pingpong.client;

import com.whale.pingpong.util.ArmPose;
import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 乒乓球动作→**玩家模型骨骼**的姿态（五期 M6，第二轮按实测反馈重做）。
 *
 * <h2>为什么动作要动模型而不是只转手里的拍子</h2>
 * 之前 {@link PingPongAnimations} 只对「手持物坐标系」做矩阵变换 —— 只有那把拍子在动，
 * 玩家的胳膊、身体纹丝不动。用户要的是「胳膊身体可以弯曲的那种动作，和那些动作优化 mod 一样」，
 * 所以动作必须落到**骨骼**上（见 {@code mixin/PlayerEntityModelMixin}）。
 *
 * <h2>第二版修了什么（都来自实测反馈）</h2>
 * <ol>
 *   <li><b>幅度太小</b>（"只是手臂微微动了一下"）：第一版 8°~30°，而原版走路摆手本身就有 40°；
 *       现在摆幅 90°（引拍）+ 145°（挥拍）；</li>
 *   <li><b>方向做反了</b>（"正反手好像反了"）：靠 {@code tools/pose_solver.js} 反解修正，
 *       现在待机手在身前、引拍手在身后、前挥手回身前，{@code tools/pose_direction_check.js} 守住；</li>
 *   <li><b>蓄力时没有引拍</b>（用户点名要的）：蓄力进度直接驱动引拍，松手从已引好的姿态继续挥；</li>
 *   <li><b>手臂不会弯</b>（"要能体现出大臂和小臂的弯曲"）：走 playerAnimator 的 bend 接口
 *       （顶点级形变，底层 bendy-lib），见 {@link #applyElbowBend}；</li>
 *   <li><b>姿态是全局静态的</b>：多个玩家会互相覆写彼此的缓动状态 —— 现在改成
 *       <b>按玩家 UUID 隔离</b>（{@link #states}），联机时每个人各算各的。</li>
 * </ol>
 */
public final class PingPongModelPose {

	/** 每个玩家各存一份姿态状态（目标 + 缓动中的当前值） */
	private static final class PoseState {
		float armPitch;
		float armYaw;
		float armRoll;
		float bodyPitch;
		float bodyYaw;
		float headYaw;
		float offArmPitch;
		float elbowBend;

		float curArmPitch;
		float curArmYaw;
		float curArmRoll;
		float curBodyPitch;
		float curBodyYaw;
		float curHeadYaw;
		float curOffArmPitch;
		float curElbowBend;
		boolean active;

		boolean isRest() {
			return !active
					&& Math.abs(curArmPitch) < 0.01F && Math.abs(curArmYaw) < 0.01F
					&& Math.abs(curArmRoll) < 0.01F && Math.abs(curBodyPitch) < 0.01F
					&& Math.abs(curBodyYaw) < 0.01F && Math.abs(curHeadYaw) < 0.01F
					&& Math.abs(curOffArmPitch) < 0.01F && Math.abs(curElbowBend) < 0.01F;
		}

		/** 把目标姿态全部清零（空手时回到原版） */
		void resetTargets() {
			armPitch = 0.0F;
			armYaw = 0.0F;
			armRoll = 0.0F;
			bodyPitch = 0.0F;
			bodyYaw = 0.0F;
			headYaw = 0.0F;
			offArmPitch = 0.0F;
			elbowBend = 0.0F;
		}
	}

	private static final Map<UUID, PoseState> states = new HashMap<>();

	/** 最近一次 update() 算姿态的玩家；apply() 据此取对应那份状态 */
	private static PoseState current = new PoseState();

	// ------------------------------------------------------------------
	// 动作幅度常量（度）
	//
	// 【符号与方向：由 tools/pose_solver.js 反解后定下，不再靠手感猜】
	// pitch 以 0° = 手臂自然下垂为原点：**正值 = 手往身后抬，负值 = 手往身前伸**。
	// handedSign = 正手 +1 / 反手 −1，作用在 yaw/roll/bodyYaw 上 → 两只手镜像。
	// 实测若发现左右整体反了，把 HANDED_FLIP 改成 -1 一次翻过来，不必逐个改数字。
	// ------------------------------------------------------------------

	/** 手型符号总开关：实测左右反了就改成 -1.0F */
	private static final float HANDED_FLIP = 1.0F;

	/** 缓动系数：引拍慢（做出蓄势感） / 触球快（出拍干脆） / 随挥缓（收得住） */
	private static final float EASE_WINDUP = 0.30F;
	private static final float EASE_FORWARD = 0.62F;
	private static final float EASE_FOLLOW = 0.26F;
	private static final float EASE_IDLE = 0.18F;

	// ---- 肘部弯曲（大臂/小臂的折角，见 applyElbowBend）----
	/** 持拍待机的基础弯曲（度）：手臂不会伸得笔直 */
	private static final float BEND_BASE = 24.0F;
	/** 引拍时额外增加的弯曲（度）：收拍到身后时小臂收着 */
	private static final float BEND_WINDUP = 34.0F;
	/** 前挥时回伸的量（度）：出拍要伸出去 */
	private static final float BEND_FORWARD = 22.0F;

	private PingPongModelPose() {
	}

	/** 退出世界时清空（避免 UUID 表越积越大） */
	public static void clear() {
		states.clear();
		current = new PoseState();
	}

	// ------------------------------------------------------------------
	// 每帧更新
	// ------------------------------------------------------------------

	/**
	 * 由客户端每帧调用（渲染前），根据「手里拿没拿球拍 + 在蓄力还是在挥拍」算出目标姿态。
	 * 算完后 {@link #apply} 会取用同一份状态，所以两者必须成对调用（见 PlayerEntityModelMixin）。
	 */
	public static void update(AbstractClientPlayerEntity player) {
		PoseState st = states.computeIfAbsent(player.getUuid(), key -> new PoseState());
		current = st;

		boolean holding = player.getMainHandStack().isOf(com.whale.pingpong.item.ModItems.PINGPONG_PADDLE);
		if (!holding) {
			st.active = false;
			st.resetTargets();
			return;
		}
		st.active = true;

		boolean local = player == net.minecraft.client.MinecraftClient.getInstance().player;
		PaddlePoseCache.Pose cached = PaddlePoseCache.get(player.getUuid());
		PlayerHand hand = local ? PingPongClientState.hand() : cached.hand;
		float swing = local ? PingPongClientState.swingProgress() : PaddlePoseCache.swingProgressOf(cached);
		StrokeType stroke = local ? PingPongClientState.lastStroke() : cached.stroke;
		double tiltForPose = local ? PingPongClientState.tilt() : cached.tilt;
		float charge = local ? (float) PingPongClientState.chargeRatio() : 0.0F;

		// ---- 姿态角度统一由 util/ArmPose 给出（与击球判定同一套数学，用户第 4 条反馈）----
		//
		// 【为什么不再在本类里写角度】角度原先只活在这个渲染类里，而服务端判定用的是
		// TableGeometry.paddlePoint 的一组**固定偏移** —— 于是"看到的拍子"与"判定用的拍子"分家，
		// 玩家会觉得"明明够到了却没打到"。现在两端共用 ArmPose：客户端拿它摆动画，
		// 服务端拿它定判定点，只传参数（手型 + 击球类型 + 力度），服务端权威不受影响。
		double windup;
		double forward;
		double follow;
		if (swing <= 0.0F) {
			// 蓄力阶段：推进引拍（用户要的"蓄力时就有往后引拍的动作"）。
			// 用 sqrt 让前半程就走得明显（线性的话前半程几乎看不出来）。
			windup = Math.sqrt(MathHelper.clamp(charge, 0.0F, 1.0F));
			forward = 0.0;
			follow = 0.0;
		} else if (swing < 0.35F) {
			windup = 1.0;          // 松手瞬间仍保持引拍姿态，不做跳变
			forward = 0.0;
			follow = 0.0;
		} else if (swing < 0.75F) {
			windup = 1.0 - (swing - 0.35F) / 0.40F;
			forward = (swing - 0.35F) / 0.40F;
			follow = 0.0;
		} else {
			windup = 0.0;
			forward = 1.0;
			follow = (swing - 0.75F) / 0.25F;
		}

		StrokeType s = stroke == null ? StrokeType.DRIVE_FOREHAND : stroke;
		ArmPose.Angles angles = ArmPose.anglesFor(s, hand == PlayerHand.FOREHAND, windup, forward, follow);

		float pitch = (float) angles.pitch;
		// 球拍俯仰也让手臂跟着变，这样"拍面角度"在第三人称一眼可见（需求 0）
		pitch -= (float) tiltForPose * 12.0F;

		/*
		 * 【肘部弯曲】用户要的「大臂和小臂的那种弯曲」，三个来源叠加：
		 *   ① 基础弯曲 24°：持拍时手臂本来就不会伸得笔直；
		 *   ② 引拍越多越弯（最多 +34°）：收拍到身后时小臂是收着的，这是"引拍"看起来像引拍的关键；
		 *   ③ 击球类型差异：搓球要压得低而**直**（贴着台面推），弧圈/削球弯得多（蓄力兜起来）。
		 */
		double strokeBend;
		if (s == StrokeType.PUSH_FOREHAND || s == StrokeType.PUSH_BACKHAND) {
			strokeBend = -14.0;      // 搓球：手臂放低、几乎伸直往前推
		} else if (s == StrokeType.LOOP_FOREHAND || s == StrokeType.LOOP_BACKHAND) {
			strokeBend = 12.0;       // 拉球：兜得更弯
		} else if (s == StrokeType.CHOP_FOREHAND || s == StrokeType.CHOP_BACKHAND) {
			strokeBend = 8.0;
		} else {
			strokeBend = 0.0;
		}

		st.armPitch = pitch;
		st.armYaw = (float) angles.yaw;
		st.armRoll = (float) angles.roll;
		st.bodyPitch = (float) angles.bodyPitch;
		st.bodyYaw = (float) angles.bodyYaw;
		st.headYaw = (float) (-angles.bodyYaw * 0.45);
		st.offArmPitch = (float) (30.0 * windup + 18.0 * forward);
		st.elbowBend = (float) MathHelper.clamp(
				BEND_BASE + BEND_WINDUP * windup + strokeBend - BEND_FORWARD * forward, 0.0, 80.0);
	}

	/**
	 * 把**最近一次 update() 那个玩家**的姿态缓动一步并写进模型部件。
	 * 由 {@code PlayerEntityModelMixin} 紧跟在 update() 之后调用，两者成对。
	 */
	public static void apply(ModelPart rightArm, ModelPart leftArm, ModelPart body, ModelPart head,
							 ModelPart hat, boolean offHandLeft, float ease) {
		PoseState st = current;
		float k = MathHelper.clamp(ease, 0.0F, 1.0F);
		if (st.isRest()) {
			return;   // 完全回到原版姿态后就不再碰模型（空手走路保持原样）
		}

		st.curArmPitch = ease(st.curArmPitch, st.armPitch, k);
		st.curArmYaw = ease(st.curArmYaw, st.armYaw, k);
		st.curArmRoll = ease(st.curArmRoll, st.armRoll, k);
		st.curBodyPitch = ease(st.curBodyPitch, st.bodyPitch, k);
		st.curBodyYaw = ease(st.curBodyYaw, st.bodyYaw, k);
		st.curHeadYaw = ease(st.curHeadYaw, st.headYaw, k);
		st.curOffArmPitch = ease(st.curOffArmPitch, st.offArmPitch, k);
		st.curElbowBend = ease(st.curElbowBend, st.elbowBend, k);

		ModelPart paddleArm = offHandLeft ? leftArm : rightArm;
		ModelPart otherArm = offHandLeft ? rightArm : leftArm;

		paddleArm.pitch += toRadians(st.curArmPitch);
		paddleArm.yaw += toRadians(st.curArmYaw);
		paddleArm.roll += toRadians(st.curArmRoll);

		otherArm.pitch += toRadians(st.curOffArmPitch * 0.5F);
		otherArm.roll -= toRadians(st.curOffArmPitch * 0.3F);

		body.pitch += toRadians(st.curBodyPitch);
		body.yaw += toRadians(st.curBodyYaw);

		/*
		 * 【头部与头发必须一起转（用户实测："只有头和头发分离了，头发没有跟着动"）】
		 *
		 * 原因不在头发坏了，而在**原版把 hat（头发/帽层）的旋转复制自 head** ——
		 * BipedEntityModel.setAngles 末尾写着 head.copyTransform(hat)（1.20.1 是 copyTransform）。
		 * 我们是在那个复制**之后**才改 head.yaw，于是 head 转了、hat 还留在原角度，
		 * 两层皮肤就此错位。修法：把同一个增量也加到 hat 上。
		 *
		 * 【为什么不能只改 hat.pitch/hat.yaw】hat 的枢轴与 head 相同（都是 (0,0,0)），
		 * 所以同样的增量等价于"一起转"，不需要复制全部字段。
		 */
		float headDelta = toRadians(st.curHeadYaw - st.curBodyYaw * 0.5F);
		head.yaw += headDelta;
		if (hat != null) {
			hat.yaw += headDelta;
		}

		applyElbowBend(paddleArm, st.curElbowBend);
		applyElbowBend(otherArm, st.curElbowBend * 0.55F);
		/*
		 * 【躯干弯曲（用户实测："拉球时上半身和腿部是直接折开的"）】
		 *
		 * 原版躯干是一个绕腰旋转的**刚体方块**，所以弯腰时上半身和腿之间是"折"而不是"弯"，
		 * 看起来像两块木板拼的。手臂能弯是因为走了 bend 接口 —— 躯干同理：
		 * 把身体也在腰部弯一点，腰胯之间就有了过渡。
		 *
		 * 弯曲量跟着转体走（转体越多弯得越多），这样"拉球时转腰"看起来是身体在拧，
		 * 而不是上半身整体平移了一下。
		 */
		float waistBend = Math.abs(st.curBodyYaw) * 0.55F + Math.abs(st.curBodyPitch) * 0.8F;
		applyElbowBend(body, Math.min(waistBend, 26.0F));
	}

	/**
	 * 让手臂在**肘部**弯曲（大臂/小臂的折角）。
	 *
	 * <p>普通 ModelPart 只会绕枢轴**刚体旋转**，做不出"手臂打弯"；真正的形变要靠
	 * playerAnimator 的 bend 接口（顶点级重排，底层 bendy-lib）。
	 * 这里用反射调用，理由是：<b>bendy-lib 是可选的</b> —— 没装时 playerAnimator 内部是
	 * 一个空实现，直接引用它的类不会崩，但用反射写就不必把 playerAnimator 的编译期依赖
	 * 泄漏到更多地方，也方便将来它改签名时只在这里改。
	 *
	 * @param bendDegrees 弯曲角度（度），0 = 伸直
	 */
	private static void applyElbowBend(ModelPart arm, float bendDegrees) {
		if (Math.abs(bendDegrees) < 0.5F || arm == null) {
			return;
		}
		try {
			Class<?> helperClass = Class.forName("dev.kosmx.playerAnim.impl.animation.IBendHelper");
			Object helper = helperClass.getField("INSTANCE").get(null);
			helperClass.getMethod("bend", ModelPart.class, float.class, float.class)
					.invoke(helper, arm, toRadians(bendDegrees), 0.0F);
		} catch (Throwable ignored) {
			// 没装 bendy-lib / playerAnimator 换了实现：动作照常，只是手臂不弯。
			// 这是可选依赖的正常降级，不该刷日志、更不该崩。
		}
	}

	private static float ease(float currentValue, float target, float k) {
		return currentValue + (target - currentValue) * k;
	}

	private static float toRadians(float degrees) {
		return degrees * 0.017453292F;
	}

	/** 当前这一帧的缓动系数（引拍慢 / 触球快 / 随挥缓）。 */
	public static float easeFor(float swingProgress) {
		if (!current.active) {
			return EASE_IDLE;
		}
		if (swingProgress <= 0.0F) {
			return EASE_WINDUP;   // 蓄力中：引拍要慢慢来，才像"蓄势"
		}
		if (swingProgress < 0.75F) {
			return EASE_FORWARD;
		}
		return EASE_FOLLOW;
	}
}

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
		/** 上一帧的时间戳（纳秒），用于把缓动做成"帧率无关" */
		long lastFrameNanos;

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

	/**
	 * 缓动系数：**每 1/20 秒**朝目标收敛的比例（实际按帧长折算，见 {@link #easeForFrame}）。
	 *
	 * 【为什么调慢过】原来这几档是 0.18~0.62，看着像"每帧走一大步"——
	 * 高帧率下几乎两三帧就到位，动作就变成"跳过去"。现在整体放缓，
	 * 60fps 下引拍约 0.4 秒、前挥约 0.2 秒，符合"引拍慢、出拍快、随挥缓"的手感。
	 */
	private static final float EASE_WINDUP = 0.15F;
	private static final float EASE_FORWARD = 0.28F;
	private static final float EASE_FOLLOW = 0.13F;
	private static final float EASE_IDLE = 0.10F;

	// ---- 肘部弯曲（大臂/小臂的折角，见 applyElbowBend）----
	//
	// 【为什么一路调到这么极端】诊断数据证明 bend 确实在生效（弯矩 52°、成功 11036 次、
	// 零失败），但屏幕上仍看不出 —— 说明"几十度"对 11 像素长的细长部件是**视觉噪声级**的。
	// 这一版直接把量送到 130°（远超"折成直角"）做**极限定性**：
	//   · 看得出形变 → 机制可用，再往回调到手感合适的值；
	//   · 还是看不出   → 顶点变形在这个模型上的视觉极限，得换机制（自定义物品渲染器等）。
	/** 持拍待机的基础弯曲（度） */
	private static final float BEND_BASE = 40.0F;
	/** 引拍时额外增加的弯曲（度） */
	private static final float BEND_WINDUP = 75.0F;
	/** 前挥时回伸的量（度） */
	private static final float BEND_FORWARD = 60.0F;

	/**
	 * 调试用弯矩覆盖（度）：&lt;0 = 不覆盖，走正常动作。
	 *
	 * <p>【为什么留这个后门】调弯曲幅度时，如果每次都改常量→编译→发版→让玩家重启游戏，
	 * 一轮要花十几分钟，而真正需要的只是"看一眼 60° 和 120° 差多少"。
	 * 有了它，玩家在游戏里敲 `/pingpong bend 120` 就能当场看到效果、当场改，
	 * 找到合适的量再把它固化成常量。这是"少绕弯"该有的样子。
	 */
	private static float bendOverride = -1.0F;

	/** 设置调试弯矩（度）；传负数恢复跟随动作 */
	public static void setBendOverride(float degrees) {
		bendOverride = degrees;
	}

	/** 当前调试弯矩（负数 = 未覆盖） */
	public static float bendOverride() {
		return bendOverride;
	}

	private PingPongModelPose() {
	}

	/** 退出世界时清空（避免 UUID 表越积越大） */
	public static void clear() {
		states.clear();
		current = new PoseState();
	}

	// ------------------------------------------------------------------
	// 运行时诊断计数（`/pingpong diag` 用）
	//
	// 【为什么要这些计数】"Mixin 有没有真的改到模型"从外面看不出来：日志干净、
	// mod 也确实加载了，但注入可能落在另一个模型实例上、或者根本没跑。
	// 数出来才看得见 —— 这是排查"改了没效果"的唯一直接证据。
	// ------------------------------------------------------------------

	/** setAngles 注入命中次数（每次玩家模型算角度都会 +1） */
	private static long diagApplyCalls;
	/** 其中「手里拿球拍」的帧数 —— 为 0 说明一直没握着拍子渲染过 */
	private static long diagHoldingCalls;
	/** bend 成功调用的次数 */
	private static long diagBendCalls;
	/** bend 抛异常的次数（反射/签名/方向不对都会落这里） */
	private static long diagBendFailures;
	/** 最近一次出错的异常摘要 */
	private static String diagLastBendError = "（无）";
	/** 最近一次实际作用的弯矩（度）与手臂姿态角，便于判断"幅度够不够" */
	private static float diagLastBendDegrees;
	private static float diagLastArmPitch;

	/** 供 mixin 在命中注入点时调用 */
	public static void noteMixinHit() {
		diagApplyCalls++;
	}

	/** 一行行的人类可读诊断（交给 /pingpong diag 展示） */
	public static String diagnostics() {
		String held = current.active ? "是" : "否";
		return "Mixin 注入命中: " + diagApplyCalls + " 次（其中持拍 " + diagHoldingCalls + " 帧）\n"
				+ "bendy-lib 可用: " + (bendAvailable() ? "是" : "否")
				+ " / 已 initBend 部件: " + initializedParts.size() + " 个\n"
				+ ForearmPart.diagnostics() + "\n"
				+ "bend 调用: 成功 " + diagBendCalls + " 次 / 失败 " + diagBendFailures + " 次\n"
				+ "最近的弯矩: " + String.format("%.1f", diagLastBendDegrees)
				+ "° / 大臂俯仰: " + String.format("%.1f", diagLastArmPitch)
				+ "° / 当前姿态生效: " + held + "\n"
				+ "最近 bend 错误: " + diagLastBendError;
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
		diagHoldingCalls++;

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
		} else {
			/*
			 * 【挥拍：权重平滑混合，不再"顺序切换"（用户反馈"动作还是很僵硬"）】
			 *
			 * 旧写法是 if/else 分段：swing<0.35 只给引拍、0.35~0.75 只给前挥、之后只给随挥 ——
			 * 每段边界上权重是**阶跃**的，动作会"咔"地跳一下，看起来就是僵硬。
			 *
			 * 现在改成**相邻相位之间用 smoothstep 交叉淡入淡出**：
			 * 引拍→前挥在 0.0~0.45 之间过渡，前挥→随挥在 0.55~1.0 之间过渡。
			 * 于是任何时刻都是两段姿态按比例混合，没有阶跃点。
			 */
			double t = MathHelper.clamp(swing, 0.0F, 1.0F);
			double blendUp = smoothstep(t / 0.45);              // 0 → 1：引拍交棒给前挥
			double blendFollow = smoothstep((t - 0.55) / 0.45); // 0 → 1：前挥交棒给随挥
			windup = 1.0 - blendUp;
			forward = blendUp * (1.0 - blendFollow);
			follow = blendUp * blendFollow;
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
				BEND_BASE + BEND_WINDUP * windup + strokeBend - BEND_FORWARD * forward, 0.0, 130.0);
	}

	/**
	 * 把**最近一次 update() 那个玩家**的姿态缓动一步并写进模型部件。
	 * 由 {@code PlayerEntityModelMixin} 紧跟在 update() 之后调用，两者成对。
	 */
	public static void apply(ModelPart rightArm, ModelPart leftArm, ModelPart body, ModelPart head,
							 ModelPart hat, boolean offHandLeft, float ease) {
		PoseState st = current;
		/*
		 * 【时间基准缓动（用户反馈"动作还是很僵硬"）】
		 *
		 * 旧写法是「每帧朝目标走固定比例 k」，而 k 是从挥拍进度里拿的固定表 ——
		 * 于是 30fps 和 240fps 的收敛速度差 8 倍，高帧率下动作又会显得"一顿一顿"。
		 * 现在把 k 解释成"每 1/20 秒收敛的比例"，按**这一帧实际过了多久**折算：
		 * 无论帧率高低，同一个动作在同样的时间里走完同样的距离。
		 */
		float k = easeForFrame(ease);
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
		// 诊断用：记录实际应用到持拍臂上的角度（/pingpong diag 会打出来）
		diagLastArmPitch = st.curArmPitch;

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

		applyElbowBend(paddleArm, st.curElbowBend, true);
		applyElbowBend(otherArm, st.curElbowBend * 0.55F, false);

		/*
		 * 【真·肘关节（第九轮）】顶点变形（bend）从 40° 试到 150°，用户答复是
		 * 「全都没区别」—— 说明变形在这个模型上的视觉贡献约等于 0。
		 * 所以改成**几何关节**：给手臂真的加一段前臂部件，相对上臂折一个角。
		 * bend 那条路留着当兜底（关节部件构造失败时仍然走它）。
		 */
		if (ForearmPart.available()) {
			ForearmPart.apply(paddleArm, !offHandLeft, st.curElbowBend);
		}

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
		applyElbowBend(body, Math.min(waistBend, 26.0F), false);
	}

	/**
	 * 让手臂/躯干在**关节处**弯曲（大臂与小臂的折角、腰胯之间的过渡）。
	 *
	 * <p>普通 ModelPart 只会绕枢轴**刚体旋转**，做不出"打弯"；真正的形变要靠
	 * playerAnimator 的 bend 接口（顶点级重排，底层是 **bendy-lib**）。
	 *
	 * <p>【两个必踩的坑，都栽过】
	 * <ol>
	 *   <li><b>没装 bendy-lib 时是静默空实现</b>：playerAnimator 在静态初始化时检测它，
	 *       没装就把实现换成 {@code DummyBendable} —— 调用不报错、但什么都不会弯。
	 *       所以先查 {@code Helper.isBendEnabled()}。</li>
	 *   <li><b>光调 {@code bend()} 不够，必须先 {@code initBend()}</b>：反汇编
	 *       {@code BendHelper.bend()} 可以看到它内部是
	 *       {@code getAndActivateMutator("bend")} → {@code applyBend(...)}，
	 *       而名为 "bend" 的 mutator <b>只有 {@code initBend(part, direction)} 会注册</b>
	 *       （registerMutator("bend", …)）。少了 initBend，mutator 不存在，弯曲自然毫无动静
	 *       —— 这就是"代码明明在调 bend 却没效果"的真正原因。</li>
	 * </ol>
	 * 所以流程是：首次给某个部件 {@code initBend(part, Direction.DOWN)}（在中间弯折），
	 * 之后每帧 {@code bend(part, 角度, 0)}。initBend 只做一次，用表记住做过哪些部件。
	 *
	 * @param bendDegrees 弯曲角度（度），0 = 伸直
	 */
	private static void applyElbowBend(ModelPart part, float bendDegrees) {
		applyElbowBend(part, bendDegrees, false);
	}

	/**
	 * @param isArm 是否是"持拍手臂"——只有它的弯矩才写进诊断（否则会被躯干的值覆盖掉）
	 */
	private static void applyElbowBend(ModelPart part, float bendDegrees, boolean isArm) {
		if (isArm && bendOverride >= 0.0F) {
			bendDegrees = bendOverride;   // 调试覆盖：/pingpong bend <度数>
		}
		if (Math.abs(bendDegrees) < 0.5F || part == null || !bendAvailable()) {
			return;
		}
		try {
			Class<?> helperClass = Class.forName("dev.kosmx.playerAnim.impl.animation.IBendHelper");
			Object helper = helperClass.getField("INSTANCE").get(null);
			if (initializedParts.put(part, Boolean.TRUE) == null) {
				// 首次见到这个部件：注册 "bend" mutator（方向 DOWN = 在部件中部弯折）
				helperClass.getMethod("initBend", ModelPart.class, net.minecraft.util.math.Direction.class)
						.invoke(helper, part, net.minecraft.util.math.Direction.DOWN);
			}
			helperClass.getMethod("bend", ModelPart.class, float.class, float.class)
					.invoke(helper, part, toRadians(bendDegrees), 0.0F);
			diagBendCalls++;
			if (isArm) {
				diagLastBendDegrees = bendDegrees;
			}
		} catch (Throwable error) {
			// playerAnimator 换了实现/签名：动作照常，只是不弯。可选依赖的正常降级。
			// 但要把失败记下来 —— 静默失败正是这次排查最耗时间的地方。
			diagBendFailures++;
			String message = error.getClass().getSimpleName() + ": " + error.getMessage();
			if (error.getCause() != null) {
				message += " ← " + error.getCause().getClass().getSimpleName() + ": " + error.getCause().getMessage();
			}
			diagLastBendError = message;
		}
	}

	/**
	 * 已经 {@code initBend} 过的部件。
	 * 【为什么用弱键】ModelPart 由渲染器持有（玩家模型是共享实例），这里只借来去重，
	 * 不该阻止它们被回收。
	 */
	private static final java.util.Map<ModelPart, Boolean> initializedParts =
			new java.util.WeakHashMap<>();
	/**
	 * 客户端启动时主动探测一次 bendy-lib。
	 *
	 * 【为什么要在启动时探测】原先只在"第一次需要弯曲"时才检测，而那只发生在渲染玩家模型时
	 * （要先进入世界）—— 日志里就看不到结论，排查时得先进游戏。
	 * 现在启动阶段就把结果打进日志，一眼就能确认"弯曲到底能不能生效"。
	 */
	public static void probeBendSupport() {
		bendAvailable();
	}

	private static boolean bendChecked;
	private static boolean bendAvailable;

	/** bendy-lib 是否可用（只探测一次）。可用时才值得走反射调用。 */
	private static boolean bendAvailable() {
		if (bendChecked) {
			return bendAvailable;
		}
		bendChecked = true;
		try {
			Class<?> helper = Class.forName("dev.kosmx.playerAnim.impl.Helper");
			Object enabled = helper.getMethod("isBendEnabled").invoke(null);
			bendAvailable = enabled instanceof Boolean && (Boolean) enabled;
			if (!bendAvailable) {
				com.whale.pingpong.PingPongMod.LOGGER.info(
						"[pingpong] 未检测到 bendy-lib：动作照常播放，但手臂/躯干的弯曲效果不会出现。"
								+ "想看到弯曲请安装 bendy-lib（客户端 mod）。");
			} else {
				com.whale.pingpong.PingPongMod.LOGGER.info(
						"[pingpong] 已检测到 bendy-lib：手臂与躯干会有关节弯曲。");
			}
		} catch (Throwable ignored) {
			bendAvailable = false;
		}
		return bendAvailable;
	}

	private static float ease(float currentValue, float target, float k) {
		return currentValue + (target - currentValue) * k;
	}

	private static float toRadians(float degrees) {
		return degrees * 0.017453292F;
	}

	/** smoothstep：两端导数为 0 的插值，用来做相位之间的交叉淡入淡出（消除"阶跃感"）。 */
	private static double smoothstep(double t) {
		double x = MathHelper.clamp(t, 0.0, 1.0);
		return x * x * (3.0 - 2.0 * x);
	}

	/**
	 * 把"每 1/20 秒的收敛比例"折算成**这一帧**该用的比例。
	 *
	 * <p>帧长按最近两次渲染的时间差算，并夹在 4~100ms 之间 ——
	 * 卡顿一下不至于把动作一次性拉满，高帧率下也不会慢得像树懒。
	 */
	private static float easeForFrame(float perTickRatio) {
		float ratio = MathHelper.clamp(perTickRatio, 0.01F, 1.0F);
		long now = System.nanoTime();
		long last = current.lastFrameNanos;
		current.lastFrameNanos = now;
		if (last == 0L) {
			return ratio;   // 第一帧还没有时间差，先用原值
		}
		float frameSeconds = MathHelper.clamp((now - last) / 1.0e9F, 0.004F, 0.1F);
		return (float) (1.0 - Math.pow(1.0 - ratio, frameSeconds * 20.0));
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

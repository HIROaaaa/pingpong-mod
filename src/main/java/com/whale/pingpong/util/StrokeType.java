package com.whale.pingpong.util;

import com.whale.pingpong.physics.PingPongContact;
import net.minecraft.util.math.Vec3d;

/**
 * 击球类型（需求 9~22）。
 *
 * <h2>局部坐标系约定</h2>
 * 这里所有方向都在「球台局部坐标」里写死：<b>+x = 从击球者飞向对面</b>，+y = 上，+z = 击球者右手侧。
 * 实际使用时把结果旋转到世界坐标（用水平出球方向当 +x）。这样参数表读起来就是
 * "拍面朝哪、往哪挥"，不会被世界坐标搞晕。
 *
 * <h2>参数含义</h2>
 * <ul>
 *   <li><b>normalTiltDegrees</b>：拍面法线与水平面的夹角（度）。正 = 法线朝上 = 拍面**后仰**（托球、下旋）；
 *       负 = 法线朝下 = 拍面**前倾**（压球、上旋）。</li>
 *   <li><b>swingX / swingY</b>：挥拍方向单位矢量的前向与竖直分量。
 *       <b>swingX 必须为正</b>（+x 是"飞向对面"）—— 挥拍方向与出球方向同侧；
 *       写成负数会让拍子朝来球方向挥，接触模型算出来的球会**倒着往回飞**（脚本实测过这个 bug）。
 *       swingY 为正 = 向上刷（弧圈），为负 = 向下劈（搓/削）。</li>
 *   <li><b>swingSpeed</b>：满力度时的挥拍速度（格/tick）。它**不是**出球速度 ——
 *       出球速度由 {@link PingPongContact} 的接触模型算出来，这才是"符合物理"的地方。</li>
 * </ul>
 */
public enum StrokeType {

	//                        id         法线倾角  挥拍(x, y)     挥拍速度  胶皮
	/** 正手攻球：拍面后仰 52°、水平前挥。力度换速度，自旋很弱（所以平击没弧线）。 */
	DRIVE_FOREHAND("drive_fh", 52, 1.00, 0.05, 0.23, PingPongContact.PaddleSurface.NORMAL),
	/** 反手攻球：角度略小、挥速略低。 */
	DRIVE_BACKHAND("drive_bh", 50, 1.00, 0.05, 0.21, PingPongContact.PaddleSurface.NORMAL),

	/** 正手拉弧圈：拍面几乎平（16°）、斜向上刷。薄摩擦 + 高挥速 → 上旋 + 弧线。 */
	LOOP_FOREHAND("loop_fh", 16, 0.92, 0.39, 0.20, PingPongContact.PaddleSurface.BRUSH),
	/** 反手拉弧圈：反手引拍幅度小。 */
	LOOP_BACKHAND("loop_bh", 17, 0.94, 0.34, 0.19, PingPongContact.PaddleSurface.BRUSH),

	/** 正手搓球（需求 17/18）：拍面后仰 30°、手臂放低向前推。慢、低、带下旋。 */
	PUSH_FOREHAND("push_fh", 30, 1.00, -0.18, 0.18, PingPongContact.PaddleSurface.BRUSH),
	/** 反手搓球：肘收拢，推得更平。 */
	PUSH_BACKHAND("push_bh", 29, 1.00, -0.12, 0.17, PingPongContact.PaddleSurface.BRUSH),

	/** 正手削球（需求 21）：后仰 37°、从上往下劈。强烈下旋（实测 -1.64 rad/tick）。 */
	CHOP_FOREHAND("chop_fh", 37, 0.82, -0.57, 0.43, PingPongContact.PaddleSurface.BRUSH),
	/** 反手削球：劈得更陡。 */
	CHOP_BACKHAND("chop_bh", 36, 0.86, -0.51, 0.40, PingPongContact.PaddleSurface.BRUSH);

	/** 稳定标识（日志 / 调试用） */
	public final String id;
	/** 翻译键（HUD 用） */
	public final String translationKey;
	/** 拍面法线与水平面的夹角（度），正 = 后仰 */
	public final double normalTiltDegrees;
	/** 挥拍方向的前向分量 */
	public final double swingX;
	/** 挥拍方向的竖直分量（+ 向上刷、- 向下劈） */
	public final double swingY;
	/** 满力度时的挥拍速度（格/tick） */
	public final double swingSpeed;
	/** 胶皮参数（恢复系数 + 摩擦系数） */
	public final PingPongContact.PaddleSurface surface;

	StrokeType(String id, double normalTiltDegrees, double swingX, double swingY,
			   double swingSpeed, PingPongContact.PaddleSurface surface) {
		this.id = id;
		this.normalTiltDegrees = normalTiltDegrees;
		this.swingX = swingX;
		this.swingY = swingY;
		this.swingSpeed = swingSpeed;
		this.surface = surface;
		// 【Java 8 兼容】原来这里用 switch **表达式**（Java 14+），改成先算再赋值的普通写法
		String family = id.substring(0, id.indexOf('_'));
		if ("drive".equals(family) || "loop".equals(family) || "push".equals(family)) {
			this.translationKey = "stroke.pingpong." + family;
		} else {
			this.translationKey = "stroke.pingpong.chop";
		}
	}

	// ------------------------------------------------------------------
	// 分类查询
	// ------------------------------------------------------------------

	public boolean isBackhand() {
		return this == DRIVE_BACKHAND || this == LOOP_BACKHAND || this == PUSH_BACKHAND || this == CHOP_BACKHAND;
	}

	public boolean isLoop() {
		return this == LOOP_FOREHAND || this == LOOP_BACKHAND;
	}

	public boolean isPush() {
		return this == PUSH_FOREHAND || this == PUSH_BACKHAND;
	}

	public boolean isChop() {
		return this == CHOP_FOREHAND || this == CHOP_BACKHAND;
	}

	public boolean isDrive() {
		return this == DRIVE_FOREHAND || this == DRIVE_BACKHAND;
	}

	/** 同一类击球的手型切换（正手 ↔ 反手）。【Java 8 兼容】用传统 switch 而不是 switch 表达式。 */
	public StrokeType flipHand() {
		switch (this) {
			case DRIVE_FOREHAND:
				return DRIVE_BACKHAND;
			case DRIVE_BACKHAND:
				return DRIVE_FOREHAND;
			case LOOP_FOREHAND:
				return LOOP_BACKHAND;
			case LOOP_BACKHAND:
				return LOOP_FOREHAND;
			case PUSH_FOREHAND:
				return PUSH_BACKHAND;
			case PUSH_BACKHAND:
				return PUSH_FOREHAND;
			case CHOP_FOREHAND:
				return CHOP_BACKHAND;
			case CHOP_BACKHAND:
			default:
				return CHOP_FOREHAND;
		}
	}

	/** 按需切换：{@code backhand == true} 时返回反手版本，否则原样返回。 */
	public StrokeType flipHandIf(boolean backhand) {
		return backhand == isBackhand() ? this : flipHand();
	}

	// ------------------------------------------------------------------
	// 局部方向（+x = 飞向对面）
	// ------------------------------------------------------------------

	/** 局部坐标下的拍面法线：法线朝球来的那一侧，所以前倾(负角)时它指向下前方。 */
	public Vec3d localNormal() {
		double rad = Math.toRadians(normalTiltDegrees);
		// 拍面法线在 x-y 平面内：后仰(正角) → 法线朝上前方；前倾(负角) → 法线朝下前方
		return new Vec3d(Math.cos(rad), Math.sin(rad), 0.0).normalize();
	}

	/** 局部坐标下的挥拍方向（单位向量）。 */
	public Vec3d localSwing() {
		return new Vec3d(swingX, swingY, 0.0).normalize();
	}

	// ------------------------------------------------------------------
	// 局部 → 世界 坐标变换
	// ------------------------------------------------------------------

	/**
	 * 局部基：+x = 从击球者飞向对面（水平），+y = 世界上方，+z = 击球者右手侧。
	 *
	 * @param forward 水平出球方向（单位向量，会重新归一化）
	 */
	/**
	 * 局部基：+x = 从击球者飞向对面（水平），+y = 世界上方，+z = 击球者右手侧。
	 * 【Java 8 兼容】普通不可变类（原来是 record）。
	 */
	public static final class Basis {
		private final Vec3d forward;
		private final Vec3d up;
		private final Vec3d right;

		public Basis(Vec3d forward, Vec3d up, Vec3d right) {
			this.forward = forward;
			this.up = up;
			this.right = right;
		}

		/** 水平前进方向 */
		public Vec3d forward() {
			return this.forward;
		}

		/** 世界上方 */
		public Vec3d up() {
			return this.up;
		}

		/** 击球者右手侧 */
		public Vec3d right() {
			return this.right;
		}

		/** 由水平前进方向构造（会重新归一化） */
		public static Basis of(Vec3d forwardHorizontal) {
			Vec3d f = new Vec3d(forwardHorizontal.x, 0.0, forwardHorizontal.z);
			f = f.lengthSquared() < 1.0e-8 ? new Vec3d(1.0, 0.0, 0.0) : f.normalize();
			Vec3d up = new Vec3d(0.0, 1.0, 0.0);
			Vec3d right = f.crossProduct(up).normalize();   // 前 × 上 = 右手侧
			return new Basis(f, up, right);
		}

		/** 把局部方向 (x, y, z) 转到世界坐标 */
		public Vec3d localToWorld(double x, double y, double z) {
			return this.forward.multiply(x).add(this.up.multiply(y)).add(this.right.multiply(z));
		}
	}

	/**
	 * 世界坐标下的拍面法线。
	 *
	 * @param tiltOffsetDegrees 玩家用滚轮额外调整的角度（正 = 更后仰），叠加在本类型的基准角上
	 */
	public Vec3d worldNormal(Basis basis, double tiltOffsetDegrees) {
		double rad = Math.toRadians(normalTiltDegrees + tiltOffsetDegrees);
		Vec3d local = new Vec3d(Math.cos(rad), Math.sin(rad), 0.0).normalize();
		return basis.localToWorld(local.x, local.y, local.z);
	}

	/** 世界坐标下的挥拍方向（把力量偏到某一侧时再加侧向分量）。 */
	public Vec3d worldSwing(Basis basis, double sideDegrees) {
		Vec3d local = localSwing();
		if (Math.abs(sideDegrees) > 1.0e-4) {
			double rad = Math.toRadians(sideDegrees);
			double nx = local.x * Math.cos(rad) - local.z * Math.sin(rad);
			double nz = local.x * Math.sin(rad) + local.z * Math.cos(rad);
			local = new Vec3d(nx, local.y, nz).normalize();
		}
		return basis.localToWorld(local.x, local.y, local.z);
	}

	// ------------------------------------------------------------------
	// 选择规则（需求 17 / 20b）
	// ------------------------------------------------------------------

	/** 右键蓄力超过这个比例就改成削球（真实发力比例：轻推是搓、大力才是削） */
	public static final double CHOP_POWER_THRESHOLD = 0.57;

	/**
	 * 按「哪只手 / 哪个键 / 蓄力多大」选出击球类型。
	 *
	 * <ul>
	 *   <li>左键（攻球键）：蓄力大 → 拉弧圈（薄摩擦刷球，造上旋），否则 → 攻球</li>
	 *   <li>右键：蓄力 &lt; 57% → 搓球（慢、低、下旋），≥ 57% → 削球（从上往下劈、强烈下旋）</li>
	 * </ul>
	 *
	 * @param backhand 是否反手
	 * @param rightButton 是否右键（搓/削）
	 * @param power 蓄力力度 0~1
	 */
	public static StrokeType select(boolean backhand, boolean rightButton, double power) {
		StrokeType base;
		if (rightButton) {
			base = power >= CHOP_POWER_THRESHOLD ? CHOP_FOREHAND : PUSH_FOREHAND;
		} else {
			base = power >= CHOP_POWER_THRESHOLD ? LOOP_FOREHAND : DRIVE_FOREHAND;
		}
		return base.flipHandIf(backhand);
	}

	/** 按序号取（网络包里只传一个字节）。 */
	public static StrokeType byId(int index) {
		StrokeType[] values = values();
		if (index < 0 || index >= values.length) {
			return DRIVE_FOREHAND;
		}
		return values[index];
	}
}

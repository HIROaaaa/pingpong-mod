package com.whale.pingpong.physics;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 乒乓球物理模型（纯数学，不碰世界，方便单独调参 + 单元测试）。
 *
 * 单位约定：长度=格(block)，时间=tick(1 tick = 1/20 秒)，自旋 ω = rad/tick。
 * 真实乒乓球：直径 40mm、质量 2.7g、空心壳（I = 2/3 m r²）、空气阻力极大、马格努斯效应极明显。
 * 这里做了游戏化缩放：球比真实大（半径 0.14 格，否则玩家看不见也打不到），
 * 但保留了「阻力 + 马格努斯 + 摩擦弹跳」三件套的相对关系。
 */
public final class PingPongPhysics {

	/** 球半径（格）。与实体碰撞箱 0.28 匹配。 */
	public static final double BALL_RADIUS = 0.14;
	/** 球质量（归一化，只在冲量公式里当比例用） */
	public static final double MASS = 1.0;
	/** 空心球转动惯量 I = 2/3 * m * r² */
	public static final double INERTIA = (2.0 / 3.0) * MASS * BALL_RADIUS * BALL_RADIUS;

	/** 重力加速度（格/tick²）。真实 9.8m/s² ≈ 0.0245，这里略大让球更「实」。 */
	public static final double GRAVITY = 0.030;
	/** 空气阻力线性项：每 tick 速度衰减比例 */
	public static final double DRAG_LINEAR = 0.010;
	/**
	 * 空气阻力二次项：真实阻力 ∝ v²，除以 v 后就是 ∝ v。
	 * 二期取 0.016，用户实测「打的太远了」，这里加到 0.022：满力度 1.15 格/tick 的球
	 * 每 tick 多损失约 0.7% 速度，落到对面半台时已经明显掉速，更接近乒乓球的短促飞行。
	 */
	public static final double DRAG_QUADRATIC = 0.022;
	/**
	 * 马格努斯系数：a = k * (ω × v)。
	 *
	 * 【与自旋上限的配合关系（调一个必须调另一个）】
	 * k 决定「力」，ω·R 决定接触点滑不滑动 —— 两者一起决定手感是否真实。
	 * 现在 MAX_SPIN = 9.0、球半径 0.14 → ω·R ≈ 1.26，与出球速度 0.3~0.45 同量级
	 * （真实乒乓球也是「球面速度 ≈ 球速」，这样摩擦才能把自旋真正变成前冲/回搓）。
	 * 取 k = 0.003 让 ω=9、v=0.4 时马格努斯加速度 ≈ 0.011 ≈ 重力的 1/3 ——
	 * 明显但不至于把球变成滑翔机（历史教训：k=0.014/ωmax=4 时下旋升力 0.048 > 重力 0.030，球飞 21 格）。
	 *
	 * 【五期 M8：0.003 → 0.005】用户三次反馈「侧旋根本体现不出来」。side_spin_sweep.js 实测：
	 * 第一跳前的横向位移在 k=0.003/0.004/0.005/0.006 下分别是 0.076/0.102/0.127/0.152 格
	 * （球台全场才 2.74 格，这一段的侧移才是玩家看得见的那部分）。
	 * 0.005 配合侧旋轴倾角改到 75°，合计把肉眼可见的侧弯从 0.076 提到约 0.146 格（近一倍）；
	 * 此时马格努斯力约为重力的 0.17×，离"滑翔机"那条线（0.048 > 0.030）还很远。
	 * 若后续出现上旋球出台，先回落这一项而不是动倾角。
	 */
	public static final double MAGNUS_COEFFICIENT = 0.005;
	/** 自旋每 tick 的保持率（空气里自旋会慢慢衰减） */
	public static final double SPIN_DECAY = 0.988;
	/**
	 * 自旋上限（rad/tick）。
	 *
	 * 【为什么是 5.2】两头都会被卡住：
	 * - 太小（<4）：弹跳时切向摩擦冲量小于法向冲量，「上旋球落台前冲」出不来；
	 * - 太大（>6.5）：出球速度已经降到 0.30~0.45，而马格努斯力正比于 ω×v，
	 *   自旋过强会让上旋球变成"滑翔机"（脚本实测 6.08 时上旋 4.9 格、下旋直接下网）。
	 * 5.2 配 k=0.003：ω·R ≈ 0.73，与球速 0.3~0.45 同量级，弹跳能出前冲/回缩，飞行弧线也可控。
	 * 实测（tools/physics_sanity_check.js + calibrate_hit_speed.js）：上旋落点 2.9 格、下旋 2.7 格，都在对面台面内。
	 */
	public static final double MAX_SPIN = 5.2;
	/**
	 * @deprecated 自旋保留率已按表面分开（见 {@link Surface#spinRetain()}），这个常量不再被物理使用，
	 *             只保留给旧脚本读；新代码请用 {@code surface.spinRetain()} 或 {@link #groundSpinDecay(boolean)}。
	 */
	@Deprecated
	public static final double SPIN_BOUNCE_RETAIN = 0.85;

	private PingPongPhysics() {
	}

	/**
	 * 碰撞面材质。不同表面给不同的恢复系数与摩擦 —— 这是「球台能弹、草地不弹、球网吃球」的开关。
	 *
	 * <p>【Java 8 兼容】原来是 {@code record}（Java 16+），M5 多版本要降到 Java 8，
	 * 所以改写成普通不可变类 + getter。字段名与访问方式保持一致（{@code restitution()} 等方法名不变），
	 * 调用方不用改。</p>
	 */
	public static final class Surface {
		private final double restitution;
		private final double friction;
		private final double spinRetain;

		public Surface(double restitution, double friction, double spinRetain) {
			this.restitution = restitution;
			this.friction = friction;
			this.spinRetain = spinRetain;
		}

		/** 恢复系数 e（法向速度保留比例） */
		public double restitution() {
			return this.restitution;
		}

		/** 摩擦系数 μ（切向冲量上限 = μ|J_n|，决定自旋与速度怎么互相转化） */
		public double friction() {
			return this.friction;
		}

		/**
		 * 单次接触后自旋的保留率。
		 * 【为什么按表面分开】二期只有一个全局 0.85：球在**球台上**弹一下自旋就掉 15%，
		 * 两次弹跳后侧旋几乎归零 —— 用户报的「侧旋落地拐一下就没了」正是它。
		 * 台面是光滑硬木，自旋保留得比泥地高得多。
		 */
		public double spinRetain() {
			return this.spinRetain;
		}

		/** 球台台面：硬，弹得高，且**自旋保留多**（侧旋落地继续拐的关键） */
		public static final Surface TABLE = new Surface(0.90, 0.60, 0.96);
		/** 球台侧面 / 桌腿：木结构 */
		public static final Surface TABLE_SIDE = new Surface(0.70, 0.65, 0.90);
		/** 球网：软，几乎吃掉动能，摩擦极大 */
		public static final Surface NET = new Surface(0.22, 0.95, 0.60);
		/** 其他地面（泥土 / 石头 / 草地）：默认，自旋掉得最快 */
		public static final Surface GROUND = new Surface(0.75, 0.65, 0.85);
	}

	/**
	 * 贴地滚动时每 tick 的自旋保留率。
	 * 台面上 0.97（侧旋球落台后还要靠自旋继续侧拐），普通地面 0.85（快速吃掉自旋，
	 * 否则球会被自己的自旋在地面反复「搓」着跑 —— 二期实测侧旋在地面横移 7 格以上）。
	 */
	public static double groundSpinDecay(boolean onTable) {
		return onTable ? 0.97 : 0.85;
	}

	/**
	 * 马格努斯加速度：a = k (ω × v)。
	 * 上旋球（ω 与 up×dir 同向）→ a 向下 → 弧线下扎；
	 * 下旋球 → a 向上 → 球「飘」；
	 * 侧旋球（自旋轴朝行进方向倾斜）→ 横向加速 → 香蕉球。
	 */
	public static Vec3d magnusAcceleration(Vec3d velocity, Vec3d spin) {
		return spin.crossProduct(velocity).multiply(MAGNUS_COEFFICIENT);
	}

	/** 空气阻力：线性 + 二次项，二次项让高速球掉得更快（更接近真实乒乓）。 */
	public static Vec3d applyAirDrag(Vec3d velocity) {
		double speed = velocity.length();
		double factor = 1.0 - (DRAG_LINEAR + DRAG_QUADRATIC * speed);
		// 下限保护：即使数值极端也不允许反向
		return velocity.multiply(MathHelper.clamp(factor, 0.75, 1.0));
	}

	/** 自旋衰减 + 夹紧。 */
	public static Vec3d decaySpin(Vec3d spin) {
		return clampSpin(spin.multiply(SPIN_DECAY));
	}

	/** 把自旋长度限制在 MAX_SPIN 内（方向不变）。 */
	public static Vec3d clampSpin(Vec3d spin) {
		double length = spin.length();
		if (length > MAX_SPIN) {
			return spin.multiply(MAX_SPIN / length);
		}
		return spin;
	}

	/** 弹跳结果：新的速度与新的自旋。（Java 8 兼容：普通不可变类） */
	public static final class BounceResult {
		private final Vec3d velocity;
		private final Vec3d spin;

		public BounceResult(Vec3d velocity, Vec3d spin) {
			this.velocity = velocity;
			this.spin = spin;
		}

		public Vec3d velocity() {
			return this.velocity;
		}

		public Vec3d spin() {
			return this.spin;
		}
	}

	/**
	 * 刚体球撞到平面（法线 surfaceNormal，指向球所在的一侧）后的速度与自旋。
	 *
	 * 物理要点（这是「自旋影响弹跳方向」的核心）：
	 * 1. 法向：按恢复系数反弹 v_n' = -e * v_n；
	 * 2. 接触点速度 u = v + ω × r，其中 r 是球心指向接触点的向量（= -R * n）；
	 * 3. 切向摩擦冲量 J_t 方向与 u 的切向分量相反，大小 min(μ|J_n|, 恰好止滑的冲量)；
	 * 4. 冲量同时改变线速度（v += J/m）和角速度（ω += (r × J)/I）。
	 *
	 * 于是：下旋球接触点相对台面「向前滑」→ 摩擦力向后 → 球被搓回来（会往回跳）；
	 *       上旋球接触点「向后滑」→ 摩擦力向前 → 球加速前冲、弹得很低（前冲弧圈）；
	 *       侧旋球（轴朝行进方向倾斜）撞地 → 产生侧向搓动，球落地后侧拐。
	 *
	 * @param surface 碰撞面材质，决定 e 与 μ。球台 e=0.90 弹得高，草地 e=0.75 一般，球网 e=0.22 吃球。
	 */
	public static BounceResult bounce(Vec3d velocity, Vec3d spin, Vec3d surfaceNormal, Surface surface) {
		Vec3d n = surfaceNormal.normalize();
		double normalSpeed = velocity.dotProduct(n);
		if (normalSpeed > 0.0) {
			// 已经在离开表面，不做处理（防止抖动）
			return new BounceResult(velocity, spin);
		}

		// --- 1. 法向冲量 ---
		double normalImpulse = -(1.0 + surface.restitution()) * normalSpeed * MASS;
		Vec3d newVelocity = velocity.add(n.multiply(normalImpulse / MASS));

		// --- 2. 接触点速度（球心速度 + 自旋带来的线速度） ---
		Vec3d contactOffset = n.multiply(-BALL_RADIUS);              // r：球心 → 接触点
		Vec3d contactVelocity = velocity.add(spin.crossProduct(contactOffset));
		Vec3d contactTangent = contactVelocity.subtract(n.multiply(contactVelocity.dotProduct(n)));
		double slipSpeed = contactTangent.length();

		Vec3d newSpin = spin;
		if (slipSpeed > 1.0e-6) {
			// --- 3. 切向摩擦冲量 ---
			Vec3d tangentDir = contactTangent.multiply(-1.0 / slipSpeed); // 摩擦方向：反向于滑动
			// 恰好让接触点停止滑动所需冲量：|u_t| / (1/m + r²/I)
			double stoppingImpulse = slipSpeed / (1.0 / MASS + (BALL_RADIUS * BALL_RADIUS) / INERTIA);
			double tangentImpulse = Math.min(surface.friction() * Math.abs(normalImpulse), stoppingImpulse);
			Vec3d impulse = tangentDir.multiply(tangentImpulse);

			// --- 4. 冲量作用于线速度与角速度 ---
			newVelocity = newVelocity.add(impulse.multiply(1.0 / MASS));
			newSpin = spin.add(contactOffset.crossProduct(impulse).multiply(1.0 / INERTIA));
		}

		newSpin = clampSpin(newSpin.multiply(surface.spinRetain()));
		return new BounceResult(newVelocity, newSpin);
	}

	/**
	 * 判断速度是否小到可以视为静止（格/tick）。
	 * 取 0.03：球贴地时每个 tick 会被重力推 0.03、再被 e 反弹回 0.022，
	 * 阈值低于它就永远判定不了「静止」，球会在 0↔0.022 之间无休止抖动（实测确认）。
	 */
	public static boolean isNearlyStill(Vec3d velocity) {
		return velocity.lengthSquared() < 9.0e-4;
	}
}

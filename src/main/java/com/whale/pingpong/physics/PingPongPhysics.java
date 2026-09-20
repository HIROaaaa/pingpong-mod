package com.whale.pingpong.physics;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 乒乓球物理模型（纯数学，不碰世界，方便单独调参）。
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
	/** 空气阻力二次项：随速度线性增长（真实阻力 ∝ v²，除以 v 后就是 ∝ v） */
	public static final double DRAG_QUADRATIC = 0.020;
	/**
	 * 马格努斯系数：a = k * (ω × v)。
	 * 取值依据：最大自旋 5.5 搭配 0.9 格/tick 的球速时 a ≈ 0.025，
	 * 略小于重力 0.030 —— 下旋球明显「飘」但不会变成滑翔机，
	 * 上旋球叠加到约 1.8 倍重力，做出肉眼可见的下扎弧线。
	 */
	public static final double MAGNUS_COEFFICIENT = 0.005;
	/** 自旋每 tick 的保持率（空气里自旋会慢慢衰减） */
	public static final double SPIN_DECAY = 0.988;
	/**
	 * 自旋上限（rad/tick）。
	 * 关键：真实乒乓球的自旋强到「球面速度 ω·R ≈ 球速」，
	 * 这时接触点几乎不滑动，摩擦力才会把自旋真正转成前进/后退的冲量。
	 * 本 Mod 的球半径 0.14 格，取 ω = 5.5 时 ω·R = 0.77，正好落在球速量级上。
	 */
	public static final double MAX_SPIN = 5.5;

	/** 弹跳恢复系数（法向速度保留比例），乒乓球偏弹，取 0.72 */
	public static final double RESTITUTION = 0.72;
	/** 台面/地面摩擦系数 μ，决定自旋和速度之间怎么互相转化 */
	public static final double FRICTION = 0.65;
	/** 每次弹跳自旋额外损失 */
	public static final double SPIN_BOUNCE_RETAIN = 0.85;

	private PingPongPhysics() {
	}

	/**
	 * 马格努斯加速度：a = k (ω × v)。
	 * 上旋球（ω 与 up×dir 同向）→ a 向下 → 弧线下扎；
	 * 下旋球 → a 向上 → 球「飘」；
	 * 侧旋球 → 横向加速 → 香蕉球。
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

	/**
	 * 弹跳结果：新的速度与新的自旋。
	 */
	public record BounceResult(Vec3d velocity, Vec3d spin) {
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
	 *       上旋球接触点「向后滑」→ 摩擦力向前 → 球加速前冲、弹得很低（前冲弧圈）。
	 *       侧旋球撞墙 → 产生竖直方向的搓动（侧拐球弹起后会上下乱窜）。
	 */
	public static BounceResult bounce(Vec3d velocity, Vec3d spin, Vec3d surfaceNormal) {
		Vec3d n = surfaceNormal.normalize();
		double normalSpeed = velocity.dotProduct(n);
		if (normalSpeed > 0.0) {
			// 已经在离开表面，不做处理（防止抖动）
			return new BounceResult(velocity, spin);
		}

		// --- 1. 法向冲量 ---
		double normalImpulse = -(1.0 + RESTITUTION) * normalSpeed * MASS;
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
			double tangentImpulse = Math.min(FRICTION * Math.abs(normalImpulse), stoppingImpulse);
			Vec3d impulse = tangentDir.multiply(tangentImpulse);

			// --- 4. 冲量作用于线速度与角速度 ---
			newVelocity = newVelocity.add(impulse.multiply(1.0 / MASS));
			newSpin = spin.add(contactOffset.crossProduct(impulse).multiply(1.0 / INERTIA));
		}

		newSpin = clampSpin(newSpin.multiply(SPIN_BOUNCE_RETAIN));
		return new BounceResult(newVelocity, newSpin);
	}

	/** 判断速度是否小到可以视为静止（格/tick）。 */
	public static boolean isNearlyStill(Vec3d velocity) {
		return velocity.lengthSquared() < 4.0e-4;
	}
}

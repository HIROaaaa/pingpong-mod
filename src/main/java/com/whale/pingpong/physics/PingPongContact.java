package com.whale.pingpong.physics;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * 球拍—球的接触模型（需求 9~22 的公共底座）。
 *
 * <h2>为什么必须换掉旧算法</h2>
 * 二期/三期的做法是「按拍面角度查表给自旋」：出球速度 = 常数 × 力度，自旋 = 拍面角度 × 上限。
 * 这在物理上不成立，于是出现了用户报的一堆现象：平拍挡上旋球不往下走、侧旋球被平拍挡不往旁边弹、
 * 削球削不住却没有"打滑"的概念。
 *
 * <h2>真实接触模型</h2>
 * 把球拍当成一个**运动中的刚性平面**（拍面），球是刚性球，接触时：
 * <pre>
 *   u  = v_ball - V_paddle                 ← 相对速度（球相对拍面）
 *   c  = u - ω × (R·n)                     ← 接触点相对拍的滑移速度
 *   un = u · n                             ← 法向分量（&lt; 0 才是撞上来）
 *   s  = c - (c·n)·n                       ← 切向滑移
 *   Jn = -(1 + e)·un · m                   ← 法向冲量（e = 胶皮恢复系数）
 *   Jt = -min(μ|Jn|, |s| / (1/m + R²/I)) · ŝ   ← 切向摩擦冲量（可能打滑）
 *   v' = v + (Jn·n + Jt) / m
 *   ω' = ω + ((-R·n) × Jt) / I
 * </pre>
 *
 * 这一个公式就能同时解释用户列出的全部现象：
 * <ul>
 *   <li><b>上旋球被平拍挡 → 下网</b>：ω×(-Rn) 让接触点向前滑 → 摩擦向上抬，但法向把球压向下，
 *       出球自旋减小后剩下的上旋被马格努斯往下压；</li>
 *   <li><b>侧旋球被平拍挡 → 往旁边弹</b>：ω 的竖直分量在接触点造成侧向滑移 → 侧向摩擦冲量；</li>
 *   <li><b>强上旋重削 → 飞高出台</b>：法向分量小、切向分量大 → 打滑（Jt 取 μ|Jn| 而不是止滑值），
 *       出球速度骤降但 ω' 仍在；</li>
 *   <li><b>拉球有弧线、平击没弧线</b>：拉球时拍面向上挥（V 有向上分量）→ 切向摩擦向上 →
 *       高转速 + 合适出射角；平击时 V 水平、拍面打开 → 几乎无切向摩擦 → 几乎无自旋。</li>
 * </ul>
 *
 * 纯数学、不碰世界，方便脚本离线验证（tools/contact_model_check.js）。
 */
public final class PingPongContact {

	/** 接触参数：由球拍胶皮决定 */
	public record PaddleSurface(double restitution, double friction) {
		/**
		 * 普通胶皮（攻球/挡球）：胶皮弹性一般、摩擦中等。
		 * e=0.72 略低于球台的 0.90（胶皮比木台软），μ=0.85 是反胶的典型量级。
		 */
		public static final PaddleSurface NORMAL = new PaddleSurface(0.72, 0.85);
		/**
		 * 薄摩擦（拉弧圈 / 搓球 / 削球）：拍面"刷"过球，切向咬得更住。
		 * μ=1.15 是"吃住球"的量化表达 —— 真实反胶能到 1.0~1.3，这是弧圈球能造出来的物理基础。
		 */
		public static final PaddleSurface BRUSH = new PaddleSurface(0.62, 1.15);
	}

	/** 接触结果 */
	public record Result(Vec3d velocity, Vec3d spin, double slipSpeed, boolean slipping, double normalImpulse) {
	}

	/**
	 * 算一次接触。
	 *
	 * <h3>法线符号约定（四期在这里连翻两次车，务必读完）</h3>
	 * 传入的 {@code paddleNormal} 由调用方保证**指向出击方向**（即球应该飞出去的那一侧）；
	 * 而"是否发生接触"用**相对速度在法线上的投影是否为负**来判断：
	 * <pre>
	 *   un = (v_ball - V_paddle) · n
	 *   un &lt; 0  → 球相对拍面正朝法线负方向运动 = 球正在"钻进"拍面 = 接触
	 *   un &gt; 0  → 球正在离开拍面 = 不接触
	 * </pre>
	 * 用"朝出击方向"当法线有个额外好处：{@link #hit} 里判断"这一挥是不是插进拍面里"用的是同一支向量，
	 * 两个用途不打架。（第一版把法线定义成"指向球来的那一侧"，结果撞击判定与挥拍去侵入判定互相抵消，
	 * 挥拍速度被整段投影掉、出球等于没打 —— 现在两边统一成这一个约定。）
	 *
	 * @param ballVelocity   来球速度（格/tick）
	 * @param ballSpin       来球自旋 ω（rad/tick）
	 * @param ballRadius     球半径（格）
	 * @param ballMass       球质量（归一化即可）
	 * @param ballInertia    转动惯量 I = 2/3·m·r²（空心球）
	 * @param paddleNormal   拍面法线（单位向量，**朝出击方向**）
	 * @param paddleVelocity 拍面中心的运动速度（挥拍速度，格/tick）
	 * @param surface        胶皮参数
	 * @return 出球速度与自旋；未接触时原样返回
	 */
	public static Result resolve(Vec3d ballVelocity, Vec3d ballSpin,
								 double ballRadius, double ballMass, double ballInertia,
								 Vec3d paddleNormal, Vec3d paddleVelocity,
								 PaddleSurface surface) {
		Vec3d n = paddleNormal.normalize();
		Vec3d relative = ballVelocity.subtract(paddleVelocity);
		double un = relative.dotProduct(n);
		if (un >= 0.0) {
			// 球相对拍面在朝出击方向走：已经分开了，不接触
			return new Result(ballVelocity, ballSpin, 0.0, false, 0.0);
		}

		// 接触点相对拍面的滑移：球心相对速度 + 自旋贡献
		// r = -R·n 是球心指向接触点的向量；ω × r 是那一点因自旋产生的线速度
		Vec3d contactOffset = n.multiply(-ballRadius);
		Vec3d contactVelocity = relative.add(ballSpin.crossProduct(contactOffset));
		Vec3d tangent = contactVelocity.subtract(n.multiply(contactVelocity.dotProduct(n)));
		double slipSpeed = tangent.length();

		// --- 法向冲量：把「朝拍里钻」的那部分相对速度反弹回来（1 + e 倍）---
		double normalImpulse = -(1.0 + surface.restitution()) * un * ballMass;
		Vec3d newVelocity = ballVelocity.add(n.multiply(normalImpulse / ballMass));

		// --- 切向摩擦冲量 ---
		Vec3d newSpin = ballSpin;
		boolean slipping = false;
		if (slipSpeed > 1.0e-6) {
			Vec3d slipDir = tangent.multiply(-1.0 / slipSpeed);   // 摩擦方向：反向于滑移
			// 「恰好止滑」所需冲量：|s| / (1/m + R²/I)
			double stoppingImpulse = slipSpeed / (1.0 / ballMass + (ballRadius * ballRadius) / ballInertia);
			double frictionLimit = surface.friction() * Math.abs(normalImpulse);
			double tangentImpulse = Math.min(frictionLimit, stoppingImpulse);
			slipping = frictionLimit < stoppingImpulse;           // 摩擦不够 = 打滑 = 吃不住球

			Vec3d impulse = slipDir.multiply(tangentImpulse);
			newVelocity = newVelocity.add(impulse.multiply(1.0 / ballMass));
			newSpin = newSpin.add(contactOffset.crossProduct(impulse).multiply(1.0 / ballInertia));
		}

		return new Result(newVelocity, newSpin, slipSpeed, slipping, normalImpulse);
	}

	/**
	 * 力度 → 挥拍速度的缩放系数。
	 *
	 * 【为什么不是线性 →power】出球速度的动态范围受球台尺度限制：球台只有 2.74 格，
	 * 能过网又不出台的出球速度区间大约只有 0.30~0.45 格/tick。
	 * 若挥拍速度按 power 线性缩放（0 → 满），轻打必然下网、满打必然出台
	 * （扫参实测：线性映射下力度 0.2 全部下网、1.0 全部出台）。
	 * 压成 0.6~1.0 倍后，各击球的可用力度区间覆盖 0.2~1.0。
	 */
	public static final double SWING_POWER_FLOOR = 0.6;
	public static final double SWING_POWER_RANGE = 0.4;

	/** 力度 → 挥拍速度倍率 */
	public static double swingScale(double power) {
		return SWING_POWER_FLOOR + SWING_POWER_RANGE * MathHelper.clamp(power, 0.0, 1.0);
	}

	/**
	 * 一次击球的完整出球计算：给定「来球 + 拍面朝向 + 挥拍方向 + 挥拍速度 + 力度」。
	 *
	 * <h3>为什么要"保前向"而不是整段去掉法向分量</h3>
	 * 直觉是"挥拍速度里插进拍面的那部分不算数"，但那样会把**前倾拍面的正前挥拍整段删掉**
	 * （前倾时法线的前向分量与挥拍前向相反），于是搓球/拉球这种前倾拍面直接变成"拍不动"。
	 * 实际手感里，前倾拍面的正前挥拍恰恰是"压着往前盖"，是有效动作。
	 * 所以这里只把法向分量的**侵入部分削到 0**（不许把球按进拍里），
	 * 保留它沿拍面前进的那部分（它通过切向摩擦给球前向加速）。
	 *
	 * @param power 力度 0~1
	 */
	public static Result hit(Vec3d ballVelocity, Vec3d ballSpin,
							 double ballRadius, double ballMass, double ballInertia,
							 Vec3d paddleNormal, Vec3d swingDirection, double swingSpeed, double power,
							 PaddleSurface surface) {
		Vec3d n = paddleNormal.normalize();
		Vec3d swing = swingDirection.normalize().multiply(swingSpeed * swingScale(power));

		// 法向分量不许为负（负 = 把球往拍面里压）。削到 0 即可，不要整段去掉。
		double alongNormal = swing.dotProduct(n);
		if (alongNormal < 0.0) {
			swing = swing.subtract(n.multiply(alongNormal));
		}

		return resolve(ballVelocity, ballSpin, ballRadius, ballMass, ballInertia, n, swing, surface);
	}

	/** 出球速度的竖直分量是否足以让球离台（用于离线自检）。 */
	public static boolean isUpward(Vec3d velocity) {
		return velocity.y > 0.0;
	}
}

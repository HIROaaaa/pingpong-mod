package com.whale.pingpong.util;

import com.whale.pingpong.block.ModBlocks;
import com.whale.pingpong.block.PingPongTableBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 球台几何：找台、算「该站哪边、往哪打」。
 *
 * 【为什么需要它】用户要求 6：跟球视角下击球点不能跟着视线跑，而应该由
 * 「球台的朝向 + 玩家站在球台哪一边」决定 —— 两个人站在台子两端，击球点必须各在一侧，
 * 否则两人会挤在同一侧的同一片空气里挥拍。
 *
 * 这里只做纯几何计算，不碰实体、不发包，方便服务端权威使用，也方便单测。
 */
public final class TableGeometry {

	/** 击球点相对眼睛的高度偏移（格）：正手比眼睛略低，球心大约在这个高度 */
	private static final double PADDLE_HEIGHT = 0.45;
	/** 击球点朝球台方向的前伸距离（格） */
	private static final double FORWARD = 0.55;
	/** 击球点左右偏移（格）：正手在外侧、反手在内侧 */
	private static final double SIDE_OUT = 0.38;

	// ---- 引拍圆弧（需求 12）----
	/** 正手引拍圆弧半径（格）：以肘关节为圆心 */
	private static final double FOREHAND_ARC_RADIUS = 0.45;
	/** 正手引拍时肘部整体后移量（格） */
	private static final double ELBOW_BACK = 0.25;
	/** 反手引拍圆弧半径（格）：幅度明显小于正手 */
	private static final double BACKHAND_ARC_RADIUS = 0.28;
	/** 反手引拍时整体后移量（格） */
	private static final double BACKHAND_BACK = 0.12;

	/** 「台内」判定距离（格）：眼到最近台缘的水平距离小于它就算站在台内（需求 19） */
	private static final double IN_TABLE_DISTANCE = 1.15;
	/** 球台半长（沿长边，格）：3 格长的台子 */
	private static final double TABLE_HALF_ALONG = 1.5;
	/** 球台半宽（沿短边，格）：2 格宽的台子 */
	private static final double TABLE_HALF_ACROSS = 1.0;

	private TableGeometry() {
	}

	/** 找球台：返回锚点（球台正中那一格）的坐标，64 格内没台子返回 null。 */
	public static BlockPos findTable(World world, Vec3d from) {
		if (world == null || from == null) {
			return null;
		}
		BlockPos origin = BlockPos.ofFloored(from);
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		for (BlockPos pos : BlockPos.iterateOutwards(origin, 8, 3, 8)) {
			BlockState state = world.getBlockState(pos);
			if (!state.isOf(ModBlocks.PINGPONG_TABLE)) {
				continue;
			}
			Vec3d center = centerOf(pos);
			double distance = center.squaredDistanceTo(from);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = pos.toImmutable();
			}
		}
		return best;
	}

	/** 球台锚点方块的中心坐标（台面中心）。 */
	public static Vec3d centerOf(BlockPos anchor) {
		return new Vec3d(anchor.getX() + 0.5, anchor.getY() + PingPongTableBlock.TOP_Y, anchor.getZ() + 0.5);
	}

	/** 台面中心 → 玩家的水平单位向量；玩家站在台子正中时返回 Vec3d.ZERO。 */
	public static Vec3d outward(World world, Vec3d playerPos) {
		BlockPos anchor = findTable(world, playerPos);
		if (anchor == null) {
			return Vec3d.ZERO;
		}
		return outwardFrom(centerOf(anchor), playerPos);
	}

	/**
	 * 台面中心 → 玩家的水平单位向量。
	 * 玩家正好站在台子正中（水平距离极小）时返回 ZERO，调用方应退回「按视线」的旧逻辑。
	 */
	public static Vec3d outwardFrom(Vec3d tableCenter, Vec3d playerPos) {
		if (tableCenter == null || playerPos == null) {
			return Vec3d.ZERO;
		}
		Vec3d flat = new Vec3d(playerPos.x - tableCenter.x, 0.0, playerPos.z - tableCenter.z);
		if (flat.lengthSquared() < 1.0e-4) {
			return Vec3d.ZERO;
		}
		return flat.normalize();
	}

	/**
	 * 计算击球点（拍面中心）的世界坐标。
	 *
	 * @param lookUnit 玩家视线单位向量
	 * @param outward  台面中心 → 玩家的水平单位向量（Vec3d.ZERO 表示附近没有球台）
	 * @param hand     当前手型
	 * @param eyePos   玩家眼睛位置
	 */
	public static Vec3d paddlePoint(Vec3d eyePos, Vec3d lookUnit, Vec3d outward, PlayerHand hand) {
		return paddlePoint(eyePos, lookUnit, outward, hand, 0.0);
	}

	/**
	 * 计算击球点（拍面中心）的世界坐标，可带**引拍进度**（需求 12）。
	 *
	 * <h3>为什么击球点会随引拍变化</h3>
	 * 现实里引拍不是"把手往后一收"这么简单：正手引拍时，前臂以**肘关节**为圆心向后下方画圆，
	 * 于是击球点同时**后移、下沉、并绕着肘划弧**；反手引拍幅度小得多，而且更偏"贴身往后带"。
	 * 这条直接决定"你引拍到什么程度，球拍就出现在哪里"，所以必须参与命中判定，
	 * 否则视觉上拍子在身后、判定却还在身前，玩家会觉得"明明够到了却没打到"。
	 *
	 * <pre>
	 * 正手：以肘为圆心、半径 0.45 格，圆弧 0° → 70°，整体后移 0.25·p、下沉 0.18·p
	 * 反手：半径 0.28 格，圆弧 0° → 40°，后移 0.12·p、下沉 0.10·p
	 * </pre>
	 *
	 * @param windUp 引拍进度 0~1（= 蓄力比例；0 表示没引拍，击球点在准备位置）
	 */
	public static Vec3d paddlePoint(Vec3d eyePos, Vec3d lookUnit, Vec3d outward, PlayerHand hand, double windUp) {
		return paddlePoint(eyePos, lookUnit, outward, hand, windUp, false);
	}

	/**
	 * 完整版：带引拍圆弧（需求 12）与台内伸手（需求 19）。
	 *
	 * @param inTable 是否处于台内（由 {@link #inTable(World, Vec3d)} 判定后传入）
	 */
	public static Vec3d paddlePoint(Vec3d eyePos, Vec3d lookUnit, Vec3d outward, PlayerHand hand, double windUp,
									boolean inTable) {
		// 侧向：水平垂直于「我 → 台子中心」方向的那根轴。
		// radial 指向玩家（背离球台），取 radial 与竖直轴的叉积得到玩家的右手方向。
		Vec3d side = outward.lengthSquared() < 1.0e-8
				? rightOfLook(lookUnit)
				: new Vec3d(0.0, 1.0, 0.0).crossProduct(outward).normalize();

		double sideSign = hand == PlayerHand.FOREHAND ? 1.0 : -1.0;
		// 没有球台时 radial 为零，退化成「拍面在视线前方」，至少不朝身后挥
		Vec3d forward = outward.lengthSquared() < 1.0e-8 ? horizontalLook(lookUnit) : outward.multiply(-1.0);

		double p = Math.max(0.0, Math.min(1.0, windUp));
		double backOut;
		double drop;
		double sideOut = SIDE_OUT * sideSign;
		if (hand == PlayerHand.FOREHAND) {
			// 以肘为圆心的圆弧：角度越大，拍子越靠后越靠下；同时肘本身也往后带一点
			double angle = Math.toRadians(70.0 * p);
			backOut = ELBOW_BACK * p + FOREHAND_ARC_RADIUS * Math.sin(angle);
			drop = PADDLE_HEIGHT - FOREHAND_ARC_RADIUS * (1.0 - Math.cos(angle)) * 0.6;
			sideOut += FOREHAND_ARC_RADIUS * Math.sin(angle) * 0.35 * sideSign;  // 绕肘的侧向摆动（让动作看得出是画圈）
		} else {
			// 反手：贴身往后带，幅度小很多
			double angle = Math.toRadians(40.0 * p);
			backOut = BACKHAND_BACK * p + BACKHAND_ARC_RADIUS * Math.sin(angle);
			drop = PADDLE_HEIGHT - BACKHAND_ARC_RADIUS * (1.0 - Math.cos(angle)) * 0.5;
			sideOut -= BACKHAND_ARC_RADIUS * Math.sin(angle) * 0.15 * sideSign;
		}

		// 【需求 19】台内：眼到台缘很近时，手臂要往台内多伸一点。
		double reach = inTable ? IN_TABLE_REACH : 0.0;

		return eyePos
				.add(forward.multiply(FORWARD - backOut + reach))
				.add(side.multiply(sideOut))
				.add(0.0, drop, 0.0);
	}

	/** 台内伸手最大量（格）：需求 19「手臂也要向球的方向伸」 */
	public static final double IN_TABLE_REACH = 0.25;

	/**
	 * 是否处于「台内」位置（需求 19）：玩家离最近台缘的水平距离小于 {@link #IN_TABLE_DISTANCE}。
	 *
	 * 台内搓球时身体要往台内前倾、手臂也要向球伸出去 —— 渲染层按这个标志改姿态，
	 * 命中判定也据此把击球点往前送一点。
	 */
	public static boolean inTable(World world, Vec3d playerPos) {
		BlockPos anchor = findTable(world, playerPos);
		if (anchor == null) {
			return false;
		}
		Vec3d center = centerOf(anchor);
		// 台子是 3 格长 × 2 格宽（半长边 1.5、半短边 1.0），取玩家到台面外接矩形的水平距离
		double dx = Math.abs(playerPos.x - center.x);
		double dz = Math.abs(playerPos.z - center.z);
		// 到台缘的近似距离：考虑两个轴向，取"超出多少"的欧氏长度
		double outsideX = Math.max(0.0, dx - TABLE_HALF_ALONG);
		double outsideZ = Math.max(0.0, dz - TABLE_HALF_ACROSS);
		double outside = Math.sqrt(outsideX * outsideX + outsideZ * outsideZ);
		return outside < IN_TABLE_DISTANCE;
	}

	/**
	 * 出球方向：
	 * - 有球台：主要朝球台对面的那一侧（-outward），再按视线做修正 ——
	 *   这样「跟球视角」下即便视线锁在球上，打出去的球依然是朝对面半台的；
	 * - 没球台：退回原来的「按视线」，保持自由练习时的手感。
	 */
	public static Vec3d hitDirection(Vec3d lookUnit, Vec3d outward) {
		if (outward.lengthSquared() < 1.0e-8) {
			return lookUnit.normalize();
		}
		Vec3d toward = outward.multiply(-1.0);
		Vec3d horizontalLook = horizontalLook(lookUnit);
		// 视线本来就在球的水平方向上，混一点保证「指向哪儿打到哪儿」的手感
		Vec3d mixed = horizontalLook.lengthSquared() < 1.0e-8
				? toward
				: toward.multiply(0.60).add(horizontalLook.multiply(0.40));
		return mixed.normalize();
	}

	/** 水平朝向球台中心的单位向量；正好在台子正上方时返回 ZERO。 */
	public static Vec3d towardTable(World world, Vec3d playerPos) {
		return outward(world, playerPos).multiply(-1.0);
	}

	private static Vec3d horizontalLook(Vec3d look) {
		Vec3d flat = new Vec3d(look.x, 0.0, look.z);
		return flat.lengthSquared() < 1.0e-6 ? Vec3d.ZERO : flat.normalize();
	}

	private static Vec3d rightOfLook(Vec3d look) {
		Vec3d flat = horizontalLook(look);
		if (flat == Vec3d.ZERO) {
			return new Vec3d(1.0, 0.0, 0.0);
		}
		// 右方向 = 前向 × 上方向（右手系）
		return flat.crossProduct(new Vec3d(0.0, 1.0, 0.0)).normalize();
	}
}

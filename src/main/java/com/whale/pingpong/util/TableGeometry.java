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
		// 侧向：水平垂直于「我 → 台子中心」方向的那根轴。
		// radial 指向玩家（背离球台），取 radial 与竖直轴的叉积得到玩家的右手方向。
		Vec3d side = outward.lengthSquared() < 1.0e-8
				? rightOfLook(lookUnit)
				: new Vec3d(0.0, 1.0, 0.0).crossProduct(outward).normalize();

		double sideSign = hand == PlayerHand.FOREHAND ? 1.0 : -1.0;
		// 没有球台时 radial 为零，退化成「拍面在视线前方」，至少不朝身后挥
		Vec3d forward = outward.lengthSquared() < 1.0e-8 ? horizontalLook(lookUnit) : outward.multiply(-1.0);

		return eyePos
				.add(forward.multiply(FORWARD))
				.add(side.multiply(SIDE_OUT * sideSign))
				.add(0.0, PADDLE_HEIGHT, 0.0);
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

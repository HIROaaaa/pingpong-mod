package com.whale.pingpong.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * 乒乓球台 —— <b>一个方块就是一整张球台</b>（用户要求「一个大的方块一次性做好」）。
 *
 * 做法：方块的模型与碰撞箱都刻意超出自身 1×1×1 的体积，直接覆盖 3 格长 × 2 格宽 × 0.75 格高，
 * 中间那条 0.15 格高的薄片就是球网。放下去一次成型，拆掉也一次拆完。
 * 锚点方块位于球台正中（长边 ±1 格、宽边 ±0.5 格）。
 *
 * 【为什么几何体是 -16 ~ +32 而不是 0 ~ 48】
 * 原版模型格式对 elements 的坐标有硬限制：只允许 [-16, 32]（即最多向外延伸 2 格）。
 * 一开始写成 0~48，客户端直接报
 * {@code JsonParseException: 'to' specifier exceeds the allowed boundaries}，模型加载失败。
 * 居中成 -16~32 正好是 3 格，一个方块照样能画下整张台子。
 *
 * 两个朝向变体（along_x）而不是四个 facing，是为了让「模型长边」和「碰撞箱长边」严格对齐 ——
 * 靠 blockstate 的 y 旋转去对齐两者很容易差 90°，这样写不会错。
 *
 * 单位：模型与形状都用 1/16 格（3 格 = 48，0.75 格 = 12，2 格 = 32，0.9 格 = 14.4）。
 */
public class PingPongTableBlock extends Block {

	/** 长边方向两侧各延伸几个方块（合计 3 格长） */
	public static final int LENGTH_ARM = 1;
	/** 台面高度（格）：真实球台 76cm */
	public static final double TOP_Y = 0.75;
	/** 球网高出台面多少（格）：真实 15.25cm，这里取 15cm */
	public static final double NET_HEIGHT = 0.15;

	/** true = 长边沿 X 轴铺开 */
	public static final BooleanProperty ALONG_X = BooleanProperty.of("along_x");

	/** 长边沿 X 的碰撞/轮廓形状（含网） */
	private static final VoxelShape SHAPE_X = VoxelShapes.union(
			// 台面：实心盒 0~0.75，玩家可以站上去
			Block.createCuboidShape(-16.0, 0.0, -8.0, 32.0, 12.0, 24.0),
			// 球网：台面正中一片薄板，y 12~14.4（即 0.75~0.90）
			Block.createCuboidShape(7.4, 12.0, -8.0, 8.6, 14.4, 24.0));

	/** 长边沿 Z 的碰撞/轮廓形状（含网） */
	private static final VoxelShape SHAPE_Z = VoxelShapes.union(
			Block.createCuboidShape(-8.0, 0.0, -16.0, 24.0, 12.0, 32.0),
			Block.createCuboidShape(-8.0, 12.0, 7.4, 24.0, 14.4, 8.6));

	public PingPongTableBlock(Settings settings) {
		super(settings);
		this.setDefaultState(this.stateManager.getDefaultState().with(ALONG_X, true));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(ALONG_X);
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return state.get(ALONG_X) ? SHAPE_X : SHAPE_Z;
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return state.get(ALONG_X) ? SHAPE_X : SHAPE_Z;
	}

	/**
	 * 放置：长边沿着玩家面朝的方向铺开（人站球台一端打得最舒服）。
	 * 顺便校验球台占的 3×3 格都是空的，占不下就返回 null（放置失败），免得球台一半插进墙里。
	 */
	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		Direction facing = ctx.getHorizontalPlayerFacing();
		boolean alongX = facing.getAxis() == Direction.Axis.X;
		BlockPos origin = ctx.getBlockPos();

		for (int dx = -LENGTH_ARM; dx <= LENGTH_ARM; dx++) {
			for (int dz = -LENGTH_ARM; dz <= LENGTH_ARM; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				BlockPos pos = origin.add(dx, 0, dz);
				if (!ctx.getWorld().getBlockState(pos).isReplaceable()) {
					return null; // 空间不够
				}
			}
		}

		return this.getDefaultState().with(ALONG_X, alongX);
	}
}

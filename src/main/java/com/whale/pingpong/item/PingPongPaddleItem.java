package com.whale.pingpong.item;

import com.whale.pingpong.entity.PingPongBallEntity;
import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.TableGeometry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * 乒乓球拍。
 *
 * 操作：
 * - 左键：挥拍击球（击球力度由按住时长决定，客户端打包发给服务端判定，见 ModNetworking），不改变玩家视角
 * - C 键：切换正手 / 反手（在选项→控制→按键绑定里可改键）
 * - V 键：切换跟球视角
 * - 潜行 + 右键：回收场上的乒乓球，方便重新开始
 *
 * 【注意】球拍<b>只负责击球</b>，右键不会生成或打出乒乓球。
 * 球由「乒乓球」物品单独上抛（见 {@link PingPongBallItem}），玩家自己调拍形去击球 ——
 * 这是用户明确要求的交互，不要在这里加「便利」的自动发球。
 */
public class PingPongPaddleItem extends Item {

	/** 潜行右键回收球的半径（格） */
	public static final double CLEAR_RADIUS = 16.0;

	public PingPongPaddleItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);

		// 普通右键：什么都不做（球拍不产生球）
		if (!user.isSneaking()) {
			return TypedActionResult.pass(stack);
		}

		// 潜行右键：回收附近的球
		if (!world.isClient) {
			int removed = clearNearbyBalls(world, user);
			if (removed > 0 && user instanceof ServerPlayerEntity serverPlayer) {
				serverPlayer.sendMessage(
						Text.translatable("message.pingpong.cleared", removed).formatted(Formatting.GRAY), true);
			}
		}
		return TypedActionResult.success(stack);
	}

	/** 右键方块时不要触发放置逻辑。 */
	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		return ActionResult.PASS;
	}

	private static int clearNearbyBalls(World world, PlayerEntity user) {
		Box area = user.getBoundingBox().expand(CLEAR_RADIUS);
		List<PingPongBallEntity> balls = world.getEntitiesByClass(PingPongBallEntity.class, area, ball -> true);
		for (PingPongBallEntity ball : balls) {
			ball.discard();
		}
		return balls.size();
	}

	/** 供客户端 HUD / 挥拍判定判断「手里是不是球拍」。 */
	public static boolean isHoldingPaddle(PlayerEntity player) {
		return player != null && player.getMainHandStack().getItem() instanceof PingPongPaddleItem;
	}

	/**
	 * 球拍的击球点（拍面中心）。
	 *
	 * 【为什么不再是「眼睛前方 1.1 格」】
	 * 用户要求 4/6：击球点要分正反手、并且<b>不跟随视角</b>，而是由「球台朝向 + 玩家站在哪一边」决定 ——
	 * 两个人站在球台两端时，击球点必须各在一侧，否则会挤在同一片空气里挥拍。
	 * 具体几何在 {@link TableGeometry}。
	 */
	public static Vec3d paddlePoint(PlayerEntity player, PlayerHand hand) {
		Vec3d look = player.getRotationVec(1.0F);
		Vec3d outward = TableGeometry.outward(player.getWorld(), player.getPos());
		return TableGeometry.paddlePoint(player.getEyePos(), look, outward, hand);
	}
}

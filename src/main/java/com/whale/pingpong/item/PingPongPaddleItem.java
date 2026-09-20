package com.whale.pingpong.item;

import com.whale.pingpong.entity.PingPongBallEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
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
 * - 左键：挥拍击球（客户端打包发给服务端判定，见 ModNetworking），不改变玩家视角
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

	/** 球拍正前方的击球点（眼睛前方 1.1 格），服务端命中判定用它当圆心。 */
	public static Vec3d paddlePoint(PlayerEntity player) {
		return player.getEyePos().add(player.getRotationVec(1.0F).multiply(1.1));
	}
}

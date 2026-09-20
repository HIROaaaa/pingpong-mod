package com.whale.pingpong.item;

import com.whale.pingpong.entity.PingPongBallEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
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
 * - 左键：挥拍击球（客户端打包发给服务端判定，见 ModNetworking）
 * - 右键：发球（在身前生成一颗低速球）
 * - 潜行 + 右键：清掉附近的球，方便重新开始
 *
 * 注意：挥拍【不会】改变玩家视角，服务端也从不调用 player.lookAt / setYaw，
 * 只做击球判定 + 挥臂动画，所以视角始终由玩家自己控制。
 */
public class PingPongPaddleItem extends Item {

	/** 发球冷却（tick） */
	public static final int SERVE_COOLDOWN = 8;
	/** 清球半径（格） */
	public static final double CLEAR_RADIUS = 16.0;

	public PingPongPaddleItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);

		if (world.isClient) {
			// 客户端只负责摆手；真正生成实体在服务端做
			user.swingHand(hand);
			return TypedActionResult.success(stack);
		}

		if (user.isSneaking()) {
			int removed = clearNearbyBalls(world, user);
			if (removed > 0 && user instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
				serverPlayer.sendMessage(Text.translatable("message.pingpong.cleared", removed).formatted(Formatting.GRAY), true);
			}
		} else {
			// 发球：球从身前轻轻抛出去，玩家再用左键挥拍开打
			PingPongBallEntity.spawn(world, user, 0.45, 0.12);
			world.playSound(null, user.getX(), user.getY(), user.getZ(),
					SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.7F, 1.4F);
		}

		user.getItemCooldownManager().set(this, SERVE_COOLDOWN);
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

	/** 供客户端 HUD 判断「手里是不是球拍」。 */
	public static boolean isHoldingPaddle(PlayerEntity player) {
		return player != null && player.getMainHandStack().getItem() instanceof PingPongPaddleItem;
	}

	/** 未被使用，留作调试：球拍正前方的击球点。 */
	public static Vec3d paddlePoint(PlayerEntity player) {
		return player.getEyePos().add(player.getRotationVec(1.0F).multiply(1.1));
	}
}

package com.whale.pingpong.item;

import com.whale.pingpong.entity.PingPongBallEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * 乒乓球物品：右键把球【垂直上抛】，等它落下来时自己调拍形用球拍去打 ——
 * 和现实里打乒乓球的发球一样。
 *
 * 刻意不沿视线扔：扔出去玩家没时间调拍形，也没法自己「接」。
 */
public class PingPongBallItem extends Item {

	/** 上抛初速度（格/tick）。0.45 约上升 3.4 格、滞空 1.5 秒，够玩家调拍形 */
	public static final double TOSS_SPEED = 0.45;
	/** 水平飘移比例：只带一点点视线方向的分量，免得球正好落回自己头上 */
	public static final double TOSS_DRIFT = 0.06;

	public PingPongBallItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);

		if (!world.isClient) {
			PingPongBallEntity.toss(world, user, TOSS_SPEED, TOSS_DRIFT);
			world.playSound(null, user.getX(), user.getY(), user.getZ(),
					SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.6F, 1.2F);

			if (!user.getAbilities().creativeMode) {
				stack.decrement(1);
			}
			user.getItemCooldownManager().set(this, 4);
		}

		return TypedActionResult.success(stack);
	}
}

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

/** 乒乓球物品：右键投掷出一颗球（多人时用来互相发球）。 */
public class PingPongBallItem extends Item {

	public PingPongBallItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);

		if (!world.isClient) {
			PingPongBallEntity.spawn(world, user, 0.95, 0.05);
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

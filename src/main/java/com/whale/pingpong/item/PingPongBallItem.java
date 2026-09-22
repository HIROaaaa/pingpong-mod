package com.whale.pingpong.item;

import com.whale.pingpong.entity.PingPongBallEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * 乒乓球物品：右键【按住蓄力】，松手把球垂直上抛 —— 和现实里发球前的抛球一样。
 *
 * 用户要求 5：「拿着乒乓球抛球的时候也是按下右键的时间越长，抛球高度越高，但是也要有一个上限高度」。
 * 做法用的是原版物品使用计时（{@link #getMaxUseTime} + {@link #onStoppedUsing}），
 * 不需要任何额外的库，也不会误判「点一下就抛」。
 *
 * 刻意不沿视线扔：扔出去玩家没时间调拍形，也没法自己「接」。
 */
public class PingPongBallItem extends Item {

	/** 最短蓄力（tick）：点一下也有这么高 */
	private static final int MIN_CHARGE_TICKS = 3;
	/** 蓄满需要的 tick 数（1.1 秒） */
	private static final int FULL_CHARGE_TICKS = 22;

	/**
	 * 上抛初速度下限 / 上限（格/tick）。
	 *
	 * 【单位换算】峰值高度 = v²/(2g)，g=0.030：
	 * 旧值 0.34 → 1.93 格、0.74 → 9.13 格（用户原话「抛球的默认高度也太高了」，
	 * 9 格等于抛到树顶上，而球台才 0.76 格高）。
	 * 现在 0.20 → 0.67 格、0.32 → 1.71 格：球从头顶飞起一两格再落回手边，
	 * 滞空 13~21 tick（0.65~1.05 秒），够玩家翻腕调拍形。
	 */
	public static final double MIN_TOSS_SPEED = 0.20;
	public static final double MAX_TOSS_SPEED = 0.32;

	public PingPongBallItem(Settings settings) {
		super(settings);
	}

	@Override
	public int getMaxUseTime(ItemStack stack) {
		// 上限给足，让玩家能一直举着球调整位置
		return 72000;
	}

	/**
	 * 举球时的动作（需求 15/16：「发球时拿着乒乓球抛球的动作也要做出来」）。
	 *
	 * 用原版 {@code UseAction.BOW}：手臂会做出"举到身前再放开"的姿势 ——
	 * 这正是抛球该有的样子，而且**不用写任何 mixin**（原版动画系统直接支持）。
	 * 松手时 {@link #onStoppedUsing} 把球抛出去，动作与球离手在同一刻结束。
	 */
	@Override
	public net.minecraft.util.UseAction getUseAction(ItemStack stack) {
		return net.minecraft.util.UseAction.BOW;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		// 开始蓄力：只举个球在手里，等松手（onStoppedUsing）才真正抛出去
		user.setCurrentHand(hand);
		return TypedActionResult.consume(stack);
	}

	@Override
	public void onStoppedUsing(ItemStack stack, World world, net.minecraft.entity.LivingEntity user, int remainingUseTicks) {
		if (!(user instanceof PlayerEntity)) {
			return;
		}
		// 【Java 8 兼容】显式强转代替 instanceof 模式匹配
		PlayerEntity player = (PlayerEntity) user;
		int usedTicks = this.getMaxUseTime(stack) - remainingUseTicks;
		double speed = tossSpeedFor(usedTicks);

		if (!world.isClient) {
			PingPongBallEntity.toss(world, player, speed);
			world.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS,
					0.45F + 0.25F * (float) chargeRatio(usedTicks),
					1.35F - 0.35F * (float) chargeRatio(usedTicks));

			// 【五期 M7.6：需求 24「使用不消耗」被用户撤销】
			// 原话：「副手发球后球要消失，乒乓球不要不消耗了，但是等球完全不动了之后要自动回到玩家身上」。
			// 所以恢复消耗 —— 一颗球用一次，但那颗球**会自己飞回物品栏**
			// （见 PingPongBallEntity 的飞回逻辑：静止 1 秒后出发，到达即还回球）。
			// 4 tick 冷却保留：否则狂点右键仍然会每 tick 生成一颗球。
			if (!player.getAbilities().creativeMode) {
				stack.decrement(1);
			}
			player.getItemCooldownManager().set(this, 4);
		}
	}

	/** 按住时长 → 上抛初速度。线性映射，两头都夹紧（需求 5 的「上限高度」）。 */
	public static double tossSpeedFor(int usedTicks) {
		return MathHelper.lerp(chargeRatio(usedTicks), MIN_TOSS_SPEED, MAX_TOSS_SPEED);
	}

	/** 蓄力进度 0~1，HUD 用它画进度条。 */
	public static double chargeRatio(int usedTicks) {
		double ratio = (double) (usedTicks - MIN_CHARGE_TICKS) / (FULL_CHARGE_TICKS - MIN_CHARGE_TICKS);
		return MathHelper.clamp(ratio, 0.0, 1.0);
	}
}

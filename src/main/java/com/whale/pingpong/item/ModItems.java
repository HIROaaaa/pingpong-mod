package com.whale.pingpong.item;

import com.whale.pingpong.util.Registrar;
import net.minecraft.item.Item;

/** 物品注册表。 */
public final class ModItems {

	/** 乒乓球拍：主手持有，左键挥拍击球，右键搓/削。 */
	public static final Item PINGPONG_PADDLE = new PingPongPaddleItem(new Item.Settings().maxCount(1));

	/** 乒乓球（物品形态）：右键按住蓄力上抛，可以被球拍击打。使用不消耗（用户需求 24）。 */
	public static final Item PINGPONG_BALL = new PingPongBallItem(new Item.Settings().maxCount(16));

	private ModItems() {
	}

	public static void register() {
		// 注册走 Registrar（M5 版本适配层）：多版本时只需改那一处
		Registrar.item("pingpong_paddle", PINGPONG_PADDLE);
		Registrar.item("pingpong_ball", PINGPONG_BALL);

		// 【物品栏归属】二期是把这两件塞进原版「战斗」页签（图省事），
		// 用户要求 6 改成独立的「乒乓球」页签 —— 见 {@link ModItemGroups}。
		// 这里**不再**往 ItemGroups.COMBAT 里加，否则物品会在两个页签里重复出现。
	}
}

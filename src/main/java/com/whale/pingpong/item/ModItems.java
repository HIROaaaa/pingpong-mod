package com.whale.pingpong.item;

import com.whale.pingpong.PingPongMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/** 物品注册表。 */
public final class ModItems {

	/** 乒乓球拍：主手持有，左键挥拍击球。 */
	public static final Item PINGPONG_PADDLE = new PingPongPaddleItem(new Item.Settings().maxCount(1));

	/** 乒乓球（物品形态）：右键投掷，也可以被球拍发球直接生成。 */
	public static final Item PINGPONG_BALL = new PingPongBallItem(new Item.Settings().maxCount(16));

	private ModItems() {
	}

	public static void register() {
		Registry.register(Registries.ITEM, PingPongMod.id("pingpong_paddle"), PINGPONG_PADDLE);
		Registry.register(Registries.ITEM, PingPongMod.id("pingpong_ball"), PINGPONG_BALL);

		// 塞进原版「战斗」创造模式物品栏，省得再做一个物品栏贴图
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
			entries.add(PINGPONG_PADDLE);
			entries.add(PINGPONG_BALL);
		});
	}
}

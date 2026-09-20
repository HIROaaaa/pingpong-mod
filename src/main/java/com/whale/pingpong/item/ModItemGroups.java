package com.whale.pingpong.item;

import com.whale.pingpong.PingPongMod;
import com.whale.pingpong.block.ModBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;

/**
 * 独立的创造模式物品栏（用户要求 6：「把 mod 里的物品单独放到一个创造模式物品栏里」）。
 *
 * 【为什么不用 ItemGroupEvents 往原版「战斗」里塞】二期就是这么干的，结果是物品散在原版页签里、
 * 找起来费劲。这里注册一个自己的页签，图标用球拍（最直观）。
 *
 * 【1.20.1 的 API 事实】用 {@code FabricItemGroup.builder()}（Fabric API 的
 * {@code fabric-item-group-api-v1} 提供的 {@code FabricItemGroup}），
 * 它在 {@code net.fabricmc.fabric.api.itemgroup.v1} 包里。
 * 注意别和原版 1.19.3+ 的 {@code net.minecraft.item.ItemGroups} 混了 —— 那是原版页签的常量表。
 */
public final class ModItemGroups {

	/** 页签本体。所有物品按「球拍 → 球 → 球台」的顺序摆放。 */
	public static final ItemGroup PINGPONG = Registry.register(
			Registries.ITEM_GROUP,
			PingPongMod.id("pingpong"),
			FabricItemGroup.builder()
					.icon(() -> new ItemStack(ModItems.PINGPONG_PADDLE))
					.displayName(Text.translatable("itemGroup.pingpong.pingpong"))
					.entries((context, entries) -> {
						entries.add(ModItems.PINGPONG_PADDLE);
						entries.add(ModItems.PINGPONG_BALL);
						entries.add(ModBlocks.PINGPONG_TABLE.asItem());
					})
					.build()
	);

	private ModItemGroups() {
	}

	/** 触发静态初始化（注册发生在字段初始化时）。必须在物品注册之后调用。 */
	public static void register() {
	}
}

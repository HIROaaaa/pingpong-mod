package com.whale.pingpong.block;

import com.whale.pingpong.PingPongMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/** 方块注册表。 */
public final class ModBlocks {

	/** 乒乓球台：一个方块就是一整张球台（模型与碰撞箱都跨 3×2 格）。 */
	public static final Block PINGPONG_TABLE = new PingPongTableBlock(
			AbstractBlock.Settings.create()
					.strength(1.0F)
					.sounds(BlockSoundGroup.WOOD)
					.nonOpaque());

	public static final Item PINGPONG_TABLE_ITEM = new BlockItem(PINGPONG_TABLE, new Item.Settings());

	private ModBlocks() {
	}

	public static void register() {
		Registry.register(Registries.BLOCK, PingPongMod.id("pingpong_table"), PINGPONG_TABLE);
		Registry.register(Registries.ITEM, PingPongMod.id("pingpong_table"), PINGPONG_TABLE_ITEM);

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(PINGPONG_TABLE_ITEM));
	}
}

package com.whale.pingpong.block;

import com.whale.pingpong.util.Registrar;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
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
		// 注册走 Registrar（M5 版本适配层）
		Registrar.block("pingpong_table", PINGPONG_TABLE);
		Registrar.item("pingpong_table", PINGPONG_TABLE_ITEM);

		// 【物品栏】原来往原版「功能方块」页签塞一份，现在球台只出现在自建的「乒乓球」页签里
		// （见 item/ModItemGroups），避免同一个物品在两处重复。
		// 注意：方块本身仍可用 /give 或配方获得，页签归属只影响创造模式物品栏。
	}
}

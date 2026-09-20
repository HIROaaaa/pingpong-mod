package com.whale.pingpong.util;

import com.whale.pingpong.PingPongMod;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 注册适配层（M5 多版本的第一步）。
 *
 * <h2>为什么单独抽一层</h2>
 * 多版本移植真正痛苦的从来不是物理或玩法代码，而是这些「同一个动作、每个版本写法不同」的地方。
 * 它们的差异集中在几行里，但散落在 20 多个文件，于是每次加版本都要满仓库改一遍。
 * 把这些动作收进一个类，业务代码只依赖本类的方法名，将来加版本只改这里。
 *
 * <h2>本类覆盖的差异（1.20.1 vs 1.16.5）</h2>
 * <table>
 *   <tr><th>动作</th><th>1.20.1（现在）</th><th>1.16.5</th></tr>
 *   <tr><td>注册表入口</td><td>{@code net.minecraft.registry.Registries.ITEM}</td>
 *       <td>{@code net.minecraft.util.registry.Registry.ITEM}</td></tr>
 *   <tr><td>Identifier 构造</td><td>{@code new Identifier(ns, path)}</td>
 *       <td>同（1.16 起就有双参构造，无需适配）</td></tr>
 * </table>
 *
 * 其余差异（创造页签 API、玩家 NBT、{@code getEyePos}、相机/手持物 Mixin 签名、
 * 网络注册的多参数差异）都有明确的替代路径，见 {@code docs/plan/v1.3-plan.md} §7 的对照表，
 * 但它们各自需要一个独立的版本分支，留到真正开 1.16.5 目标时按表逐个处理。
 */
public final class Registrar {

	private Registrar() {
	}

	/** 注册一个物品。 */
	public static Item item(String path, Item value) {
		return Registry.register(Registries.ITEM, PingPongMod.id(path), value);
	}

	/** 注册一个方块。 */
	public static Block block(String path, Block value) {
		return Registry.register(Registries.BLOCK, PingPongMod.id(path), value);
	}

	/** 注册一个实体类型。 */
	public static <T extends net.minecraft.entity.EntityType<?>>
	T entityType(String path, T value) {
		return Registry.register(Registries.ENTITY_TYPE, PingPongMod.id(path), value);
	}

	/** 注册一个物品栏（1.16.5 走 FabricItemGroupBuilder，1.20.1 走 FabricItemGroup.builder）。 */
	public static net.minecraft.item.ItemGroup itemGroup(String path, net.minecraft.item.ItemGroup value) {
		return Registry.register(Registries.ITEM_GROUP, PingPongMod.id(path), value);
	}

	/** 直接取 Id，方便业务代码不用 import Identifier。 */
	public static Identifier id(String path) {
		return PingPongMod.id(path);
	}
}

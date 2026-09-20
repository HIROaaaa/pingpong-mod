package com.whale.pingpong.entity;

import com.whale.pingpong.PingPongMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/** 实体类型注册。 */
public final class ModEntities {

	/**
	 * 乒乓球实体。
	 * - trackRangeBlocks(128)：128 格内可见
	 * - trackedUpdateRate(1)：每 tick 同步一次位置（球很快，慢了会瞬移）
	 */
	public static final EntityType<PingPongBallEntity> PINGPONG_BALL = Registry.register(
			Registries.ENTITY_TYPE,
			PingPongMod.id("pingpong_ball"),
			FabricEntityTypeBuilder.<PingPongBallEntity>create(SpawnGroup.MISC, PingPongBallEntity::new)
					.dimensions(EntityDimensions.fixed(0.28F, 0.28F))
					.trackRangeBlocks(128)
					.trackedUpdateRate(1)
					.build()
	);

	private ModEntities() {
	}

	/** 触发静态字段初始化（注册发生在这里）。 */
	public static void register() {
	}
}

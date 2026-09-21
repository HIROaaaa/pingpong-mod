package com.whale.pingpong.mixin;

import net.minecraft.client.model.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

/**
 * 打开 {@link ModelPart} 的私有结构，供 {@code client/ForearmPart} 做几何切割用。
 *
 * <h2>为什么必须用 Accessor 而不是反射</h2>
 * 之前用 `Class.forName("net.minecraft.client.model.ModelPart$Cuboid")` 这种**字符串反射**，
 * 开发环境（Yarn 名）能跑，但**生产环境的 jar 里是 intermediary 名**（`class_630$class_628`）
 * —— 用户实测报 `ClassNotFoundException: …ModelPart$Cuboid`，切割从来没执行过。
 * 这是一个只在"装进游戏"时才暴露的错，开发环境怎么测都测不出来。
 *
 * <p>Mixin 生成的方法名会在 remap 阶段一起转换，所以用 Accessor 就天然带着正确的映射，
 * 无论 Yarn 还是 intermediary 都能命中。
 */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {

	/** {@code ModelPart.cuboids} —— 部件里的方块几何列表 */
	@Accessor("cuboids")
	List<ModelPart.Cuboid> pingpong$getCuboids();

	/** {@code ModelPart.cuboids} 的 setter：切割后用它换掉整份几何 */
	@Accessor("cuboids")
	void pingpong$setCuboids(List<ModelPart.Cuboid> cuboids);

	/** {@code ModelPart.children} —— 子部件表（把"小臂"挂进去） */
	@Accessor("children")
	Map<String, ModelPart> pingpong$getChildren();
}

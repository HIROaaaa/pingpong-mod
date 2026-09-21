package com.whale.pingpong.mixin;

import net.minecraft.client.model.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

/**
 * 打开 {@link ModelPart} 的私有结构，供 {@code client/ForearmPart} 做几何切割用。
 *
 * <h2>三轮踩坑记录（每一轮都只在真实游戏里才暴露）</h2>
 * <ol>
 *   <li><b>类名字符串反射 → ClassNotFoundException</b>：开发环境是 Yarn 名
 *       （`net.minecraft.client.model.ModelPart$Cuboid`），而生产 jar 里是 intermediary 名
 *       （`class_630$class_628`）。改用**类引用**（`ModelPart.Cuboid.class`）与 Accessor 解决。</li>
 *   <li><b>直接写 final 字段 → IllegalAccessError</b>：`cuboids` 是 final，
 *       Mixin 生成的普通 setter 会被 JVM 拒绝（"Update to non-static final field …
 *       attempted from a different method than the initializer &lt;init&gt;"）。</li>
 *   <li><b>改列表内容 → UnsupportedOperationException</b>：原版这个列表是
 *       `Collections.unmodifiableList(...)`，`clear()` / `add()` 都不允许。</li>
 * </ol>
 *
 * <h2>最终解法</h2>
 * 给 setter 加 **`@Mutable`**：Mixin 会生成一个"去掉 final 限制"的字段写入，
 * 这样就能整体替换 cuboids 列表（新列表用 `new ArrayList<>` 建，完全可写）。
 * 这也是 Mixin 处理 final 字段的标准做法。
 */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {

	/** {@code ModelPart.cuboids} —— 部件里的方块几何列表 */
	@Accessor("cuboids")
	List<ModelPart.Cuboid> pingpong$getCuboids();

	/**
	 * 替换几何列表。
	 *
	 * <p>{@code @Mutable} 是必需的：字段本身是 final，且原列表是不可变实现，
	 * 所以既不能直接赋值、也不能改内容 —— 只能靠 Mixin 的 mutable accessor 整体换掉。
	 */
	@Mutable
	@Accessor("cuboids")
	void pingpong$setCuboids(List<ModelPart.Cuboid> cuboids);

	/** {@code ModelPart.children} —— 子部件表（把切出来的"小臂"挂进去） */
	@Accessor("children")
	Map<String, ModelPart> pingpong$getChildren();
}

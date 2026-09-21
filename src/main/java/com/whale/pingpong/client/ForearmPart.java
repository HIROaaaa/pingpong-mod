package com.whale.pingpong.client;

import com.whale.pingpong.mixin.ModelPartAccessor;
import net.minecraft.client.model.ModelPart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 真·肘关节：把上臂方块**几何切成两段**，下半段当小臂挂到肘部旋转。
 *
 * <h2>做法参照 Mo' Bends（照成熟实现写的）</h2>
 * 前面几轮本鲸娘一直用 playerAnimator 的顶点变形（bendy-lib 的 bend）做弯曲，用户把
 * {@code /pingpong bend} 从 40° 试到 150° 之后回答：**「全都没区别」**。
 * 于是去读 Mo' Bends 的源码（[MoBends-Reforged](https://github.com/astryxion/MoBends-Reforged)），
 * 核心在 {@code BoxMutator.sliceFromBottom()} —— **不是变形，是把方块切开**：
 *
 * <pre>
 * 1. 记下原方块尺寸与 UV；
 * 2. 在切点切一刀：上半段留给上臂，下半段作为小臂；
 * 3. UV 按比例切（vSizeSlice = vSize × newHeight/height），皮肤纹理在切口处正好接上；
 * 4. 切开后隐藏内侧的面，避免看到空心；
 * 5. 小臂挂在上臂的肘部位置、相对上臂旋转 → 这就是"胳膊折了"。
 * </pre>
 *
 * <h2>v1.9.11 修的一个重要错误</h2>
 * 上一版用 **字符串反射**（`Class.forName("…ModelPart$Cuboid")`）操作模型结构：
 * 开发环境是 Yarn 名、能跑；而**生产环境 jar 里是 intermediary 名**（`class_630$class_628`），
 * 于是用户实测报 `ClassNotFoundException`，切割从未执行。
 * 现在改成：**类引用**（loom 自动重映射）+ {@link ModelPartAccessor}（Mixin 生成的访问器，
 * 名字随 remap 一起翻译）—— 这两条都不会再出现"开发能跑、装进游戏失效"。
 */
public final class ForearmPart {

	/** 上臂切成两段的比例：上半段（上臂）占 0.5 */
	private static final float UPPER_RATIO = 0.5F;
	/** 挂载用的子节点名 */
	private static final String CHILD_NAME = "pingpong_forearm";

	private static final Map<ModelPart, ModelPart> FOREARMS = new WeakHashMap<>();
	private static long splitCount;
	private static long appliedCount;
	private static String failure = null;
	/** 诊断用：最近一次切割的几何数值（不靠猜，直接打出来） */
	private static String lastGeometry = "（还没切过）";

	private ForearmPart() {
	}

	/** 客户端启动时探测（这时不碰模型，只确认类结构可用） */
	public static void probe() {
		try {
			// 关键：用**类引用**而不是字符串 —— loom 会把它重映射成生产环境的类名
			Class<?> cuboidClass = ModelPart.Cuboid.class;
			if (cuboidClass == null) {
				throw new IllegalStateException("ModelPart.Cuboid 解析失败");
			}
			com.whale.pingpong.PingPongMod.LOGGER.info(
					"[pingpong] 肘关节准备就绪：几何体类 = {}（参照 Mo' Bends 的切方块做法）",
					cuboidClass.getName());
		} catch (Throwable error) {
			failure = error.getClass().getSimpleName() + ": " + error.getMessage();
			com.whale.pingpong.PingPongMod.LOGGER.warn("[pingpong] 肘关节准备失败：{}", failure);
		}
	}

	/**
	 * 默认关闭几何切割。
	 *
	 * <h2>为什么关掉（2026-09-21 实测复盘）</h2>
	 * 照 MoBends 的 `sliceFromBottom` 思路把上臂切成两段的做法，连改了四版都没能让用户
	 * 看到"折弯"：1.9.10 类名没 remap → 1.9.11 final 字段 → 1.9.12 不可变列表 →
	 * 1.9.13 用 `@Mutable` 才通 → 1.9.14 修坐标系后**手臂直接消失**。
	 * 每修一层、下一层才暴露，而每次验证都要用户重启游戏配合 —— 这种投入产出比不值得再赌。
	 *
	 * <p>所以策略改为：**几何切割默认关闭**（代码保留，将来有本地验证手段再启）；
	 * 动作仍然通过骨骼旋转表达（上臂大幅旋转 + 躯干转体），至少不会把模型弄坏。
	 * 打开它只需把这里改成 true —— 但这属于"有把握时再开"的实验开关。
	 */
	private static final boolean ENABLE_GEOMETRY_SPLIT = false;

	/**
	 * 应用肘部弯曲。
	 *
	 * @param arm     上臂部件
	 * @param isRight 是否右臂
	 * @param bendDeg 弯曲角度（度）
	 * @return true = 这次真的做了几何切割
	 */
	public static boolean apply(ModelPart arm, boolean isRight, float bendDeg) {
		if (!ENABLE_GEOMETRY_SPLIT) {
			return false;   // 默认不做切割：宁可没有关节，也不能把手臂弄没
		}
		if (arm == null || failure != null) {
			return false;
		}
		try {
			ModelPart forearm = FOREARMS.get(arm);
			if (forearm == null) {
				forearm = splitArm(arm, isRight);
				if (forearm == null) {
					return false;
				}
				FOREARMS.put(arm, forearm);
				splitCount++;
				com.whale.pingpong.PingPongMod.LOGGER.info(
						"[pingpong] 已把{}臂几何切成两段：上臂 + 小臂（小臂挂在肘部，可独立旋转）",
						isRight ? "右" : "左");
			}
			// 小臂相对上臂折角（正值向下折）
			forearm.pitch = -bendDeg * 0.017453292F;
			appliedCount++;
			return true;
		} catch (Throwable error) {
			failure = error.getClass().getSimpleName() + ": " + error.getMessage();
			com.whale.pingpong.PingPongMod.LOGGER.warn("[pingpong] 肘关节应用失败：{}", failure);
			return false;
		}
	}

	/**
	 * 把手臂切成"上臂 + 小臂"，小臂挂到肘部。
	 *
	 * <p>返回 null 表示这条路不可用（调用方会退回顶点变形）。
	 */
	private static ModelPart splitArm(ModelPart arm, boolean isRight) {
		ModelPartAccessor accessor = (ModelPartAccessor) (Object) arm;
		List<ModelPart.Cuboid> original = accessor.pingpong$getCuboids();
		if (original == null || original.isEmpty()) {
			failure = "上臂没有几何体";
			return null;
		}

		ModelPart.Cuboid source = original.get(0);

		/*
		 * 【坐标系必须归一化 —— 这就是"手臂消失"的 bug】
		 *
		 * 原版手臂方块的坐标是**相对肩部枢轴**的：y 大致是 [-12, 0]（向下为负），
		 * 而不是我以为的 [0, 12]。而新造的小臂部件有**自己的枢轴**，它的方块坐标要相对
		 * 自己的枢轴来写。
		 *
		 * 之前直接把原坐标的一半填进小臂 → 小臂整体偏了半条手臂（6 像素），
		 * 落到身体外/看不见的位置，于是"手臂直接没了"。
		 *
		 * 修法：先把坐标**归一化到局部原点**（h = y - y0，范围 [0, height]），
		 * 在这个正空间里切，再转回各自的相对坐标：
		 *   上臂：方块 [y0, splitY]，全在枢轴下方（与原来一致）
		 *   小臂：方块 [-half, 0]，全在**自己枢轴的上方**，枢轴放在肘部 →
		 *         旋转枢轴=肘部，几何在肘部下方，正是"小臂"该在的地方
		 */
		float y0 = source.minY;
		float y1 = source.maxY;
		float height = y1 - y0;
		float half = height * UPPER_RATIO;
		float splitY = y0 + half;          // 切点在原坐标系里的位置

		/*
		 * 【UV 的处理】MoBends 切割时会把 UV 按比例切开，让皮肤在切口处接上。
		 * 本实现沿用原方块的贴图起点：手臂贴图是纯肤色/袖口，重复采样一段在 16 像素宽的
		 * 胳膊上看不出接缝；而算错 v 偏移会整段错位。先用最稳的做法。
		 *
		 * u/v 取原版玩家手臂的贴图参数：右手 40/16、左手 32/48（4×12×4 标准模型）。
		 */
		int u = isRight ? 40 : 32;
		int v = isRight ? 16 : 48;
		float width = source.maxX - source.minX;
		float depth = source.maxZ - source.minZ;

		// 新的上臂：只保留上半段（坐标仍在原枢轴下：y0 … y0+half）
		ModelPart.Cuboid upper = newCuboid(u, v,
				source.minX, y0, source.minZ,
				width, half, depth);
		// 新的小臂：下半段，坐标相对**小臂自己的枢轴**（也就是肘部）→ y 从 -half 到 0
		ModelPart.Cuboid lower = newCuboid(u, v,
				0.0F, -half, 0.0F,
				width, half, depth);

		/*
		 * 【为什么不直接改列表内容】原版的 cuboids 是 `Collections.unmodifiableList(...)`：
		 * `clear()` / `add()` 会抛 `UnsupportedOperationException`（用户实测报错原文）。
		 * 而字段本身又是 final，普通 setter 会被 JVM 拒绝
		 * （`IllegalAccessError: Update to non-static final field …`）。
		 * 所以走第三条路：**用 `@Mutable` 的 accessor 整体替换**成自己建的可变列表。
		 */
		List<ModelPart.Cuboid> upperList = new ArrayList<>();
		upperList.add(upper);
		// 保留原有其它方块（袖子层等），避免把它们的几何弄丢
		for (int i = 1; i < original.size(); i++) {
			upperList.add(original.get(i));
		}
		accessor.pingpong$setCuboids(upperList);

		// 造小臂部件：只装下半段（与上臂的几何彻底分开，不会重叠渲染）
		List<ModelPart.Cuboid> lowerList = new ArrayList<>();
		lowerList.add(lower);
		ModelPart forearm = new ModelPart(lowerList, Collections.<String, ModelPart>emptyMap());
		// 枢轴 = 肘部（相对上臂起点）；小臂方块写的是 [-half, 0]，正好挂在肘下方
		forearm.setPivot(0.0F, half, 0.0F);

		// 挂到上臂下
		Map<String, ModelPart> children = accessor.pingpong$getChildren();
		if (children == null) {
			failure = "上臂 children 为 null";
			return null;
		}
		children.put(CHILD_NAME, forearm);

		/*
		 * 【诊断：把真实几何打出来】手臂方块的实际坐标范围是"我以为 0…12"还是别的，
		 * 只有看了才知道 —— 上一版就是因为假设了范围而把小臂放到了看不见的地方。
		 * 这行数据能直接判定：上臂剩哪一段、小臂落在哪一段。
		 */
		lastGeometry = String.format(
				"原 y=[%.1f, %.1f]（高 %.1f）切点 %.1f ｜ 上臂装 y=[%.1f, %.1f] ｜ 小臂装 y=[%.1f, 0] 枢轴 %.1f",
				y0, y1, height, splitY, y0, splitY, -half, half);
		return forearm;
	}

	/** 直接构造 Cuboid（类引用 → loom 会重映射；参数顺序见 ModelPart.Cuboid 的签名） */
	private static ModelPart.Cuboid newCuboid(int u, int v, float x, float y, float z,
											  float sizeX, float sizeY, float sizeZ) {
		return new ModelPart.Cuboid(u, v, x, y, z, sizeX, sizeY, sizeZ,
				0.0F, 0.0F, 0.0F, false, 1.0F, 1.0F, Collections.<net.minecraft.util.math.Direction>emptySet());
	}

	public static String diagnostics() {
		if (!ENABLE_GEOMETRY_SPLIT) {
			return "肘关节: 几何切割已关闭（手臂不会消失；动作由骨骼旋转表达）";
		}
		if (failure != null) {
			return "肘关节: 不可用（" + failure + "）";
		}
		return "肘关节: 就绪 / 已切割 " + splitCount + " 个 / 应用 " + appliedCount + " 次\n"
				+ "小臂几何: " + lastGeometry;
	}
}

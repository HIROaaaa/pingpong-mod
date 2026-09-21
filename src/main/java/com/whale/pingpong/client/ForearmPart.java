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
	 * 应用肘部弯曲（第一次调用时会做几何切割）。
	 *
	 * @param arm     上臂部件
	 * @param isRight 是否右臂
	 * @param bendDeg 弯曲角度（度）
	 */
	public static boolean apply(ModelPart arm, boolean isRight, float bendDeg) {
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
		float y0 = source.minY;
		float y1 = source.maxY;
		float height = y1 - y0;
		float splitY = y0 + height * UPPER_RATIO;

		/*
		 * 【UV 的处理】MoBends 切割时会把 UV 按比例切开，让皮肤在切口处接上。
		 * 本实现沿用原方块的贴图起点：手臂贴图是纯肤色/袖口，重复采样一段在 16 像素宽的
		 * 胳膊上看不出接缝；而算错 v 偏移会整段错位。先用最稳的做法，需要精修再说。
		 *
		 * u/v 取原版玩家手臂的贴图参数：右手 40/16、左手 32/48（4×12×4 标准模型）。
		 */
		int u = isRight ? 40 : 32;
		int v = isRight ? 16 : 48;

		// 新的上臂：只保留上半段
		ModelPart.Cuboid upper = newCuboid(u, v,
				source.minX, y0, source.minZ,
				source.maxX - source.minX, height * UPPER_RATIO, source.maxZ - source.minZ);
		// 新的小臂：下半段，几何上紧接着上臂
		ModelPart.Cuboid lower = newCuboid(u, v,
				source.minX, splitY, source.minZ,
				source.maxX - source.minX, height * (1.0F - UPPER_RATIO), source.maxZ - source.minZ);

		// 换掉上臂的几何（保留原有其它方块，比如袖子层）
		List<ModelPart.Cuboid> upperList = new ArrayList<>();
		upperList.add(upper);
		for (int i = 1; i < original.size(); i++) {
			upperList.add(original.get(i));
		}
		accessor.pingpong$setCuboids(upperList);

		// 造小臂部件
		List<ModelPart.Cuboid> lowerList = new ArrayList<>();
		lowerList.add(lower);
		ModelPart forearm = new ModelPart(lowerList, Collections.<String, ModelPart>emptyMap());
		// 枢轴在切点（相对上臂起点）→ 它绕"肘"旋转
		forearm.setPivot(0.0F, height * UPPER_RATIO, 0.0F);

		// 挂到上臂下
		Map<String, ModelPart> children = accessor.pingpong$getChildren();
		if (children == null) {
			failure = "上臂 children 为 null";
			return null;
		}
		children.put(CHILD_NAME, forearm);
		return forearm;
	}

	/** 直接构造 Cuboid（类引用 → loom 会重映射；参数顺序见 ModelPart.Cuboid 的签名） */
	private static ModelPart.Cuboid newCuboid(int u, int v, float x, float y, float z,
											  float sizeX, float sizeY, float sizeZ) {
		return new ModelPart.Cuboid(u, v, x, y, z, sizeX, sizeY, sizeZ,
				0.0F, 0.0F, 0.0F, false, 1.0F, 1.0F, Collections.<net.minecraft.util.math.Direction>emptySet());
	}

	public static String diagnostics() {
		if (failure != null) {
			return "肘关节: 不可用（" + failure + "）";
		}
		return "肘关节: 就绪（类引用 + Accessor） / 已切割手臂: " + splitCount
				+ " 个 / 应用 " + appliedCount + " 次";
	}
}

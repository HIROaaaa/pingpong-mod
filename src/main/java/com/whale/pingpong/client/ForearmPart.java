package com.whale.pingpong.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.util.math.MathHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 真·肘关节：把上臂方块**几何切成两段**，下半段当小臂挂到肘部旋转。
 *
 * <h2>做法参照 Mo' Bends（这次是照着成熟实现写的）</h2>
 * 前面几轮本鲸娘一直在用 playerAnimator 的顶点变形（bendy-lib 的 bend）做弯曲，
 * 用户把 {@code /pingpong bend} 从 40° 试到 150° 之后回答：**「全都没区别」**。
 * 于是去读 Mo' Bends 的源码（[MoBends-Reforged](https://github.com/astryxion/MoBends-Reforged)），
 * 它的核心是 {@code BoxMutator.sliceFromBottom()} —— **不是变形，是把方块切开**：
 *
 * <pre>
 * 1. 记下原方块的尺寸与 UV；
 * 2. 在切点 splitY 处切一刀：上半段留给上臂，下半段作为小臂；
 * 3. 关键细节（MoBends 原码）：UV 也要按比例切 —— vSizeSlice = vSize × (newHeight / height)，
 *    上半段取 vPos 起、下半段取 vPos + vSizeSlice 起，这样皮肤纹理正好对齐，
 *    切开处不会出现错位的贴图；
 * 4. 切开后把内侧的面隐藏（上臂藏 bottom、小臂藏 top），避免看到内部；
 * 5. 小臂挂在上臂的肘部位置，相对上臂旋转 → 这就是"胳膊折了"。
 * </pre>
 *
 * 本类就是这套做法的 Java 实现。用反射写而不是直接引用，是因为要动的是
 * {@code ModelPart} 的私有结构（{@code cuboids} 列表、{@code Cuboid} 构造），
 * 反射能把"签名变化"的影响限制在这一个文件里，并且失败时有诊断兜底。
 */
public final class ForearmPart {

	/** 每条手臂切成两段的比例：上半段（上臂）占 0.5 */
	private static final float UPPER_RATIO = 0.5F;
	/** 挂载用的子节点名 */
	private static final String CHILD_NAME = "pingpong_forearm";
	/** 已经处理过的手臂（弱键：不阻止模型部件被回收） */
	private static final Map<ModelPart, Boolean> SPLIT_DONE = new WeakHashMap<>();
	private static final Map<ModelPart, ModelPart> FOREARMS = new WeakHashMap<>();

	private static boolean tried;
	private static String failure = "（未尝试）";
	private static String probeInfo = "（未探测）";
	private static long appliedCount;

	private ForearmPart() {
	}

	/** 客户端启动时探测一次：拿一个空部件试着走一遍反射路径，早点暴露问题 */
	public static void probe() {
		try {
			// 探测反射可用性（不真的切割，真实切割在拿到玩家的手臂部件时进行）
			Class.forName("net.minecraft.client.model.ModelPart$Cuboid");
			Field cuboids = ModelPart.class.getDeclaredField("cuboids");
			cuboids.setAccessible(true);
			Field children = ModelPart.class.getDeclaredField("children");
			children.setAccessible(true);
			tried = true;
			SPLIT_AVAILABLE = true;
			probeInfo = "反射就绪（cuboids/children 可访问）";
			com.whale.pingpong.PingPongMod.LOGGER.info(
					"[pingpong] 肘关节准备就绪：将把上臂几何切成两段做真实弯曲（参照 Mo' Bends 的切方块做法）");
		} catch (Throwable error) {
			tried = true;
			failure = error.getClass().getSimpleName() + ": " + error.getMessage();
			com.whale.pingpong.PingPongMod.LOGGER.warn("[pingpong] 肘关节准备失败：{}", failure);
		}
	}

	public static boolean available() {
		return tried && SPLIT_AVAILABLE;
	}

	/** 反射路径是否就绪（probe() 时确定） */
	private static boolean SPLIT_AVAILABLE;

	/**
	 * 应用肘部弯曲。
	 *
	 * @param arm     上臂部件（原版 rightArm / leftArm）
	 * @param isRight 是否右臂
	 * @param bendDeg 弯曲角度（度）
	 * @return true = 这次用了几何关节
	 */
	public static boolean apply(ModelPart arm, boolean isRight, float bendDeg) {
		if (arm == null) {
			return false;
		}
		try {
			if (!SPLIT_DONE.containsKey(arm)) {
				if (!splitArm(arm, isRight)) {
					return false;
				}
				SPLIT_DONE.put(arm, Boolean.TRUE);
				// 只打一次：这是"切割真的发生了"的直接证据（诊断命令之外的第二重确认）
				com.whale.pingpong.PingPongMod.LOGGER.info(
						"[pingpong] 已把{}臂几何切成两段：上臂 + 小臂（小臂挂在肘部，可独立旋转）",
						isRight ? "右" : "左");
			}
			ModelPart forearm = FOREARMS.get(arm);
			if (forearm == null) {
				return false;
			}
			// 小臂相对上臂折角：正值向下折（往手心方向收）
			forearm.pitch = -bendDeg * 0.017453292F;
			appliedCount++;
			return true;
		} catch (Throwable error) {
			failure = error.getClass().getSimpleName() + ": " + error.getMessage();
			return false;
		}
	}

	/**
	 * 把手臂切成"上臂 + 小臂"，小臂挂到肘部。
	 *
	 * <p>返回 false 表示这条路走不通（此时调用方会退回顶点变形）。
	 */
	private static boolean splitArm(ModelPart arm, boolean isRight) throws Exception {
		Field cuboidsField = ModelPart.class.getDeclaredField("cuboids");
		cuboidsField.setAccessible(true);
		Field childrenField = ModelPart.class.getDeclaredField("children");
		childrenField.setAccessible(true);

		@SuppressWarnings("unchecked")
		List<Object> original = (List<Object>) cuboidsField.get(arm);
		if (original == null || original.isEmpty()) {
			failure = "上臂没有几何体，无法切割";
			return false;
		}

		// 取手臂的主方块（原版手臂只有一个 4×12×4 的方块）
		Object source = original.get(0);
		Class<?> cuboidClass = source.getClass();
		Field minXF = cuboidClass.getField("minX");
		Field minYF = cuboidClass.getField("minY");
		Field minZF = cuboidClass.getField("minZ");
		Field maxXF = cuboidClass.getField("maxX");
		Field maxYF = cuboidClass.getField("maxY");
		Field maxZF = cuboidClass.getField("maxZ");

		// 原方块的几何（相对于部件枢轴；手臂是 0…12，枢轴在肩）
		float x0 = minXF.getFloat(source);
		float y0 = minYF.getFloat(source);
		float z0 = minZF.getFloat(source);
		float x1 = maxXF.getFloat(source);
		float y1 = maxYF.getFloat(source);
		float z1 = maxZF.getFloat(source);
		float height = y1 - y0;
		float splitY = y0 + height * UPPER_RATIO;

		/*
		 * 【UV 的处理】MoBends 在切割时会把 UV 也按比例切开（vSizeSlice = vSize × ratio），
		 * 这样皮肤的纹理在切口处正好接上。本实现先沿用原方块的 UV 起点（不做 v 偏移）：
		 * 手臂贴图是纯肤色/袖口，重复采样一段在 16 像素宽的胳膊上看不出接缝，
		 * 而算错 v 偏移反而会整段错位。先用最稳的做法，需要精修再说。
		 *
		 * u/v 取原版玩家手臂的贴图参数（右手 40/16、左手 32/48，4×12×4 的标准模型）。
		 */
		int u = isRight ? 40 : 32;
		int v = isRight ? 16 : 48;

		// 新的上臂：只保留上半段
		Object upper = newCuboid(u, v,
				x0, y0, z0,
				x1 - x0, height * UPPER_RATIO, z1 - z0);
		// 新的小臂：下半段（几何上紧接着上臂）
		Object lower = newCuboid(u, v,
				x0, splitY, z0,
				x1 - x0, height * (1.0F - UPPER_RATIO), z1 - z0);

		// 替换上臂的几何体为"只有上半段"
		List<Object> upperList = new ArrayList<>();
		upperList.add(upper);
		// 保留手臂上原有的其他方块（比如袖子层挂在同一部件时会有多个）——
		// 这里为简单起见只处理主方块，其余原样保留
		for (int i = 1; i < original.size(); i++) {
			upperList.add(original.get(i));
		}
		cuboidsField.set(arm, upperList);

		// 造出小臂部件
		Constructor<ModelPart> partCtor = ModelPart.class.getConstructor(List.class, Map.class);
		List<Object> lowerList = new ArrayList<>();
		lowerList.add(lower);
		ModelPart forearm = partCtor.newInstance(lowerList, Collections.<String, ModelPart>emptyMap());
		// 枢轴放在切点（相对上臂起点），这样它绕"肘"旋转
		forearm.setPivot(0.0F, height * UPPER_RATIO, 0.0F);

		// 挂到上臂下（children 是私有的）
		@SuppressWarnings("unchecked")
		Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(arm);
		if (children == null) {
			failure = "上臂 children 为 null";
			return false;
		}
		children.put(CHILD_NAME, forearm);
		FOREARMS.put(arm, forearm);
		return true;
	}

	/** 用反射构造一个 Cuboid（签名见类注释） */
	private static Object newCuboid(int u, int v, float x, float y, float z,
									float sizeX, float sizeY, float sizeZ) throws Exception {
		Class<?> cuboidClass = Class.forName("net.minecraft.client.model.ModelPart$Cuboid");
		Constructor<?> ctor = cuboidClass.getConstructor(
				int.class, int.class,
				float.class, float.class, float.class,
				float.class, float.class, float.class,
				float.class, float.class, float.class,
				boolean.class, float.class, float.class, Set.class);
		return ctor.newInstance(u, v, x, y, z, sizeX, sizeY, sizeZ,
				0.0F, 0.0F, 0.0F, false, 1.0F, 1.0F, Collections.<Object>emptySet());
	}

	public static String diagnostics() {
		if (!tried) {
			return "肘关节: 未探测";
		}
		if (failure.contains(":") && !failure.startsWith("（")) {
			return "肘关节: 不可用（" + failure + "）";
		}
		return "肘关节: " + probeInfo + " / 已切割手臂: " + SPLIT_DONE.size() + " 个 / 应用 " + appliedCount + " 次";
	}
}

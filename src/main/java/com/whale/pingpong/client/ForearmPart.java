package com.whale.pingpong.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.util.math.MathHelper;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 真·肘关节：给玩家手臂**凭空加一段前臂部件**（五期 M6 第九轮）。
 *
 * <h2>为什么走到这一步</h2>
 * 之前两轮都在用 playerAnimator 的 {@code IBendHelper.bend()}（顶点级变形，底层 bendy-lib）做手臂弯曲。
 * 诊断数据证明它**确实在执行**（弯矩 52°、成功 11036 次、零失败），但用户把
 * {@code /pingpong bend} 从 40° 试到 150° 之后给了决定性回答：**「全都没区别」**。
 * 也就是说顶点变形在这个模型上的视觉贡献约等于 0 —— 不管怎么调参都没用。
 *
 * <h2>现在的做法</h2>
 * 不用变形，直接**加一段真实的模型部件**当小臂：它挂在上臂的肘部位置，
 * 相对上臂旋转 → 视觉上就是"胳膊在肘部折了"。这是确定的几何，不依赖任何变形库。
 *
 * <h3>技术要点</h3>
 * <ul>
 *   <li>{@code ModelPart} 的构造签名（1.20.1 Yarn，javap 查证）：
 *       {@code ModelPart(List<Cuboid>, Map<String, ModelPart>)}；</li>
 *   <li>{@code ModelPart.Cuboid} 的签名：
 *       {@code Cuboid(int u, int v, float x, float y, float z, float sizeX, float sizeY, float sizeZ,
 *       float extraX, float extraY, float extraZ, boolean mirror, float uScale, float vScale, Set<Direction>)}；</li>
 *   <li>两个类的构造函数都是 public，但 {@code ModelPart} 是 final class —— 用反射构造，
 *       这样即使将来签名变化也只是这里抛异常（有 try/catch + 诊断计数兜底），不会让 mod 崩。</li>
 * </ul>
 */
public final class ForearmPart {

	/** 前臂长度（模型像素）：手臂总共 12，留 6 给上臂、6 给前臂 */
	private static final float FOREARM_LENGTH = 6.0F;
	/** 前臂截面（与上臂一致：4×4） */
	private static final float WIDTH = 4.0F;

	private static boolean tried;
	private static ModelPart leftForearm;
	private static ModelPart rightForearm;
	private static String failure = "（未尝试）";

	private ForearmPart() {
	}

	/** 是否可用（第一次调用时就地构造两个前臂部件） */
	public static boolean available() {
		if (!tried) {
			tried = true;
			try {
				rightForearm = build(0, 0);
				leftForearm = build(0, 0);
				com.whale.pingpong.PingPongMod.LOGGER.info(
						"[pingpong] 前臂部件构造成功：手臂会有关节弯曲（几何关节，不依赖 bendy-lib）");
			} catch (Throwable error) {
				failure = error.getClass().getSimpleName() + ": " + error.getMessage();
				rightForearm = null;
				leftForearm = null;
				com.whale.pingpong.PingPongMod.LOGGER.warn(
						"[pingpong] 前臂部件构造失败，回退到 bend 变形：{}", failure);
			}
		}
		return rightForearm != null;
	}

	/** 客户端启动时主动探测一次（失败要在启动阶段就看到，而不是等到渲染时） */
	public static void probe() {
		available();
	}

	/** 构造一个前臂部件。u,v 是贴图起点（先用上臂的贴图区域，视觉上接着手臂的皮肤） */
	private static ModelPart build(int u, int v) throws Exception {
		Class<?> cuboidClass = Class.forName("net.minecraft.client.model.ModelPart$Cuboid");
		Constructor<?> cuboidCtor = cuboidClass.getConstructor(
				int.class, int.class,
				float.class, float.class, float.class,
				float.class, float.class, float.class,
				float.class, float.class, float.class,
				boolean.class, float.class, float.class, java.util.Set.class);

		// 方块从"肘部"往下 6 像素：模型坐标 y 向下为正，所以 y 取 0 → 6
		Object cuboid = cuboidCtor.newInstance(
				u, v,
				0.0F, 0.0F, 0.0F,          // 位置（相对本部件的枢轴）
				WIDTH, FOREARM_LENGTH, WIDTH,
				0.0F, 0.0F, 0.0F,          // extra（无膨胀）
				false, 1.0F, 1.0F,
				java.util.Collections.emptySet());

		Constructor<ModelPart> partCtor = ModelPart.class.getConstructor(List.class, Map.class);
		ModelPart part = partCtor.newInstance(
				Collections.singletonList(cuboid),
				Collections.<String, ModelPart>emptyMap());
		// 枢轴放在"肘"上：上臂长 12，肘在相对上臂起点 6 像素处；
		// 这个部件被挂成上臂的子节点，所以枢轴写 (0, 6, 0)。
		part.setPivot(0.0F, 6.0F, 0.0F);
		return part;
	}

	/**
	 * 把前臂挂到手臂上并按弯曲角旋转。
	 *
	 * @param arm      上臂部件（原版 rightArm / leftArm）
	 * @param isRight  是否右臂（决定取哪个前臂实例）
	 * @param bendDeg  肘部弯曲角（度），0 = 直
	 * @return true = 这次真的用了几何关节（而不是顶点变形）
	 */
	public static boolean apply(ModelPart arm, boolean isRight, float bendDeg) {
		if (!available() || arm == null) {
			return false;
		}
		ModelPart forearm = isRight ? rightForearm : leftForearm;
		if (forearm == null) {
			return false;
		}
		// 挂成子节点（幂等：原版会重建 children，所以要检查）
		// children 是 ModelPart 的私有字段，用反射访问（与构造 Cuboid 同样的理由：
		// 避免把内部字段名写进编译期依赖，签名变了也只是这里失败并记进诊断）
		if (!arm.hasChild(CHILD_NAME)) {
			try {
				java.lang.reflect.Field childrenField = ModelPart.class.getDeclaredField("children");
				childrenField.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(arm);
				if (children != null) {
					children.put(CHILD_NAME, forearm);
				}
			} catch (Throwable error) {
				failure = "挂载失败 " + error.getClass().getSimpleName() + ": " + error.getMessage();
				return false;
			}
		}
		// 相对上臂折一个角：正值 = 往手心方向收（前臂向前折）
		forearm.pitch = -bendDeg * 0.017453292F;
		return true;
	}

	/** 子节点名（挂载用；每次渲染重新确认，原版重建 children 后会自动补回） */
	private static final String CHILD_NAME = "pingpong_forearm";

	public static String diagnostics() {
		if (!tried) {
			return "前臂部件: 未初始化";
		}
		return available()
				? "前臂部件: 可用（几何肘关节）"
				: "前臂部件: 构造失败（" + failure + "）";
	}
}

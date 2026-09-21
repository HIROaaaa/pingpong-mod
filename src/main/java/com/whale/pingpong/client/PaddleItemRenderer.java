package com.whale.pingpong.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 球拍的自定义物品渲染器 —— **几何在代码里画**，不再受"JSON 只能拼长方体"的限制。
 *
 * <h2>这是照 L_Ender's Cataclysm 的做法学的</h2>
 * 用户提示"灾变的 3D 武器是正常的"，于是翻它的源码（桌面那份 `new1.20.1-master`）：
 *
 * <ul>
 *   <li>模型的 json 只写两行关键配置：`"parent": "minecraft:builtin/entity"` +
 *       `"display"`（几何是空的）；</li>
 *   <li>几何写在 Java 里：`Incinerator_Model extends AdvancedEntityModel`，
 *       构造函数里一串 `addBox(...)` 定义每个部件；</li>
 *   <li>渲染由 `CMItemstackRenderer extends BlockEntityWithoutLevelRenderer` 统一负责，
 *       按物品 switch 到对应模型。</li>
 * </ul>
 *
 * 在 Fabric 上对应的是 {@link BuiltinItemRendererRegistry}（注册"物品用代码渲染"），
 * 本类就是那个渲染器：用原版 {@link ModelPart} 当容器，几何用 {@code ModelPart.Cuboid}
 * 在代码里拼出来。
 *
 * <h2>为什么这样能解决"像 2D 贴图"</h2>
 * 之前所有尝试都在 JSON 层面（长方体拼圆、加厚、改 display）。而这里：
 * 拍面用**多段宽度递减的方块**拼出圆盘（10 段），每段还能单独给厚度与 UV ——
 * 于是轮廓是圆的、侧面有厚度、手柄是立体的，写多少几何就有多少几何。
 */
public final class PaddleItemRenderer implements BuiltinItemRenderer {

	/**
	 * 贴图路径 —— **必须写完整**（含 textures/ 前缀与 .png）。
	 *
	 * 【踩过的坑】这里是 {@code RenderLayer.getEntitySolid(Identifier)}，它**不会**像模型系统那样
	 * 帮忙拼 `textures/` 与 `.png`：写成 `pingpong:item/pingpong_paddle` 会直接
	 * `FileNotFoundException: pingpong:item/pingpong_paddle`，物品渲染成空气（用户实测"任何材质都没有"）。
	 * 正确写法是资源管理器实际查找的完整路径。
	 */
	private static final Identifier TEXTURE =
			new Identifier("pingpong", "textures/item/pingpong_paddle.png");

	/** 模型空间 1 像素 = 1/16 格 */
	private static final float PIXEL = 0.0625F;

	/** 拍面：分 10 段拼圆，每段的半宽按圆方程算（半径 5 像素） */
	private static final int BLADE_SEGMENTS = 10;
	private static final float BLADE_RADIUS = 5.0F;
	/** 拍面中心（模型像素，向上为负 y） */
	private static final float BLADE_CY = -9.0F;
	/** 各层厚度（像素）：正面胶皮、反面胶皮、木芯半厚 */
	private static final float RUBBER = 1.2F;
	private static final float CORE_HALF = 0.8F;

	private final ModelPart root;

	/** 几何是否真的建出来了（诊断用：0 = 构造逻辑有问题） */
	private final int cuboidCount;
	/** render 被调用的次数（诊断用：0 = 这条路根本没被游戏调用） */
	private static long renderCalls;
	private static boolean loggedFirstCall;

	public PaddleItemRenderer() {
		List<ModelPart.Cuboid> parts = new ArrayList<>();
		buildBlade(parts);
		buildHandle(parts);
		this.cuboidCount = parts.size();
		// 一个 ModelPart 装下全部几何；pivot 设在原点（ItemRenderer 已把物品变换铺好）
		this.root = new ModelPart(parts, Collections.<String, ModelPart>emptyMap());
		this.root.setPivot(0.0F, 0.0F, 0.0F);
		// 【诊断】把几何包围盒打出来：坐标是否落在合理范围、有没有被 display 变换甩出视野，
		// 光靠"看见/看不见"判断不了，得看数。
		float[] bounds = boundsOf(parts);
		com.whale.pingpong.PingPongMod.LOGGER.info(
				"[pingpong] 球拍几何：{} 个方块，范围 x[{}, {}] y[{}, {}] z[{}, {}]（模型像素）",
				cuboidCount, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
	}

	/** 计算所有方块的包围盒（顺序：minX maxX minY maxY minZ maxZ） */
	private static float[] boundsOf(List<ModelPart.Cuboid> parts) {
		float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE,
				-Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
		for (ModelPart.Cuboid c : parts) {
			b[0] = Math.min(b[0], c.minX);
			b[1] = Math.max(b[1], c.maxX);
			b[2] = Math.min(b[2], c.minY);
			b[3] = Math.max(b[3], c.maxY);
			b[4] = Math.min(b[4], c.minZ);
			b[5] = Math.max(b[5], c.maxZ);
		}
		return b;
	}

	/** 供诊断输出 */
	public static long renderCalls() {
		return renderCalls;
	}

	public static String diagnostics() {
		return "球拍代码渲染: render 被调用 " + renderCalls + " 次"
				+ (loggedFirstCall ? "" : "（**一次都没调用** → builtin/entity 链路没走到）");
	}

	/**
	 * 拍面：10 段拼圆，每段三块（正面胶皮 / 木芯 / 反面胶皮）。
	 *
	 * <p>坐标用"模型像素、y 向上为负"的写法与 JSON 模型一致，渲染时整体乘 {@link #PIXEL}。
	 */
	private void buildBlade(List<ModelPart.Cuboid> out) {
		for (int i = 0; i < BLADE_SEGMENTS; i++) {
			double a0 = Math.PI * 2 * i / BLADE_SEGMENTS;
			double a1 = Math.PI * 2 * (i + 1) / BLADE_SEGMENTS;
			// 圆盘在 y 方向的上下边界
			float yTop = BLADE_CY - (float) (Math.sin(a1) * BLADE_RADIUS);
			float yBot = BLADE_CY - (float) (Math.sin(a0) * BLADE_RADIUS);
			float halfTop = (float) (Math.cos(a1) * BLADE_RADIUS);
			float halfBot = (float) (Math.cos(a0) * BLADE_RADIUS);
			// 用两条边里较窄的一侧当这一段的宽度（保证不超出圆）
			float half = Math.min(Math.abs(halfTop), Math.abs(halfBot));

			float y0 = Math.min(yTop, yBot);
			float y1 = Math.max(yTop, yBot);
			if (y1 - y0 < 0.2F || half < 0.2F) {
				continue;
			}
			// 正面红胶皮 / 木芯 / 反面黑胶皮
			out.add(box(-half, y0, CORE_HALF, half, y1, CORE_HALF + RUBBER, 0, 0));
			out.add(box(-half + 0.2F, y0, -CORE_HALF, half - 0.2F, y1, CORE_HALF, 16, 16));
			out.add(box(-half, y0, -CORE_HALF - RUBBER, half, y1, -CORE_HALF, 16, 0));
		}
	}

	/** 手柄：两段（握把 + 底端加宽），从拍面下方延伸到 y=0 */
	private void buildHandle(List<ModelPart.Cuboid> out) {
		out.add(box(-1.0F, -5.0F, -0.9F, 1.0F, 0.0F, 0.9F, 16, 16));   // 握把
		out.add(box(-1.3F, -1.2F, -1.1F, 1.3F, 0.0F, 1.1F, 16, 16));   // 底端加宽
	}

	/** 造一个长方体：坐标是模型像素（y 向上为负），u/v 是贴图起点 */
	private static ModelPart.Cuboid box(float x0, float y0, float z0,
										float x1, float y1, float z1, int u, int v) {
		return new ModelPart.Cuboid(u, v,
				x0, y0, z0,
				x1 - x0, y1 - y0, z1 - z0,
				0.0F, 0.0F, 0.0F, false, 1.0F, 1.0F,
				Collections.<Direction>emptySet());
	}

	@Override
	public void render(ItemStack stack, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
					   int light, int overlay) {
		renderCalls++;
		if (!loggedFirstCall) {
			loggedFirstCall = true;
			com.whale.pingpong.PingPongMod.LOGGER.info(
					"[pingpong] 球拍代码渲染被调用（这正是 builtin/entity 生效的证据）");
		}
		/*
		 * 【最小可见测试】先绕开 ModelPart 与 UV，直接用顶点画一个纯色方块。
		 *
		 * 为什么要这样：日志已证明渲染器被调用、几何包围盒也正常，但屏幕上什么都看不到 ——
		 * 说明问题出在"ModelPart + UV 这条更长的链路"上，而不是"渲染器有没有被调"。
		 * 用最简单的几何（纯色、不采样贴图）先确认"画出来的东西能不能被看见"，
		 * 把变量从五个（模型/UV/贴图/缩放/变换）压到一个。
		 */
		matrices.push();
		matrices.scale(PIXEL, PIXEL, PIXEL);
		VertexConsumer testBuffer = vertexConsumers.getBuffer(RenderLayer.getEntitySolid(TEXTURE));
		// 一个 8×8×2 像素的方块（像素坐标）
		testBox(testBuffer, matrices, light, overlay);
		matrices.pop();
	}

	/** 画一个纯色（顶点着色）长方体，用于验证渲染链路 */
	private static void testBox(VertexConsumer buf, MatrixStack matrices, int light, int overlay) {
		org.joml.Matrix4f m = matrices.peek().getPositionMatrix();
		float x0 = -4.0F, x1 = 4.0F, y0 = -8.0F, y1 = 0.0F, z0 = -1.0F, z1 = 1.0F;
		int r = 255, g = 60, b = 60, a = 255;
		// 正面与背面（z 方向），四条四边形
		quad(buf, m, light, overlay, r, g, b, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		quad(buf, m, light, overlay, r, g, b, a, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
		// 左右侧面
		quad(buf, m, light, overlay, r, g, b, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		quad(buf, m, light, overlay, r, g, b, a, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
		// 顶面与底面
		quad(buf, m, light, overlay, r, g, b, a, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
		quad(buf, m, light, overlay, r, g, b, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
	}

	private static void quad(VertexConsumer buf, org.joml.Matrix4f m, int light, int overlay,
							 int r, int g, int b, int a,
							 float x0, float y0, float z0,
							 float x1, float y1, float z1,
							 float x2, float y2, float z2,
							 float x3, float y3, float z3) {
		buf.vertex(m, x0, y0, z0).color(r, g, b, a).texture(0.0F, 1.0F).overlay(overlay).light(light).normal(0.0F, 0.0F, 1.0F);
		buf.vertex(m, x1, y1, z1).color(r, g, b, a).texture(1.0F, 1.0F).overlay(overlay).light(light).normal(0.0F, 0.0F, 1.0F);
		buf.vertex(m, x2, y2, z2).color(r, g, b, a).texture(1.0F, 0.0F).overlay(overlay).light(light).normal(0.0F, 0.0F, 1.0F);
		buf.vertex(m, x3, y3, z3).color(r, g, b, a).texture(0.0F, 0.0F).overlay(overlay).light(light).normal(0.0F, 0.0F, 1.0F);
	}
}

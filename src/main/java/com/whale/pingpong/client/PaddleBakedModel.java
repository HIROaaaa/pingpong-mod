package com.whale.pingpong.client;

import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * 球拍的自定义烘焙模型：**用代码直接生成几何体**，不靠 JSON 元素拼方块。
 *
 * <h2>为什么走这条路</h2>
 * 用户从第二轮起反复说"球拍还是 2D 的平面"。JSON 元素只能画长方体，圆形拍面得拿 9 层小方块去拼，
 * 结果是"一堆薄片叠成的近似圆"，边缘阶梯状、厚度方向做不出圆润感 —— 怎么调都像一块板。
 *
 * <p>这里改用 Fabric 的渲染 API（{@code QuadEmitter}）**逐面生成四边形**：
 * <ul>
 *   <li><b>拍面</b>：正 16 边形的每个扇区是一条四边形（A→B→B'→A'），边缘是平滑斜边而非阶梯；</li>
 *   <li><b>球面感</b>：胶皮分两层，外层向圆心收 12% → 侧看有弧度；</li>
 *   <li><b>厚度</b>：胶皮 2×(1.4+0.8) + 木芯 2.0 ≈ 5.2 像素（0.33 格），拿在手里明显是一块板；</li>
 *   <li><b>手柄</b>：颈 → 握把 → 底端喇叭口，三段长方体。</li>
 * </ul>
 *
 * <p>贴图沿用原来的 32×32 四象限图集（红胶皮 / 黑胶皮 / 木芯），
 * UV 用**图集像素坐标**（0~32），与 JSON 模型里的 uv 语义一致。
 * 顶点坐标同样是模型空间 0~16 像素 —— 于是原来的 display（旋转/缩放）设置原封不动可用。
 */
public final class PaddleBakedModel implements BakedModel {

	/** 拍面分段数：16 段已经看不出棱角 */
	private static final int SEGMENTS = 16;
	/** 拍面半径（像素） */
	private static final float RADIUS = 6.5F;
	/** 拍面中心（y 偏上一点，下方留给手柄） */
	private static final float CX = 8.0F;
	private static final float CY = 9.5F;
	/** 厚度（像素）：木芯半厚、每层胶皮厚 */
	private static final float CORE_HALF = 1.0F;
	private static final float RUBBER_INNER = 1.4F;
	private static final float RUBBER_OUTER = 0.8F;

	/** 贴图 UV（32×32 图集：左上红胶皮 / 右上黑胶皮 / 右下木芯） */
	private static final float[] UV_RED = {0F, 0F, 16F, 16F};
	private static final float[] UV_BLACK = {16F, 0F, 32F, 16F};
	private static final float[] UV_WOOD = {16F, 16F, 32F, 32F};

	private final BakedModel original;
	private final Sprite sprite;

	public PaddleBakedModel(BakedModel original) {
		this.original = original;
		this.sprite = original.getParticleSprite();
	}

	@Override
	public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
		Renderer renderer = RendererAccess.INSTANCE.getRenderer();
		if (renderer == null) {
			return original.getQuads(state, face, random);   // 拿不到渲染器时退回原模型，不崩
		}
		MeshBuilder builder = renderer.meshBuilder();
		QuadEmitter e = builder.getEmitter();

		buildBlade(e);
		buildHandle(e);

		Mesh mesh = builder.build();
		List<BakedQuad> out = new ArrayList<>();
		mesh.forEach(quad -> out.add(quad.toBakedQuad(0, sprite, false)));
		return out;
	}

	/**
	 * 拍面：正 16 边形，逐扇区生成"一块小扇形板"（正反两面 + 一条外缘侧边）。
	 *
	 * <p>每层的做法一致，只是 z 区间与轮廓缩放不同：
	 * 木芯（居中）→ 胶皮内层（全尺寸）→ 胶皮外层（收 12%，形成圆弧收边）。
	 */
	private void buildBlade(QuadEmitter e) {
		for (int i = 0; i < SEGMENTS; i++) {
			double a0 = Math.PI * 2 * i / SEGMENTS;
			double a1 = Math.PI * 2 * (i + 1) / SEGMENTS;
			float ax = CX + (float) (Math.cos(a0) * RADIUS);
			float ay = CY + (float) (Math.sin(a0) * RADIUS);
			float bx = CX + (float) (Math.cos(a1) * RADIUS);
			float by = CY + (float) (Math.sin(a1) * RADIUS);

			// 木芯（中间，稍薄）
			slab(e, CX, CY, ax, ay, bx, by, -CORE_HALF, CORE_HALF, UV_WOOD, false);
			// 正面：红胶皮内层 + 外层（外层朝圆心收 → 弧面）
			slab(e, CX, CY, ax, ay, bx, by, CORE_HALF, CORE_HALF + RUBBER_INNER, UV_RED, false);
			slab(e, CX, CY, ax, ay, bx, by, CORE_HALF + RUBBER_INNER,
					CORE_HALF + RUBBER_INNER + RUBBER_OUTER, UV_RED, true);
			// 反面：黑胶皮内层 + 外层
			slab(e, CX, CY, ax, ay, bx, by, -CORE_HALF - RUBBER_INNER, -CORE_HALF, UV_BLACK, false);
			slab(e, CX, CY, ax, ay, bx, by, -CORE_HALF - RUBBER_INNER - RUBBER_OUTER,
					-CORE_HALF - RUBBER_INNER, UV_BLACK, true);
		}
	}

	/**
	 * 一块"扇形板"：由顶点 O（圆心）、A、B 组成三角形，向 z 方向拉出厚度。
	 *
	 * <p>为了少画退化面，简化为**两条四边形**：正面（O,A,B,B 退化）与反面 + 外缘侧边。
	 * 圆心点在拍面内部，两个扇区共享同一条径向边，拼接后看不到缝。
	 *
	 * @param shrinkOuter 是否把轮廓朝圆心收一点（做弧面收边）
	 */
	private void slab(QuadEmitter e, float ox, float oy, float ax, float ay, float bx, float by,
					  float z0, float z1, float[] uv, boolean shrinkOuter) {
		if (shrinkOuter) {
			ax = CX + (ax - CX) * 0.88F;
			ay = CY + (ay - CY) * 0.88F;
			bx = CX + (bx - CX) * 0.88F;
			by = CY + (by - CY) * 0.88F;
		}
		// 正面（法线 +z）
		e.pos(0, ox, oy, z1).uv(0, uv[0], uv[1]);
		e.pos(1, ax, ay, z1).uv(1, uv[2], uv[1]);
		e.pos(2, bx, by, z1).uv(2, uv[2], uv[3]);
		e.pos(3, ox, oy, z1).uv(3, uv[0], uv[3]);
		e.nominalFace(Direction.SOUTH);
		e.emit();
		// 反面（法线 -z，顶点顺序反向）
		e.pos(0, ox, oy, z0).uv(0, uv[0], uv[1]);
		e.pos(1, ox, oy, z0).uv(1, uv[2], uv[1]);
		e.pos(2, bx, by, z0).uv(2, uv[2], uv[3]);
		e.pos(3, ax, ay, z0).uv(3, uv[0], uv[3]);
		e.nominalFace(Direction.NORTH);
		e.emit();
		// 外缘侧边（A→B 那条边，拉出厚度）
		e.pos(0, ax, ay, z0).uv(0, uv[0], uv[1]);
		e.pos(1, bx, by, z0).uv(1, uv[2], uv[1]);
		e.pos(2, bx, by, z1).uv(2, uv[2], uv[3]);
		e.pos(3, ax, ay, z1).uv(3, uv[0], uv[3]);
		e.nominalFace(Direction.UP);
		e.emit();
	}

	/** 手柄：颈 → 握把 → 底端喇叭口 */
	private void buildHandle(QuadEmitter e) {
		box(e, 6.6F, 3.4F, 7.5F, 9.4F, 5.6F, 8.5F, UV_WOOD);   // 颈（连拍面）
		box(e, 6.9F, 1.2F, 7.6F, 9.1F, 4.0F, 8.4F, UV_WOOD);   // 握把
		box(e, 6.5F, 0.0F, 7.4F, 9.5F, 1.5F, 8.6F, UV_WOOD);   // 底端喇叭口
	}

	/** 标准长方体：6 个面，每个面一条四边形 */
	private void box(QuadEmitter e, float x0, float y0, float z0,
					 float x1, float y1, float z1, float[] uv) {
		face(e, uv, Direction.UP, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
		face(e, uv, Direction.DOWN, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		face(e, uv, Direction.NORTH, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
		face(e, uv, Direction.SOUTH, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		face(e, uv, Direction.WEST, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		face(e, uv, Direction.EAST, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
	}

	/** 一条四边形：四个顶点 (x,y,z) 依次给出 */
	private void face(QuadEmitter e, float[] uv, Direction dir,
					  float x0, float y0, float z0,
					  float x1, float y1, float z1,
					  float x2, float y2, float z2,
					  float x3, float y3, float z3) {
		e.pos(0, x0, y0, z0).uv(0, uv[0], uv[1]);
		e.pos(1, x1, y1, z1).uv(1, uv[2], uv[1]);
		e.pos(2, x2, y2, z2).uv(2, uv[2], uv[3]);
		e.pos(3, x3, y3, z3).uv(3, uv[0], uv[3]);
		e.nominalFace(dir);
		e.emit();
	}

	// ------------------------------------------------------------------
	// 其余接口：原样透传原模型的设置（显示变换、粒子贴图、环境光等）
	// ------------------------------------------------------------------

	@Override
	public boolean useAmbientOcclusion() {
		return original.useAmbientOcclusion();
	}

	@Override
	public boolean hasDepth() {
		return original.hasDepth();
	}

	@Override
	public boolean isSideLit() {
		return original.isSideLit();
	}

	@Override
	public boolean isBuiltin() {
		return original.isBuiltin();
	}

	@Override
	public Sprite getParticleSprite() {
		return sprite;
	}

	@Override
	public ModelTransformation getTransformation() {
		return original.getTransformation();
	}

	@Override
	public ModelOverrideList getOverrides() {
		return original.getOverrides();
	}
}

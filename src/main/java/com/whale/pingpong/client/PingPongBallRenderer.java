package com.whale.pingpong.client;

import com.whale.pingpong.PingPongMod;
import com.whale.pingpong.entity.PingPongBallEntity;
import com.whale.pingpong.item.ModItems;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 乒乓球渲染：把「乒乓球物品」的模型贴在实体位置上，
 * 用 dispatcher.getRotation() 做公告板朝向，保证任何角度看都是个球。
 */
public class PingPongBallRenderer extends EntityRenderer<PingPongBallEntity> {

	private final ItemRenderer itemRenderer;
	private final ItemStack ballStack = new ItemStack(ModItems.PINGPONG_BALL);

	public PingPongBallRenderer(EntityRendererFactory.Context context) {
		super(context);
		this.itemRenderer = context.getItemRenderer();
		this.shadowRadius = 0.12F;
		this.shadowOpacity = 0.35F;
	}

	@Override
	public void render(PingPongBallEntity entity, float yaw, float tickDelta, MatrixStack matrices,
					   VertexConsumerProvider vertexConsumers, int light) {
		// ---- 旋转尾迹（五期 M8 现象 C）：把「球在弯」这件事画出来 ----
		// 放在球本体之前画，残影被球盖住一部分，看起来才像球拖出来的影子。
		Vec3d spin = entity.getSpin();
		double spinStrength = spin.length();
		List<Vec3d> trail = PingPongTrail.update(entity, spinStrength);
		if (!trail.isEmpty() && PingPongTrail.isUsableFor(entity)) {
			renderTrail(entity, trail, spinStrength, matrices, vertexConsumers, light);
		}

		matrices.push();

		// 实体坐标原点在碰撞箱底部，球心要抬高半个箱高（0.14）
		matrices.translate(0.0, 0.14, 0.0);
		// 公告板：始终面向摄像机
		matrices.multiply(this.dispatcher.getRotation());
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F));
		matrices.scale(0.3F, 0.3F, 0.3F);

		// 自旋可视化：按自旋方向和大小让贴图自己转，能一眼看出上旋/下旋/侧旋
		double spinX = entity.getSpin().x;
		double spinY = entity.getSpin().y;
		double spinZ = entity.getSpin().z;
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) (spinZ * 8.0)));
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) (spinX * 8.0)));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) (spinY * 8.0)));

		this.itemRenderer.renderItem(this.ballStack, ModelTransformationMode.GROUND, light,
				OverlayTexture.DEFAULT_UV, matrices, vertexConsumers, entity.getWorld(), entity.getId());

		matrices.pop();
		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	/**
	 * 画残影：沿最近的飞行轨迹摆一串越来越小、越来越淡的球。
	 *
	 * 侧旋越猛 → 轨迹越弯 → 尾迹的弯曲肉眼可见；上旋下扎同理。
	 * 尾迹长度随自旋强度变化（转得猛的球尾巴长），球不转时 {@link PingPongTrail} 直接返回空表。
	 */
	private void renderTrail(PingPongBallEntity entity, List<Vec3d> trail, double spinStrength,
							 MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		Vec3d origin = entity.getPos();
		int count = trail.size();
		for (int i = 0; i < count; i++) {
			float age = (float) (i + 1) / count;          // 0 = 最近的点，1 = 最老的
			float scale = 0.28F * (1.0F - 0.62F * age) * (float) Math.min(1.0, 0.55 + 0.09 * spinStrength);
			Vec3d point = trail.get(i);
			matrices.push();
			matrices.translate(point.x - origin.x, point.y - origin.y + 0.14, point.z - origin.z);
			matrices.scale(scale, scale, scale);
			this.itemRenderer.renderItem(this.ballStack, ModelTransformationMode.GROUND, light,
					OverlayTexture.DEFAULT_UV, matrices, vertexConsumers, entity.getWorld(), entity.getId());
			matrices.pop();
		}
	}

	@Override
	public Identifier getTexture(PingPongBallEntity entity) {
		return PingPongMod.id("textures/entity/pingpong_ball.png");
	}
}

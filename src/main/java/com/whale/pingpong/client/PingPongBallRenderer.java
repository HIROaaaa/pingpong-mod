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

	@Override
	public Identifier getTexture(PingPongBallEntity entity) {
		return PingPongMod.id("textures/entity/pingpong_ball.png");
	}
}

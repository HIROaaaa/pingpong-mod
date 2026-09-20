package com.whale.pingpong.mixin;

import com.whale.pingpong.client.PingPongClientState;
import com.whale.pingpong.item.ModItems;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称「独立挥拍」动画。
 *
 * 原版挥拍动的是整条手臂，这里额外给球拍加一层绕手腕的旋转：
 * - 拍面俯仰（滚轮上/下）→ 绕 X 轴后仰 / 前倾
 * - 拍面侧偏（Alt+滚轮）→ 绕 Z 轴左右翻
 * - 挥拍进度 → 横向扫动 + 下压 + 前送
 *
 * 关键：这些矩阵只作用在手持物渲染上，玩家的摄像机（视角）一点都不会动。
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {

	@Unique
	private boolean pingpong$matrixPushed;

	@Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
	private void pingpong$beforeRenderFirstPersonItem(AbstractClientPlayerEntity player, float tickDelta,
													  float pitch, Hand hand, float swingProgress, ItemStack item,
													  float equipProgress, MatrixStack matrices,
													  VertexConsumerProvider vertexConsumers, int light,
													  CallbackInfo ci) {
		if (!item.isOf(ModItems.PINGPONG_PADDLE)) {
			return;
		}

		matrices.push();
		this.pingpong$matrixPushed = true;

		// 挥拍进度：0 → 1 → 0 的摆动曲线
		float swing = MathHelper.sin(MathHelper.sqrt(MathHelper.clamp(swingProgress, 0.0F, 1.0F)) * 3.1415927F);
		float tiltDegrees = (float) (PingPongClientState.tilt() * 42.0);
		float sideDegrees = (float) (PingPongClientState.sideTilt() * 36.0);

		// 1. 常态拍面角度 + 挥拍时加大幅度
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(tiltDegrees * (0.45F + 0.55F * swing)));
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(sideDegrees * (0.45F + 0.55F * swing)));

		// 2. 独立挥拍：手腕横向扫动 + 一点下压前送
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-28.0F * swing));
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-14.0F * swing));
		matrices.translate(0.0F, -0.05F * swing, -0.06F * swing);
	}

	@Inject(method = "renderFirstPersonItem", at = @At("RETURN"))
	private void pingpong$afterRenderFirstPersonItem(AbstractClientPlayerEntity player, float tickDelta,
													 float pitch, Hand hand, float swingProgress, ItemStack item,
													 float equipProgress, MatrixStack matrices,
													 VertexConsumerProvider vertexConsumers, int light,
													 CallbackInfo ci) {
		if (this.pingpong$matrixPushed) {
			matrices.pop();
			this.pingpong$matrixPushed = false;
		}
	}
}

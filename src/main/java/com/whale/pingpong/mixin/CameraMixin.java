package com.whale.pingpong.mixin;

import com.whale.pingpong.client.PingPongClientState;
import com.whale.pingpong.entity.PingPongBallEntity;
import com.whale.pingpong.item.ModItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 跟球视角（需求 6，默认关闭，V 键切换）。
 *
 * 开启后相机始终把<b>最近的乒乓球</b>放在画面正中；玩家的真实朝向（{@code player.getYaw/Pitch}）
 * 完全不动，所以：
 * - 击球判定用的还是玩家自己的朝向与由球台径向决定的击球点，不会因为视角而改变；
 * - 第三人称看到的身体朝向也不会跟着扭。
 *
 * 相机朝向用「对准球的方向」逐帧平滑插值，避免球飞出视野时画面猛甩。
 */
@Mixin(Camera.class)
public class CameraMixin {

	@Shadow
	private float yaw;

	@Shadow
	private float pitch;

	@Shadow
	protected void setRotation(float yaw, float pitch) {
	}

	/** 每帧最多调整多少比例（0~1），越小越稳、越大越跟手 */
	@Unique
	private static final float PINGPONG_LERP = 0.35F;
	/** 追踪半径（格） */
	@Unique
	private static final double PINGPONG_TRACK_RANGE = 40.0;

	@Inject(method = "update", at = @At("RETURN"))
	private void pingpong$trackBall(BlockView area, Entity focusedEntity, boolean thirdPerson,
									boolean inverseView, float tickDelta, CallbackInfo ci) {
		if (!PingPongClientState.isBallCam()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null || client.currentScreen != null) {
			return;
		}
		if (!client.player.getMainHandStack().isOf(ModItems.PINGPONG_PADDLE)) {
			return;
		}

		Vec3d target = pingpong$nearestBall(client, focusedEntity);
		if (target == null) {
			return;
		}

		Vec3d eye = focusedEntity.getEyePos();
		Vec3d look = target.subtract(eye);
		double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
		if (look.lengthSquared() < 1.0e-6) {
			return;
		}

		// 与 Camera.update 内部同一套换算：yaw = atan2(z, x) + 90°，pitch = -atan2(y, 水平距离)
		float wantYaw = (float) (MathHelper.atan2(look.z, look.x) * 57.29577951308232 - 90.0);
		float wantPitch = (float) (-(MathHelper.atan2(look.y, horizontal) * 57.29577951308232));

		setRotation(MathHelper.lerpAngleDegrees(PINGPONG_LERP, this.yaw, wantYaw),
				MathHelper.lerp(PINGPONG_LERP, this.pitch, wantPitch));
	}

	@Unique
	private static Vec3d pingpong$nearestBall(MinecraftClient client, Entity focusedEntity) {
		List<PingPongBallEntity> balls = client.world.getEntitiesByClass(
				PingPongBallEntity.class, focusedEntity.getBoundingBox().expand(PINGPONG_TRACK_RANGE),
				ball -> true);
		PingPongBallEntity nearest = null;
		double best = Double.MAX_VALUE;
		for (PingPongBallEntity ball : balls) {
			double distance = ball.squaredDistanceTo(focusedEntity);
			if (distance < best) {
				best = distance;
				nearest = ball;
			}
		}
		return nearest == null ? null : nearest.getPos().add(0.0, 0.14, 0.0);
	}
}

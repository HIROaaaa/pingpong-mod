package com.whale.pingpong.mixin;

import com.whale.pingpong.client.PaddlePoseCache;
import com.whale.pingpong.client.PingPongAnimations;
import com.whale.pingpong.client.PingPongClient;
import com.whale.pingpong.client.PingPongClientState;
import com.whale.pingpong.item.ModItems;
import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 球拍姿态/挥拍动画的<b>渲染</b>注入。
 *
 * 两条渲染路径都要管，否则就会出现用户报的现象 ——「别的视角看不出来球拍变动」：
 * 1. {@code renderFirstPersonItem}：第一人称手持物，用本地实时状态（最跟手）；
 * 2. {@code renderItem(LivingEntity, ...)}：第三人称/其他玩家，用服务端广播来的姿态缓存。
 *
 * 关键：这些矩阵只作用在手持物渲染上，玩家的摄像机（视角）一点都不会动。
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {

	@Unique
	private boolean pingpong$firstPersonPushed;

	@Unique
	private boolean pingpong$thirdPersonPushed;

	// ------------------------------------------------------------------
	// 第一人称
	// ------------------------------------------------------------------

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
		this.pingpong$firstPersonPushed = true;

		// 本地实时状态：滚轮/按键的回馈必须是零延迟的
		//
		// 【2026-09-23 补：蓄力期间也要有动作】用户反馈「蓄力的时候第一时间没有动作，
		// 松手开始打球的时候球拍才呈现动作」。真因是 startSwing() 只在松手时调用。
		// 现在三分支：挥拍中走三段式；**蓄力中走引拍**（按住越久拉得越开）；都没有才静止。
		float progress;
		if (PingPongClientState.isSwinging()) {
			progress = PingPongClientState.swingProgress();
		} else if (PingPongClientState.isCharging()) {
			progress = PingPongAnimations.chargeWindupProgress(PingPongClientState.chargeRatio());
		} else {
			progress = swingFallback(swingProgress);
		}
		// 动作按「这一拍要打什么」选：**优先用实时按键判断**（零延迟），
		// 没按任何键时才回退到上一拍的类型。
		// 【2026-09-23】原来只用 previewStroke()，它依赖只在 tick 里更新的 attackKeyWasDown，
		// 于是换手型（正↔反）或换击球种类（削→拉）后的头几帧会播出旧动作。
		StrokeType stroke = PingPongClient.liveStroke(MinecraftClient.getInstance());
		PingPongAnimations.apply(matrices,
				PingPongClientState.tilt(), PingPongClientState.sideTilt(),
				progress, PingPongClientState.hand(),
				stroke != null ? stroke : PingPongClientState.lastStroke(),
				pingpong$isInTable(player));
	}

	/**
	 * 是否站在台内（需求 19）。用**眼高**做判据：球台台面世界高度 0.76，站在台边时眼高约 1.38；
	 * 这个高度区间既排除"站在地上远处"（眼高 1.62 + 脚下无台）也排除"站在台面上"（>1.8）。
	 * 比每帧查方块便宜，而且第一/第三人称都只需要一个近似值。
	 */
	@Unique
	private static boolean pingpong$isInTable(AbstractClientPlayerEntity player) {
		double eyeY = player.getEyePos().y;
		return eyeY > 1.25 && eyeY < 1.62;
	}

	@Inject(method = "renderFirstPersonItem", at = @At("RETURN"))
	private void pingpong$afterRenderFirstPersonItem(AbstractClientPlayerEntity player, float tickDelta,
													 float pitch, Hand hand, float swingProgress, ItemStack item,
													 float equipProgress, MatrixStack matrices,
													 VertexConsumerProvider vertexConsumers, int light,
													 CallbackInfo ci) {
		if (this.pingpong$firstPersonPushed) {
			matrices.pop();
			this.pingpong$firstPersonPushed = false;
		}
	}

	// ------------------------------------------------------------------
	// 第三人称 / 其他玩家
	// ------------------------------------------------------------------

	@Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
			+ "Lnet/minecraft/client/render/model/json/ModelTransformationMode;Z"
			+ "Lnet/minecraft/client/util/math/MatrixStack;"
			+ "Lnet/minecraft/client/render/VertexConsumerProvider;I)V", at = @At("HEAD"))
	private void pingpong$beforeThirdPersonItem(LivingEntity entity, ItemStack item,
												net.minecraft.client.render.model.json.ModelTransformationMode mode,
												boolean leftHanded, MatrixStack matrices,
												VertexConsumerProvider vertexConsumers, int light,
												CallbackInfo ci) {
		if (!item.isOf(ModItems.PINGPONG_PADDLE) || !pingpong$shouldApplyThirdPerson(entity, leftHanded, mode)) {
			return;
		}

		matrices.push();
		this.pingpong$thirdPersonPushed = true;

		double tilt;
		double sideTilt;
		PlayerHand hand;
		float progress;
		StrokeType stroke;

		ClientPlayerEntity self = MinecraftClient.getInstance().player;
		if (entity == self) {
			// 自己：本地状态最跟手
			tilt = PingPongClientState.tilt();
			sideTilt = PingPongClientState.sideTilt();
			hand = PingPongClientState.hand();
			// 与第一人称一致：挥拍中播三段式、蓄力中播引拍（否则蓄力时看不到任何动作）
			if (PingPongClientState.isSwinging()) {
				progress = PingPongClientState.swingProgress();
			} else if (PingPongClientState.isCharging()) {
				progress = PingPongAnimations.chargeWindupProgress(PingPongClientState.chargeRatio());
			} else {
				progress = 0.0F;
			}
			// 与第一人称一致：优先实时按键判断，回退到上一拍
			// （第三人称也要零延迟，否则切手/换招后自己看到的还是旧动作）
			StrokeType live = PingPongClient.liveStroke(MinecraftClient.getInstance());
			stroke = live != null ? live : PingPongClientState.lastStroke();
		} else {
			// 别人：用服务端广播来的姿态（这是「能看出对方拍形与动作」的关键）
			PaddlePoseCache.Pose pose = PaddlePoseCache.get(entity.getUuid());
			tilt = pose.tilt;
			sideTilt = pose.sideTilt;
			hand = pose.hand;
			stroke = pose.stroke;
			progress = pose.swingTicks > 0
					? 1.0F - (float) pose.swingTicks / PingPongClientState.SWING_TICKS
					: 0.0F;
		}

		PingPongAnimations.apply(matrices, tilt, sideTilt, progress, hand, stroke,
				pingpong$isInTable(MinecraftClient.getInstance().player));
	}

	@Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
			+ "Lnet/minecraft/client/render/model/json/ModelTransformationMode;Z"
			+ "Lnet/minecraft/client/util/math/MatrixStack;"
			+ "Lnet/minecraft/client/render/VertexConsumerProvider;I)V", at = @At("RETURN"))
	private void pingpong$afterThirdPersonItem(LivingEntity entity, ItemStack item,
											   net.minecraft.client.render.model.json.ModelTransformationMode mode,
											   boolean leftHanded, MatrixStack matrices,
											   VertexConsumerProvider vertexConsumers, int light,
											   CallbackInfo ci) {
		if (this.pingpong$thirdPersonPushed) {
			matrices.pop();
			this.pingpong$thirdPersonPushed = false;
		}
	}

	/** 只有第三人称视角才在这里加变换（第一人称已由 renderFirstPersonItem 处理，避免叠加两次）。 */
	@Unique
	private static boolean pingpong$isThirdPerson() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.options != null && client.options.getPerspective() != Perspective.FIRST_PERSON;
	}

	/**
	 * 是否应该在这次 {@code renderItem} 调用上叠加球拍变换。
	 *
	 * {@code HeldItemRenderer.renderItem} 同时服务「第一人称的手持物」和「第三人称/其他玩家的手持物」，
	 * 所以这里要三条判断一起用，否则第一人称会被叠加两次旋转：
	 * 1. 第三人称物品：一律叠加；
	 * 2. 玩家自己 + 第一人称视角 + 渲染的正是主手/副手的那个物品：那是第一人称路径，跳过。
	 *    （只认「当前手持的那个栈」是有意的：玩家在第一人称下看向别人时，别人的球拍仍然要正确显示）
	 */
	@Unique
	private static boolean pingpong$shouldApplyThirdPerson(LivingEntity entity, boolean leftHanded,
														   net.minecraft.client.render.model.json.ModelTransformationMode mode) {
		if (mode != net.minecraft.client.render.model.json.ModelTransformationMode.THIRD_PERSON_LEFT_HAND
				&& mode != net.minecraft.client.render.model.json.ModelTransformationMode.THIRD_PERSON_RIGHT_HAND) {
			return true;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options == null || client.options.getPerspective() != Perspective.FIRST_PERSON) {
			return true;
		}
		if (client.player == null || entity != client.player) {
			return true;
		}
		// 第一人称下渲染自己的主/副手：交给 renderFirstPersonItem 处理
		net.minecraft.util.Hand hand = leftHanded ? net.minecraft.util.Hand.OFF_HAND : net.minecraft.util.Hand.MAIN_HAND;
		return !client.player.getStackInHand(hand).isOf(ModItems.PINGPONG_PADDLE);
	}

	/** 原版挥拍进度（0→1→0）作为兜底，自己的自定义动画结束时不至于突然僵住。 */
	@Unique
	private static float swingFallback(float swingProgress) {
		if (swingProgress <= 0.0F) {
			return 0.0F;
		}
		float sin = net.minecraft.util.math.MathHelper.sin(
				net.minecraft.util.math.MathHelper.sqrt(swingProgress) * 3.1415927F);
		return sin * 0.6F;
	}
}

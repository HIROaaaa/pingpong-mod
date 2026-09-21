package com.whale.pingpong.mixin;

import com.whale.pingpong.client.PingPongModelPose;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把乒乓球动作写进**玩家模型骨骼**（五期 M6）。
 *
 * <h2>为什么注入在 setAngles 的末尾</h2>
 * 原版每帧先用 {@code setAngles} 把走路摆动、手臂姿态算进 {@code ModelPart.pitch/yaw/roll}，
 * 我们在它算完之后**叠加**乒乓球动作 —— 于是"走路摆手"和"打球挥拍"能共存，
 * 而不是二选一（这正是"动作优化类 mod"的观感来源）。
 *
 * <h2>为什么挂在 BipedEntityModel 上</h2>
 * 1.20.1 的 {@code PlayerEntityModel} 没有覆写 {@code setAngles}（直接从 BipedEntityModel 继承），
 * 而 BipedEntityModel 不继承 EntityModel，直接继承 Object，因此这里用 {@code instanceof}
 * 把范围收窄到玩家模型 —— 僵尸、骷髅等同样用 BipedEntityModel 的实体不会被误改。
 *
 * 【签名说明】参数类型写 {@code LivingEntity} 是因为泛型 T 会被擦除成它的上界
 * （LivingEntity），方法描述符是 {@code (Lnet/minecraft/entity/LivingEntity;FFFFFF)V} ——
 * 这个签名是用 javap 从 remapped jar 里取出来的，不是猜的。
 * 编译期注解处理器会对匹配不上的目标发 warning，所以"编译没警告"就是签名对了的第一道证据。
 */
@Mixin(BipedEntityModel.class)
public class PlayerEntityModelMixin {

	/*
	 * 【为什么是 RETURN 而不是 TAIL】这里做的是**加法**（paddleArm.pitch += …）。
	 * TAIL 注入到"最后一条 return 之前"，如果目标方法中途还有提前 return（原版 setAngles
	 * 按姿势分支确实会早退），一次调用就可能命中两次 —— 于是每帧叠加、旋转值指数增长，
	 * 几秒钟后手臂会扭成一团。RETURN 注入在**每个出口之后**生效，一次调用只加一次，
	 * 与原版设的角度严格"每帧重算一次"，不会累积。
	 */
	@Inject(method = "setAngles", at = @At("RETURN"))
	private void pingpong$applyPaddlePose(LivingEntity entity, float limbAngle, float limbDistance,
										  float animationProgress, float headYaw, float headPitch,
										  CallbackInfo ci) {
		if (!(entity instanceof AbstractClientPlayerEntity)) {
			return;
		}
		Object self = this;
		if (!(self instanceof PlayerEntityModel)) {
			return;   // 其他用 BipedEntityModel 的实体（僵尸/骷髅…）保持原版
		}

		// 【为什么要 (Object) 中转】Mixin 类在源码里与目标类没有继承关系，
		// 直接 (BipedEntityModel) this 编译不过（javac 认为不可能）。运行时注入后 this 就是模型本身，
		// 所以先转 Object 再转目标类型 —— 这是 mixin 里访问自身实例的常规写法。
		BipedEntityModel<?> model = (BipedEntityModel<?>) (Object) this;
		PingPongModelPose.update((AbstractClientPlayerEntity) entity);
		// hat 必须一起传：原版把 hat（头发/帽层）的旋转**复制自 head**，
		// 我们改 head 之后不带上 hat，就会出现"头和头发分离"（用户实测反馈）。
		PingPongModelPose.apply(
				model.rightArm, model.leftArm, model.body, model.head, model.hat,
				false,   // 球拍永远在主手（副手拿球是"发球"用的），需要时再按 hand 交换
				PingPongModelPose.easeFor(currentSwingProgress(entity)));
	}

	/** 当前挥拍进度：决定这一帧用多快的缓动（引拍慢 / 触球快 / 随挥缓）。 */
	private static float currentSwingProgress(LivingEntity entity) {
		if (entity == net.minecraft.client.MinecraftClient.getInstance().player) {
			return com.whale.pingpong.client.PingPongClientState.swingProgress();
		}
		return com.whale.pingpong.client.PaddlePoseCache.swingProgressOf(
				com.whale.pingpong.client.PaddlePoseCache.get(entity.getUuid()));
	}
}

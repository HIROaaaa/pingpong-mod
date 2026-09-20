package com.whale.pingpong.entity;

import com.whale.pingpong.net.ModNetworking;
import com.whale.pingpong.physics.PingPongPhysics;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * 乒乓球实体。
 *
 * 【服务端权威】飞行、马格努斯偏转、弹跳、自旋衰减全部只在服务端 tick 里算，
 * 客户端不跑物理，只接收 / 插值，所以多人联机时所有人看到的球完全一致。
 *
 * 同步策略：
 * - 位置 / 朝向：原版实体追踪自动同步（trackedUpdateRate = 1）；
 * - 自旋：TrackedData<Float> ×3 自动同步（客户端用来做旋转特效与渲染）；
 * - 速度：击球瞬间用自定义 S2C 包补一发（见 ModNetworking#broadcastBallMotion）。
 */
public class PingPongBallEntity extends Entity {

	// ---- 同步数据：自旋（客户端渲染用） ----
	private static final TrackedData<Float> SPIN_X =
			DataTracker.registerData(PingPongBallEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Float> SPIN_Y =
			DataTracker.registerData(PingPongBallEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Float> SPIN_Z =
			DataTracker.registerData(PingPongBallEntity.class, TrackedDataHandlerRegistry.FLOAT);

	/** 击球后多少 tick 内不允许再次被击打（防止一拍多段连击把球卡在身上） */
	public static final int HIT_COOLDOWN_TICKS = 3;
	/** 最长存活时间（tick）：30 秒后自动消失 */
	public static final int MAX_LIFE_TICKS = 600;
	/** 静止多久后自动消失（tick） */
	public static final int MAX_REST_TICKS = 200;
	/** 单步移动最大长度：把一 tick 的位移切碎，防止高速穿墙 */
	private static final double MAX_STEP = 0.2;

	// ---- 击球参数（想改手感就动这几个数） ----
	/** 基础出球速度（格/tick） */
	public static final double BASE_HIT_SPEED = 0.85;
	/** 借力系数：把来球速度的一部分加回去，来球越快回球越快 */
	public static final double HIT_SPEED_INHERIT = 0.30;
	public static final double MIN_HIT_SPEED = 0.45;
	public static final double MAX_HIT_SPEED = 1.80;
	/** 拍面后仰 / 前倾的最大角度（度）：同时决定出球仰角和自旋强度 */
	public static final double MAX_TILT_DEGREES = 22.0;
	/** 最大自旋（rad/tick） */
	public static final double MAX_SPIN = PingPongPhysics.MAX_SPIN;

	// ---- 服务端状态 ----
	/** 自旋向量 ω（rad/tick）。服务端权威值，客户端请用 getSpin() 读同步值。 */
	private double spinX;
	private double spinY;
	private double spinZ;
	private int lifeTicks;
	private int restTicks;
	private int hitCooldown;
	private boolean spinDirty = true;
	private UUID ownerUuid;

	public PingPongBallEntity(EntityType<? extends PingPongBallEntity> entityType, World world) {
		super(entityType, world);
		// 重力由我们自己按 PingPongPhysics.GRAVITY 施加，这里只做标记
		this.setNoGravity(true);
	}

	// ==================================================================
	// 生成
	// ==================================================================

	/** 在玩家身前生成一颗球，速度 = 视线方向 * speed + 竖直 upward。 */
	public static PingPongBallEntity spawn(World world, PlayerEntity thrower, double speed, double upward) {
		PingPongBallEntity ball = new PingPongBallEntity(ModEntities.PINGPONG_BALL, world);

		Vec3d look = thrower.getRotationVec(1.0F);
		Vec3d pos = new Vec3d(thrower.getX(), thrower.getEyeY() - 0.25, thrower.getZ())
				.add(look.multiply(0.7));

		ball.refreshPositionAndAngles(pos.x, pos.y, pos.z, thrower.getYaw(), thrower.getPitch());
		ball.setOwner(thrower);
		ball.setVelocity(look.multiply(speed).add(0.0, upward, 0.0));
		ball.setSpin(Vec3d.ZERO);

		world.spawnEntity(ball);
		return ball;
	}

	// ==================================================================
	// 数据同步
	// ==================================================================

	/**
	 * 生成包：服务端追踪器（EntityTrackerEntry）会调用它把实体推给客户端。
	 * 球没有额外的生成数据（自旋走 TrackedData），用原版通用生成包即可。
	 */
	@Override
	public net.minecraft.network.packet.Packet<net.minecraft.network.listener.ClientPlayPacketListener> createSpawnPacket() {
		return new net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket(this);
	}

	@Override
	protected void initDataTracker() {
		this.dataTracker.startTracking(SPIN_X, 0.0F);
		this.dataTracker.startTracking(SPIN_Y, 0.0F);
		this.dataTracker.startTracking(SPIN_Z, 0.0F);
	}

	/** 读取自旋：客户端读同步值，服务端读权威值。 */
	public Vec3d getSpin() {
		if (this.getWorld().isClient) {
			return new Vec3d(this.dataTracker.get(SPIN_X), this.dataTracker.get(SPIN_Y), this.dataTracker.get(SPIN_Z));
		}
		return new Vec3d(this.spinX, this.spinY, this.spinZ);
	}

	public void setSpin(Vec3d spin) {
		Vec3d clamped = PingPongPhysics.clampSpin(spin);
		this.spinX = clamped.x;
		this.spinY = clamped.y;
		this.spinZ = clamped.z;
		this.spinDirty = true;
		this.pushSpin();
	}

	/** 客户端收到 S2C 速度包时顺带覆盖一次自旋显示值。 */
	public void applyClientSpin(Vec3d spin) {
		this.spinX = spin.x;
		this.spinY = spin.y;
		this.spinZ = spin.z;
		this.spinDirty = true;
		this.pushSpin();
	}

	/** 把服务端自旋写进 TrackedData（变化时才写，省带宽）。 */
	private void pushSpin() {
		if (!this.spinDirty) {
			return;
		}
		this.dataTracker.set(SPIN_X, (float) this.spinX);
		this.dataTracker.set(SPIN_Y, (float) this.spinY);
		this.dataTracker.set(SPIN_Z, (float) this.spinZ);
		this.spinDirty = false;
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putDouble("SpinX", this.spinX);
		nbt.putDouble("SpinY", this.spinY);
		nbt.putDouble("SpinZ", this.spinZ);
		nbt.putInt("LifeTicks", this.lifeTicks);
		if (this.ownerUuid != null) {
			nbt.putUuid("Owner", this.ownerUuid);
		}
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		this.spinX = nbt.getDouble("SpinX");
		this.spinY = nbt.getDouble("SpinY");
		this.spinZ = nbt.getDouble("SpinZ");
		this.lifeTicks = nbt.getInt("LifeTicks");
		if (nbt.containsUuid("Owner")) {
			this.ownerUuid = nbt.getUuid("Owner");
		}
		this.spinDirty = true;
	}

	public UUID getOwnerUuid() {
		return this.ownerUuid;
	}

	public void setOwner(PlayerEntity player) {
		this.ownerUuid = player.getUuid();
	}

	// ==================================================================
	// tick
	// ==================================================================

	@Override
	public void tick() {
		super.tick();

		if (this.getWorld().isClient) {
			this.tickClient();
		} else {
			this.tickServer();
		}
	}

	/** 服务端：唯一的物理权威。 */
	private void tickServer() {
		if (this.hitCooldown > 0) {
			this.hitCooldown--;
		}
		this.lifeTicks++;

		// --- 1. 自旋衰减 ---
		Vec3d spin = PingPongPhysics.decaySpin(this.getSpin());
		this.spinX = spin.x;
		this.spinY = spin.y;
		this.spinZ = spin.z;
		this.spinDirty = true;

		// --- 2. 加速度 = 重力 + 马格努斯 ---
		Vec3d acceleration = new Vec3d(0.0, -PingPongPhysics.GRAVITY, 0.0)
				.add(PingPongPhysics.magnusAcceleration(this.getVelocity(), spin));

		// --- 3. 速度积分 + 空气阻力 ---
		Vec3d velocity = PingPongPhysics.applyAirDrag(this.getVelocity().add(acceleration));
		this.setVelocity(velocity);

		// --- 4. 分步移动 + 碰撞反弹 ---
		this.stepMovement();

		this.updateRotationFromVelocity();
		this.pushSpin();

		// --- 5. 静止 / 生命周期判定 ---
		if (this.isOnGround() && PingPongPhysics.isNearlyStill(this.getVelocity())) {
			this.restTicks++;
			// 贴地滚动时把残余速度抹掉，免得一直抖
			this.setVelocity(this.getVelocity().multiply(0.6, 0.0, 0.6));
			this.setSpin(this.getSpin().multiply(0.9));
		} else {
			this.restTicks = 0;
		}

		if (this.lifeTicks > MAX_LIFE_TICKS
				|| this.restTicks > MAX_REST_TICKS
				|| this.getY() < this.getWorld().getBottomY() - 16) {
			this.discard();
		}
	}

	/** 客户端：不跑物理，只做一点点视觉表现。 */
	private void tickClient() {
		Vec3d spin = this.getSpin();
		// 自旋够快时撒点粒子，让「转」看得见
		if (spin.lengthSquared() > 1.0 && this.age % 2 == 0) {
			this.getWorld().addParticle(ParticleTypes.CRIT,
					this.getX(), this.getY() + 0.14, this.getZ(),
					0.0, 0.0, 0.0);
		}
	}

	/**
	 * 分步移动：把这一 tick 的位移切成若干小步，每步做一次碰撞检测。
	 * 撞到东西就用 PingPongPhysics.bounce() 算反射速度与自旋变化 —— 这就是
	 * 「自旋影响弹跳方向」的地方。
	 */
	private void stepMovement() {
		Vec3d velocity = this.getVelocity();
		double distance = velocity.length();
		if (distance < 1.0e-6) {
			return;
		}

		int steps = MathHelper.clamp((int) Math.ceil(distance / MAX_STEP), 1, 32);
		Vec3d step = velocity.multiply(1.0 / steps);

		for (int i = 0; i < steps; i++) {
			Vec3d before = this.getPos();
			this.move(MovementType.SELF, step);
			Vec3d actual = this.getPos().subtract(before);
			Vec3d residual = step.subtract(actual);

			if (residual.lengthSquared() > 1.0e-8) {
				Vec3d normal = this.dominantCollisionNormal(step, residual);
				if (normal != null) {
					PingPongPhysics.BounceResult result =
							PingPongPhysics.bounce(this.getVelocity(), this.getSpin(), normal);

					this.setVelocity(result.velocity());
					this.setSpin(result.spin());

					// 沿法线推出去一点，避免下一步还在同一个面上反复触发
					this.setPosition(this.getPos().add(normal.multiply(1.0e-3)));
					this.playBounceEffects(normal);

					// 后续步进按新速度重新分配
					step = this.getVelocity().multiply(1.0 / steps);
					if (this.getVelocity().lengthSquared() < 1.0e-6) {
						break;
					}
				}
			}
		}
	}

	/**
	 * 从「期望位移 - 实际位移」的残差里推断碰撞法线。
	 * 原版 Entity#move 只给出「某个轴被挡住了」，残差最大的那个轴就是主要碰撞面。
	 */
	private Vec3d dominantCollisionNormal(Vec3d intent, Vec3d residual) {
		double rx = intent.x != 0.0 ? Math.abs(residual.x) : 0.0;
		double ry = intent.y != 0.0 ? Math.abs(residual.y) : 0.0;
		double rz = intent.z != 0.0 ? Math.abs(residual.z) : 0.0;

		if (rx <= 1.0e-9 && ry <= 1.0e-9 && rz <= 1.0e-9) {
			return null;
		}

		if (rx >= ry && rx >= rz) {
			return new Vec3d(-Math.signum(intent.x), 0.0, 0.0);
		}
		if (ry >= rx && ry >= rz) {
			return new Vec3d(0.0, -Math.signum(intent.y), 0.0);
		}
		return new Vec3d(0.0, 0.0, -Math.signum(intent.z));
	}

	/** 弹跳音效 + 粒子：撞击越猛，声音越响、音调越低。 */
	private void playBounceEffects(Vec3d normal) {
		double impact = Math.abs(this.getVelocity().dotProduct(normal));
		if (impact < 0.02) {
			return;
		}
		float volume = (float) MathHelper.clamp(0.15 + impact * 0.9, 0.1, 1.0);
		float pitch = (float) MathHelper.clamp(1.9 - impact * 0.6, 0.8, 2.0);

		this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(),
				SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.BLOCKS, volume, pitch);

		if (this.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld && impact > 0.25) {
			serverWorld.spawnParticles(ParticleTypes.CRIT,
					this.getX(), this.getY() + 0.14, this.getZ(),
					2, 0.05, 0.05, 0.05, 0.01);
		}
	}

	/** 让球「头朝着飞行方向」（原版会用这个 yaw/pitch 做位置包同步）。 */
	private void updateRotationFromVelocity() {
		Vec3d velocity = this.getVelocity();
		double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		if (horizontal > 1.0e-4 || Math.abs(velocity.y) > 1.0e-4) {
			this.setYaw((float) (MathHelper.atan2(velocity.x, velocity.z) * 57.29577951308232));
			this.setPitch((float) (-MathHelper.atan2(velocity.y, horizontal) * 57.29577951308232));
		}
	}

	// ==================================================================
	// 击球（由服务端网络包调用）
	// ==================================================================

	/**
	 * 被球拍击中。
	 *
	 * @param player   击球玩家
	 * @param tilt     拍面俯仰：> 0 = 后仰（滚轮上）→ 上旋球；< 0 = 前倾（滚轮下）→ 下旋球
	 * @param sideTilt 拍面侧偏：Alt + 滚轮，产生侧旋（香蕉球）
	 * @return 是否真的打到
	 */
	public boolean hitByPaddle(ServerPlayerEntity player, double tilt, double sideTilt) {
		if (this.hitCooldown > 0) {
			return false;
		}

		double t = MathHelper.clamp(tilt, -1.0, 1.0);
		double s = MathHelper.clamp(sideTilt, -1.0, 1.0);

		// --- 1. 出球方向：玩家视线 + 拍面俯仰/侧偏（视角本身不动，只是球拍角度变了） ---
		double pitchRad = Math.toRadians(player.getPitch() - t * MAX_TILT_DEGREES);
		double yawRad = Math.toRadians(player.getYaw() + s * MAX_TILT_DEGREES);
		Vec3d direction = new Vec3d(
				-Math.sin(yawRad) * Math.cos(pitchRad),
				-Math.sin(pitchRad),
				Math.cos(yawRad) * Math.cos(pitchRad)
		).normalize();

		// --- 2. 出球速度：基础速度 + 借用来球动能 ---
		double incoming = this.getVelocity().length();
		double speed = MathHelper.clamp(BASE_HIT_SPEED + HIT_SPEED_INHERIT * incoming,
				MIN_HIT_SPEED, MAX_HIT_SPEED);
		this.setVelocity(direction.multiply(speed));

		// --- 3. 自旋 ---
		// 上旋轴 = up × 水平出球方向（右手定则：球顶部向前转）
		Vec3d flat = new Vec3d(direction.x, 0.0, direction.z);
		flat = flat.lengthSquared() < 1.0e-6 ? new Vec3d(0.0, 0.0, 1.0) : flat.normalize();
		Vec3d topspinAxis = new Vec3d(0.0, 1.0, 0.0).crossProduct(flat).normalize();
		Vec3d spin = topspinAxis.multiply(t * MAX_SPIN)
				.add(new Vec3d(0.0, 1.0, 0.0).multiply(s * MAX_SPIN));
		this.setSpin(spin);

		// --- 4. 收尾：冷却、位置微调、同步、特效 ---
		this.hitCooldown = HIT_COOLDOWN_TICKS;
		this.restTicks = 0;
		this.ownerUuid = player.getUuid();

		// 原版 Entity#move 会把玩家碰撞箱当成墙，贴身球会被自己挡住，
		// 所以离身体太近的球直接「拨」到拍面位置再飞出去。
		if (this.getPos().squaredDistanceTo(player.getEyePos()) < 1.0) {
			Vec3d paddlePos = player.getEyePos().add(player.getRotationVec(1.0F).multiply(1.1));
			this.refreshPositionAndAngles(paddlePos.x, paddlePos.y, paddlePos.z, this.getYaw(), this.getPitch());
		} else {
			this.setPosition(this.getPos().add(direction.multiply(0.2)));
		}

		ModNetworking.broadcastBallMotion(this);

		this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(),
				SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.6F, 1.6F);

		if (this.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
			serverWorld.spawnParticles(ParticleTypes.CRIT,
					this.getX(), this.getY() + 0.14, this.getZ(),
					6, 0.08, 0.08, 0.08, 0.05);
		}
		return true;
	}

	// ==================================================================
	// 杂项
	// ==================================================================

	/** 球不参与原版实体推挤，否则会被玩家顶着走。 */
	@Override
	public boolean isCollidable() {
		return false;
	}

	@Override
	public boolean canHit() {
		return false;
	}

	/** 服务端 tick 时即使附近没有玩家也不该被卸载（放在已加载区块里就正常 tick）。 */
	@Override
	public boolean shouldRender(double distance) {
		return distance < 128.0 * 128.0;
	}
}

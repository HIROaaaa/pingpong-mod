package com.whale.pingpong.entity;

import com.whale.pingpong.PingPongMod;
import com.whale.pingpong.block.ModBlocks;
import com.whale.pingpong.block.PingPongTableBlock;
import com.whale.pingpong.net.ModNetworking;
import com.whale.pingpong.physics.PingPongPhysics;
import net.minecraft.block.BlockState;
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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
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
 * 【速度为什么自己存一份】原版 {@code Entity#move} 在碰撞时会把实体速度清零
 * （1.20.1 字节码里能看到两处 setVelocity 调用），于是「落地瞬间的真实速度」被抹掉，
 * 反弹就永远算不出来 —— 实测就是球落地后一次都不弹。
 * 所以物理量存在 {@link #physicsVelocity} 里，原版速度只用于追踪同步与渲染。
 *
 * 同步策略：
 * - 位置 / 朝向：原版实体追踪自动同步（trackedUpdateRate = 1）；
 * - 自旋：TrackedData&lt;Float&gt;×3 自动同步（客户端用来做旋转特效与渲染）；
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

	/**
	 * 调试开关：设置环境变量 PINGPONG_TRACE=1 后，球每 tick 会把位置/速度打进日志，
	 * 用来核对弹跳轨迹（外部采样受服务器命令队列影响，根本对不齐 tick，这个才准）。
	 */
	private static final boolean TRACE = System.getenv("PINGPONG_TRACE") != null;
	private static final int TRACE_TICKS = 260;

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
	/**
	 * 出球的最小仰角（度）。
	 * 平射球的竖直速度太小：0.85 格/tick 平射落地时 v_y ≈ -0.2，
	 * 就算反弹也弹不到 0.4 格，肉眼看就是「贴地滚」——用户报的「不会弹跳」有一半是它。
	 */
	public static final double MIN_LAUNCH_ELEVATION_DEGREES = 6.0;
	/** 自旋对球速的耦合：上旋更快、下旋更慢（真实乒乓球也是这样） */
	public static final double SPIN_SPEED_COUPLING = 0.15;
	/**
	 * 侧旋轴相对竖直方向的倾斜角（度）。
	 * 【关键】纯竖直轴的自旋，在水平地面上的接触点速度 ω×r 恒为 0 ——
	 * 数学上就不可能侧拐。倾斜后：飞行横向马格努斯保留 cos，落地侧向搓动来自 sin。
	 */
	public static final double SIDE_AXIS_TILT_DEGREES = 40.0;
	private static final double SIDE_AXIS_TILT = Math.toRadians(SIDE_AXIS_TILT_DEGREES);
	/** 最大自旋（rad/tick） */
	public static final double MAX_SPIN = PingPongPhysics.MAX_SPIN;
	/**
	 * 贴地时自旋的额外每 tick 衰减。
	 * 【为什么必须有】地面接触会把自旋不断转成切向冲量：自旋不消，球就会被自己的自旋
	 * 反复「拧」着跑（实测侧旋球在地面滚出 7 格以上横向位移，完全失真）。
	 * 真实球在台面上滚两下自旋就没了，0.85/tick 约 15 tick 衰减到 9%。
	 */
	public static final double GROUND_SPIN_DECAY = 0.85;
	/** 贴地时的滚动阻力：水平速度每 tick 保留比例（乒乓球的滚动阻力很大，滚不远） */
	public static final double GROUND_ROLL_FRICTION = 0.96;

	// ---- 服务端状态 ----
	/**
	 * 自己维护的权威速度。原版 Entity#move 会在碰撞时清零实体速度，物理不能依赖它。
	 */
	private Vec3d physicsVelocity = Vec3d.ZERO;
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
	// 物理速度（自己维护，不被原版 move 破坏）
	// ==================================================================

	public Vec3d getPhysicsVelocity() {
		return this.physicsVelocity;
	}

	private void setPhysicsVelocity(Vec3d velocity) {
		this.physicsVelocity = velocity;
		// 同步给原版，用于实体追踪包与渲染
		this.setVelocity(velocity);
	}

	// ==================================================================
	// 生成
	// ==================================================================

	/**
	 * 把球【垂直上抛】—— 用户要的发球方式：球自己上抛、落下，玩家调好拍形去打。
	 *
	 * @param verticalSpeed   竖直初速度（格/tick）。0.45 约上升 3.4 格、滞空 1.5 秒
	 * @param horizontalDrift 水平飘移比例，只带一点点视线方向分量，免得球正好落回自己头上
	 */
	public static PingPongBallEntity toss(World world, PlayerEntity thrower, double verticalSpeed, double horizontalDrift) {
		PingPongBallEntity ball = new PingPongBallEntity(ModEntities.PINGPONG_BALL, world);

		Vec3d look = thrower.getRotationVec(1.0F);
		Vec3d flat = new Vec3d(look.x, 0.0, look.z);
		flat = flat.lengthSquared() < 1.0e-6 ? new Vec3d(0.0, 0.0, 1.0) : flat.normalize();

		// 生成点放在身前 0.55 格、眼高：正好在玩家碰撞箱外面。
		// 放在身体里的话，原版 Entity#move 会把玩家当墙，球根本抛不起来。
		Vec3d pos = thrower.getEyePos().add(flat.multiply(0.55));

		ball.refreshPositionAndAngles(pos.x, pos.y, pos.z, thrower.getYaw(), thrower.getPitch());
		ball.setOwner(thrower);
		ball.setPhysicsVelocity(new Vec3d(flat.x * horizontalDrift, verticalSpeed, flat.z * horizontalDrift));
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
		// 从存档恢复时，物理速度取原版存下来的 Motion
		this.physicsVelocity = this.getVelocity();
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
				.add(PingPongPhysics.magnusAcceleration(this.physicsVelocity, spin));

		// --- 3. 速度积分 + 空气阻力 ---
		this.setPhysicsVelocity(PingPongPhysics.applyAirDrag(this.physicsVelocity.add(acceleration)));

		// --- 4. 分步移动 + 碰撞反弹 ---
		this.stepMovement();

		// --- 4.5 贴地耗散：地面摩擦快速吃掉自旋，滚动也有阻力 ---
		// （不做这一步的话，球会靠自旋在地面反复被搓着乱跑，实测能横移 7 格以上）
		if (this.isOnGround()) {
			this.spinX *= GROUND_SPIN_DECAY;
			this.spinY *= GROUND_SPIN_DECAY;
			this.spinZ *= GROUND_SPIN_DECAY;
			this.spinDirty = true;
			Vec3d rolling = this.physicsVelocity;
			this.setPhysicsVelocity(new Vec3d(
					rolling.x * GROUND_ROLL_FRICTION, rolling.y, rolling.z * GROUND_ROLL_FRICTION));
		}

		this.updateRotationFromVelocity();
		this.pushSpin();

		// --- 5. 静止 / 生命周期判定 ---
		if (this.isOnGround() && PingPongPhysics.isNearlyStill(this.physicsVelocity)) {
			this.restTicks++;
			// 贴地滚动时把残余速度抹掉，免得一直抖
			Vec3d rolling = this.physicsVelocity;
			this.setPhysicsVelocity(new Vec3d(rolling.x * 0.6, 0.0, rolling.z * 0.6));
			this.setSpin(this.getSpin().multiply(0.9));
		} else {
			this.restTicks = 0;
		}

		if (TRACE && this.lifeTicks <= TRACE_TICKS) {
			PingPongMod.LOGGER.info("[trace] t=" + this.lifeTicks
					+ " y=" + this.getY()
					+ " vy=" + this.physicsVelocity.y
					+ " vx=" + this.physicsVelocity.x
					+ " ground=" + this.isOnGround()
					+ " rest=" + this.restTicks);
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
	 * 撞到东西就用 PingPongPhysics.bounce() 算反射速度与自旋变化 ——
	 * 这就是「自旋影响弹跳方向」的地方。
	 *
	 * 碰撞用的是 {@link #physicsVelocity}（自己维护的那份），不是原版速度 ——
	 * 原版 Entity#move 会在碰撞时清零实体速度，用它就永远算不出反弹。
	 */
	private void stepMovement() {
		double distance = this.physicsVelocity.length();
		if (distance < 1.0e-6) {
			return;
		}

		int steps = MathHelper.clamp((int) Math.ceil(distance / MAX_STEP), 1, 32);
		Vec3d step = this.physicsVelocity.multiply(1.0 / steps);

		for (int i = 0; i < steps; i++) {
			Vec3d preMoveVelocity = this.physicsVelocity;

			Vec3d before = this.getPos();
			this.move(MovementType.SELF, step);
			Vec3d actual = this.getPos().subtract(before);
			Vec3d residual = step.subtract(actual);

			if (residual.lengthSquared() > 1.0e-8) {
				Vec3d normal = this.dominantCollisionNormal(step, residual);
				if (normal != null) {
					PingPongPhysics.Surface surface = this.surfaceFor(normal);
					PingPongPhysics.BounceResult result =
							PingPongPhysics.bounce(preMoveVelocity, this.getSpin(), normal, surface);

					double impactSpeed = Math.abs(preMoveVelocity.dotProduct(normal));

					this.setPhysicsVelocity(result.velocity());
					this.setSpin(result.spin());

					// 沿法线推出去一点，避免下一步还在同一个面上反复触发
					this.setPosition(this.getPos().add(normal.multiply(1.0e-3)));
					this.playBounceEffects(normal, impactSpeed);

					// 后续步进按新速度重新分配
					step = this.physicsVelocity.multiply(1.0 / steps);
					if (this.physicsVelocity.lengthSquared() < 1.0e-6) {
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

	/**
	 * 判断撞到的是什么材质的表面：球台台面 / 球台侧面 / 球网 / 普通地面。
	 * 做法：沿法线反方向退一点点取那个方块，再看撞击点在同一方块内的高度来区分台面与网。
	 */
	private PingPongPhysics.Surface surfaceFor(Vec3d normal) {
		Vec3d probe = this.getPos().add(normal.multiply(-0.05));
		BlockPos surfacePos = BlockPos.ofFloored(probe.x, probe.y, probe.z);
		BlockState state = this.getWorld().getBlockState(surfacePos);

		if (!state.isOf(ModBlocks.PINGPONG_TABLE)) {
			return PingPongPhysics.Surface.GROUND;
		}
		if (normal.y > 0.5) {
			return PingPongPhysics.Surface.TABLE;          // 台面朝上：硬，弹得高
		}
		// 球台方块里，网片占据 y 0.75~0.90 这一段薄板
		double localY = this.getY() - surfacePos.getY();
		return localY > PingPongTableBlock.TOP_Y - 0.05
				? PingPongPhysics.Surface.NET
				: PingPongPhysics.Surface.TABLE_SIDE;
	}

	/** 弹跳音效 + 粒子：撞击越猛，声音越响、音调越低。 */
	private void playBounceEffects(Vec3d normal, double impactSpeed) {
		if (impactSpeed < 0.02) {
			return;
		}
		float volume = (float) MathHelper.clamp(0.15 + impactSpeed * 0.9, 0.1, 1.0);
		float pitch = (float) MathHelper.clamp(1.9 - impactSpeed * 0.6, 0.8, 2.0);

		this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(),
				SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.BLOCKS, volume, pitch);

		if (this.getWorld() instanceof ServerWorld serverWorld && impactSpeed > 0.25) {
			serverWorld.spawnParticles(ParticleTypes.CRIT,
					this.getX(), this.getY() + 0.14, this.getZ(),
					2, 0.05, 0.05, 0.05, 0.01);
		}
	}

	/** 让球「头朝着飞行方向」（原版会用这个 yaw/pitch 做位置包同步）。 */
	private void updateRotationFromVelocity() {
		Vec3d velocity = this.physicsVelocity;
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
	 * @param tilt     拍面俯仰：&gt; 0 = 后仰（滚轮上）→ 上旋球；&lt; 0 = 前倾（滚轮下）→ 下旋球
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
		double pitchDegrees = player.getPitch() - t * MAX_TILT_DEGREES;
		// 至少抬 6°：平射球落地时竖直速度太小，反弹高度不足半格，肉眼看就是「贴地滚」
		pitchDegrees = Math.min(pitchDegrees, -MIN_LAUNCH_ELEVATION_DEGREES);
		double pitchRad = Math.toRadians(pitchDegrees);
		double yawRad = Math.toRadians(player.getYaw() + s * MAX_TILT_DEGREES);
		Vec3d direction = new Vec3d(
				-Math.sin(yawRad) * Math.cos(pitchRad),
				-Math.sin(pitchRad),
				Math.cos(yawRad) * Math.cos(pitchRad)
		).normalize();

		// --- 2. 出球速度：基础速度 + 借用来球动能 + 自旋耦合（上旋更快、下旋更慢） ---
		double incoming = this.physicsVelocity.length();
		double spinCoupling = 1.0 + t * SPIN_SPEED_COUPLING;
		double speed = MathHelper.clamp(
				(BASE_HIT_SPEED + HIT_SPEED_INHERIT * incoming) * spinCoupling,
				MIN_HIT_SPEED, MAX_HIT_SPEED);
		this.setPhysicsVelocity(direction.multiply(speed));

		// --- 3. 自旋 ---
		Vec3d flat = new Vec3d(direction.x, 0.0, direction.z);
		flat = flat.lengthSquared() < 1.0e-6 ? new Vec3d(0.0, 0.0, 1.0) : flat.normalize();

		// 上旋轴 = up × 水平出球方向（右手定则：球顶部向前转，马格努斯力向下 → 弧线下扎）
		Vec3d topspinAxis = new Vec3d(0.0, 1.0, 0.0).crossProduct(flat).normalize();
		// 侧旋轴朝行进方向倾斜 40°：纯竖直轴的自旋在水平面上接触点速度恒为 0，永远不会侧拐
		Vec3d sideAxis = new Vec3d(0.0, 1.0, 0.0).multiply(Math.cos(SIDE_AXIS_TILT))
				.add(flat.multiply(Math.sin(SIDE_AXIS_TILT)))
				.normalize();

		Vec3d spin = topspinAxis.multiply(t * MAX_SPIN).add(sideAxis.multiply(s * MAX_SPIN));
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

		if (this.getWorld() instanceof ServerWorld serverWorld) {
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

	/** 128 格内都渲染（球要飞得远）。 */
	@Override
	public boolean shouldRender(double distance) {
		return distance < 128.0 * 128.0;
	}
}

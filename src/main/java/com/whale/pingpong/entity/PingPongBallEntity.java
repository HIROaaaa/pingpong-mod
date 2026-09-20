package com.whale.pingpong.entity;

import com.whale.pingpong.PingPongMod;
import com.whale.pingpong.block.ModBlocks;
import com.whale.pingpong.block.PingPongTableBlock;
import com.whale.pingpong.net.ModNetworking;
import com.whale.pingpong.physics.PingPongPhysics;
import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.TableGeometry;
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

	// ---- 击球参数（想改手感就动这几个数，改完必须跑 tools/calibrate_hit_speed.js 与 physics_sanity_check.js） ----
	/**
	 * 基础拍速（格/tick）：轻点一下的最低出球速度。
	 *
	 * 【怎么定出来的】球台几何换算成格：半场 1.37、全场 2.74、网高 0.15（台面上方）、
	 * 击球点约在台面上方 0.23 格、距球网 1.97 格、距远端台缘 3.34 格。
	 * 用 tools/calibrate_hit_speed.js 反解：速度低于 0.28 时**无论怎么调仰角都爬不过网**
	 * （低速球在 1.97 格的飞行距离内抬不起 0.15 格），所以 0.30 是留了余量的合理下限区间。
	 */
	public static final double BASE_HIT_SPEED = 0.30;
	/** 蓄力加成：满力度额外加多少（格/tick）。0.30+0.11=0.41 → 落点约 2.9 格（压线攻球） */
	public static final double CHARGE_SPEED_BONUS = 0.11;
	/** 借力系数：来球越快回球越快。0.25 会让「挡回去」的球也飞出球台，降到 0.10 */
	public static final double HIT_SPEED_INHERIT = 0.10;
	/** 速度下限：低于 0.28 的球在本模型里物理上过不了网（见 calibrate 脚本的可行性扫描） */
	public static final double MIN_HIT_SPEED = 0.28;
	/** 速度上限：0.45 格/tick 落点约 3.4 格，已经是「人手能打出的极限」 */
	public static final double MAX_HIT_SPEED = 0.45;
	/**
	 * 拍面后仰 / 前倾的最大角度（度）。
	 * 这里只负责**玩家能看到的拍面姿态**与自旋强度；出球仰角另由基准曲线 + 拍面偏移给出。
	 */
	public static final double MAX_TILT_DEGREES = 26.0;
	/** 绝对最低仰角（度）：低于它球会贴着台面走，视觉上像「滚」而不是「飞」 */
	public static final double MIN_LAUNCH_ELEVATION_DEGREES = 6.0;
	/**
	 * 基准起跳仰角：力度 0 → 30°，力度 1 → 14°。
	 *
	 * 【为什么不沿用「固定 6° 仰角 + 速度决定一切」】击球点只比台面高 0.23 格，
	 * 而球网在 1.97 格外高出 0.15 格 —— 低速球要过网**必须抬高弧线**，
	 * 这是真实乒乓球的手法（轻挡要抬、抽杀可以压）。calibrate 脚本对每档力度反解出
	 * 「过网余量 = 0.12 格」所需的仰角，线性拟合结果就是 30.5° − 16.0°×力度。
	 */
	public static final double LAUNCH_BASE_SLOW_DEGREES = 30.0;
	/** 力度 1 时的基准仰角（度） */
	public static final double LAUNCH_BASE_FAST_DEGREES = 14.0;
	/**
	 * 拍面额外贡献多少仰角（度）。
	 * 方向：**前倾（tilt>0，上旋/弧圈）压低弧线、后仰（tilt<0，下旋/搓削）抬高弧线**。
	 * 取值 12° 是脚本联合扫参结果（与 SPIN_SPEED_COUPLING=0.10 配对）：力度 0.5 时
	 * 上旋仰角 14°（落点 3.11 格、过网余量 0.09）、下旋 38°（落点 2.99 格、余量 0.33），
	 * 两者都在对面台面内，且上旋球落台后**前冲**、下旋球落台后**回缩**。
	 */
	public static final double PADDLE_ELEVATION_MAX_DEGREES = 12.0;
	/** 玩家视角俯仰还能额外影响多少度（只保留手感，弧线主体由基准曲线保证打得上台） */
	public static final double AIM_PITCH_WEIGHT = 0.25;
	/**
	 * 球台台面的世界高度（格）。与 block/PingPongTableBlock 的碰撞箱一致：
	 * 台面在方块内 0.75 处、方块放在地面上 → 世界高度约 0.76。
	 * 这里单独放一份是为了让「出球可行性试算」不依赖方块类的加载。
	 */
	public static final double PINGPONG_TABLE_TOP_Y = 0.76;
	/**
	 * 自旋对球速的耦合：**上旋更快**（前冲弧圈：球被"抽"出去，出球速度本来就高），
	 * **下旋更慢**（搓/削：速度换转速，球慢慢飘过去）。用 1 + tilt×COUPLING。
	 * 取 0.10 而非更大的值：再大就会让上旋球出台（脚本扫参：0.15 时上旋落点 3.24 格已偏出台）。
	 */
	public static final double SPIN_SPEED_COUPLING = 0.10;
	/**
	 * 侧旋轴相对竖直方向的倾斜角（度）。
	 * 【关键】纯竖直轴的自旋，在水平地面上的接触点速度 ω×r 恒为 0 —— 数学上就不可能侧拐。
	 * 侧旋轴写成 sin(θ)·up + cos(θ)·flat 后：竖直分量 sin(θ) 负责飞行侧弯，
	 * 行进分量 cos(θ) 负责落地侧向搓动。θ 取 60°（竖直分量更大）让飞行中的侧弯看得更清楚。
	 */
	public static final double SIDE_AXIS_TILT_DEGREES = 60.0;
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
	/**
	 * 出生后的若干 tick 里不与抛球者本人碰撞。
	 * 抛球点虽然在头顶上方，但球开始下落时正是玩家要击球的时候，贴身状态下
	 * 原版 {@code Entity#move} 会把玩家碰撞箱当墙，球会被"顶住"看着像卡在身上。
	 */
	private int noSelfCollisionTicks;
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

	/** 抛出后多少 tick 内不与抛球者本人碰撞（贴身球会被自己的碰撞箱挡住） */
	private static final int NO_SELF_COLLISION_TICKS = 2;

	/**
	 * 把球【垂直上抛】—— 用户要的发球方式：球自己上抛、落下，玩家调好拍形去打。
	 *
	 * 【两种写法都不往前】用户要求「抛球暂时垂直向上抛，现在抛球会有一点向前」：
	 * 1. 水平速度必须是 0（旧版有个 TOSS_DRIFT=0.06 的水平漂移，那 0.06 就是"向前"的来源）；
	 * 2. 生成点必须在玩家**正上方**。旧版生成在「视线水平前方 0.55 格」——
	 *    平面抛体落回原生成点，所以球会在身前那条竖直线上起落，看着仍然是"往前抛出去了"。
	 * 现在生成在头顶上方 0.3 格（一次跳跃就能穿过的空间也放得下），落回来正好在脚边。
	 *
	 * @param verticalSpeed 竖直初速度（格/tick）。0.20~0.32 → 峰值约 0.67~1.7 格、滞空 13~21 tick
	 */
	public static PingPongBallEntity toss(World world, PlayerEntity thrower, double verticalSpeed) {
		PingPongBallEntity ball = new PingPongBallEntity(ModEntities.PINGPONG_BALL, world);

		double spawnY = thrower.getEyePos().y + 0.30;
		Vec3d pos = new Vec3d(thrower.getX(), spawnY, thrower.getZ());
		// 生成点被方块占住（例如 2 格高的天花板下）就退回「身前 0.55 格、眼高」，
		// 宁可带一点点位移，也不能让球卡在墙壁里出不来。
		if (!world.isSpaceEmpty(ball, ball.getBoundingBox().offset(pos.subtract(ball.getPos())))) {
			Vec3d look = thrower.getRotationVec(1.0F);
			Vec3d flat = new Vec3d(look.x, 0.0, look.z);
			flat = flat.lengthSquared() < 1.0e-6 ? new Vec3d(0.0, 0.0, 1.0) : flat.normalize();
			pos = thrower.getEyePos().add(flat.multiply(0.55));
		}

		ball.refreshPositionAndAngles(pos.x, pos.y, pos.z, thrower.getYaw(), thrower.getPitch());
		ball.setOwner(thrower);
		// 水平分量严格为 0：这就是「纯垂直向上抛」。
		ball.setPhysicsVelocity(new Vec3d(0.0, verticalSpeed, 0.0));
		ball.setSpin(Vec3d.ZERO);
		ball.noSelfCollisionTicks = NO_SELF_COLLISION_TICKS;

		// 【需求 5：一个世界里同时只能存在一个球】
		// 抛新球时把同一个世界里其它球都收回，避免"跟球跟到之前扔出去、还没消失的旧球"。
		if (!world.isClient) {
			for (PingPongBallEntity old : world.getEntitiesByClass(PingPongBallEntity.class,
					thrower.getBoundingBox().expand(MAX_CLEANUP_RADIUS), b -> b != ball)) {
				old.discard();
			}
		}

		world.spawnEntity(ball);
		return ball;
	}

	/** 抛新球时回收旧球的最大半径（格）：一整个球台场地都够用 */
	public static final double MAX_CLEANUP_RADIUS = 64.0;

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
		if (this.noSelfCollisionTicks > 0) {
			this.noSelfCollisionTicks--;
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
			// 【需求 4 的关键】台面上要**留住**自旋：侧旋球落台后还要靠自旋继续侧拐，
			// 所以台面用 0.97/tick，只有普通地面才用 0.85 快速吃掉（二期全局 0.85 是"拐一下就没了"的元凶）。
			double spinDecay = PingPongPhysics.groundSpinDecay(this.isOnTable());
			this.spinX *= spinDecay;
			this.spinY *= spinDecay;
			this.spinZ *= spinDecay;
			this.spinDirty = true;
			Vec3d rolling = this.physicsVelocity;
			this.setPhysicsVelocity(new Vec3d(
					rolling.x * GROUND_ROLL_FRICTION, rolling.y, rolling.z * GROUND_ROLL_FRICTION));
		}

		this.updateRotationFromVelocity();
		this.pushSpin();

		// --- 4.6 按需把权威速度/自旋补发给客户端（自旋球每 2 tick 一次），
		//     这样客户端预测出来的弧线不会漂太久（用户报的"侧旋只动一下"就是缺了这一步）
		ModNetworking.syncBallMotionIfNeeded(this);

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

	/**
	 * 客户端：不跑权威物理，但**要跑一份马格努斯积分**。
	 *
	 * 【这是「侧旋只动一下就走直线」的真因之一】原来客户端只撒粒子、位置完全靠原版实体追踪包，
	 * 而原版每 tick 的位置包只带位置与朝向、<b>不带自旋造成的加速度</b>，
	 * 于是两次 ball_motion 包之间球在屏幕上就是一条直线 —— 只有击球瞬间那一下看得出来。
	 * 现在客户端用同一套马格努斯+阻力公式推进 velocities：服务端每 2 tick 用权威值纠正一次，
	 * 中间由客户端把弧线"补"出来，视觉上才是连续的香蕉球 / 下扎。
	 */
	private void tickClient() {
		Vec3d spin = this.getSpin();

		// 自旋够快时撒点粒子，让「转」看得见
		if (spin.lengthSquared() > 1.0 && this.age % 2 == 0) {
			this.getWorld().addParticle(ParticleTypes.CRIT,
					this.getX(), this.getY() + 0.14, this.getZ(),
					0.0, 0.0, 0.0);
		}

		// 客户端预测：重力 + 马格努斯 + 空气阻力（不含碰撞，碰撞交给服务端权威修正）
		if (spin.lengthSquared() > 0.04) {
			Vec3d velocity = this.getVelocity();
			Vec3d accel = new Vec3d(0.0, -PingPongPhysics.GRAVITY, 0.0)
					.add(PingPongPhysics.magnusAcceleration(velocity, spin));
			this.setVelocity(PingPongPhysics.applyAirDrag(velocity.add(accel)));
			// 自旋同样按服务端同一条衰减曲线走，避免客户端的弧线"越来越弯"
			this.applyClientSpin(PingPongPhysics.decaySpin(spin));
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

	/**
	 * 球是否正站在**球台台面**上（不是普通地面）。
	 * 用来决定贴地自旋衰减用哪一档 —— 台面要留住自旋（侧旋继续侧拐），地面要快速吃掉。
	 */
	private boolean isOnTable() {
		BlockPos below = BlockPos.ofFloored(this.getX(), this.getY() - 0.08, this.getZ());
		return this.getWorld().getBlockState(below).isOf(ModBlocks.PINGPONG_TABLE);
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
	 * 把起跳仰角夹到「这个速度下物理上打得过网」的范围里。
	 *
	 * 【为什么需要】击球点只比台面高 0.23 格，球网却在 1.97 格外高出 0.15 格。
	 * 于是每个速度都有一个**可行的仰角窗口**：太快 → 怎么打都出台；太慢 → 怎么打都爬不过网。
	 * 玩家把拍面压到极限（前倾 + 低力度）或仰到极限（后仰 + 满力度）时就会落到窗口外，
	 * 表现得像「球一碰就下网」这种不讲道理的手感。这里用与运行时同一套公式快速试算，
	 * 把仰角夹回窗口内 —— 结果依然尊重玩家的拍面选择（窗口内不动），只是不再出物理笑话。
	 *
	 * 试算场景取「球台前 0.6 格、台面上方 0.23 格、正对球网」，与 TableGeometry 的击球点一致。
	 */
	private static double clampElevation(double speed, double elevationRad, double topspinRadPerTick) {
		final double contactY = PINGPONG_TABLE_TOP_Y + 0.23;
		final double netX = 1.97;
		final double farEdgeX = 3.34;
		final double netTopY = PINGPONG_TABLE_TOP_Y + 0.15;

		double minElevation = Double.NaN;
		double maxElevation = Double.NaN;

		for (int i = 0; i <= 86; i++) {
			double deg = MIN_LAUNCH_ELEVATION_DEGREES + i * 0.5;
			double rad = Math.toRadians(deg);

			double vx = speed * Math.cos(rad);
			double vy = speed * Math.sin(rad);
			double spin = topspinRadPerTick;   // 上旋为正（沿 +x 前进时马格努斯把球往下压）
			double x = 0.0;
			double y = contactY;
			boolean reachedNet = false;
			double netClearance = Double.NaN;
			double landingX = Double.NaN;

			for (int t = 0; t < 200; t++) {
				// 与 tickServer 同序：重力 + 马格努斯 → 阻力 → 积分。自旋也要衰减。
				double magnusX = PingPongPhysics.MAGNUS_COEFFICIENT * (0.0 - spin * vy);
				double magnusY = PingPongPhysics.MAGNUS_COEFFICIENT * (spin * vx - 0.0);
				vx += magnusX;
				vy += -PingPongPhysics.GRAVITY + magnusY;
				double factor = PingPongPhysics.applyAirDrag(new Vec3d(vx, vy, 0.0)).length() / Math.max(1.0e-9, Math.hypot(vx, vy));
				vx *= factor;
				vy *= factor;
				spin *= PingPongPhysics.SPIN_DECAY;
				x += vx;
				y += vy;

				if (!reachedNet && x >= netX) {
					reachedNet = true;
					netClearance = y - netTopY;
					if (netClearance <= 0.0) {
						break; // 撞网
					}
					// 已经过网了：继续飞，等落地距离
					continue;
				}
				if (reachedNet && y <= PINGPONG_TABLE_TOP_Y) {
					landingX = x;
					break;
				}
				if (y < -2.0) {
					break;
				}
			}

			if (reachedNet && !Double.isNaN(landingX) && netClearance > 0.0 && landingX < farEdgeX) {
				if (Double.isNaN(minElevation)) {
					minElevation = rad;
				}
				maxElevation = rad;
			}
		}

		if (Double.isNaN(minElevation)) {
			// 这个速度怎么打都上不了台：直接给一个「最容易过网」的 45°
			return Math.toRadians(45.0);
		}
		if (elevationRad < minElevation) {
			return minElevation;
		}
		if (elevationRad > maxElevation) {
			return maxElevation;
		}
		return elevationRad;
	}

	/**
	 * 被球拍击中。
	 *
	 * @param player   击球玩家
	 * @param tilt     拍面俯仰：&gt; 0 = 前倾（拍盖上压）→ 上旋球；&lt; 0 = 后仰（兜球）→ 下旋球
	 * @param sideTilt 拍面侧偏：Alt + 滚轮，产生侧旋（香蕉球）
	 * @param power    击球力度 0~1：由客户端「左键按住时长」决定，服务端只做范围校验（需求 1）
	 * @param hand     正手 / 反手：决定出球的侧向分量与轻微自旋差异（需求 4）
	 * @return 是否真的打到
	 */
	public boolean hitByPaddle(ServerPlayerEntity player, double tilt, double sideTilt, double power, PlayerHand hand) {
		if (this.hitCooldown > 0) {
			return false;
		}

		double t = MathHelper.clamp(tilt, -1.0, 1.0);
		double s = MathHelper.clamp(sideTilt, -1.0, 1.0);
		double hitPower = MathHelper.clamp(power, 0.0, 1.0);

		// --- 1. 出球方向：水平朝向 + 起跳仰角 ---
		// 有球台时主要朝「球台对面」那一侧（跟球视角下视线锁在球上也能把球打回去，需求 6），
		// 附近没球台就退回「按视线」，保持自由练习的手感。
		Vec3d outward = TableGeometry.outward(this.getWorld(), player.getPos());
		Vec3d horizontal = TableGeometry.hitDirection(player.getRotationVec(1.0F), outward);

		// 起跳仰角 = 基准曲线（力度决定，保证打得上台）+ 拍面（后仰抬高 / 前倾压低）+ 玩家俯仰的少量修正。
		// 之后再过一遍「物理可行性夹紧」：这个速度下任何仰角都过不了网/一定出台时，夹到能过网的边界上。
		double baseElevation = LAUNCH_BASE_SLOW_DEGREES
				+ (LAUNCH_BASE_FAST_DEGREES - LAUNCH_BASE_SLOW_DEGREES) * hitPower;
		double elevationDegrees = baseElevation
				- t * PADDLE_ELEVATION_MAX_DEGREES
				+ player.getPitch() * AIM_PITCH_WEIGHT;
		double elevationRad = Math.toRadians(elevationDegrees);

		// 正反手各带一点侧向分量：正手扫出去略偏右，反手推出去略偏左（真实拍形差异）
		double handYaw = hand == PlayerHand.FOREHAND ? 3.5 : -3.5;
		double sideDegrees = s * MAX_TILT_DEGREES * 0.85 + handYaw;
		double sideRad = Math.toRadians(sideDegrees);

		Vec3d direction = new Vec3d(
				horizontal.x * Math.cos(sideRad) - horizontal.z * Math.sin(sideRad),
				Math.sin(elevationRad),
				horizontal.x * Math.sin(sideRad) + horizontal.z * Math.cos(sideRad)
		).normalize();

		// --- 2. 出球速度：基础拍速 + 蓄力力度 + 借用来球动能 + 自旋耦合（上旋更快、下旋更慢） ---
		double incoming = this.physicsVelocity.length();
		double spinCoupling = 1.0 + t * SPIN_SPEED_COUPLING;
		double speed = MathHelper.clamp(
				(BASE_HIT_SPEED + CHARGE_SPEED_BONUS * hitPower + HIT_SPEED_INHERIT * incoming) * spinCoupling,
				MIN_HIT_SPEED, MAX_HIT_SPEED);

		// 可行性夹紧：见 clampElevation 的注释。夹完再重算一次方向。
		// 【为什么要把上旋量传进去】上旋的马格努斯力是"往下压"的，强上旋 + 压平的拍面
		// 会让球在过网前就掉下去（脚本实测：满力度强上旋时净空 −0.22 格 = 下网）。
		// 夹紧时带上同一支自旋量，才能算出真正可行的仰角窗口。
		double plannedTopspin = t * MAX_SPIN * (0.35 + 0.65 * hitPower);
		double safeElevation = clampElevation(speed, Math.toRadians(elevationDegrees), plannedTopspin);
		if (Math.abs(safeElevation - elevationRad) > 1.0e-4) {
			Vec3d flatDir = new Vec3d(direction.x, 0.0, direction.z).normalize();
			direction = new Vec3d(
					flatDir.x * Math.cos(safeElevation),
					Math.sin(safeElevation),
					flatDir.z * Math.cos(safeElevation)).normalize();
		}
		this.setPhysicsVelocity(direction.multiply(speed));

		// --- 3. 自旋：力度越大转得越狠（现实里也是用力抽才转） ---
		Vec3d flat = new Vec3d(direction.x, 0.0, direction.z);
		flat = flat.lengthSquared() < 1.0e-6 ? new Vec3d(0.0, 0.0, 1.0) : flat.normalize();

		// 上旋轴 = up × 水平出球方向（右手定则：球顶部向前转，马格努斯力向下 → 弧线下扎）
		Vec3d topspinAxis = new Vec3d(0.0, 1.0, 0.0).crossProduct(flat).normalize();
		// 侧旋轴 = sin(θ)·up + cos(θ)·flat（朝行进方向倾斜 θ）。
		// 【千万别写反】反过来写成 cos·up + sin·flat 会得到一个几乎纯竖直的轴，
		// 而纯竖直轴在水平面上 ω×r ≡ 0 —— 落地永远不会侧拐（二期已踩过这个坑）。
		// θ=60°：竖直分量 sin60°=0.87 负责飞行侧弯，行进分量 cos60°=0.5 负责落地侧拐（需求 4 两者都要）。
		Vec3d sideAxis = new Vec3d(0.0, 1.0, 0.0).multiply(Math.sin(SIDE_AXIS_TILT))
				.add(flat.multiply(Math.cos(SIDE_AXIS_TILT)))
				.normalize();

		// 【符号 bug 修复】tilt > 0 = 拍面前倾、压着打 = **上旋**：轴取 -topspinAxis，
		// 这样 ω×v 指向下（球下扎、落台前冲）；tilt < 0 = 后仰、兜球 = 下旋，球发飘。
		// 旧代码写成 +t，等于把上旋与下旋整体调反 —— 用户报的
		//「上旋球和下旋球速度都一样 / 旋转没有任何体现」有一半来自这里
		//（另一半是客户端不跑马格努斯，见 ModNetworking 的运动同步）。
		double spinScale = MAX_SPIN * (0.35 + 0.65 * hitPower);
		Vec3d spin = topspinAxis.multiply(-t * spinScale).add(sideAxis.multiply(s * spinScale));
		this.setSpin(spin);

		// --- 4. 收尾：冷却、位置微调、同步、特效 ---
		this.hitCooldown = HIT_COOLDOWN_TICKS;
		this.restTicks = 0;
		this.ownerUuid = player.getUuid();

		// 原版 Entity#move 会把玩家碰撞箱当成墙，贴身球会被自己挡住，
		// 所以离身体太近的球直接「拨」到击球点位置再飞出去。
		Vec3d paddlePos = TableGeometry.paddlePoint(player.getEyePos(), player.getRotationVec(1.0F), outward, hand);
		if (this.getPos().squaredDistanceTo(player.getEyePos()) < 2.25) {
			this.refreshPositionAndAngles(paddlePos.x, paddlePos.y, paddlePos.z, this.getYaw(), this.getPitch());
		} else {
			this.setPosition(this.getPos().add(direction.multiply(0.2)));
		}

		ModNetworking.broadcastBallMotion(this);

		// 力度越大，击球声越响、音调越低
		float volume = (float) (0.35 + 0.45 * hitPower);
		float pitch = (float) (1.85 - 0.55 * hitPower);
		this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(),
				SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, volume, pitch);

		if (this.getWorld() instanceof ServerWorld serverWorld) {
			serverWorld.spawnParticles(ParticleTypes.CRIT,
					this.getX(), this.getY() + 0.14, this.getZ(),
					3 + (int) Math.round(6.0 * hitPower), 0.08, 0.08, 0.08, 0.05);
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

	/**
	 * 抛球后的头几 tick 忽略抛球者本人：贴身下落时原版 {@code Entity#move} 会把玩家碰撞箱当墙，
	 * 把球顶住看着像卡在身上。只忽略抛球者，别的玩家/生物照常能挡球。
	 * （方法名以 1.20.1 Yarn 为准：{@code collidesWith}，不是 canCollideWith —— 后者在这个版本不存在。）
	 */
	@Override
	public boolean collidesWith(net.minecraft.entity.Entity other) {
		if (this.noSelfCollisionTicks > 0 && this.ownerUuid != null
				&& this.ownerUuid.equals(other.getUuid())) {
			return false;
		}
		return super.collidesWith(other);
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

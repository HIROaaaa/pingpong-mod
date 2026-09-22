package com.whale.pingpong.entity;

import com.whale.pingpong.PingPongMod;
import com.whale.pingpong.block.ModBlocks;
import com.whale.pingpong.block.PingPongTableBlock;
import com.whale.pingpong.net.ModNetworking;
import com.whale.pingpong.physics.PingPongContact;
import com.whale.pingpong.physics.PingPongPhysics;
import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import com.whale.pingpong.util.TableGeometry;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
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
	 * 是否正在「飞回」玩家（五期 M7.6 需求 30）。
	 *
	 * 【为什么必须同步给客户端】飞回期间服务端每 tick 用命令式的速度推向玩家，
	 * 而客户端默认跑自己的马格努斯+重力积分 —— 不告诉它"这球在飞回"，两边会打架：
	 * 客户端把球往下拽、服务端又往上推，屏幕上就是一路抖回玩家身上。
	 */
	private static final TrackedData<Boolean> RETURNING =
			DataTracker.registerData(PingPongBallEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

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
	// ---- 回收（五期 M7.6 需求 30）----
	/** 静止多少 tick 后开始「飞回」玩家（20 tick = 1 秒，让玩家看清楚球停在哪） */
	public static final int RETURN_DELAY_TICKS = 20;
	/** 飞回时的起始速度（格/tick） */
	public static final double RETURN_START_SPEED = 0.06;
	/** 飞回时每 tick 增加多少速度（越飞越快，不然慢得让人等） */
	public static final double RETURN_ACCEL = 0.012;
	/** 飞回的最大速度（格/tick） */
	public static final double RETURN_MAX_SPEED = 0.32;
	/** 距玩家眼睛多近算「接住了」（格） */
	public static final double RETURN_ARRIVE_DISTANCE = 0.75;

	/** 静止多久后自动消失（tick）。比「开始飞回」宽松得多：飞回失败（主人离线）时兜底。 */
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
	 *
	 * 【五期 M7.6 §8.3：0.30 → 0.27】用户反馈「击球后球出去的距离还是太远了」。
	 * 计划里写的是降到 0.24，但 tools/calibrate_m76.js 实测：**0.24 和 0.25 任何仰角都过不了网**
	 * （0.26 也不行），可行下界是 0.265（需 42.5° 仰角，控制窗口极窄）。
	 * 所以取 0.27：既是物理上真正可行的下界，又让落点从 2.47~3.02 收到 2.35~2.65。
	 */
	public static final double BASE_HIT_SPEED = 0.27;
	/** 蓄力加成：满力度额外加多少（格/tick）。M7.6 由 0.11 降到 0.07 → 满力 0.34 格/tick（落点约 2.65） */
	public static final double CHARGE_SPEED_BONUS = 0.07;
	/** 借力系数：来球越快回球越快。0.25 会让「挡回去」的球也飞出球台，降到 0.10 */
	public static final double HIT_SPEED_INHERIT = 0.10;
	/** 速度下限：M7.6 由 0.28 降到 0.265 —— 0.26 已过不了网，这是物理下界的极限值 */
	public static final double MIN_HIT_SPEED = 0.265;
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
	 * 基准起跳仰角：力度 0 → 24°，力度 1 → 14°。
	 *
	 * 【为什么不沿用「固定 6° 仰角 + 速度决定一切」】击球点只比台面高 0.23 格，
	 * 而球网在 1.97 格外高出 0.15 格 —— 低速球要过网**必须抬高弧线**，
	 * 这是真实乒乓球的手法（轻挡要抬、抽杀可以压）。calibrate 脚本对每档力度反解出
	 * 「过网余量 = 0.12 格」所需的仰角，原始线性拟合为 30.5° − 16.0°×力度。
	 *
	 * 【五期 M7.5 现象 D：30° → 24°】30° 是「过网余量 0.12」的反解值，偏保守；
	 * 用户实测反馈「球被击中后上升的高度有点高」，所以把慢速端的基准弧线压低，
	 * 改取「过网余量约 0.06 格」的档位。可行性由 {@link #clampElevation} 兜底：
	 * 压过头（下网）时它会自动把仰角夹回窗口，所以这是**只降不涨**的调整。
	 */
	public static final double LAUNCH_BASE_SLOW_DEGREES = 24.0;
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
	/**
	 * 玩家视角俯仰还能额外影响多少度（只保留手感，弧线主体由基准曲线保证打得上台）
	 */
	public static final double AIM_PITCH_WEIGHT = 0.25;
	/**
	 * 玩家用滚轮最多能把拍面角调整多少度（M3 起）。
	 * 击球类型自带基准角（攻球 52°、拉球 16°、搓球 30°、削球 37°），滚轮只做**微调**，
	 * 所以给 ±12° 而不是二期的 ±26° —— 幅度再大就把接触模型的标定打乱了。
	 */
	public static final double PLAYER_TILT_MAX_DEGREES = 12.0;
	/**
	 * 玩家侧偏（Alt+滚轮）**直接偏转出球方向**的最大角度（度）。
	 *
	 * 【五期 M7.5 现象 B 的修法】M3 换用接触模型后，侧偏只被喂进「挥拍方向」，
	 * 而挥拍方向的侧向分量会被切向摩擦**吸收成自旋**，出球水平方向几乎不变 ——
	 * 于是玩家左右拨滚轮，看到的球飞得一模一样。
	 *
	 * 现在把侧偏拆成两部分：
	 * <ul>
	 *   <li><b>本常量（10°）</b>：直接绕竖直轴旋转出球速度的水平方向 —— 眼睛看得见球被"拨"向哪边；</li>
	 *   <li>{@link #SWING_SIDE_MAX_DEGREES}（30°）：仍然偏转挥拍方向，负责造侧旋（香蕉球的弧线）。</li>
	 * </ul>
	 * 只取滚轮那一部分（{@code s}）参与偏转：正反手固有的 ±3.5° 挥拍偏置是「握姿」，
	 * 不是玩家在拨拍，不该让球无故横飘。
	 */
	public static final double PLAYER_SIDE_MAX_DEGREES = 10.0;
	/** 玩家侧偏最多让**挥拍方向**偏多少度（Alt+滚轮），负责产生侧旋（与上一条配对使用） */
	public static final double SWING_SIDE_MAX_DEGREES = 30.0;
	/** 击球后沿出球方向轻推的距离（格）：只为让球脱离玩家碰撞箱，见现象 A 的注释 */
	public static final double PADDLE_HIT_NUDGE = 0.15;
	/** 击球后把球抬高一点（格）：避免贴着地面/台面被判定成"滚球" */
	public static final double PADDLE_HIT_LIFT = 0.05;
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
	 * 行进分量 cos(θ) 负责落地侧向搓动。
	 *
	 * 【五期 M8：60° → 75°，与计划里的 45° 相反】用户三次反馈「侧旋根本体现不出来」。
	 * 用 tools/side_spin_sweep.js 在**球台实际尺度**里量：第一跳前的横向位移
	 * 30°/45°/60°/75°/82° 分别是 0.039/0.060/0.076/0.088/0.091 格 ——
	 * **倾角越大、飞行侧弯越明显**（竖直分量 ∝ sin θ）。计划里写 45° 的用意是"加强落地侧拐"，
	 * 但那恰恰砍掉了飞行侧弯，而玩家看得见的正是飞行那一段（球台只有 2.74 格，
	 * 二跳基本已经飞出视野）。所以这里反向调整到 75°。
	 */
	public static final double SIDE_AXIS_TILT_DEGREES = 75.0;
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
	/** 飞回进度（tick）：仅服务端有意义，见 {@link #tickReturning(PersistenceType)} */
	private int returnTicks;

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

	/** 抛球出手点：沿视线水平方向前移多少格（举在面前） */
	private static final double TOSS_FORWARD = 0.45;
	/** 抛球出手点比眼睛低多少格（手举在面前，略低于视线） */
	private static final double TOSS_DROP = 0.15;
	/** 身前被挡住时，向上重试的最大次数（每次 +0.15 格） */
	private static final int TOSS_RETRY_MAX = 4;

	/**
	 * 把球【垂直上抛】—— 用户要的发球方式：球自己上抛、落下，玩家调好拍形去打。
	 *
	 * <h3>出手点：为什么在身前 0.45 格</h3>
	 * 两次实测反馈把这件事推到了现在的位置：
	 * <ol>
	 *   <li>用户要求「抛球暂时垂直向上抛，现在抛球会有一点向前」→ 去掉水平漂移（TOSS_DRIFT），
	 *       并把生成点移到玩家**正上方**；</li>
	 *   <li>但正上方马上就出了新问题：用户原话「抛球不要从玩家身上开始抛球，要从玩家视角前面一点，
	 *       现在发球直接从身体里发出去了，根本看不见球」—— 生成在身体正中，球从脖子/胸口冒出来。</li>
	 * </ol>
	 * 现在取「眼睛 + 视线水平方向 × 0.45 格、低 0.15 格」：举在面前的球，第一人称看得见，
	 * 也符合抛球的动作。**注意：出手点前移不等于往前飞** —— 水平速度仍然是严格 0，
	 * 球从那个点垂直起落（这正是两次需求不冲突的地方）。
	 *
	 * @param verticalSpeed 竖直初速度（格/tick）。0.20~0.32 → 峰值约 0.67~1.7 格、滞空 13~21 tick
	 */
	public static PingPongBallEntity toss(World world, PlayerEntity thrower, double verticalSpeed) {
		PingPongBallEntity ball = new PingPongBallEntity(ModEntities.PINGPONG_BALL, world);

		Vec3d look = thrower.getRotationVec(1.0F);
		Vec3d flat = new Vec3d(look.x, 0.0, look.z);
		flat = flat.lengthSquared() < 1.0e-6 ? new Vec3d(0.0, 0.0, 1.0) : flat.normalize();
		Vec3d base = thrower.getEyePos().add(flat.multiply(TOSS_FORWARD)).add(0.0, -TOSS_DROP, 0.0);

		// 身前被方块挡住（贴着墙、或面前就是球台/方块）时逐步抬高出手点，
		// 一路都放不下才退回「正上方」——保证球永远有地方出现，不会卡在方块里。
		Vec3d pos = base;
		boolean placed = false;
		for (int i = 0; i <= TOSS_RETRY_MAX; i++) {
			Vec3d candidate = base.add(0.0, 0.15 * i, 0.0);
			if (world.isSpaceEmpty(ball, ball.getBoundingBox().offset(candidate.subtract(ball.getPos())))) {
				pos = candidate;
				placed = true;
				break;
			}
		}
		if (!placed) {
			pos = new Vec3d(thrower.getX(), thrower.getEyePos().y + 0.30, thrower.getZ());
		}

		ball.refreshPositionAndAngles(pos.x, pos.y, pos.z, thrower.getYaw(), thrower.getPitch());
		ball.setOwner(thrower);
		// 水平分量严格为 0：这就是「纯垂直向上抛」（与出手点前移不冲突）。
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
		this.dataTracker.startTracking(RETURNING, false);
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

	/** 是否正在飞回玩家（服务端与客户端都读得到，见 RETURNING 的注释）。 */
	public boolean isReturning() {
		return this.dataTracker.get(RETURNING);
	}

	/** 开始飞回：清掉残余速度与自旋，让球"被吸走"而不是继续滚。 */
	private void startReturn() {
		this.returnTicks = 0;
		this.setPhysicsVelocity(Vec3d.ZERO);
		this.setVelocity(Vec3d.ZERO);
		this.setSpin(Vec3d.ZERO);
		this.dataTracker.set(RETURNING, true);
		this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(),
				net.minecraft.sound.SoundEvents.ENTITY_ITEM_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS,
				0.25F, 1.8F);
	}

	/**
	 * 飞回：朝**主人（最后抛球或击球的人）**的眼睛直线加速飞行，到了就回到他的物品栏。
	 *
	 * 需求原话：「等球完全不动了之后要自动回到玩家身上」。所以这里是"回到身上"而不是"原地消失"，
	 * 并配了上行音效（ENTITY_ITEM_PICKUP 音调拉高），让玩家知道球是回来了、不是没了。
	 */
	private void tickReturning() {
		this.returnTicks++;
		ServerPlayerEntity owner = this.resolveOwner();
		if (owner == null) {
			// 主人离线/换维度：停留超过 MAX_REST_TICKS 由 tickServer 的兜底分支直接丢弃
			return;
		}

		Vec3d target = owner.getEyePos();
		Vec3d toTarget = target.subtract(this.getPos());
		if (toTarget.length() < RETURN_ARRIVE_DISTANCE) {
			this.finishReturn(owner);
			return;
		}

		double speed = Math.min(RETURN_MAX_SPEED, RETURN_START_SPEED + RETURN_ACCEL * this.returnTicks);
		Vec3d velocity = toTarget.normalize().multiply(speed);
		this.physicsVelocity = velocity;
		this.setVelocity(velocity);
		this.move(MovementType.SELF, velocity);
		this.updateRotationFromVelocity();
	}

	/** 飞到玩家身上了：把球还回物品栏（或掉在脚下），然后消失。 */
	private void finishReturn(ServerPlayerEntity owner) {
		ItemStack stack = new ItemStack(com.whale.pingpong.item.ModItems.PINGPONG_BALL);
		if (!owner.giveItemStack(stack)) {
			// 背包满：掉在玩家脚下，而不是凭空蒸发
			owner.dropItem(stack, false);
		}
		this.getWorld().playSound(null, owner.getX(), owner.getY(), owner.getZ(),
				net.minecraft.sound.SoundEvents.ENTITY_ITEM_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS,
				0.35F, 2.0F);
		this.discard();
	}

	/** 主人 = 最后抛球或击球的那个玩家；离线/找不到就返回 null。 */
	private ServerPlayerEntity resolveOwner() {
		if (this.ownerUuid == null || !(this.getWorld() instanceof ServerWorld)) {
			return null;
		}
		return ((ServerWorld) this.getWorld()).getServer().getPlayerManager().getPlayer(this.ownerUuid);
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

		// --- 0. 飞回阶段（五期 M7.6 需求 30）---
		// 独立分支：飞回期间不跑重力/马格努斯/碰撞，否则球会被物理拖住、回不到玩家身上。
		if (this.isReturning()) {
			this.tickReturning();
			return;
		}

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

		// 【五期 M7.6 需求 30：静止后飞回玩家】
		// 原来的行为是「静止超过 MAX_REST_TICKS 就 discard（凭空消失）」，用户明确要求
		// 「等球完全不动了之后要自动回到玩家身上」。现在的顺序是：
		//   静止 RETURN_DELAY_TICKS（1 秒，让玩家看清楚球停在哪）→ 开始飞回 → 到达玩家物品栏。
		// 球直接在主人手里/身上时不算「静止回收」的场合 —— 那种情况球早就被接住了。
		// MAX_REST_TICKS 退居兜底：主人离线、或球卡在飞回不了的地方时才丢掉。
		if (this.restTicks == RETURN_DELAY_TICKS) {
			this.startReturn();
			return;
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
		// 飞回期间不做客户端物理预测：服务端每 tick 直接命令位置，客户端再叠一份重力与马格努斯
		// 只会让球一路抖着飞回来。位置跟随由实体追踪包负责。
		if (this.isReturning()) {
			return;
		}

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

		// 【Java 8 兼容】不用 instanceof 模式匹配（Java 16+），改成显式强转
		if (this.getWorld() instanceof ServerWorld && impactSpeed > 0.25) {
			ServerWorld serverWorld = (ServerWorld) this.getWorld();
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
	 * 把出球速度的**水平方向**按玩家侧偏转一个角度（五期 M7.5 现象 B）。
	 *
	 * <p>方向约定与「挥拍向哪边偏」一致：侧偏量 s &gt; 0 时向击球者**右手侧**偏转。
	 * 旋转公式直接照搬 {@link StrokeType#worldSwing} 里对局部坐标的写法，
	 * 保证「看到的拍子偏移方向」和「球飞出去偏的方向」是同一个符号。
	 *
	 * @param result   夹紧后的接触结果（速度/自旋/接触量）
	 * @param basis    当拍的前进方向基（只用其水平 forward / right 两轴）
	 * @param side     玩家侧偏量 −1~1（Alt + 滚轮，只有它参与偏转）
	 * @param speed    出球速度大小（格/tick）；水平分量会被重新归一化后乘回这个大小
	 */
	private static PingPongContact.Result applySideDeflection(PingPongContact.Result result,
															 StrokeType.Basis basis,
															 double side, double speed) {
		double deflectDegrees = side * PLAYER_SIDE_MAX_DEGREES;
		if (Math.abs(deflectDegrees) < 1.0e-4 || speed < 1.0e-4) {
			return result;
		}
		Vec3d velocity = result.velocity();
		Vec3d flat = new Vec3d(velocity.x, 0.0, velocity.z);
		if (flat.lengthSquared() < 1.0e-8) {
			return result;   // 纯竖直方向的球（理论上不该出现）：没有水平方向可偏
		}
		Vec3d flatDir = flat.normalize();
		double rad = Math.toRadians(deflectDegrees);
		double x = flatDir.x * Math.cos(rad) - flatDir.z * Math.sin(rad);
		double z = flatDir.x * Math.sin(rad) + flatDir.z * Math.cos(rad);
		Vec3d rotated = new Vec3d(x, velocity.y, z).normalize();
		return new PingPongContact.Result(rotated.multiply(speed), result.spin(),
				result.slipSpeed(), result.slipping(), result.normalImpulse());
	}

	/**
	 * 把自旋向量投影成「上旋量」（沿 topAxis = up × 前进方向 的分量，正 = 上旋）。
	 * 夹紧试算只需要知道球是上旋还是下旋、有多强，横向分量对纵向落点影响很小。
	 */
	private static double calculateTopspin(Vec3d spin, Vec3d forward) {
		Vec3d flat = new Vec3d(forward.x, 0.0, forward.z);
		if (flat.lengthSquared() < 1.0e-8) {
			return 0.0;
		}
		flat = flat.normalize();
		Vec3d topspinAxis = new Vec3d(0.0, 1.0, 0.0).crossProduct(flat).normalize();
		// 上旋 = 自旋沿 -topspinAxis（与 hitByPaddle 里 topspinAxis.multiply(-t) 的约定一致）
		return -spin.dotProduct(topspinAxis);
	}

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
	public boolean hitByPaddle(ServerPlayerEntity player, double tilt, double sideTilt, double power,
							   PlayerHand hand, StrokeType strokeType) {
		if (this.hitCooldown > 0) {
			return false;
		}

		double t = MathHelper.clamp(tilt, -1.0, 1.0);
		double s = MathHelper.clamp(sideTilt, -1.0, 1.0);
		double hitPower = MathHelper.clamp(power, 0.0, 1.0);

		// --- 1. 出球方向与接触（M3：改用真实接触模型）---
		// 有球台时主要朝「球台对面」那一侧（跟球视角下视线锁在球上也能把球打回去，需求 6），
		// 附近没球台就退回「按视线」，保持自由练习的手感。
		Vec3d outward = TableGeometry.outward(this.getWorld(), player.getPos());
		Vec3d forward = TableGeometry.hitDirection(player.getRotationVec(1.0F), outward);

		// 击球类型由服务端按「哪个键 + 蓄力大小」选出（见 ModNetworking.swing），
		// 搓/削/弧圈/攻球各自的拍面角与挥拍速度都在 StrokeType 里标定过。
		StrokeType stroke = strokeType == null ? StrokeType.DRIVE_FOREHAND : strokeType;
		stroke = stroke.flipHandIf(hand == PlayerHand.BACKHAND);
		StrokeType.Basis basis = StrokeType.Basis.of(forward);

		// 玩家用滚轮微调拍面角（±12°），视线俯仰也带一点点
		double tiltOffset = -t * PLAYER_TILT_MAX_DEGREES + player.getPitch() * AIM_PITCH_WEIGHT;
		Vec3d normal = stroke.worldNormal(basis, tiltOffset);
		// 【M7.5 现象 B】侧偏拆成两处用：
		//   ① 这里偏转**挥拍方向**（30°/满偏），靠切向摩擦造侧旋；
		//   ② 下面拿到出球速度后再把**水平方向**直接偏 10°/满偏（见 applySideDeflection），
		//      让"球拍往哪边拨、球就往哪边去"这件事肉眼可见。
		// 正反手固有 ±3.5° 是握姿偏置，只参与挥拍、不参与出球偏转。
		double sideDegrees = s * SWING_SIDE_MAX_DEGREES + (hand == PlayerHand.FOREHAND ? 3.5 : -3.5);
		Vec3d swing = stroke.worldSwing(basis, sideDegrees);

		Vec3d incomingVelocity = this.physicsVelocity;
		Vec3d incomingSpin = this.getSpin();
		PingPongContact.Result contactResult = PingPongContact.hit(
				incomingVelocity, incomingSpin,
				PingPongPhysics.BALL_RADIUS, PingPongPhysics.MASS, PingPongPhysics.INERTIA,
				normal, swing, stroke.swingSpeed, hitPower, stroke.surface);

		// --- 2. 可行性夹紧：保证这一拍能过网落台 ---
		// 【为什么必须有】接触模型是"有单位的真物理"，但本 Mod 的球台只有 2.74 格，
		// 能过网又不出台的出球角度窗口很窄。标定只能保证"某个基准来球 + 中等力度"落点合适，
		// 来球速度/自旋一变（比如对手拉过来的强上旋）就会下网或出台。
		// 这里只调整**出球方向**（保留接触模型算出的自旋与速度大小），
		// 把仰角夹进「这个速度 + 这支自旋下真正可行」的窗口：
		//   - 强上旋 + 压平 → 夹高一点，避免过网前就被马格努斯压下去；
		//   - 高球速 + 抬太高 → 夹低一点，避免飞出台。
		double topspinForClamp = calculateTopspin(contactResult.spin(), basis.forward());
		double outgoingSpeed = contactResult.velocity().length();
		double elevationRad = Math.toRadians(LAUNCH_BASE_SLOW_DEGREES
				+ (LAUNCH_BASE_FAST_DEGREES - LAUNCH_BASE_SLOW_DEGREES) * hitPower);
		if (outgoingSpeed > 1.0e-4) {
			double safeElevation = clampElevation(outgoingSpeed, elevationRad, topspinForClamp);
			if (Math.abs(safeElevation - elevationRad) > 1.0e-4) {
				Vec3d flatDir = new Vec3d(basis.forward().x, 0.0, basis.forward().z).normalize();
				Vec3d clamped = new Vec3d(
						flatDir.x * Math.cos(safeElevation),
						Math.sin(safeElevation),
						flatDir.z * Math.cos(safeElevation)).normalize();
				contactResult = new PingPongContact.Result(
						clamped.multiply(outgoingSpeed), contactResult.spin(),
						contactResult.slipSpeed(), contactResult.slipping(), contactResult.normalImpulse());
			}
		}

		// --- 3. 侧偏：把出球方向的水平分量按滚轮侧偏转一个角（M7.5 现象 B）---
		// 必须在夹紧**之后**做：夹紧只重设仰角、用的是"水平正前方"这个水平方向，
		// 若偏转放在夹紧之前，偏转好的方向会被夹紧结果整个覆盖掉（现象 B 看起来没好，就是栽在这）。
		contactResult = applySideDeflection(contactResult, basis, s, outgoingSpeed);

		this.setPhysicsVelocity(contactResult.velocity());
		this.setSpin(PingPongPhysics.clampSpin(contactResult.spin()));
		Vec3d direction = contactResult.velocity().lengthSquared() < 1.0e-9
				? forward.normalize()
				: contactResult.velocity().normalize();

		// --- 4. 收尾：冷却、位置微调、同步、特效 ---
		this.hitCooldown = HIT_COOLDOWN_TICKS;
		this.restTicks = 0;
		this.ownerUuid = player.getUuid();

		// 【M7.5 现象 A：不再把球瞬移到拍面点】
		// 旧写法是「离眼睛 1.5 格以内 → refreshPositionAndAngles 到 TableGeometry.paddlePoint」，
		// 而那个点是「前 0.55 + 侧向 ±0.38 + 上 0.45」的固定偏移 —— 于是每次击球，
		// 球都被拖到身体侧前方再飞出去，看起来就是"先向人物的左边偏移一下再出去"，
		// 而且正反手的 ±0.38 让左右两侧都往同一边偏（现象 A 与现象 E 同源）。
		// 现在只沿出球方向轻推 0.15 格：目的是让球脱离玩家碰撞箱（原版 move 会把玩家当墙顶住球），
		// 水平位置基本不动，视觉上就是"原地被抽出去"。
		Vec3d nudge = direction.lengthSquared() < 1.0e-9 ? new Vec3d(0.0, 0.0, 0.0) : direction;
		this.setPosition(this.getPos().add(nudge.multiply(PADDLE_HIT_NUDGE))
				.add(0.0, PADDLE_HIT_LIFT, 0.0));

		ModNetworking.broadcastBallMotion(this);

		// 力度越大，击球声越响、音调越低
		float volume = (float) (0.35 + 0.45 * hitPower);
		float pitch = (float) (1.85 - 0.55 * hitPower);
		this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(),
				SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, volume, pitch);

		if (this.getWorld() instanceof ServerWorld) {
			ServerWorld serverWorld = (ServerWorld) this.getWorld();
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

package com.whale.pingpong.net;

import com.whale.pingpong.PingPongMod;
import com.whale.pingpong.entity.PingPongBallEntity;
import com.whale.pingpong.item.PingPongPaddleItem;
import com.whale.pingpong.server.PaddlePoseTracker;
import com.whale.pingpong.util.ArmPose;
import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import com.whale.pingpong.util.TableGeometry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 网络层（1.20.1 Fabric 经典 Identifier + PacketByteBuf 通道）。
 *
 * <pre>
 * 上行 C2S  paddle_action ：玩家的持拍交互
 *            0 = POSE  拍形/手型变了（滚轮、切正反手）
 *            1 = SWING 松手挥拍，带蓄力力度 → 服务端做命中判定与物理出球
 *            2 = CHARGE 按下左键开始蓄力（服务端留作将来做音效/动作提示）
 *            3 = BALL_CAM 跟球视角开/关
 * 下行 S2C  paddle_pose ：服务端广播某玩家的权威持拍状态（第三人称渲染 / 自己对齐）
 * 下行 S2C  ball_motion ：击球瞬间广播球的新速度与自旋
 * </pre>
 *
 * 所有游戏逻辑都只在服务端执行（server.execute 里），客户端永远不能直接改球的状态。
 */
public final class ModNetworking {

	public static final Identifier ACTION_CHANNEL = PingPongMod.id("paddle_action");
	public static final Identifier POSE_CHANNEL = PingPongMod.id("paddle_pose");
	public static final Identifier MOTION_CHANNEL = PingPongMod.id("ball_motion");

	/** 动作 ID */
	public static final byte ACTION_POSE = 0;
	public static final byte ACTION_SWING = 1;
	public static final byte ACTION_CHARGE = 2;
	public static final byte ACTION_BALL_CAM = 3;

	/**
	 * 球拍够得着的距离（格），从「击球点」算起。
	 *
	 * 【五期 M7.6 §8.2：2.6 → 1.15；2026-09-22 回调到 1.45】
	 * 用户原话「击球范围不要那么大，现实中一个乒乓球拍的大小是多少，
	 * 你按照差不多的比例来设置击球范围的大小」。实查：拍面直径约 15cm、总长约 25cm，
	 * 换算成格只有 0.15 / 0.25 —— 比我们的球（直径 0.28 格）还小。
	 * 纯真实的 0.3~0.5 格在 MC 里太苛刻（移动是分步的，容差小会一直"明明碰到却没打到"）。
	 *
	 * 1.15 上线后用户实测反馈「感觉击球点有点问题或者是太小了，根本打不到球」，
	 * 回调到 1.45：仍远小于原来的 2.6，但比真实尺寸留出了足够的手感余量。
	 * 同时把判定相位对齐到挥拍中段（见下面 anglesFor 的注释），两处一起修「打不到」。
	 */
	private static final double HIT_REACH = 1.45;
	/** 同一个玩家两次挥拍之间的最小间隔（tick），防连点刷包 */
	private static final int SWING_COOLDOWN_TICKS = 2;

	/** 记录每个玩家上次挥拍的游戏时间，用于限流 */
	private static final Map<UUID, Long> LAST_SWING = new HashMap<>();

	private ModNetworking() {
	}

	// ==================================================================
	// 注册
	// ==================================================================

	/** 两端共用：注册服务端接收器。 */
	public static void registerCommon() {
		ServerPlayNetworking.registerGlobalReceiver(ACTION_CHANNEL, (server, player, handler, buf, responseSender) -> {
			// 注意：读包必须在网络线程读完，逻辑再丢回主线程
			byte action = buf.readByte();
			float a = buf.readFloat();
			float b = buf.readFloat();
			byte handId = buf.readByte();
			server.execute(() -> handleAction(player, action, a, b, handId));
		});
	}

	/** 客户端：注册 S2C 接收器。 */
	@Environment(EnvType.CLIENT)
	public static void registerClient() {
		ClientPlayNetworking.registerGlobalReceiver(POSE_CHANNEL, (client, handler, buf, responseSender) -> {
			UUID uuid = buf.readUuid();
			float tilt = buf.readFloat();
			float sideTilt = buf.readFloat();
			byte handId = buf.readByte();
			float swingPower = buf.readFloat();
			boolean ballCam = buf.readBoolean();
			byte strokeId = buf.readByte();
			client.execute(() -> applyPose(uuid, tilt, sideTilt, PlayerHand.byId(handId), swingPower, ballCam,
					StrokeType.byId(strokeId)));
		});

		ClientPlayNetworking.registerGlobalReceiver(MOTION_CHANNEL, (client, handler, buf, responseSender) -> {
			int entityId = buf.readVarInt();
			double vx = buf.readDouble();
			double vy = buf.readDouble();
			double vz = buf.readDouble();
			float sx = buf.readFloat();
			float sy = buf.readFloat();
			float sz = buf.readFloat();
			client.execute(() -> applyMotion(entityId, vx, vy, vz, sx, sy, sz));
		});
	}

	// ==================================================================
	// 客户端 → 服务端
	// ==================================================================

	/** 拍形/手型变了（滚轮调节、切正反手、切槽位读档）。 */
	@Environment(EnvType.CLIENT)
	public static void sendPose(double tilt, double sideTilt, PlayerHand hand) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeByte(ACTION_POSE);
		buf.writeFloat((float) tilt);
		buf.writeFloat((float) sideTilt);
		buf.writeByte((byte) hand.ordinal());
		ClientPlayNetworking.send(ACTION_CHANNEL, buf);
	}

	/** 左键松手：一次挥拍，带上蓄力力度 0~1（需求 1）。 */
	@Environment(EnvType.CLIENT)
	public static void sendSwing(double power) {
		sendSwing(power, false);
	}

	/**
	 * 松手挥拍。
	 *
	 * @param power       蓄力力度 0~1
	 * @param rightButton true = 右键（搓球/削球），false = 左键（攻球/弧圈）
	 */
	@Environment(EnvType.CLIENT)
	public static void sendSwing(double power, boolean rightButton) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeByte(ACTION_SWING);
		buf.writeFloat((float) power);
		buf.writeFloat(0.0F);
		// 手型与"哪个键"都用现有的字段传：handId 低位当手型，最高位当右键标记
		int handId = PingPongClientStateHand();
		int packed = (handId & 0x7F) | (rightButton ? 0x80 : 0);
		buf.writeByte((byte) packed);
		ClientPlayNetworking.send(ACTION_CHANNEL, buf);
	}

	/** 左键按下：开始蓄力（服务端目前只记状态，留给动作/音效提示）。 */
	@Environment(EnvType.CLIENT)
	public static void sendChargeStart() {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeByte(ACTION_CHARGE);
		buf.writeFloat(0.0F);
		buf.writeFloat(0.0F);
		buf.writeByte((byte) PingPongClientStateHand());
		ClientPlayNetworking.send(ACTION_CHANNEL, buf);
	}

	/** 跟球视角开关。 */
	@Environment(EnvType.CLIENT)
	public static void sendBallCam(boolean enabled) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeByte(ACTION_BALL_CAM);
		buf.writeFloat(enabled ? 1.0F : 0.0F);
		buf.writeFloat(0.0F);
		buf.writeByte((byte) PingPongClientStateHand());
		ClientPlayNetworking.send(ACTION_CHANNEL, buf);
	}

	/** 小工具：取当前客户端手型序号（避免在发送方法里散落客户端状态引用）。 */
	@Environment(EnvType.CLIENT)
	private static int PingPongClientStateHand() {
		return com.whale.pingpong.client.PingPongClientState.hand().ordinal();
	}

	// ==================================================================
	// 服务端 → 客户端
	// ==================================================================

	/** 服务端：把一个玩家的持拍状态发给指定接收者。 */
	public static void sendPaddlePose(ServerPlayerEntity receiver, ServerPlayerEntity owner,
									  PaddlePoseTracker.State state) {
		if (!ServerPlayNetworking.canSend(receiver, POSE_CHANNEL)) {
			return;
		}
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeUuid(owner.getUuid());
		buf.writeFloat(state.tilt);
		buf.writeFloat(state.sideTilt);
		buf.writeByte((byte) state.hand.ordinal());
		buf.writeFloat(state.swingPower);
		buf.writeBoolean(state.ballCam);
		// 击球类型：第三人称要按它选动作（搓/削/弧圈/攻球各一套）
		buf.writeByte((byte) state.lastStroke.ordinal());
		ServerPlayNetworking.send(receiver, POSE_CHANNEL, buf);
	}

	/** 服务端：把球的最新速度 / 自旋推给所有能看到它的玩家。 */
	public static void broadcastBallMotion(PingPongBallEntity ball) {
		if (!(ball.getWorld() instanceof ServerWorld)) {
			return;
		}
		// 【Java 8 兼容】显式强转代替 instanceof 模式匹配
		final ServerWorld serverWorld = (ServerWorld) ball.getWorld();
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(ball.getId());
		Vec3d velocity = ball.getVelocity();
		buf.writeDouble(velocity.x);
		buf.writeDouble(velocity.y);
		buf.writeDouble(velocity.z);
		Vec3d spin = ball.getSpin();
		buf.writeFloat((float) spin.x);
		buf.writeFloat((float) spin.y);
		buf.writeFloat((float) spin.z);

		// 收集对象：所有能看到这颗球的玩家 + 出球的那个人（刚生成的球可能还没进追踪列表）
		java.util.Set<ServerPlayerEntity> receivers = new java.util.HashSet<>(PlayerLookup.tracking(ball));
		UUID owner = ball.getOwnerUuid();
		if (owner != null) {
			ServerPlayerEntity ownerPlayer = serverWorld.getServer().getPlayerManager().getPlayer(owner);
			if (ownerPlayer != null) {
				receivers.add(ownerPlayer);
			}
		}

		for (ServerPlayerEntity player : receivers) {
			ServerPlayNetworking.send(player, MOTION_CHANNEL, buf);
		}
	}

	/**
	 * 服务端每 tick 的「按需同步」：只在球的自旋够大、而且离上次同步够久时才发。
	 *
	 * 【为什么要这个】原来只在**击球那一瞬间**发一次 ball_motion，于是客户端两次包之间
	 * 只能靠原版位置包插值 —— 那里面没有马格努斯加速度，屏幕上就是一条直线，
	 * 只有击球那一下"动一下"（用户原话：「侧旋只有击球的那一下能向侧面动一下」）。
	 * 现在自旋球每 {@link #MOTION_RESYNC_TICKS} tick 补一次权威速度与自旋，
	 * 客户端的预测积分就不会漂太久；自旋很小时完全不发包，几乎不增加带宽。
	 */
	public static void syncBallMotionIfNeeded(PingPongBallEntity ball) {
		if (!(ball.getWorld() instanceof ServerWorld)) {
			return;
		}
		Vec3d spin = ball.getSpin();
		if (spin.lengthSquared() < MOTION_SYNC_MIN_SPIN_SQ) {
			return;
		}
		if (ball.age % MOTION_RESYNC_TICKS != 0) {
			return;
		}
		broadcastBallMotion(ball);
	}

	/** 自旋大于这个值时才开始按需补包（半速自旋就够明显了） */
	private static final double MOTION_SYNC_MIN_SPIN_SQ = 0.25;
	/** 补包间隔（tick）：2 tick 一次 ≈ 每秒 10 个包，一局里最多也就几十个球 */
	private static final int MOTION_RESYNC_TICKS = 2;

	// ==================================================================
	// 服务端逻辑
	// ==================================================================

	private static void handleAction(ServerPlayerEntity player, byte action, float a, float b, byte handId) {
		if (player.isRemoved() || player.isSpectator()) {
			return;
		}
		PaddlePoseTracker.State state = PaddlePoseTracker.get(player);

		// 【Java 8 兼容】传统 switch 语句（原来是箭头式 case，Java 14+）
		switch (action) {
			case ACTION_POSE:
				// 校验：必须真的拿着球拍（客户端可以伪造包，服务端说了算）
				if (!(player.getMainHandStack().getItem() instanceof PingPongPaddleItem)) {
					return;
				}
				PlayerHand hand = PlayerHand.byId(handId);
				boolean handChanged = hand != state.hand;
				state.hand = hand;
				if (handChanged) {
					// 切手：拍形一律回到该手型的准备姿势，不接受客户端随便编的数值（需求 4.2）
					state.tilt = (float) hand.readyTilt();
					state.sideTilt = (float) hand.readySideTilt();
				} else {
					state.tilt = clampPose(a);
					state.sideTilt = clampPose(b);
				}
				state.swingPower = 0.0F;
				PaddlePoseTracker.broadcast(player.getServer(), player, true);
				break;
			case ACTION_SWING:
				swing(player, a, handId, state);
				break;
			case ACTION_CHARGE:
				state.swingPower = 0.0F;
				break;
			case ACTION_BALL_CAM:
				state.ballCam = a > 0.5F;
				PaddlePoseTracker.broadcast(player.getServer(), player, true);
				break;
			default:
				break;
		}
	}

	/** 处理一次挥拍：校验 → 找球 → 算物理 → 播放动画。 */
	private static void swing(ServerPlayerEntity player, float charge, byte handId, PaddlePoseTracker.State state) {
		// 校验 1：必须真的拿着球拍
		if (!(player.getMainHandStack().getItem() instanceof PingPongPaddleItem)) {
			return;
		}
		// 校验 2：挥拍频率限流
		long now = player.getWorld().getTime();
		Long last = LAST_SWING.get(player.getUuid());
		if (last != null && now - last < SWING_COOLDOWN_TICKS) {
			return;
		}
		LAST_SWING.put(player.getUuid(), now);

		// 手型以服务端记录为准（客户端可以伪造包）
		PlayerHand hand = PlayerHand.byId(handId & 0x7F);
		if (hand != state.hand) {
			// 允许一次「随挥拍顺带切手」，但不接受与任何已知状态都不符的乱填值
			hand = state.hand;
		}

		// 哪个键 = 哪一类击球（需求 17/20b）：
		//   左键：蓄力大 → 拉弧圈；否则 → 攻球
		//   右键：蓄力 ≥ 57% → 削球；否则 → 搓球
		boolean rightButton = (handId & 0x80) != 0;
		float power = clampPose(charge);
		StrokeType stroke = StrokeType.select(hand == PlayerHand.BACKHAND, rightButton, power);

		// 击球点：**跟着动作里球拍的实际位置**（用户第 4 条反馈：「点位要跟着动画里球拍的位置更改，
		// 不要固定在一个地方」）。
		//
		// 【为什么不再用固定偏移】以前判定点是 TableGeometry.paddlePoint 的一组写死偏移
		// （前 0.55 / 侧 ±0.38 / 上 0.45），而动画早就把手臂摆到别处了 —— 于是"看到的拍子"
		// 与"判定的拍子"分家，玩家会觉得"明明够到了却没打到"。
		// 现在判定点由 util/ArmPose 按「击球类型 + 力度 + 手型」算出手臂姿态后再取球拍中心，
		// 与客户端摆动画用的是**同一个函数**，两边天然对齐。
		//
		// 判定相位取「**击球中段**」而不是"蓄力结束那一刻"。
		//
		// 【2026-09-22 用户报「感觉击球点有点问题或者是太小了，根本打不到球」】
		// 原来传的是 (windup = power*0.35, forward = 0.65 + 0.35*power)：那套参数描述的是
		// **蓄力刚结束、还没挥出去**的姿态。用 tools/paddle_point_check.js 对照：
		// 蓄满力时球拍在身前 +0.25 格（正手）/ +0.42（反手），而真正挥出去时在 +0.70/+0.73 ——
		// 判定点比"玩家看到的拍子"落后约 0.3 格，1.15 格容差下就是"明明碰到却没打到"。
		// 现在固定用 (0.2, 0.8)：对应球拍已经挥到身前的击球时刻，与客户端动画的中段对齐。
		Vec3d eyePos = player.getEyePos();
		Vec3d look = player.getRotationVec(1.0F);
		Vec3d outward = TableGeometry.outward(player.getWorld(), player.getPos());
		ArmPose.Angles paddlePose = ArmPose.anglesFor(stroke, hand == PlayerHand.FOREHAND,
				0.2, 0.8, 0.0);
		Vec3d paddlePos = ArmPose.paddleWorld(eyePos, look, outward, paddlePose,
				hand == PlayerHand.FOREHAND);

		Box searchBox = Box.from(paddlePos).expand(HIT_REACH);
		PingPongBallEntity target = null;
		double bestDistance = HIT_REACH * HIT_REACH;
		// 【五期 M7.6 §8.2：判定形状改成"朝前的扁盒"】
		// 球拍是一块板，不是一颗球 —— 用球体搜索会在**拍子背面**也判定命中（球从身后飞过也算打到）。
		// 以「眼睛 → 拍面中心」为朝前法线，只接受法线前方（含一点侧向容差）的球。
		Vec3d faceNormal = paddlePos.subtract(eyePos);
		if (faceNormal.lengthSquared() > 1.0e-6) {
			faceNormal = faceNormal.normalize();
		} else {
			faceNormal = look;
		}
		for (PingPongBallEntity ball : player.getWorld()
				.getEntitiesByClass(PingPongBallEntity.class, searchBox, ball -> true)) {
			double distance = ball.getPos().squaredDistanceTo(paddlePos);
			if (distance >= bestDistance || !hasLineOfSight(player, ball)) {
				continue;
			}
			double alongFace = ball.getPos().subtract(paddlePos).dotProduct(faceNormal);
			// 背后容差从 0.35 放宽到 0.5：击球是连续动作，判定点只是一个瞬时采样，
			// 球擦着拍边/拍背一点点飞过时应该算命中（用户报「根本打不到球」后一起放宽的）。
			if (alongFace < -HIT_REACH * 0.5) {
				continue;
			}
			bestDistance = distance;
			target = ball;
		}

		// 动作**无条件播放**（需求 20a：碰不到球也要做动作），只有球是否响应不同。
		state.lastStroke = stroke;
		state.swingUntil = now + 8;
		state.swingPower = 0.35F + 0.65F * power;
		player.swingHand(Hand.MAIN_HAND);
		PaddlePoseTracker.broadcast(player.getServer(), player, false);

		if (target != null && target.hitByPaddle(player, state.tilt, state.sideTilt, power, hand, stroke)) {
			// 命中：球的反应由接触模型在 hitByPaddle 里算完（速度、自旋、可行性夹紧）
		} else {
			// 空挥：只有声音
			player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
					net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_WEAK,
					net.minecraft.sound.SoundCategory.PLAYERS, 0.25F, 1.8F);
		}
	}

	private static float clampPose(float value) {
		if (Float.isNaN(value)) {
			return 0.0F;
		}
		return Math.max(-1.0F, Math.min(1.0F, value));
	}

	/**
	 * 眼睛到球之间有没有被实心方块挡住（需求 20a「先算好能不能碰到球」的一部分）。
	 *
	 * 【为什么需要】球拍能"穿墙击球"很出戏：玩家隔着球台下面或者墙都能把球打回来。
	 * 这里从眼睛向球心采样 6 个点，任意一点落在碰撞形状非空的方块里就算被挡住 —— 此时**不碰球**，
	 * 但动作照常播放（动作在 swing() 里是无条件广播的）。
	 */
	private static boolean hasLineOfSight(ServerPlayerEntity player, PingPongBallEntity ball) {
		Vec3d eye = player.getEyePos();
		Vec3d target = ball.getPos();
		for (int i = 1; i <= 6; i++) {
			double t = i / 7.0;
			BlockPos pos = BlockPos.ofFloored(
					eye.x + (target.x - eye.x) * t,
					eye.y + (target.y - eye.y) * t,
					eye.z + (target.z - eye.z) * t);
			if (!player.getWorld().getBlockState(pos).getCollisionShape(player.getWorld(), pos).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	// ==================================================================
	// 客户端应用下行数据
	// ==================================================================

	@Environment(EnvType.CLIENT)
	private static void applyPose(UUID uuid, float tilt, float sideTilt, PlayerHand hand,
								  float swingPower, boolean ballCam, StrokeType stroke) {
		com.whale.pingpong.client.PaddlePoseCache.update(uuid, tilt, sideTilt, hand, swingPower, ballCam, stroke);

		net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
		if (client.player != null && client.player.getUuid().equals(uuid)) {
			// 自己：服务端已确认，清掉 dirty 标记，避免每 tick 重复发包
			com.whale.pingpong.client.PingPongClientState.markSynced(tilt, sideTilt, hand);
		}
	}

	@Environment(EnvType.CLIENT)
	private static void applyMotion(int entityId, double vx, double vy, double vz, float sx, float sy, float sz) {
		net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		Entity entity = client.world.getEntityById(entityId);
		if (entity instanceof PingPongBallEntity) {
			PingPongBallEntity ball = (PingPongBallEntity) entity;
			ball.setVelocity(vx, vy, vz);
			ball.applyClientSpin(new Vec3d(sx, sy, sz));
		}
	}
}

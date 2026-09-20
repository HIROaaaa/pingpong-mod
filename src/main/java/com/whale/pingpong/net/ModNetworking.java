package com.whale.pingpong.net;

import com.whale.pingpong.PingPongMod;
import com.whale.pingpong.entity.PingPongBallEntity;
import com.whale.pingpong.item.PingPongPaddleItem;
import com.whale.pingpong.server.PaddlePoseTracker;
import com.whale.pingpong.util.PlayerHand;
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

	/** 球拍够得着的距离（格），从「击球点」算起 */
	private static final double HIT_REACH = 2.6;
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
			client.execute(() -> applyPose(uuid, tilt, sideTilt, PlayerHand.byId(handId), swingPower, ballCam));
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
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeByte(ACTION_SWING);
		buf.writeFloat((float) power);
		buf.writeFloat(0.0F);
		buf.writeByte((byte) PingPongClientStateHand());
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
		ServerPlayNetworking.send(receiver, POSE_CHANNEL, buf);
	}

	/** 服务端：把球的最新速度 / 自旋推给所有能看到它的玩家。 */
	public static void broadcastBallMotion(PingPongBallEntity ball) {
		if (!(ball.getWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(ball.getId());
		buf.writeDouble(ball.getVelocity().x);
		buf.writeDouble(ball.getVelocity().y);
		buf.writeDouble(ball.getVelocity().z);
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

	// ==================================================================
	// 服务端逻辑
	// ==================================================================

	private static void handleAction(ServerPlayerEntity player, byte action, float a, float b, byte handId) {
		if (player.isRemoved() || player.isSpectator()) {
			return;
		}
		PaddlePoseTracker.State state = PaddlePoseTracker.get(player);

		switch (action) {
			case ACTION_POSE -> {
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
			}
			case ACTION_SWING -> swing(player, a, handId, state);
			case ACTION_CHARGE -> state.swingPower = 0.0F;
			case ACTION_BALL_CAM -> {
				state.ballCam = a > 0.5F;
				PaddlePoseTracker.broadcast(player.getServer(), player, true);
			}
			default -> {
			}
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
		PlayerHand hand = PlayerHand.byId(handId);
		if (hand != state.hand) {
			// 允许一次「随挥拍顺带切手」，但不接受与任何已知状态都不符的乱填值
			hand = state.hand;
		}

		float power = clampPose(charge);

		// 击球点：由「球台朝向 + 玩家站在球台哪一边」决定，正反手各在一侧（需求 4 / 6）
		Vec3d eyePos = player.getEyePos();
		Vec3d look = player.getRotationVec(1.0F);
		Vec3d outward = TableGeometry.outward(player.getWorld(), player.getPos());
		Vec3d paddlePos = TableGeometry.paddlePoint(eyePos, look, outward, hand);

		Box searchBox = Box.from(paddlePos).expand(HIT_REACH);
		PingPongBallEntity target = null;
		double bestDistance = HIT_REACH * HIT_REACH;
		for (PingPongBallEntity ball : player.getWorld()
				.getEntitiesByClass(PingPongBallEntity.class, searchBox, ball -> true)) {
			double distance = ball.getPos().squaredDistanceTo(paddlePos);
			if (distance < bestDistance) {
				bestDistance = distance;
				target = ball;
			}
		}

		if (target != null && target.hitByPaddle(player, state.tilt, state.sideTilt, power, hand)) {
			// 命中：让所有人（包括自己）看到挥臂动作。视角不受影响，只有手臂/手持物在动。
			player.swingHand(Hand.MAIN_HAND);
			state.swingUntil = now + 8;
			state.swingPower = 0.35F + 0.65F * power;
			PaddlePoseTracker.broadcast(player.getServer(), player, false);
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

	// ==================================================================
	// 客户端应用下行数据
	// ==================================================================

	@Environment(EnvType.CLIENT)
	private static void applyPose(UUID uuid, float tilt, float sideTilt, PlayerHand hand,
								  float swingPower, boolean ballCam) {
		com.whale.pingpong.client.PaddlePoseCache.update(uuid, tilt, sideTilt, hand, swingPower, ballCam);

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
		if (entity instanceof PingPongBallEntity ball) {
			ball.setVelocity(vx, vy, vz);
			ball.applyClientSpin(new Vec3d(sx, sy, sz));
		}
	}
}

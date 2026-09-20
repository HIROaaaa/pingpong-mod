package com.whale.pingpong.net;

import com.whale.pingpong.PingPongMod;
import com.whale.pingpong.entity.PingPongBallEntity;
import com.whale.pingpong.item.PingPongPaddleItem;
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
 * C2S  paddle_swing ：客户端挥拍 → 服务端做命中判定与物理出球
 * S2C  ball_motion  ：服务端击球后广播新速度与自旋，客户端用于平滑表现
 *
 * 所有游戏逻辑都只在服务端执行（server.execute 里），客户端永远不能直接改球的状态。
 */
public final class ModNetworking {

	public static final Identifier SWING_CHANNEL = PingPongMod.id("paddle_swing");
	public static final Identifier MOTION_CHANNEL = PingPongMod.id("ball_motion");

	/** 球拍够得着的距离（格），从「拍面位置」算起 */
	private static final double HIT_REACH = 3.0;
	/** 同一个玩家两次挥拍之间的最小间隔（tick），防连点刷包 */
	private static final int SWING_COOLDOWN_TICKS = 2;

	/** 记录每个玩家上次挥拍的游戏时间，用于限流 */
	private static final Map<UUID, Long> LAST_SWING = new HashMap<>();

	private ModNetworking() {
	}

	/** 两端共用：注册服务端接收器。 */
	public static void registerCommon() {
		ServerPlayNetworking.registerGlobalReceiver(SWING_CHANNEL, (server, player, handler, buf, responseSender) -> {
			// 注意：读包必须在网络线程读完，逻辑再丢回主线程
			float tilt = buf.readFloat();
			float sideTilt = buf.readFloat();
			server.execute(() -> handleSwing(player, tilt, sideTilt));
		});
	}

	/** 客户端：注册 S2C 接收器。 */
	@Environment(EnvType.CLIENT)
	public static void registerClient() {
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

	/** 客户端发送挥拍包（携带当前拍面角度）。 */
	@Environment(EnvType.CLIENT)
	public static void sendSwing(double tilt, double sideTilt) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeFloat((float) tilt);
		buf.writeFloat((float) sideTilt);
		ClientPlayNetworking.send(SWING_CHANNEL, buf);
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

	/** 处理一次挥拍：反作弊校验 → 找球 → 算物理 → 播放动画。 */
	private static void handleSwing(ServerPlayerEntity player, float tilt, float sideTilt) {
		if (player.isRemoved() || player.isSpectator()) {
			return;
		}
		// 校验 1：必须真的拿着球拍（客户端可以伪造包，服务端说了算）
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

		// 拍面位置：眼睛前方 1.1 格
		Vec3d paddlePos = PingPongPaddleItem.paddlePoint(player);
		Box searchBox = new Box(paddlePos, paddlePos).expand(HIT_REACH);

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

		if (target != null && target.hitByPaddle(player, tilt, sideTilt)) {
			// 命中：让所有人（包括自己）看到挥臂动作。视角不受影响，只有手臂/手持物在动。
			player.swingHand(Hand.MAIN_HAND);
		} else {
			// 空挥：只有声音
			player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
					net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_WEAK,
					net.minecraft.sound.SoundCategory.PLAYERS, 0.25F, 1.8F);
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

package com.whale.pingpong.server;

import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端权威的「玩家持拍状态」：手型 + 拍面角度。
 *
 * 【为什么服务端也要存一份】
 * 1. 击球判定要用手型：正手/反手的击球点不一样，服务端必须知道玩家现在用哪一面；
 * 2. 第三人称渲染要用手型和角度：客户端可以算自己的，但<b>别人</b>的拍子和挥拍动作
 *    只能靠服务端转发 —— 这正是用户报的「从别的视角看不出来球拍变动」的根因。
 *
 * 数据是「每玩家一格」的纯数值，不持有任何世界对象，客户端拿到的也是同一套广播值。
 */
public final class PaddlePoseTracker {

	/** 一个玩家的持拍状态 */
	public static final class State {
		public PlayerHand hand = PlayerHand.FOREHAND;
		public float tilt;
		public float sideTilt;
		/** 最近一次击球的类型（HUD 显示与第三人称动作都要用它） */
		public StrokeType lastStroke = StrokeType.DRIVE_FOREHAND;
		/** 挥拍动画结束时间（世界 tick），用于第三人称看到别人挥拍 */
		public long swingUntil;
		public float swingPower;
		public boolean ballCam;

		public boolean isSwinging(long now) {
			return this.swingUntil > now;
		}
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private PaddlePoseTracker() {
	}

	/** 取玩家状态，没有就建一个默认的（默认正手、拍面平整）。 */
	public static State get(ServerPlayerEntity player) {
		return STATES.computeIfAbsent(player.getUuid(), key -> new State());
	}

	public static State get(UUID uuid) {
		return STATES.get(uuid);
	}

	/** 玩家退出时清掉，避免 UUID 表无限增长。 */
	public static void forget(UUID uuid) {
		STATES.remove(uuid);
	}

	/**
	 * 把一个玩家的当前状态广播给所有能看到他的玩家（含被广播者自己能看到的其他人）。
	 *
	 * @param includeSelf true 时也发给玩家本人（重连/刚进服务器时用来对齐初始状态）
	 */
	public static void broadcast(MinecraftServer server, ServerPlayerEntity player, boolean includeSelf) {
		State state = get(player);
		for (ServerPlayerEntity receiver : PlayerLookup.tracking(player)) {
			if (!includeSelf && receiver == player) {
				continue;
			}
			com.whale.pingpong.net.ModNetworking.sendPaddlePose(receiver, player, state);
		}
		if (includeSelf) {
			com.whale.pingpong.net.ModNetworking.sendPaddlePose(player, player, state);
		}
	}
}

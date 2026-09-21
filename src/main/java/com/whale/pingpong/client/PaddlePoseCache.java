package com.whale.pingpong.client;

import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 其他玩家（以及自己）的持拍姿态缓存：手型 + 拍面角度 + 挥拍动画 + 击球类型。
 *
 * 数据来源是服务端广播的 S2C 包（见 ModNetworking.handleClientPose）。
 * 渲染层用它来画「别人手里的球拍」——没有这份缓存，第三人称看到的永远是固定角度的拍子。
 */
public final class PaddlePoseCache {

	/** 一个玩家的可见持拍状态 */
	public static final class Pose {
		public PlayerHand hand = PlayerHand.FOREHAND;
		public float tilt;
		public float sideTilt;
		/** 最近一次击球类型：决定第三人称放哪一套动作 */
		public StrokeType stroke = StrokeType.DRIVE_FOREHAND;
		/** 挥拍动画剩余时间（客户端 tick 数） */
		public int swingTicks;
		public float swingPower;
		public boolean ballCam;
	}

	private static final Map<UUID, Pose> POSES = new HashMap<>();

	private PaddlePoseCache() {
	}

	public static Pose get(UUID uuid) {
		return POSES.computeIfAbsent(uuid, key -> new Pose());
	}

	/** 服务端姿态包到达时写入。 */
	public static void update(UUID uuid, float tilt, float sideTilt, PlayerHand hand, float swingPower,
							  boolean ballCam, StrokeType stroke) {
		Pose pose = get(uuid);
		pose.tilt = tilt;
		pose.sideTilt = sideTilt;
		pose.hand = hand;
		pose.ballCam = ballCam;
		pose.stroke = stroke;
		if (swingPower > 0.0F) {
			pose.swingTicks = PingPongClientState.SWING_TICKS;
			pose.swingPower = swingPower;
		}
	}

	/** 每客户端 tick 调一次，推进所有人的挥拍动画。 */
	public static void tick() {
		for (Pose pose : POSES.values()) {
			if (pose.swingTicks > 0) {
				pose.swingTicks--;
			}
		}
	}

	/**
	 * 挥拍进度 0~1（和本地玩家的 {@code PingPongClientState.swingProgress()} 同一语义）。
	 *
	 * 【为什么需要它】M6 的动作系统要按三段式（引拍/触球/随挥）给骨骼摆姿势，
	 * 而其他玩家的挥拍是服务端广播过来的「剩余 tick 数」——这里换算成进度，
	 * 于是本地和远程玩家走的是同一套动作公式。
	 */
	public static float swingProgressOf(Pose pose) {
		if (pose == null || pose.swingTicks <= 0) {
			return 0.0F;
		}
		return 1.0F - (float) pose.swingTicks / PingPongClientState.SWING_TICKS;
	}

	/** 退出世界时清空。 */
	public static void clear() {
		POSES.clear();
	}
}

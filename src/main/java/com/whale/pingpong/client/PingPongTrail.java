package com.whale.pingpong.client;

import com.whale.pingpong.entity.PingPongBallEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 球飞过的位置采样，用来画「旋转尾迹」（五期 M8，现象 C）。
 *
 * 【为什么要有它】用户三次反馈「侧旋根本体现不出来」。实测横向位移是有的
 * （第一跳前 0.105 格、第二跳前 0.63 格），但**看得见的信息量为零** ——
 * 球是个没有细节的白色小方块，转弯也转得晚、转得缓，肉眼根本分不出来。
 * 所以这一版不再只调物理，而是把「球在弯」这件事直接画出来。
 *
 * 【为什么是位置采样而不是画自旋轴】玩家在游戏里判断球路靠的是**轨迹形状**：
 * 真实比赛里你看得出侧旋，是因为球的飞行路线是弯的。残影把这条弯线留在屏幕上，
 * 比在球上转一条条纹有效得多（而且球是公告板朝向的，条纹转起来还会跟朝向打架）。
 *
 * 【采样按距离而不是按 tick】按 tick 采样时，球飞得越快尾迹越稀疏、慢球反而拖得很长。
 * 按固定距离采样，慢球和快球拖出来的尾迹长度一致，看起来才像"一条线"。
 */
public final class PingPongTrail {

	/** 残影点之间至少相隔的距离（格） */
	private static final double SAMPLE_DISTANCE = 0.13;
	/** 尾迹最长的点数 */
	public static final int MAX_POINTS = 12;
	/** 自旋长度低于这个值就不画尾迹（球没转，别拖一条白线） */
	private static final double MIN_SPIN_FOR_TRAIL = 0.45;
	/** 缓存上限：防异常情况下 Map 无限增长 */
	private static final int MAX_TRACKED_BALLS = 32;

	private static final Map<PingPongBallEntity, List<Vec3d>> TRAILS = new HashMap<>();

	private PingPongTrail() {
	}

	/**
	 * 记录一次位置，返回当前应画的残影点（最新的在最后）。
	 *
	 * @param entity 球实体（地图键）
	 * @param spinStrength 自旋长度：决定尾迹画多长（球转得越猛、尾巴越长）
	 */
	public static List<Vec3d> update(PingPongBallEntity entity, double spinStrength) {
		if (entity.isRemoved()) {
			TRAILS.remove(entity);
			return java.util.Collections.emptyList();
		}

		Vec3d pos = entity.getPos();
		List<Vec3d> trail = TRAILS.get(entity);
		if (trail == null) {
			if (TRAILS.size() >= MAX_TRACKED_BALLS) {
				prune();
			}
			trail = new ArrayList<>();
			TRAILS.put(entity, trail);
		}

		// 按距离采样：够远才记一个新点；球停下不动时不会把同一个点堆满
		if (trail.isEmpty() || trail.get(trail.size() - 1).squaredDistanceTo(pos) >= SAMPLE_DISTANCE * SAMPLE_DISTANCE) {
			trail.add(pos);
			while (trail.size() > MAX_POINTS) {
				trail.remove(0);
			}
		}

		if (spinStrength < MIN_SPIN_FOR_TRAIL || entity.isReturning()) {
			return java.util.Collections.emptyList();
		}
		return trail;
	}

	/** 清掉已经不在世界里的球（渲染器每帧调用一次，成本极低）。 */
	public static void retire() {
		Iterator<Map.Entry<PingPongBallEntity, List<Vec3d>>> it = TRAILS.entrySet().iterator();
		while (it.hasNext()) {
			if (it.next().getKey().isRemoved()) {
				it.remove();
			}
		}
	}

	/** 数量超限时丢掉最早的一个，避免长时间游玩后 Map 变大。 */
	private static void prune() {
		Iterator<Map.Entry<PingPongBallEntity, List<Vec3d>>> it = TRAILS.entrySet().iterator();
		if (it.hasNext()) {
			it.next();
			it.remove();
		}
	}

	/** 尾迹点相对当前的位置是否需要渲染（服务端可能已经把球拉走）。 */
	public static boolean isUsableFor(PingPongBallEntity entity) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.world != null && client.world.getEntityById(entity.getId()) == entity;
	}
}

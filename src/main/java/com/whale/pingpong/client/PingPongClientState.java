package com.whale.pingpong.client;

import com.whale.pingpong.item.ModItems;
import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端本地的「球拍状态」：拍面俯仰 / 侧偏 / 手型 / 蓄力 / 跟球视角。
 *
 * 与二期的最大差别：
 * 1. 【不再归零】以前手里没拿球拍就把角度慢慢衰减到 0，用户实测「切物品再切回来拍形就没了」。
 *    现在角度<b>按快捷栏槽位分别保存</b>，切走再切回来原样恢复（需求 2）。
 * 2. 【有手型】正手 / 反手（需求 4），默认正手，切换时拍形回到该手型的准备姿势（需求 4.2）。
 * 3. 【有力度】左键按住时长决定击球力度（需求 1），这里只管攒力度，判定在服务端。
 * 4. 【跟球视角】开关状态（需求 6），相机实现在 CameraMixin。
 *
 * 这些值属于输入状态，服务端不需要每 tick 知道；但拍面角度与手型要<b>发给服务端转发给别人</b>，
 * 否则第三人称和其他玩家看到的永远是固定角度的拍子（这正是用户报的「别的视角看不出变动」）。
 */
public final class PingPongClientState {

	/**
	 * 滚轮每一格改变多少角度比例。
	 * 取 0.25：滚 4 格到满值。之前是 0.1（要滚 10 格），
	 * 用户实测说「旋转没有任何体现」，有一大半原因就是这个步长太小、根本摸不到满自旋。
	 */
	public static final double SCROLL_STEP = 0.25;
	/** 挥拍动画持续多少 tick（仅用于 HUD 提示与动画相位） */
	public static final int SWING_TICKS = 8;

	/** 左键轻点（不够 MIN 就当没蓄力） */
	private static final int CHARGE_MIN_TICKS = 1;
	/** 左键按住多少 tick 达到满力度。0.8 秒：比真实乒乓球引拍短一点，但游戏里够用了 */
	private static final int CHARGE_FULL_TICKS = 16;

	/** 拍面俯仰：+1 = 极限前倾（上旋），-1 = 极限后仰（下旋/兜球） */
	private static double tilt;
	/** 拍面侧偏：+1 / -1 = 左右极限（侧旋） */
	private static double sideTilt;

	private static PlayerHand hand = PlayerHand.FOREHAND;
	private static int swingTicks;
	private static boolean ballCam;
	private static int chargeTicks;

	/** 每个快捷栏槽位各存一份拍形，切物品不丢（需求 2） */
	private static final Map<Integer, Pose> POSES = new HashMap<>();
	/** 拍形/手型有变化时置位，PingPongClient 负责发包（状态类不碰网络） */
	private static boolean poseDirty;
	/** 服务端确认过的拍形，避免每 tick 重复发包 */
	private static double syncedTilt = Double.NaN;
	private static double syncedSide = Double.NaN;
	private static PlayerHand syncedHand = null;

	private PingPongClientState() {
	}

	/** 一个快捷栏槽位的拍形存档 */
	private static final class Pose {
		double tilt;
		double side;

		Pose(double tilt, double side) {
			this.tilt = tilt;
			this.side = side;
		}
	}

	// ==================================================================
	// 读状态
	// ==================================================================

	public static double tilt() {
		return tilt;
	}

	public static double sideTilt() {
		return sideTilt;
	}

	public static PlayerHand hand() {
		return hand;
	}

	public static boolean isSwinging() {
		return swingTicks > 0;
	}

	public static boolean isBallCam() {
		return ballCam;
	}

	/** 挥拍动画进度 0~1，渲染层用它做后摆 → 前挥 → 随挥 */
	public static float swingProgress() {
		if (swingTicks <= 0) {
			return 0.0F;
		}
		return 1.0F - (float) swingTicks / SWING_TICKS;
	}

	/** 是否正在蓄力（左键按住） */
	public static boolean isCharging() {
		return chargeTicks > 0;
	}

	/** 蓄力进度 0~1，仅用于 HUD 显示 */
	public static double chargeRatio() {
		return MathHelper.clamp((double) chargeTicks / CHARGE_FULL_TICKS, 0.0, 1.0);
	}

	/**
	 * 实际打出去的力度 0~1。
	 * 曲线取 sqrt：轻点也有约 25% 力度（不然轻点等于把球丢掉），按住越久增长越慢。
	 */
	public static double hitPower() {
		return Math.sqrt(chargeRatio());
	}

	// ==================================================================
	// 修改状态
	// ==================================================================

	/** 鼠标滚轮回调：alt 为 true 时调侧偏，否则调俯仰。 */
	public static void onScroll(double vertical, boolean alt) {
		double delta = Math.signum(vertical) * SCROLL_STEP;
		if (alt) {
			sideTilt = MathHelper.clamp(sideTilt + delta, -1.0, 1.0);
		} else {
			tilt = MathHelper.clamp(tilt + delta, -1.0, 1.0);
		}
		poseDirty = true;
	}

	/** 开始一次挥拍（用于 HUD 与自定义动画）。 */
	public static void startSwing() {
		swingTicks = SWING_TICKS;
	}

	/** 最近一次真正打出去的击球类型（第一人称动作要按它选）。 */
	private static StrokeType lastStroke = StrokeType.DRIVE_FOREHAND;

	public static StrokeType lastStroke() {
		return lastStroke;
	}

	public static void setLastStroke(StrokeType stroke) {
		if (stroke != null) {
			lastStroke = stroke;
		}
	}

	/** 切换正反手，返回切换后的手型。拍形同时回到该手型的准备姿势（需求 4.2）。 */
	public static PlayerHand switchHand() {
		hand = hand.flip();
		tilt = hand.readyTilt();
		sideTilt = hand.readySideTilt();
		poseDirty = true;
		return hand;
	}

	/** V 键：切换跟球视角。 */
	public static boolean toggleBallCam() {
		ballCam = !ballCam;
		return ballCam;
	}

	/** 左键按下：开始蓄力。 */
	public static void startCharge() {
		chargeTicks = CHARGE_MIN_TICKS;
	}

	/** 左键松手：结束蓄力，返回本次力度（0~1）。 */
	public static double endCharge() {
		double power = hitPower();
		chargeTicks = 0;
		return power;
	}

	/** 拍形/手型有未同步的变化时取走标记（取走即清零，避免重复发包）。 */
	public static boolean consumePoseDirty() {
		if (!poseDirty) {
			return false;
		}
		poseDirty = false;
		return true;
	}

	/** 服务端确认同步完成。 */
	public static void markSynced(double tilt, double side, PlayerHand handValue) {
		syncedTilt = tilt;
		syncedSide = side;
		syncedHand = handValue;
	}

	/** 拍形或手型是否与服务端已知值不同。 */
	public static boolean isPoseOutOfSync() {
		return syncedHand != hand
				|| Math.abs(syncedTilt - tilt) > 1.0e-4
				|| Math.abs(syncedSide - sideTilt) > 1.0e-4;
	}

	// ==================================================================
	// 每 tick
	// ==================================================================

	/** 每客户端 tick 调一次。 */
	public static void tick(MinecraftClient client) {
		if (swingTicks > 0) {
			swingTicks--;
		}
		if (client.player == null) {
			return;
		}

		ItemStack main = client.player.getMainHandStack();
		boolean holdingPaddle = main.isOf(ModItems.PINGPONG_PADDLE);

		// 换槽位：先把旧槽位的拍形存好，再读新槽位的（需求 2 的核心）
		updateSlot(client.player.getInventory().selectedSlot);

		if (holdingPaddle) {
			// 从当前槽位读回拍形。只在「这一 tick 没改过」时读，否则会把刚滚出来的角度冲掉
			if (!poseDirty) {
				Pose pose = POSES.get(client.player.getInventory().selectedSlot);
				if (pose != null) {
					tilt = pose.tilt;
					sideTilt = pose.side;
				}
			} else {
				// 【bug 修复：切回来之后改不了拍形（需求 26）】
				// 滚轮刚改过的这一 tick 就得把值写回槽位存档。原来只在「手里不是球拍」的分支里保存，
				// 于是「滚完立刻切槽位」的最后一格会丢：切槽那一 tick 走的是 else 分支，
				// 而本 tick 开头的 updateSlot() 已经用**旧存档**覆盖了 tilt/sideTilt，
				// 保存下去的自然是旧值 —— 再切回球拍槽位读到的还是旧值，看起来就像拍形改不动了。
				// savePose 幂等，重复写没有副作用。
				savePose(client.player.getInventory().selectedSlot);
			}
			// 蓄力：左键按住时每 tick 累加，到满值封顶
			if (client.options.attackKey.isPressed()) {
				chargeTicks = Math.min(chargeTicks + 1, CHARGE_FULL_TICKS);
			}
		} else {
			// 手里不是球拍：把当前角度写回原槽位存档，绝不归零（需求 2）
			savePose(client.player.getInventory().selectedSlot);
			chargeTicks = 0;
		}
	}

	/** 把当前拍形写进指定槽位的存档。 */
	public static void savePose(int slot) {
		if (slot < 0) {
			return;
		}
		POSES.put(slot, new Pose(tilt, sideTilt));
	}

	/** 切槽位时调用：先存旧槽位，再读新槽位。 */
	public static void onSlotChanged(int newSlot) {
		if (previousSlot >= 0) {
			savePose(previousSlot);
		}
		previousSlot = newSlot;
		Pose pose = POSES.get(newSlot);
		if (pose != null) {
			tilt = pose.tilt;
			sideTilt = pose.side;
		} else {
			// 新槽位还没调过拍形：用当前手型的准备姿势起步
			tilt = hand.readyTilt();
			sideTilt = hand.readySideTilt();
		}
		poseDirty = true;
	}

	private static int previousSlot = -1;

	/** 记住当前槽位（tick 里发现换槽位时用）。 */
	public static void updateSlot(int slot) {
		if (slot != previousSlot) {
			onSlotChanged(slot);
		}
	}

	/** 退出世界/换维度：清掉本地状态，但保留存档的拍形习惯。 */
	public static void reset() {
		tilt = hand.readyTilt();
		sideTilt = hand.readySideTilt();
		swingTicks = 0;
		chargeTicks = 0;
		ballCam = false;
		poseDirty = true;
		syncedTilt = Double.NaN;
		syncedSide = Double.NaN;
		syncedHand = null;
	}
}

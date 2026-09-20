package com.whale.pingpong.util;

/**
 * 握拍手型：正手 / 反手。
 *
 * 现实里乒乓球的正反手不是「换一只手」，而是同一只手把拍子翻到身体另一侧：
 * - 正手（FOREHAND）：拍子在身体<b>外侧偏右</b>，击球点更远、更舒展；
 * - 反手（BACKHAND）：拍子在身体<b>内侧偏左</b>，击球点更贴身、更靠近中线。
 *
 * 切换手型时拍形会回到各自的「准备姿势」（用户要求 4.2）：
 * 现实中打球常态就是板面稍微朝下、准备拉球，所以两边的初始俯仰都是负值（前倾），
 * 只是反手压得更低一点、侧偏朝左。
 */
public enum PlayerHand {

	/** 正手：拍面略前倾、轻微右偏 */
	FOREHAND("pingpong.hand.forehand", -0.25, 0.15),
	/** 反手：拍面更前倾、明显左偏（反手板面天然朝左） */
	BACKHAND("pingpong.hand.backhand", -0.35, -0.30);

	private final String translationKey;
	private final double readyTilt;
	private final double readySideTilt;

	PlayerHand(String translationKey, double readyTilt, double readySideTilt) {
		this.translationKey = translationKey;
		this.readyTilt = readyTilt;
		this.readySideTilt = readySideTilt;
	}

	/** 切换到这个手型时的拍面俯仰初始值（-1 前倾 ~ +1 后仰） */
	public double readyTilt() {
		return this.readyTilt;
	}

	/** 切换到这个手型时的拍面侧偏初始值（-1 左 ~ +1 右） */
	public double readySideTilt() {
		return this.readySideTilt;
	}

	public String translationKey() {
		return this.translationKey;
	}

	/** 翻手：正手 ↔ 反手 */
	public PlayerHand flip() {
		return this == FOREHAND ? BACKHAND : FOREHAND;
	}

	/** 按序号取（网络包里只传一个字节） */
	public static PlayerHand byId(int id) {
		PlayerHand[] values = values();
		if (id < 0 || id >= values.length) {
			return FOREHAND;
		}
		return values[id];
	}
}

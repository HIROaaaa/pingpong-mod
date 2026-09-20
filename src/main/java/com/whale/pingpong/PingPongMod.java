package com.whale.pingpong;

import com.whale.pingpong.block.ModBlocks;
import com.whale.pingpong.entity.ModEntities;
import com.whale.pingpong.item.ModItems;
import com.whale.pingpong.item.PingPongPaddleItem;
import com.whale.pingpong.net.ModNetworking;
import com.whale.pingpong.server.PaddlePoseTracker;
import com.whale.pingpong.util.PlayerHand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 乒乓球 Mod 主入口（两端共用）。
 *
 * 设计要点：
 * 1. 所有飞行 / 弹跳物理都在【服务端】计算，客户端只接收结果并插值渲染；
 * 2. 玩家操作（挥拍、拍面角度、手型）通过 C2S 包发给服务端，由服务端做命中判定；
 * 3. 自旋通过实体 TrackedData 同步，速度变化通过自定义 S2C 包同步；
 * 4. 持拍姿态（拍面角度 / 正反手）由服务端权威保存在 {@link PaddlePoseTracker} 并广播，
 *    这样其他玩家和第三人称视角才能看到正确的拍形。
 */
public class PingPongMod implements ModInitializer {
	public static final String MOD_ID = "pingpong";
	public static final Logger LOGGER = LoggerFactory.getLogger("PingPong");

	/** 统一构造 Identifier，避免到处写字符串。 */
	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		// 顺序有讲究：物品 → 方块 → 实体 → 网络。
		// 球台方块要先注册好，实体 tick 里的「撞到球台」判定才能认出来。
		ModItems.register();
		ModBlocks.register();
		ModEntities.register();
		ModNetworking.registerCommon();
		registerServerEvents();

		LOGGER.info("[pingpong] 乒乓球 Mod 已加载：服务端权威物理 + 马格努斯效应");
	}

	/** 玩家进出服务器时同步持拍姿态。 */
	private static void registerServerEvents() {
		// 新玩家进来：把「能看到的所有玩家」的当前姿态推给他，否则他的第三人称里
		// 别人的球拍会一直是默认角度，直到对方动一次滚轮
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity joined = handler.getPlayer();
			for (ServerPlayerEntity other : PlayerLookup.tracking(joined)) {
				PaddlePoseTracker.State state = PaddlePoseTracker.get(other);
				ModNetworking.sendPaddlePose(joined, other, state);
			}
			ModNetworking.sendPaddlePose(joined, joined, PaddlePoseTracker.get(joined));
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				PaddlePoseTracker.forget(handler.getPlayer().getUuid()));

		// 手里没拿球拍时把姿态复位：否则玩家把球拍收回物品栏后，
		// 别人那边会继续显示一把握在半空、还在倾斜/挥动的拍子
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				if (player.getMainHandStack().getItem() instanceof PingPongPaddleItem) {
					continue;
				}
				PaddlePoseTracker.State state = PaddlePoseTracker.get(player);
				if (state.hand == PlayerHand.FOREHAND
						&& state.tilt == 0.0F && state.sideTilt == 0.0F
						&& state.swingUntil <= player.getWorld().getTime()) {
					continue;
				}
				state.hand = PlayerHand.FOREHAND;
				state.tilt = 0.0F;
				state.sideTilt = 0.0F;
				state.swingUntil = 0L;
				state.swingPower = 0.0F;
				PaddlePoseTracker.broadcast(server, player, true);
			}
		});
	}
}

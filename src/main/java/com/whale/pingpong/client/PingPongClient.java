package com.whale.pingpong.client;

import com.whale.pingpong.entity.ModEntities;
import com.whale.pingpong.net.ModNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;

/** 客户端入口：实体渲染器、网络接收、HUD、每 tick 状态更新。 */
public class PingPongClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.PINGPONG_BALL, PingPongBallRenderer::new);
		ModNetworking.registerClient();
		PingPongHud.register();
		ClientTickEvents.END_CLIENT_TICK.register(PingPongClient::onEndClientTick);
	}

	private static void onEndClientTick(MinecraftClient client) {
		PingPongClientState.tick(client);
	}
}

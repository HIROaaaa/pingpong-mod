package com.whale.pingpong.client;

import com.whale.pingpong.block.ModBlocks;
import com.whale.pingpong.entity.ModEntities;
import com.whale.pingpong.net.ModNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;

/** 客户端入口：实体渲染器、方块渲染层、网络接收、HUD、每 tick 状态更新。 */
public class PingPongClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.PINGPONG_BALL, PingPongBallRenderer::new);

		// 球网贴图带透明孔，必须走 cutout 渲染层，否则会画成一块实心白板
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PINGPONG_TABLE, RenderLayer.getCutout());

		ModNetworking.registerClient();
		PingPongHud.register();
		ClientTickEvents.END_CLIENT_TICK.register(PingPongClient::onEndClientTick);
	}

	private static void onEndClientTick(MinecraftClient client) {
		PingPongClientState.tick(client);
	}
}

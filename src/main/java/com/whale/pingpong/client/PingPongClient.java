package com.whale.pingpong.client;

import com.whale.pingpong.block.ModBlocks;
import com.whale.pingpong.entity.ModEntities;
import com.whale.pingpong.item.PingPongPaddleItem;
import com.whale.pingpong.net.ModNetworking;
import com.whale.pingpong.util.PlayerHand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口：实体渲染器、方块渲染层、网络接收、HUD、按键绑定、每 tick 状态更新。
 *
 * 按键绑定用 Fabric API 自带的 {@code fabric-key-binding-api-v1}（fabric-api 已内置，
 * 不需要任何额外依赖），注册后会自动出现在「选项 → 控制 → 按键绑定 → 乒乓球」里，玩家可自行改键。
 */
public class PingPongClient implements ClientModInitializer {

	/** C：切换正手/反手 */
	public static KeyBinding keySwitchHand;
	/** V：切换跟球视角 */
	public static KeyBinding keyBallCam;

	/** 左键是否按着（用来识别「松手」那一刻） */
	private static boolean attackKeyWasDown;
	/** 松手后的挥拍冷却，避免连点刷包（服务端还会再限流一次） */
	private static int swingCooldown;
	/** 拍形没被服务端确认的持续 tick 数，用来决定要不要重发 */
	private static int outOfSyncTicks;
	/** 多少 tick 没确认就重发一次拍形 */
	private static final int RESYNC_TICKS = 20;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.PINGPONG_BALL, PingPongBallRenderer::new);

		// 球网贴图带透明孔，必须走 cutout 渲染层，否则会画成一块实心白板
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PINGPONG_TABLE, RenderLayer.getCutout());

		ModNetworking.registerClient();
		PingPongHud.register();
		registerKeyBindings();

		ClientTickEvents.END_CLIENT_TICK.register(PingPongClient::onEndClientTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PingPongClientState.reset();
			PaddlePoseCache.clear();
			attackKeyWasDown = false;
			swingCooldown = 0;
			outOfSyncTicks = 0;
		});
	}

	private static void registerKeyBindings() {
		keySwitchHand = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.pingpong.switch_hand", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, "key.categories.pingpong"));
		keyBallCam = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.pingpong.ball_cam", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.pingpong"));
	}

	/**
	 * 左键按下（由 MinecraftClientMixin 在本回合最开头调用）：
	 * 立刻进入蓄力，力度由「按住多久」决定（需求 1）。
	 */
	public static void onAttackPressed() {
		PingPongClientState.startCharge();
	}

	private static void onEndClientTick(MinecraftClient client) {
		PingPongClientState.tick(client);
		PaddlePoseCache.tick();

		if (client.player == null) {
			attackKeyWasDown = false;
			return;
		}

		handleKeyBindings(client);
		handleSwing(client);
		syncPose();
	}

	/** 正反手切换 / 跟球视角。 */
	private static void handleKeyBindings(MinecraftClient client) {
		while (keySwitchHand.wasPressed()) {
			PlayerHand hand = PingPongClientState.switchHand();
			Text label = Text.translatable(hand.translationKey()).formatted(
					hand == PlayerHand.FOREHAND ? Formatting.AQUA : Formatting.LIGHT_PURPLE);
			client.inGameHud.setOverlayMessage(
					Text.translatable("hud.pingpong.hand.switched", label), false);
		}

		while (keyBallCam.wasPressed()) {
			boolean enabled = PingPongClientState.toggleBallCam();
			ModNetworking.sendBallCam(enabled);
			client.inGameHud.setOverlayMessage(
					Text.translatable(enabled ? "hud.pingpong.ballcam.on" : "hud.pingpong.ballcam.off")
							.formatted(Formatting.GOLD), true);
		}
	}

	/**
	 * 左键松手 → 一次挥拍，力度来自蓄力时长。
	 * 按住时不重复发包，松手才打出去，这样「点一下」和「按住蓄力」的手感才分得开。
	 */
	private static void handleSwing(MinecraftClient client) {
		boolean holdingPaddle = client.player.getMainHandStack().getItem() instanceof PingPongPaddleItem;
		boolean attackDown = client.options.attackKey.isPressed() && holdingPaddle && client.currentScreen == null;

		if (swingCooldown > 0) {
			swingCooldown--;
		}

		if (attackDown && !attackKeyWasDown && swingCooldown <= 0) {
			// 已经开始蓄力（mixin 里调过 startCharge），这里只记状态
			attackKeyWasDown = true;
		} else if (!attackDown && attackKeyWasDown) {
			attackKeyWasDown = false;
			double power = PingPongClientState.endCharge();
			PingPongClientState.startSwing();
			ModNetworking.sendSwing(power);
			swingCooldown = 4;
		} else if (!attackDown) {
			attackKeyWasDown = false;
		}
	}

	/**
	 * 拍形/手型有变化就同步给服务端（服务端再转给别的玩家做第三人称渲染）。
	 *
	 * 未确认的时间超过 RESYNC_TICKS 才重发一次：发包 → 服务端广播回来 → 我们在
	 * {@code applyPose} 里标记已同步。既不会每 tick 刷包，丢包了也能自动补上。
	 */
	private static void syncPose() {
		boolean dirty = PingPongClientState.consumePoseDirty() || PingPongClientState.isPoseOutOfSync();
		if (!dirty) {
			outOfSyncTicks = 0;
			return;
		}
		if (outOfSyncTicks == 0 || outOfSyncTicks >= RESYNC_TICKS) {
			ModNetworking.sendPose(
					PingPongClientState.tilt(), PingPongClientState.sideTilt(), PingPongClientState.hand());
			outOfSyncTicks = 1;
		} else {
			outOfSyncTicks++;
		}
	}
}

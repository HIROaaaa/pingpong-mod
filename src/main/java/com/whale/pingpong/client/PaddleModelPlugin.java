package com.whale.pingpong.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.util.Identifier;

/**
 * 把球拍的物品模型**换成代码生成的自定义模型**（{@link PaddleBakedModel}）。
 *
 * <h2>做法</h2>
 * 用 Fabric 的 {@code ModelLoadingPlugin} + {@code modifyModelAfterBake}：
 * 资源重载烘焙完成后，如果这个模型正是球拍，就把烘焙结果**包一层** ——
 * 外层是我们的自定义几何，其余设置（display 变换、粒子贴图、环境光、覆盖表）全部透传原模型。
 *
 * <p>这样做的关键好处：**display（手持/掉落/GUI 的旋转缩放）沿用原来的 JSON 配置**，
 * 不需要在新模型里重写一遍；几何只负责形状。
 *
 * <h2>验证钩子</h2>
 * 物品模型**只有真正要用的时候才烘焙**（主菜单时只烘焙方块模型 —— 实测回调第一批收到的是
 * {@code minecraft:block/vine}）。所以进世界后主动打开一次创造物品栏，逼它烘焙物品模型，
 * 日志里就能立刻看到替换是否发生（`PINGPONG_AUTOCHECK=1` 时启用）。
 */
public final class PaddleModelPlugin implements ModelLoadingPlugin {

	// 【为什么用构造函数而不是 Identifier.of】1.20.1 这条线上 `Identifier.of` 并不总是存在，
	// 用老的 `new Identifier(ns, path)` 最稳（我们的其他代码也一直这么写）。
	private static final Identifier PADDLE_MODEL =
			new Identifier("pingpong", "item/pingpong_paddle");

	private static boolean loggedOnce;
	private static int tickCounter;
	private static boolean autoCheckDone;

	private PaddleModelPlugin() {
	}

	public static void register() {
		ModelLoadingPlugin.register(new PaddleModelPlugin());
		com.whale.pingpong.PingPongMod.LOGGER.info("[pingpong] 已注册球拍模型替换插件（等待资源重载烘焙）");

		// 开发自检：进世界后自动拿到球拍并截图，供开发端核对渲染结果
		//
		// 【开关用标志文件 + 绝对路径】前面试过环境变量、系统属性、相对路径标志文件，
		// 都没稳定生效（Gradle 启的 Java 进程继承情况不一，相对路径还取决于工作目录）。
		// 现在用 Minecraft 自己的 runDirectory 拼绝对路径，最可靠。
		boolean autocheck = System.getenv("PINGPONG_AUTOCHECK") != null
				|| System.getProperty("pingpong.autocheck") != null
				|| new java.io.File(MinecraftClient.getInstance().runDirectory, "PINGPONG_AUTOCHECK").exists();
		if (autocheck) {
			com.whale.pingpong.PingPongMod.LOGGER.info("[pingpong] 自检模式已开启（会给自己发球拍并截图）");
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (autoCheckDone || client.player == null || client.getNetworkHandler() == null) {
					return;
				}
				tickCounter++;
				// 先进世界并给玩家一个球拍（命令会被服务端立即执行，并回显结果）
				if (tickCounter == 40) {
					com.whale.pingpong.PingPongMod.LOGGER.info("[pingpong] 自检：给自己一个球拍");
					client.getNetworkHandler().sendChatCommand("give @s pingpong:pingpong_paddle");
					client.getNetworkHandler().sendChatCommand("gamemode creative");
				} else if (tickCounter == 60) {
					com.whale.pingpong.PingPongMod.LOGGER.info("[pingpong] 自检：截图（第一人称）");
					net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(
							client.runDirectory, client.getFramebuffer(), text -> { });
				} else if (tickCounter == 70) {
					// 切第三人称再截一张（球拍在手里应该清晰可见）
					client.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_FRONT);
				} else if (tickCounter == 85) {
					com.whale.pingpong.PingPongMod.LOGGER.info("[pingpong] 自检：截图（第三人称）");
					net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(
							client.runDirectory, client.getFramebuffer(), text -> { });
				} else if (tickCounter > 100) {
					autoCheckDone = true;
				}
			});
		}
	}

	@Override
	public void onInitializeModelLoader(Context pluginContext) {
		pluginContext.modifyModelAfterBake().register(ModelModifier.WRAP_PHASE, (model, context) -> {
			Identifier id = context.id();
			/*
			 * 【诊断为什么只认自己的命名空间】上一版把"第一次回调"打出来，结果收到的是
			 * `minecraft:block/vine`（主菜单先烘焙方块模型）—— 那是无关噪声。
			 * 现在只记录本模组的模型，于是"物品模型有没有被替换"一眼可判。
			 */
			if (id != null && "pingpong".equals(id.getNamespace())) {
				com.whale.pingpong.PingPongMod.LOGGER.info(
						"[pingpong] 模型烘焙回调：收到 {}（期望 {}）", id, PADDLE_MODEL);
			}
			if (model == null || !PADDLE_MODEL.equals(id)) {
				return model;
			}
			com.whale.pingpong.PingPongMod.LOGGER.info(
					"[pingpong] 球拍模型已换成代码生成的几何（圆形拍面 + 三层结构 + 三段手柄）");
			return new PaddleBakedModel(model);
		});
	}
}

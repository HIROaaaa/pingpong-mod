package com.whale.pingpong.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.whale.pingpong.PingPongMod;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 游戏内诊断命令 `/pingpong diag` —— 把"到底哪一环没生效"直接打到聊天框与日志。
 *
 * <h2>为什么需要它</h2>
 * 用户连续多轮反馈"动作不行、模型不行"，而服务端/客户端日志都干净：
 * 日志只能证明 **mod 被加载**，证明不了 **Mixin 是否真的改到了模型**、
 * **模型文件是否真的被这套资源包加载**。中间任何一环静默失效，从外面都看不出来。
 *
 * 这条命令把四个关键事实一次性摊开：
 * <ol>
 *   <li><b>版本号</b>：确认玩家跑的确实是新 jar（用户曾跑到旧版本而不自知）；</li>
 *   <li><b>bendy-lib 与弯曲状态</b>：playerAnimator 没装 bendy-lib 时会静默换成空实现；</li>
 *   <li><b>Mixin 是否在跑</b>：{@link PingPongModelPose#diagnostics()} 里累计的
 *       setAngles 命中次数 —— 这是"注入有没有生效"的唯一直接证据；</li>
 *   <li><b>玩家模型部件是否存在</b>：拿到的 ModelPart 是不是真的（null 说明注入点选错了）。</li>
 * </ol>
 *
 * 用法：`/pingpong diag`，也可以 `/pingpong diag <任意备注>` 让备注一起进日志，便于对照。
 */
public final class PingPongDiagCommand {

	private PingPongDiagCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("pingpong")
						.then(ClientCommandManager.literal("diag")
								.executes(ctx -> run(""))
								.then(ClientCommandManager.argument("note", StringArgumentType.greedyString())
										.executes(ctx -> run(StringArgumentType.getString(ctx, "note")))))
						/*
						 * /pingpong bend <度数> —— 调试弯矩。
						 *
						 * 【为什么要有它】调"弯曲幅度"时，每改一次常量都要 编译→发版→玩家重启游戏，
						 * 一轮十几分钟；而实际需要的只是"看一眼 60° 和 120° 差多少"。
						 * 有了这条命令，玩家在游戏里当场试、当场定值，我再固化成常量 ——
						 * 这是把"反复试错"从发版循环里拿出来。
						 */
						.then(ClientCommandManager.literal("bend")
								.then(ClientCommandManager.argument("degrees", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(-1.0F, 180.0F))
										.executes(ctx -> {
											float deg = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "degrees");
											PingPongModelPose.setBendOverride(deg);
											String msg = deg < 0.0F
													? "弯矩调试已关闭，恢复跟随动作"
													: String.format("调试弯矩 = %.0f°（-1 取消）", deg);
											show(msg);
											return 1;
										})))));
	}

	private static void show(String message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			client.player.sendMessage(Text.literal(message), false);
		}
		PingPongMod.LOGGER.info("[pingpong] {}", message);
	}

	private static int run(String note) {
		MinecraftClient client = MinecraftClient.getInstance();
		StringBuilder sb = new StringBuilder();
		sb.append("=== pingpong 诊断 ===");
		if (!note.isEmpty()) {
			sb.append(" [").append(note).append(']');
		}
		sb.append('\n').append("mod 版本: ").append(PingPongMod.version());

		// 手里拿的是什么
		if (client.player != null) {
			boolean paddle = client.player.getMainHandStack()
					.isOf(com.whale.pingpong.item.ModItems.PINGPONG_PADDLE);
			sb.append('\n').append("主手是球拍: ").append(paddle ? "是" : "否");
			sb.append(" / 视角: ").append(client.options.getPerspective().name());
			sb.append(" / 进程内玩家数: ").append(client.world == null ? 0 : client.world.getPlayers().size());
		} else {
			sb.append('\n').append("不在世界内");
		}

		sb.append('\n').append(PingPongModelPose.diagnostics());

		String full = sb.toString();
		if (client.player != null) {
			for (String line : full.split("\n")) {
				client.player.sendMessage(Text.literal(line), false);
			}
		}
		// 同时写进日志：用户只要把日志贴过来，就不用截图了
		for (String line : full.split("\n")) {
			PingPongMod.LOGGER.info("{}", line);
		}
		return 1;
	}
}
